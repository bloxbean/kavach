package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EconomicsTest {
    @TempDir Path directory;
    private static PlutusV3Script script() { return PlutusV3Script.builder().cborHex("46010000200101").build(); }
    private static ProtocolParams parameters() { return ProtocolParams.builder().coinsPerUtxoSize("4310").build(); }

    @Test void exactMinimumIncludesReferenceAndCoinAndPreservesLargerStateReserve() throws Exception {
        var output = new TransactionOutput(new Account(Networks.testnet()).baseAddress(), Value.fromCoin(BigInteger.ZERO));
        output.setScriptRef(script());
        var minimum = OutputMinimum.fund(output, parameters());
        assertEquals(minimum, OutputMinimum.required(output, parameters()));
        assertEquals(minimum, new MinAdaCalculator(parameters()).calculateMinAda(output));
        output.getValue().setCoin(minimum.subtract(BigInteger.ONE));
        assertTrue(output.getValue().getCoin().compareTo(OutputMinimum.required(output, parameters())) < 0);
        output.getValue().setCoin(BigInteger.valueOf(12_000_000));
        output.setInlineDatum(BigIntPlutusData.of(123));
        assertEquals(BigInteger.valueOf(12_000_000), OutputMinimum.fund(output, parameters()));
    }

    @Test void largerReferenceRequiresMoreCapitalAndParametersAreLive() throws Exception {
        var output = new TransactionOutput(new Account(Networks.testnet()).baseAddress(), Value.fromCoin(BigInteger.ZERO));
        var plain = OutputMinimum.fund(output, parameters());
        output.setScriptRef(script());
        var hosted = OutputMinimum.fund(output, parameters());
        assertTrue(hosted.compareTo(plain) > 0);
        var doubled = ProtocolParams.builder().coinsPerUtxoSize("8620").build();
        assertEquals(hosted.multiply(BigInteger.TWO), OutputMinimum.fund(output, doubled));
    }

    @Test void hostingSurvivesRestartAndRejectsChangedArtifactAndTraversal() throws Exception {
        var script = script();
        var hash = HexUtil.encodeHexString(script.getScriptHash());
        var publisher = new Account(Networks.testnet()).baseAddress();
        var hosting = new ReferenceHosting(directory);
        hosting.remember(script, publisher);
        hosting.remember(script, publisher);
        var restarted = new ReferenceHosting(directory);
        assertEquals(List.of(publisher), restarted.publishers(hash));
        assertEquals(script.getCborHex(), restarted.script(hash).orElseThrow().getCborHex());
        assertThrows(IllegalArgumentException.class, () -> restarted.script("../../outside"));
        var path = directory.resolve(hash + ".json");
        Files.writeString(path, Files.readString(path).replace("46010000200101", "46010000200102"));
        assertThrows(IllegalArgumentException.class, () -> restarted.script(hash));
    }

    @Test void vaultRecordsAuthenticatePublisherAccountExactArtifactAndNetworkAcrossRestart() throws Exception {
        var wallet = new Account(Networks.testnet()).baseAddress();
        byte[] owner = new Address(wallet).getPaymentCredentialHash().orElseThrow();
        var account = new AccountId(new byte[28], new byte[]{1, 2});
        var hosting = new ReferenceHosting(directory);
        var vault = hosting.rememberVault(owner, wallet, account);
        hosting.remember(script(), vault.address());
        var restarted = new ReferenceHosting(directory);
        assertEquals(vault, restarted.vault(vault.address()).orElseThrow());
        assertEquals(List.of(vault.address()), restarted.publishers(HexUtil.encodeHexString(script().getScriptHash())));
        assertTrue(restarted.vault(wallet).isEmpty());
        var path = directory.resolve("vaults").resolve(vault.hash() + ".json");
        var original = Files.readString(path);
        Files.writeString(path, original.replace("\"accountName\":\"0102\"", "\"accountName\":\"0103\""));
        assertThrows(IllegalArgumentException.class, () -> restarted.vault(vault.address()));
        Files.writeString(path, original.replace("\"version\":1", "\"version\":2"));
        assertThrows(IllegalArgumentException.class, () -> restarted.vault(vault.address()));
        Files.writeString(path, original.replace("\"networkMagic\":42", "\"networkMagic\":1"));
        assertThrows(IllegalArgumentException.class, () -> restarted.vault(vault.address()));
        var mapper = new ObjectMapper();
        for (var field : List.of("cbor", "publisherKeyHash", "publisherAddress", "address", "hash")) {
            var changed = mapper.readTree(original);
            ((ObjectNode) changed).put(field,
                    switch (field) {
                        case "cbor" -> script().getCborHex();
                        case "publisherKeyHash", "hash" -> "ff".repeat(28);
                        case "publisherAddress" -> new Account(Networks.testnet()).baseAddress();
                        default -> wallet;
                    });
            Files.writeString(path, mapper.writeValueAsString(changed));
            assertThrows(Exception.class, () -> restarted.vault(vault.address()), field);
        }
        var mainnet = new Address(wallet).getBytes();
        mainnet[0] = (byte) ((mainnet[0] & 0xf0) | 1);
        Files.writeString(path, original.replace(wallet, new Address(mainnet).toBech32()));
        assertThrows(IllegalArgumentException.class, () -> restarted.vault(vault.address()));
        Files.writeString(path, " ".repeat(200_001));
        assertThrows(IllegalArgumentException.class, () -> restarted.vault(vault.address()));
        Files.writeString(path, original);
        assertEquals(vault.cbor(), restarted.script(vault.hash()).orElseThrow().getCborHex());
    }

    @Test void fundingPaginationDoesNotStopAtPageOfReferencesAndExplicitResolutionRemainsAvailable() {
        var all = new ArrayList<Utxo>();
        for (int i = 0; i < 102; i++) all.add(Utxo.builder().txHash(String.format("%064x", i)).outputIndex(0)
                .amount(List.of(Amount.ada(5))).referenceScriptHash(i < 100 ? "ab".repeat(28) : null).build());
        UtxoSupplier source = new UtxoSupplier() {
            public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                int start = Math.min(all.size(), page * count);
                return all.subList(start, Math.min(all.size(), start + count));
            }
            public Optional<Utxo> getTxOutput(String hash, int index) { return Optional.of(all.getFirst()); }
        };
        var filtered = new PlainFundingSupplier(source);
        assertEquals(List.of(all.get(100)), filtered.getPage("address", 1, 0, OrderEnum.asc));
        assertEquals(List.of(all.get(101)), filtered.getPage("address", 1, 1, OrderEnum.asc));
        assertTrue(filtered.getPage("address", 1, 2, OrderEnum.asc).isEmpty());
        assertEquals(List.of(all.get(100), all.get(101)), filtered.getAll("address"));
        assertNotNull(filtered.getTxOutput("hash", 0).orElseThrow().getReferenceScriptHash());
    }
}
