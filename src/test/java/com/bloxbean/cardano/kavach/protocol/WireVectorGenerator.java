package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.KeyPair;
import java.math.BigInteger;

import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * Explicit fixture generator; never called by check and never writes private keys.
 */
public final class WireVectorGenerator {
    public static void main(String[] args) throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var encoded = key.getPublic().getEncoded();
        var pub = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        var vectors = new ArrayList<Object>();
        for (int tag = 0; tag < 9; tag++) {
            var envelope = WireFixtures.envelope(tag);
            var cbor = PlutusDataAdapter.toClientLib(envelope).serializeToBytes();
            var digest = Blake2bUtil.blake2bHash256(cbor);
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(digest);
            var vector = new LinkedHashMap<String, Object>();
            vector.put("actionTag", tag);
            vector.put("cbor", HexFormat.of().formatHex(cbor));
            vector.put("digest", HexFormat.of().formatHex(digest));
            vector.put("publicKey", HexFormat.of().formatHex(pub));
            vector.put("signature", HexFormat.of().formatHex(signer.sign()));
            vector.put("rendering", WireFormat.renderIntent(envelope, tag == 8 ? WireFixtures.wholeValue() : null));
            if (tag == 8)
                vector.put("resolvedInputValueCbor", PlutusDataAdapter.toClientLib(WireFixtures.wholeValue()).serializeToHex());
            vectors.add(vector);
        }
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/wire-v1-vectors.json"), JsonUtil.getPrettyJson(vectors));
        var edges = new ArrayList<Object>();
        var nativeValue = WireFixtures.list(WireFixtures.rec(WireFixtures.bytes(0, 0), WireFixtures.bytes(0, 0), WireFixtures.number(2_000_000)),
                WireFixtures.rec(WireFixtures.bytes(28, 1), WireFixtures.bytes(0, 0), WireFixtures.number(1)),
                WireFixtures.rec(WireFixtures.bytes(28, 1), WireFixtures.bytes(32, 255), WireFixtures.number(Long.MAX_VALUE)));
        var baseAddress = WireFixtures.rec(WireFixtures.rec(WireFixtures.bytes(28, 9)),
                WireFixtures.rec(WireFixtures.rec(PlutusData.constr(1, WireFixtures.bytes(28, 10)))));
        var nativeSpend = WireFixtures.replace(WireFixtures.envelope(0), 3, WireFixtures.rec(WireFixtures.list(WireFixtures.input(1)),
                WireFixtures.list(WireFixtures.rec(WireFixtures.number(0), baseAddress, nativeValue)), WireFixtures.number(0)));
        edges.add(edge("native-boundaries-and-staked-recipient", nativeSpend, null, key));
        edges.add(edge("consolidation", WireFixtures.replace(WireFixtures.envelope(0), 3, WireFixtures.rec(WireFixtures.list(WireFixtures.input(1)),
                WireFixtures.list(), WireFixtures.number(500000))), null, key));
        var largeQuantity = Value.lovelace(BigInteger.valueOf(2_000_000)).merge(Value.singleton(new PolicyId(new byte[28]),
                new TokenName(new byte[0]), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))).toPlutusData();
        var whole = WireFixtures.replace(WireFixtures.envelope(8), 3, PlutusData.constr(8, WireFixtures.input(1), WireFixtures.number(0), baseAddress,
                PlutusData.bytes(WireFormat.ledgerValueDigest(largeQuantity))));
        edges.add(edge("whole-uint64-native-token", whole, largeQuantity, key));
        Files.writeString(Path.of("build/phase0/edge-vectors.json"), JsonUtil.getPrettyJson(edges));
        WireFormat.validateState(WireFixtures.state());
        Files.writeString(Path.of("build/phase0/state-v1-vector.hex"), PlutusDataAdapter.toClientLib(WireFixtures.state()).serializeToHex() + "\n");
        var commitment = ProofDomains.recoveryCommitment(WireFixtures.state(), WireFixtures.envelope(5));
        var pending = WireFixtures.pendingState(WireFormat.digest(commitment));
        var domains = new LinkedHashMap<String, PlutusData>();
        domains.put("genesis-proof-envelope", ProofDomains.genesis(WireFixtures.state()));
        domains.put("configuration-proof-envelope", ProofDomains.configuration(WireFixtures.state(), WireFixtures.envelope(2)));
        domains.put("recovery-commitment", commitment);
        domains.put("target-proof-envelope", ProofDomains.target(pending, WireFixtures.completion()));
        var proofVectors = new LinkedHashMap<String, Object>();
        for (var entry : domains.entrySet())
            proofVectors.put(entry.getKey(), Map.of(
                    "cbor", PlutusDataAdapter.toClientLib(entry.getValue()).serializeToHex(),
                    "digest", HexFormat.of().formatHex(WireFormat.digest(entry.getValue()))));
        Files.writeString(Path.of("build/phase0/proof-domain-vectors.json"), JsonUtil.getPrettyJson(proofVectors));
        Files.writeString(Path.of("build/phase0/state-rendering.txt"), WireFormat.renderState(WireFixtures.state()));
        Files.writeString(Path.of("build/phase0/pending-state-rendering.txt"), WireFormat.renderState(pending));
        var setupKeys = List.of(key, KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        var registry = new ArrayList<PlutusData>();
        for (int i = 0; i < setupKeys.size(); i++) {
            byte[] publicEncoding = setupKeys.get(i).getPublic().getEncoded();
            registry.add(WireFixtures.rec(WireFixtures.number(i), PlutusData.bytes(Arrays.copyOfRange(publicEncoding, publicEncoding.length - 32, publicEncoding.length))));
        }
        var configFields = new ArrayList<>(((PlutusData.ConstrData) WireFixtures.config()).fields());
        configFields.set(1, new PlutusData.ListData(registry));
        var stateFields = new ArrayList<>(((PlutusData.ConstrData) WireFixtures.state()).fields());
        stateFields.set(6, new PlutusData.ConstrData(0, configFields));
        var setupState = new PlutusData.ConstrData(0, stateFields);
        var setupEnvelope = ProofDomains.genesis(setupState);
        byte[] setupDigest = WireFormat.digest(setupEnvelope);
        var proofs = new ArrayList<PlutusData>();
        for (int i = 0; i < setupKeys.size(); i++) {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(setupKeys.get(i).getPrivate());
            signer.update(setupDigest);
            proofs.add(WireFixtures.rec(WireFixtures.number(i), PlutusData.bytes(signer.sign())));
        }
        var invocation = PlutusData.constr(2, WireFixtures.number(1), setupState, new PlutusData.ListData(proofs), WireFixtures.list());
        var setup = new LinkedHashMap<String, Object>();
        setup.put("scope", "Valid all-key genesis possession over the full candidate state; not a deployed account");
        setup.put("stateCbor", PlutusDataAdapter.toClientLib(setupState).serializeToHex());
        setup.put("proofEnvelopeCbor", PlutusDataAdapter.toClientLib(setupEnvelope).serializeToHex());
        setup.put("digest", HexFormat.of().formatHex(setupDigest));
        setup.put("invocationCbor", PlutusDataAdapter.toClientLib(invocation).serializeToHex());
        Files.writeString(Path.of("build/phase0/genesis-possession-vector.json"), JsonUtil.getPrettyJson(setup));
    }

    private static Object edge(String name, PlutusData envelope, PlutusData resolved, KeyPair key) throws Exception {
        var vector = new LinkedHashMap<String, Object>();
        byte[] cbor = PlutusDataAdapter.toClientLib(envelope).serializeToBytes();
        byte[] digest = Blake2bUtil.blake2bHash256(cbor);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(digest);
        byte[] publicEncoding = key.getPublic().getEncoded();
        vector.put("name", name);
        vector.put("cbor", HexFormat.of().formatHex(cbor));
        vector.put("digest", HexFormat.of().formatHex(digest));
        vector.put("publicKey", HexFormat.of().formatHex(Arrays.copyOfRange(publicEncoding, publicEncoding.length - 32, publicEncoding.length)));
        vector.put("signature", HexFormat.of().formatHex(signer.sign()));
        vector.put("rendering", WireFormat.renderIntent(envelope, resolved));
        if (resolved != null)
            vector.put("resolvedInputValueCbor", PlutusDataAdapter.toClientLib(resolved).serializeToHex());
        return vector;
    }

}
