package com.bloxbean.cardano.kavach.auth.browser;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.KeyEntry;
import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.Signature;

import java.math.BigInteger;

/**
 * Explicit browser evidence profiles: 1 is ledger payment-key witnesses; 2 is the
 * bounded CIP-30 COSE profile. The applied module parameter fixes the mode, so evidence
 * cannot request a downgrade. Registry public keys and all policy rules remain authenticated.
 */
@OnchainLibrary
public class BrowserEvidenceLib {
    /**
     * Verifies every ordered proof against a registered key; threshold checks are separate.
     */
    public static boolean signatures(JulcList<Signature> evidence, JulcList<KeyEntry> registry,
                                     byte[] message, int maximum, BigInteger mode, BigInteger network, ScriptContext ctx) {
        if (evidence.isEmpty() || AccountLib.listSize((JulcList<PlutusData>) (Object) evidence) > maximum) return false;
        return verifySorted(evidence, registry, message, mode, network, ctx);
    }

    /**
     * Ordered subset merge rejects unknown, duplicate and descending credential IDs.
     */
    static boolean verifySorted(JulcList<Signature> evidence, JulcList<KeyEntry> registry,
                                byte[] message, BigInteger mode, BigInteger network, ScriptContext ctx) {
        if (evidence.isEmpty()) return true;
        if (registry.isEmpty()) return false;
        var proof = evidence.head();
        var key = registry.head();
        int order = proof.credentialId().compareTo(key.credentialId());
        if (order < 0) return false;
        if (order > 0) return verifySorted(evidence, registry.tail(), message, mode, network, ctx);
        if (!AccountLib.shape((PlutusData) (Object) proof, 0, 2)) return false;
        boolean valid = mode.equals(BigInteger.ONE)
                ? Builtins.lengthOfByteString(proof.signature()) == 0
                && ctx.txInfo().signatories().contains(new PubKeyHash(Builtins.blake2b_224(key.publicKey())))
                : mode.equals(BigInteger.TWO) && cose(key.publicKey(), message, proof.signature(), network);
        return valid && verifySorted(evidence.tail(), registry.tail(), message, mode, network, ctx);
    }

    /**
     * Verifies the exact COSE structure reconstructed from bounded evidence.
     * Only key-payment enterprise/base addresses and the exact protected {1:-8,address:...}
     * encoding, optionally with an address-valued kid before address, are supported.
     * A trailing byte 1 selects kid; it changes signed bytes and cannot bypass verification.
     */
    public static boolean cose(byte[] key, byte[] message, byte[] packed, BigInteger network) {
        long size = Builtins.lengthOfByteString(packed);
        boolean withKid = size == 94 || size == 122;
        if ((size != 93 && size != 121 && !withKid) || Builtins.lengthOfByteString(key) != 32
                || Builtins.lengthOfByteString(message) != 32 || (network.intValue() != 0 && network.intValue() != 1))
            return false;
        if (withKid && Builtins.indexByteString(packed, size - 1) != 1) return false;
        long addressLength = size - 64 - (withKid ? 1 : 0);
        long header = Builtins.indexByteString(packed, 0);
        long kind = header / 16;
        if (header % 16 != network.longValue() || !(addressLength == 29 ? kind == 6 : kind == 0 || kind == 2))
            return false;
        if (!Builtins.equalsByteString(Builtins.sliceByteString(1, 28, packed), Builtins.blake2b_224(key)))
            return false;
        var address = Builtins.sliceByteString(0, addressLength, packed);
        var signature = Builtins.sliceByteString(addressLength, 64, packed);
        return Builtins.verifyEd25519Signature(key, signingStructureWithKid(address, message, withKid), signature);
    }

    /**
     * Canonical CBOR Sig_structure: ["Signature1", protected, empty external AAD, 32-byte payload].
     */
    public static byte[] signingStructure(byte[] address, byte[] message) {
        return signingStructureWithKid(address, message, false);
    }

    /**
     * Reconstructs the signed optional kid, always equal to the authenticated payment address.
     */
    public static byte[] signingStructureWithKid(byte[] address, byte[] message, boolean withKid) {
        var prefix = withKid
                ? Builtins.appendByteString(Builtins.consByteString(163, new byte[]{1, 39, 4}), shortBytes(address))
                : Builtins.consByteString(162, new byte[]{1, 39});
        var protectedBytes = Builtins.appendByteString(prefix,
                Builtins.appendByteString(new byte[]{103, 97, 100, 100, 114, 101, 115, 115}, shortBytes(address)));
        return Builtins.appendByteString(Builtins.appendByteString(Builtins.consByteString(132, new byte[]{106, 83, 105, 103, 110, 97, 116, 117, 114, 101, 49}), shortBytes(protectedBytes)),
                Builtins.appendByteString(new byte[]{64, 88, 32}, message));
    }

    /**
     * Two-byte CBOR byte-string length prefix; callers supply lengths between 24 and 255.
     */
    static byte[] shortBytes(byte[] bytes) {
        return Builtins.consByteString(88, Builtins.consByteString(Builtins.lengthOfByteString(bytes), bytes));
    }
}
