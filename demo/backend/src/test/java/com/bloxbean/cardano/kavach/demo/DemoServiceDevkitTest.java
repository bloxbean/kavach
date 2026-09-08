package com.bloxbean.cardano.kavach.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import java.security.interfaces.EdECPrivateKey;

import co.nstant.in.cbor.CborEncoder;

import com.bloxbean.cardano.client.account.Account;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/**
 * Exercises exactly the demo service's public workflow with disposable CIP-30-shaped signer
 * responses.
 */
@Tag("demoDevkit")
@Timeout(600)
class DemoServiceDevkitTest {
    private final DemoService service = new DemoService();
    private final Account sponsor = new Account(new Network(0, 42));
    private final Map<String, KeyPair> authorities = new HashMap<>();
    private final BFBackendService backend =
            new BFBackendService("http://localhost:8080/api/v1/", "devkit");

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
                var approval = new HashMap<String, Object>();
                approval.put("version", 1); approval.put("kind", "approval");
                approval.put("requestID", body.get("id").asText());
                approval.put("profile", body.get("profile").asText());
                approval.put("credentialID", proof.get("id")); approval.put("publicKey", publicKey);
                approval.put("digest", proof.get("payload")); approval.put("signature", response.signature()); approval.put("key", response.key());
                var mismatched = new HashMap<>(approval); mismatched.put("requestID", "wrong-plan");
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
        var result = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
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
