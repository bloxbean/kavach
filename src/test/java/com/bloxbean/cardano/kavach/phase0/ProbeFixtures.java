package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;

final class ProbeFixtures {
    static final byte[] DOMAIN = "KAVACH_PHASE0_WITHDRAWAL_PROBE_V0".getBytes(StandardCharsets.UTF_8);

    static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static byte[] publicKey(KeyPair key) {
        byte[] encoded = key.getPublic().getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    static PlutusV3Script script(KeyPair key) {
        return script(publicKey(key));
    }

    static PlutusV3Script script(byte[] publicKey) {
        return JulcScriptLoader.load(WithdrawalProbe.class,
                PlutusDataAdapter.toClientLib(PlutusData.bytes(publicKey)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(DOMAIN)));
    }

    // Explicit protocol-independent constructor layout, checked against compiled UPLC.
    static PlutusData challenge(byte[] domain, TxOutRef input) {
        return PlutusData.constr(0, PlutusData.bytes(domain), input.toPlutusData());
    }

    static byte[] digest(PlutusData challenge) {
        byte[] cbor = Builtins.serialiseData(challenge);
        return Blake2bUtil.blake2bHash256(cbor);
    }

    static PlutusData authorize(KeyPair key, PlutusData challenge) throws Exception {
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(digest(challenge));
        return PlutusData.constr(0, challenge, PlutusData.bytes(signer.sign()));
    }
}
