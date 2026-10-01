package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;
import com.bloxbean.cardano.kavach.sdk.ReferenceVaultDeployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.List;
import java.util.Optional;

/** Public local artifact backup and address discovery hints, never an authorization registry. */
final class ReferenceHosting {
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    record Record(int networkMagic, String language, String hash, String cbor, List<String> publishers) { }

    ReferenceHosting(Path directory) { this.directory = directory; }

    synchronized void remember(PlutusV3Script script, String publisher) throws Exception {
        String hash = HexUtil.encodeHexString(script.getScriptHash());
        var previous = read(hash);
        var publishers = new ArrayList<>(previous.map(Record::publishers).orElse(List.of()));
        if (publisher != null && !publishers.contains(publisher)) publishers.add(publisher);
        if (publishers.size() > 32) throw new IllegalArgumentException("Reference hosting history exceeds 32 publishers");
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, hash, ".tmp");
        try {
            json.writeValue(temporary.toFile(), new Record(42, "PlutusV3", hash, script.getCborHex(), List.copyOf(publishers)));
            Files.move(temporary, path(hash), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Replaces public resume hints atomically; a crash must not leave a truncated commitment. */
    static void writePublicRecord(Path destination, String contents) throws Exception {
        Files.createDirectories(destination.getParent());
        var temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    Optional<PlutusV3Script> script(String hash) throws Exception {
        var record = read(hash);
        return record.map(r -> PlutusV3Script.builder().cborHex(r.cbor()).build());
    }

    List<String> publishers(String hash) throws Exception {
        return read(hash).map(Record::publishers).orElse(List.of());
    }

    private Optional<Record> read(String hash) throws Exception {
        var path = path(hash);
        if (!Files.exists(path)) return Optional.empty();
        if (Files.size(path) > 200_000) throw new IllegalArgumentException("Oversized reference hosting record");
        var record = json.readValue(path.toFile(), Record.class);
        var script = PlutusV3Script.builder().cborHex(record.cbor()).build();
        if (record.networkMagic() != 42 || !"PlutusV3".equals(record.language()) || !hash.equals(record.hash())
                || !hash.equals(HexUtil.encodeHexString(script.getScriptHash())) || record.publishers() == null
                || record.publishers().size() > 32)
            throw new IllegalArgumentException("Reference artifact identity mismatch");
        return Optional.of(record);
    }

    record Vault(int version, int networkMagic, String publisherKeyHash, String publisherAddress,
                 String accountPolicy, String accountName, String hash, String cbor, String address) {
        AccountId accountId() { return new AccountId(HexUtil.decodeHexString(accountPolicy), HexUtil.decodeHexString(accountName)); }
    }

    synchronized Vault rememberVault(byte[] publisher, String publisherAddress, AccountId account) throws Exception {
        var script = ReferenceVaultDeployment.derive(publisher, account);
        var hash = HexUtil.encodeHexString(script.getScriptHash());
        var address = ReferenceVaultDeployment.address(publisher, account, new Network(0, 42));
        var record = new Vault(1, 42, HexUtil.encodeHexString(publisher), publisherAddress,
                HexUtil.encodeHexString(account.policy()), HexUtil.encodeHexString(account.name()), hash, script.getCborHex(), address);
        remember(script, null);
        writePublicRecord(directory.resolve("vaults").resolve(hash + ".json"), json.writeValueAsString(record));
        return record;
    }

    Optional<Vault> vault(String address) throws Exception {
        var decoded = new Address(address);
        byte[] bytes = decoded.getBytes();
        if (((bytes[0] & 255) >>> 4) != 7) return Optional.empty();
        String hash = HexUtil.encodeHexString(Arrays.copyOfRange(bytes, 1, 29));
        var file = directory.resolve("vaults").resolve(hash + ".json");
        if (!Files.exists(file)) return Optional.empty();
        if (Files.size(file) > 200_000) throw new IllegalArgumentException("Oversized vault record");
        var record = json.readValue(file.toFile(), Vault.class);
        var publisher = HexUtil.decodeHexString(record.publisherKeyHash());
        var script = ReferenceVaultDeployment.derive(publisher, record.accountId());
        var wallet = new Address(record.publisherAddress());
        if (record.version() != 1 || record.networkMagic() != 42 || !hash.equals(record.hash())
                || !hash.equals(HexUtil.encodeHexString(script.getScriptHash())) || !script.getCborHex().equals(record.cbor())
                || !address.equals(record.address()) || !address.equals(ReferenceVaultDeployment.address(publisher, record.accountId(), new Network(0, 42)))
                || (wallet.getBytes()[0] & 15) != 0
                || !Arrays.equals(publisher, wallet.getPaymentCredentialHash().orElseThrow())
                || !Set.of(0, 2, 6).contains((wallet.getBytes()[0] & 255) >>> 4))
            throw new IllegalArgumentException("Vault artifact or publisher identity mismatch");
        return Optional.of(record);
    }

    private Path path(String hash) {
        if (!hash.matches("[0-9a-f]{56}")) throw new IllegalArgumentException("Invalid reference script hash");
        return directory.resolve(hash + ".json");
    }
}
