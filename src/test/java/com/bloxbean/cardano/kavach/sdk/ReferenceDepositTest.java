package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.DeploymentDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference publications must lock the ledger minimum for each script, not a flat deposit.
 *
 * <p>These are builder and serialization checks against a stubbed supplier, not ledger validation.
 * Live acceptance of the reduced deposits is established by the DevKit integration tests that
 * publish the same outputs. The deposits are unreclaimable by design, so over-reserving is a
 * permanent loss rather than a recoverable one; that is why this is asserted, not left to review.
 */
class ReferenceDepositTest {
    /** Yaci DevKit and current mainnet both charge 4310 lovelace per serialized UTxO byte. */
    private static final String COINS_PER_UTXO_SIZE = "4310";

    private ProtocolParams parameters() throws Exception {
        var parameters = new ObjectMapper().treeToValue(JsonUtil.parseJson(Files.readString(
                        Path.of("docs/phase0/evidence/binding-2026-09-07.json"))).get("protocolParameters"),
                ProtocolParams.class);
        parameters.setCoinsPerUtxoSize(COINS_PER_UTXO_SIZE);
        return parameters;
    }

    private AccountDeployment.Scripts scripts() throws Exception {
        var domain = new DeploymentDomain(BigInteger.ZERO, BigInteger.valueOf(42), new byte[32]);
        var sink = new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty());
        return AccountDeployment.derive(domain, new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO),
                new byte[28], sink, sink);
    }

    private static BigInteger minimum(ProtocolParams parameters, String holder, PlutusV3Script script)
            throws Exception {
        var output = new TransactionOutput(holder, Value.builder().coin(BigInteger.ZERO).build());
        output.setScriptRef(script.scriptRefBytes());
        return new MinAdaCalculator(parameters).calculateMinAda(output);
    }

    /**
     * A zero-coin request must be raised by the builder to exactly the ledger minimum, so no call
     * site needs a hardcoded deposit. Each account reference must also stay well under the former
     * flat 80 ADA, which is what makes the change worth the deposit-shape difference.
     */
    @Test
    void publicationLocksOnlyTheLedgerMinimum() throws Exception {
        var parameters = parameters();
        var scripts = scripts();
        String holder = AddressProvider.getEntAddress(scripts.state(), Networks.testnet()).toBech32();
        var sponsor = new Account(Networks.testnet());
        var funding = Utxo.builder().txHash(HexFormat.of().formatHex(new byte[32])).outputIndex(0)
                .address(sponsor.baseAddress()).amount(List.of(Amount.ada(10_000))).build();
        UtxoSupplier utxos = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                return page != null && page > 0 ? List.of() : List.of(funding);
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                return Optional.of(funding);
            }
        };
        ProtocolParamsSupplier supplier = () -> parameters;
        var builder = new QuickTxBuilder(utxos, supplier, null);
        var total = BigInteger.ZERO;
        for (var script : List.of(scripts.state(), scripts.checkpoint(), scripts.module(),
                scripts.nft(), scripts.asset())) {
            var transaction = builder.compose(new Tx()
                            .payToAddress(holder, Amount.lovelace(BigInteger.ZERO), script)
                            .from(sponsor.baseAddress()))
                    .feePayer(sponsor.baseAddress())
                    .withSigner(SignerProviders.signerFrom(sponsor))
                    .build();
            byte[] reference = script.scriptRefBytes();
            var published = transaction.getBody().getOutputs().stream()
                    .filter(o -> Arrays.equals(reference, o.getScriptRef())).toList();
            assertEquals(1, published.size(), "Exactly one reference output per publication");
            var deposit = published.getFirst().getValue().getCoin();
            assertEquals(minimum(parameters, holder, script), deposit,
                    "Builder must lock the ledger minimum for " + script.getScriptHash().length + "-hash script");
            assertTrue(deposit.compareTo(BigInteger.valueOf(80_000_000)) < 0,
                    "Reference deposit must stay under the former flat 80 ADA");
            total = total.add(deposit);
        }
        assertTrue(total.compareTo(BigInteger.valueOf(400_000_000)) < 0,
                "Five references must lock less than the former 400 ADA");
    }

    /**
     * Records the measured per-script minimums so deposit growth is visible when scripts change.
     * This is evidence, not a threshold: only the assertions above constrain the implementation.
     */
    @Test
    void recordsMeasuredReferenceMinimums() throws Exception {
        var parameters = parameters();
        var scripts = scripts();
        String holder = AddressProvider.getEntAddress(scripts.state(), Networks.testnet()).toBech32();
        var measured = new java.util.LinkedHashMap<String, Object>();
        var total = BigInteger.ZERO;
        for (var entry : List.of(Map.entry("state", scripts.state()), Map.entry("checkpoint", scripts.checkpoint()),
                Map.entry("module", scripts.module()), Map.entry("nft", scripts.nft()),
                Map.entry("asset", scripts.asset()))) {
            var deposit = minimum(parameters, holder, entry.getValue());
            total = total.add(deposit);
            measured.put(entry.getKey(), Map.of("scriptBytes", entry.getValue().getCborHex().length() / 2,
                    "minimumLovelace", deposit.toString()));
        }
        Files.createDirectories(Path.of("build/phase2"));
        Files.writeString(Path.of("build/phase2/reference-minimums.json"), JsonUtil.getPrettyJson(Map.of(
                "scope", "Serialized reference-output minimums at coinsPerUtxoSize " + COINS_PER_UTXO_SIZE
                        + "; derived from compiled scripts, not live ledger acceptance",
                "coinsPerUtxoSize", COINS_PER_UTXO_SIZE,
                "scripts", measured,
                "totalLovelace", total.toString())));
        assertTrue(total.signum() > 0);
    }
}
