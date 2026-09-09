package com.bloxbean.cardano.kavach.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;

import java.security.interfaces.EdECPrivateKey;

import co.nstant.in.cbor.CborEncoder;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Exercises exactly the demo service's public workflow with disposable CIP-30-shaped signer
 * responses.
 */
@Tag("demoDevkit")
@Timeout(600)
class DemoServiceDevkitTest {
    private DemoService service = new DemoService();
    private boolean captureMixedCreation;
    private final Account sponsor = new Account(new Network(0, 42));
    private final Map<String, KeyPair> authorities = new HashMap<>();
    private final BFBackendService backend =
            new BFBackendService("http://localhost:8080/api/v1/", "devkit");

    @Test
    void creationSignerBoundsRejectBeforeFunding() throws Exception {
        var request = new HashMap<String, Object>();
        request.put("action", "Create account");
        request.put("mode", "3");
        request.put("sponsor", sponsor.baseAddress());
        request.put("coseIds", List.of());
        for (int count : new int[]{2, 9}) {
            request.put("keys", newKeys(count));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> service.prepare(request))
                    .getMessage().contains("3 to 8"));
        }
    }

    @Test
    void eightSignerCreationAndRestartResume() throws Exception {
        perKeyCreationAndRestartResume(8);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void perKeyCreationAndRestartResume(int coseCount) throws Exception {
        creationWithMethods(coseCount, false);
    }

    @Test
    void allCoseMigrationWithIndependentFeePayer() throws Exception {
        creationWithMethods(1, true);
    }

    private void creationWithMethods(int coseCount, boolean migrateAllCose) throws Exception {
        captureMixedCreation = true;
        topUp(1000);
        topUp(20);
        topUp(20);
        Thread.sleep(2000);
        var request = new HashMap<String, Object>();
        request.put("action", "Create account");
        request.put("mode", "3");
        request.put("sponsor", sponsor.baseAddress());
        request.put("keys", newKeys(Math.max(3, coseCount)));
        request.put("budgetCore", true);
        request.put("coseIds", coseCount == 0 ? List.of() : coseCount == 1 ? List.of(1) : IntStream.range(0, coseCount).boxed().toList());
        request.put("amountTiers", true);
        request.put("smallPaymentAda", "10");
        request.put("smallThreshold", "1");
        request.put("smallMembers", List.of(0));
        request.put("policies", List.of(Map.of("threshold", 2, "members", List.of(0, 1)),
                Map.of("threshold", 2, "members", List.of(0, 2)), Map.of("threshold", 1, "members", List.of(1)),
                Map.of("threshold", 1, "members", List.of(2)), Map.of("threshold", 1, "members", List.of(1)),
                Map.of("threshold", 1, "members", List.of(2))));
        var plan = map(service.prepare(request));
        String locator = "";
        int confirmations = 0;
        while (true) {
            plan = execute(plan);
            confirmations++;
            if (plan.get("locator") instanceof String value) locator = value;
            int step = ((Number) plan.get("setupStep")).intValue();
            assertEquals(10, ((Number) plan.get("setupTotal")).intValue());
            if (!Boolean.TRUE.equals(plan.get("canAdvance"))) break;
            if (step >= 7) {
                assertEquals(true, map(service.restore(locator)).get("setupPending"));
                var blocked = new HashMap<>(request);
                blocked.put("action", "Send assets");
                blocked.put("locator", locator);
                assertThrows(IllegalArgumentException.class, () -> service.prepare(blocked));
                // No old Plan or Setup survives this restart; recover only public deployment and ledger state.
                service = new DemoService();
                plan = map(service.prepare(Map.of("action", "Finish account setup", "locator", locator, "sponsor", sponsor.baseAddress())));
            } else plan = map(service.update((String) plan.get("id"), "advance", Map.of()));
        }
        assertEquals(10, confirmations, "Resume must not republish or register an already confirmed script");
        var account = map(service.restore(locator));
        assertEquals(false, account.get("setupPending"));
        assertEquals(3, account.get("signingMode"));
        assertEquals(true, account.get("budgetCore"));
        var funding = new QuickTxBuilder(backend).compose(new Tx().payToAddress((String) account.get("address"), Amount.ada(40)).from(sponsor.baseAddress()))
                .feePayer(sponsor.baseAddress()).withSigner(SignerProviders.signerFrom(sponsor)).completeAndWait();
        assertTrue(funding.isSuccessful(), funding.toString());
        request.put("action", "Send assets");
        request.put("locator", locator);
        request.put("recipient", sponsor.baseAddress());
        request.put("amount", "5");
        execute(map(service.prepare(request)));
        request.put("amount", "20");
        execute(map(service.prepare(request)));
        assertEquals("15000000", map(service.restore(locator)).get("balance"));
        if (migrateAllCose) {
            var oldAddress = account.get("address");
            request.put("action", "Rotate keys");
            request.put("target", request.get("keys"));
            request.put("coseIds", List.of(0, 1, 2));
            request.put("smallPaymentAda", "30");
            execute(map(service.prepare(request)));
            var updated = map(service.restore(locator));
            assertEquals(oldAddress, updated.get("address"));
            assertEquals("30000000", updated.get("smallPaymentLimit"));
            for (var key : (List<?>) updated.get("keys")) assertEquals(2, map(key).get("method"));
            var more = new QuickTxBuilder(backend).compose(new Tx().payToAddress((String) oldAddress, Amount.ada(60)).from(sponsor.baseAddress()))
                    .feePayer(sponsor.baseAddress()).withSigner(SignerProviders.signerFrom(sponsor)).completeAndWait();
            assertTrue(more.isSuccessful(), more.toString());
            request.put("action", "Send assets");
            var fundingHash = HexUtil.encodeHexString(new Address(sponsor.baseAddress()).getPaymentCredentialHash().orElseThrow());
            for (int amount : new int[]{15, 35}) {
                request.put("amount", String.valueOf(amount));
                var transfer = map(service.prepare(request));
                assertEquals(List.of(), transfer.get("transactionAuthoritySigners"));
                assertEquals(List.of(fundingHash), transfer.get("requiredSigners"));
                assertEquals(fundingHash, map(transfer.get("feePayer")).get("paymentKeyHash"));
                assertEquals(amount == 15 ? 1 : 2, ((List<?>) transfer.get("payloads")).size());
                assertFalse(((List<?>) transfer.get("signerKeys")).stream().anyMatch(k -> fundingHash.equals(map(k).get("paymentKeyHash"))), "Fee payer is not an account authority");
                assertThrows(IllegalArgumentException.class, () -> service.update((String) transfer.get("id"), "submit", Map.of()));
                execute(transfer);
            }
            assertEquals("25000000", map(service.restore(locator)).get("balance"));
        }
    }

    @Test
    void mixedPolicyPublicationUpgradeAndAmountTiers() throws Exception {
        policyFlow(3);
    }

    @Test
    void periodicBudgetFullFlow() throws Exception {
        policyFlow(4);
    }

    private void policyFlow(int profile) throws Exception {
        topUp(1000);
        topUp(20);
        topUp(20);
        Thread.sleep(2000);
        var request = new HashMap<String, Object>();
        request.put("action", "Create account");
        request.put("keys", newKeys());
        request.put("mode", "2");
        request.put("sponsor", sponsor.baseAddress());
        request.put("budgetCore", profile == 4);
        var plan = map(service.prepare(request));
        String locator = "";
        while (true) {
            plan = execute(plan);
            if (plan.get("locator") instanceof String value) locator = value;
            if (!Boolean.TRUE.equals(plan.get("canAdvance"))) break;
            plan = map(service.update((String) plan.get("id"), "advance", Map.of()));
        }
        assertFalse(locator.isBlank());
        request.put("locator", locator);
        request.put("action", "Replace module");
        request.put("mode", String.valueOf(profile));
        request.put("budgetEnabled", profile == 4);
        request.put("budgetPeriod", "daily");
        request.put("budgetAda", "30");
        request.put("policies", List.of(
                Map.of("threshold", 2, "members", List.of(0, 1)),
                Map.of("threshold", 2, "members", List.of(0, 2)),
                Map.of("threshold", 1, "members", List.of(1)),
                Map.of("threshold", 1, "members", List.of(2)),
                Map.of("threshold", 1, "members", List.of(1)),
                Map.of("threshold", 1, "members", List.of(2))));
        request.put("smallPaymentAda", "10");
        request.put("smallThreshold", "1");
        request.put("smallMembers", List.of(0));
        request.put("coseIds", List.of(1));
        plan = map(service.prepare(request));
        while (true) {
            plan = execute(plan);
            if (!Boolean.TRUE.equals(plan.get("canAdvance"))) break;
            plan = map(service.update((String) plan.get("id"), "advance", Map.of()));
        }
        var account = map(service.restore(locator));
        assertEquals(profile, account.get("signingMode"));
        var funding = new QuickTxBuilder(backend).compose(new Tx().payToAddress(
                        (String) account.get("address"), Amount.ada(80)).from(sponsor.baseAddress())).feePayer(sponsor.baseAddress())
                .withSigner(SignerProviders.signerFrom(sponsor)).completeAndWait();
        assertTrue(funding.isSuccessful(), funding.toString());
        request.put("action", "Send assets");
        request.put("recipient", sponsor.baseAddress());
        request.put("amount", "5");
        plan = map(service.prepare(request));
        assertNotNull(plan.get("transaction"), "Small tier requires only the transaction credential");
        execute(plan);
        request.put("amount", "20");
        request.put("approvers", "0");
        assertThrows(IllegalArgumentException.class, () -> service.prepare(request));
        request.remove("approvers");
        plan = map(service.prepare(request));
        assertNull(plan.get("transaction"), "Strong tier also requires COSE");
        assertEquals(1, ((List<?>) plan.get("payloads")).size());
        execute(plan);
        assertEquals("55000000", map(service.restore(locator)).get("balance"));
        request.put("action", "Rotate keys");
        request.put("target", request.get("keys"));
        request.put("smallPaymentAda", "8");
        execute(map(service.prepare(request)));
        assertEquals("8000000", map(service.restore(locator)).get("smallPaymentLimit"));
        if (profile == 4) {
            assertEquals("25000000", map(map(service.restore(locator)).get("budget")).get("spent"));
            request.put("action", "Send assets");
            request.put("amount", "10");
            var over = assertThrows(IllegalArgumentException.class, () -> service.prepare(request));
            assertTrue(over.getMessage().contains("budget exceeded"), over.getMessage());
            request.put("action", "Rotate keys");
            request.put("budgetEnabled", false);
            execute(map(service.prepare(request)));
            request.put("action", "Send assets");
            request.put("amount", "5");
            execute(map(service.prepare(request)));
            request.put("action", "Rotate keys");
            request.put("budgetEnabled", true);
            execute(map(service.prepare(request)));
            assertEquals("25000000", map(map(service.restore(locator)).get("budget")).get("spent"));
            request.put("action", "Send assets");
            request.put("amount", "6");
            assertThrows(IllegalArgumentException.class, () -> service.prepare(request));
            request.put("amount", "5");
            execute(map(service.prepare(request)));
            assertEquals("30000000", map(map(service.restore(locator)).get("budget")).get("spent"));
            request.put("action", "Rotate keys");
            request.put("budgetPeriod", "weekly");
            execute(map(service.prepare(request)));
            request.put("action", "Send assets");
            request.put("amount", "20");
            execute(map(service.prepare(request)));
            var weekly = map(map(service.restore(locator)).get("budget"));
            assertEquals("weekly", weekly.get("period"));
            assertEquals("20000000", weekly.get("spent"));
            assertEquals("25000000", map(service.restore(locator)).get("balance"));
            // Two different account inputs still contend on the same shared counter.
            var extra = new QuickTxBuilder(backend).compose(new Tx().payToAddress(
                            (String) account.get("address"), Amount.ada(5)).from(sponsor.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(sponsor)).completeAndWait();
            assertTrue(extra.isSuccessful(), extra.toString());
            var inputs = backend.getUtxoService().getUtxos((String) account.get("address"), 100, 1).getValue();
            assertEquals(2, inputs.size());
            request.put("amount", "2");
            request.put("inputRefs", List.of(inputs.get(0).getTxHash() + "#" + inputs.get(0).getOutputIndex()));
            var first = map(service.prepare(request));
            request.put("inputRefs", List.of(inputs.get(1).getTxHash() + "#" + inputs.get(1).getOutputIndex()));
            var second = map(service.prepare(request));
            execute(first);
            assertThrows(IllegalArgumentException.class, () -> execute(second));
            execute(map(service.prepare(request)));
            assertEquals("24000000", map(map(service.restore(locator)).get("budget")).get("spent"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void createTransferRotateReplaceAndRecoverThroughDemoApi(int mode) throws Exception {
        topUp(1000);
        topUp(20);
        topUp(20);
        Thread.sleep(2000);
        String keys = newKeys();
        var request = new HashMap<String, Object>();
        request.put("action", "Create account");
        request.put("keys", keys);
        request.put(
                "policies",
                List.of(
                        Map.of("threshold", 1, "members", List.of(0, 1)),
                        Map.of("threshold", 2, "members", List.of(0, 2)),
                        Map.of("threshold", 1, "members", List.of(1)),
                        Map.of("threshold", 1, "members", List.of(2)),
                        Map.of("threshold", 1, "members", List.of(1)),
                        Map.of("threshold", 1, "members", List.of(2))));
        request.put("mode", String.valueOf(mode));
        request.put("sponsor", sponsor.baseAddress());
        var plan = map(service.prepare(request));
        String locator = "";
        while (true) {
            plan = execute(plan);
            if (plan.get("locator") instanceof String value) locator = value;
            if (!Boolean.TRUE.equals(plan.get("canAdvance"))) break;
            plan = map(service.update((String) plan.get("id"), "advance", Map.of()));
        }
        assertFalse(locator.isBlank());
        var account = map(service.restore(locator));
        assertEquals("Normal", account.get("mode"));
        var tokenPolicy =
                new ScriptPubkey(
                        HexUtil.encodeHexString(sponsor.hdKeyPair().getPublicKey().getKeyHash()));
        String tokenUnit =
                tokenPolicy.getPolicyId()
                        + HexUtil.encodeHexString("KavachDemo".getBytes(StandardCharsets.UTF_8));
        var funding =
                new QuickTxBuilder(backend)
                        .compose(
                                new Tx()
                                        .mintAssets(
                                                tokenPolicy,
                                                List.of(
                                                        new Asset(
                                                                "KavachDemo",
                                                                BigInteger.valueOf(100))))
                                        .payToAddress(
                                                (String) account.get("address"),
                                                List.of(
                                                        Amount.ada(5),
                                                        new Amount(
                                                                tokenUnit,
                                                                BigInteger.valueOf(100))))
                                        .payToAddress(
                                                (String) account.get("address"), Amount.ada(80))
                                        .from(sponsor.baseAddress()))
                        .withSigner(SignerProviders.signerFrom(sponsor))
                        .buildAndSign();
        var sent = backend.getTransactionService().submitTransaction(funding.serialize());
        assertTrue(sent.isSuccessful(), sent.toString());
        await(sent.getValue());
        topUp(20);
        Thread.sleep(1200);
        request.put("locator", locator);
        request.put("recipient", new Account(new Network(0, 42)).baseAddress());
        request.put("amount", "2");
        request.put("action", "Send assets");
        request.put("approvers", "1");
        String otherRecipient = (String) request.get("recipient");
        request.put("recipient", sponsor.baseAddress());
        request.put("amount", "22");
        var sponsored = map(service.prepare(request));
        var sponsoredResult = execute(sponsored);
        var sponsoredTx = Transaction.deserialize(HexUtil.decodeHexString((String) sponsoredResult.get("transaction")));
        assertEquals(sponsor.baseAddress(), sponsoredTx.getBody().getOutputs().getFirst().getAddress());
        assertEquals(BigInteger.valueOf(22_000_000), sponsoredTx.getBody().getOutputs().getFirst().getValue().getCoin());
        assertTrue(sponsoredTx.getBody().getInputs().size() >= 2, "Sponsor must contribute a separate fee input");
        request.put("recipient", otherRecipient);
        request.put("amount", "2");
        execute(map(service.prepare(request)));
        request.remove("approvers");
        request.put("asset", tokenUnit);
        request.put("quantity", "7");
        execute(map(service.prepare(request)));
        assertEquals(
                "93",
                ((List<Map<String, Object>>) map(service.restore(locator)).get("assets"))
                        .getFirst()
                        .get("quantity"));
        request.remove("asset");
        request.remove("quantity");
        request.put("consolidate", true);
        execute(map(service.prepare(request)));
        request.remove("consolidate");
        request.put("action", "Freeze account");
        execute(map(service.prepare(request)));
        assertEquals("Frozen", map(service.restore(locator)).get("mode"));
        request.put("action", "Unfreeze account");
        execute(map(service.prepare(request)));
        keys = newKeys();
        request.put("target", keys);
        request.put("action", "Rotate keys");
        execute(map(service.prepare(request)));
        request.put("action", "Replace module");
        request.put("mode", mode == 1 ? "2" : "1");
        plan = map(service.prepare(request));
        while (true) {
            plan = execute(plan);
            if (!Boolean.TRUE.equals(plan.get("canAdvance"))) break;
            plan = map(service.update((String) plan.get("id"), "advance", Map.of()));
        }
        assertEquals(mode == 1 ? 2 : 1, map(service.restore(locator)).get("signingMode"));
        request.put("action", "Send assets");
        request.put("approvers", "1");
        execute(map(service.prepare(request)));
        request.remove("approvers");
        request.put("action", "Start recovery");
        execute(map(service.prepare(request)));
        assertEquals("RecoveryPending", map(service.restore(locator)).get("mode"));
        request.put("action", "Cancel recovery");
        execute(map(service.prepare(request)));
        assertEquals("Frozen", map(service.restore(locator)).get("mode"));
        request.put("action", "Unfreeze account");
        execute(map(service.prepare(request)));
        request.put("action", "Send assets");
        request.put("whole", true);
        execute(map(service.prepare(request)));
        assertEquals("0", map(service.restore(locator)).get("balance"));
        assertTrue(((List<?>) map(service.restore(locator)).get("assets")).isEmpty());
        System.out.println(
                "Demo API mode "
                        + mode
                        + " creation, transfers, key rotation, cross-mode replacement and recovery"
                        + " cancellation passed");
    }

    private Map<String, Object> execute(Map<String, Object> plan) throws Exception {
        String id = (String) plan.get("id");
        while (plan.get("transaction") == null) {
            var requests = (List<?>) plan.get("payloads");
            assertFalse(requests.isEmpty());
            var proof = map(requests.getFirst());
            String publicKey = (String) proof.get("publicKey");
            var key = authorities.get(publicKey);
            assertNotNull(key);
            byte[] address = new byte[29];
            address[0] = 0x60;
            System.arraycopy(
                    BrowserSignatures.keyHash(HexUtil.decodeHexString(publicKey)),
                    0,
                    address,
                    1,
                    28);
            byte[] payload = HexUtil.decodeHexString((String) proof.get("payload"));
            var response = CIP30DataSigner.INSTANCE.signData(address, payload,
                    ((EdECPrivateKey) key.getPrivate()).getBytes().orElseThrow(), HexUtil.decodeHexString(publicKey));
            if (Boolean.TRUE.equals(proof.get("companionSupported"))) {
                var exported = map(service.update(id, "companion-request", Map.of("credentialId", proof.get("id"), "purpose", proof.get("purpose"))));
                var envelope = map(exported.get("request"));
                var body = new ObjectMapper().readTree(Base64.getDecoder().decode((String) envelope.get("body")));
                if (captureMixedCreation) {
                    var evidence = Path.of("build/mixed-creation/companion");
                    Files.createDirectories(evidence);
                    new ObjectMapper().writeValue(evidence.resolve(body.get("id").asText() + ".json").toFile(),
                            Map.of("request", envelope, "qr", exported.get("qr"), "digest", proof.get("payload")));
                }
                if (body.get("profile").asText().equals("kavach-cose-policy-v1")) {
                    assertEquals(proof.get("purpose"), body.get("proofPurpose").asText());
                    assertTrue(((String) exported.get("qr")).length() <= 2953, "Policy request must fit the dashboard low-correction QR");
                    var evidence = Path.of("build/policy/companion");
                    Files.createDirectories(evidence);
                    new ObjectMapper().writeValue(evidence.resolve(body.get("id").asText() + ".json").toFile(),
                            Map.of("request", envelope, "qr", exported.get("qr"), "digest", proof.get("payload")));
                }
                var approval = new HashMap<String, Object>();
                approval.put("version", 1);
                approval.put("kind", "approval");
                approval.put("requestID", body.get("id").asText());
                approval.put("profile", body.get("profile").asText());
                approval.put("credentialID", proof.get("id"));
                approval.put("publicKey", publicKey);
                approval.put("digest", proof.get("payload"));
                approval.put("signature", response.signature());
                approval.put("key", response.key());
                var mismatched = new HashMap<>(approval);
                mismatched.put("requestID", "wrong-plan");
                assertThrows(IllegalArgumentException.class, () -> service.update(id, "companion-proof", Map.of("credentialId", proof.get("id"), "purpose", proof.get("purpose"), "response", mismatched)));
                plan = map(service.update(id, "companion-proof", Map.of("credentialId", proof.get("id"), "purpose", proof.get("purpose"), "response", approval)));
                assertThrows(IllegalArgumentException.class, () -> service.update(id, "companion-proof", Map.of("credentialId", proof.get("id"), "purpose", proof.get("purpose"), "response", approval)));
            } else {
                plan = map(service.update(id, "proofs", Map.of("credentialId", proof.get("id"), "purpose", proof.get("purpose"), "signature", response.signature(), "key", response.key())));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> service.update(id, "submit", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.update(id, "witnesses", Map.of("witnesses", "a10180")));
        var tx = Transaction.deserialize(HexUtil.decodeHexString((String) plan.get("transaction")));
        var changed = Transaction.deserialize(tx.serialize());
        changed.getBody().setFee(changed.getBody().getFee().add(BigInteger.ONE));
        var wrongSet = new TransactionWitnessSet();
        wrongSet.setVkeyWitnesses(sponsor.sign(changed).getWitnessSet().getVkeyWitnesses());
        var wrongBytes = new ByteArrayOutputStream();
        new CborEncoder(wrongBytes).encode(wrongSet.serialize());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.update(
                                id,
                                "witnesses",
                                Map.of(
                                        "witnesses",
                                        HexUtil.encodeHexString(wrongBytes.toByteArray()))));
        assertTrue(((List<?>) map(service.plan(id)).get("approvals")).isEmpty());
        var signed = sponsor.sign(tx);
        byte[] digest = HexUtil.decodeHexString(TransactionUtil.getTxHash(tx));
        var witnesses = new ArrayList<>(signed.getWitnessSet().getVkeyWitnesses());
        for (Object required : (List<?>) plan.get("requiredSigners"))
            for (var entry : authorities.entrySet())
                if (HexUtil.encodeHexString(
                                BrowserSignatures.keyHash(HexUtil.decodeHexString(entry.getKey())))
                        .equals(required))
                    witnesses.add(
                            new VkeyWitness(
                                    HexUtil.decodeHexString(entry.getKey()),
                                    sign(entry.getValue(), digest)));
        var set = new TransactionWitnessSet();
        set.setVkeyWitnesses(witnesses);
        var bytes = new ByteArrayOutputStream();
        new CborEncoder(bytes).encode(set.serialize());
        plan =
                map(
                        service.update(
                                id,
                                "witnesses",
                                Map.of("witnesses", HexUtil.encodeHexString(bytes.toByteArray()))));
        assertEquals("Ready", plan.get("status"));
        plan = map(service.update(id, "submit", Map.of()));
        System.out.println(plan.get("title") + " confirmed candidate " + plan.get("txHash"));
        await((String) plan.get("txHash"));
        return plan;
    }

    private String newKeys() throws Exception {
        return newKeys(3);
    }

    private String newKeys(int count) throws Exception {
        var result = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] encoded = key.getPublic().getEncoded();
            String publicKey =
                    HexUtil.encodeHexString(
                            Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
            authorities.put(publicKey, key);
            result.add(publicKey);
        }
        return String.join("\n", result);
    }

    private static byte[] sign(KeyPair key, byte[] message) throws Exception {
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(message);
        return signer.sign();
    }

    private void await(String hash) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (backend.getTransactionService().getTransaction(hash).isSuccessful()) {
                Thread.sleep(1000);
                return;
            }
            Thread.sleep(1000);
        }
        fail("Unconfirmed transaction " + hash);
    }

    private void topUp(long amount) throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:10000/local-cluster/api/addresses/topup"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"address\":\""
                                                + sponsor.baseAddress()
                                                + "\",\"adaAmount\":"
                                                + amount
                                                + "}"))
                        .build();
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(
                    200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
