package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import java.security.KeyPair;
import java.nio.charset.StandardCharsets;

final class BindingFixtures {
    static final byte[] DOMAIN = "KAVACH_PHASE0_BINDING_V0".getBytes(StandardCharsets.UTF_8);
    record Pair(PlutusV3Script module, PlutusV3Script core) {}
    static Pair pair(KeyPair key, byte[] policy, Address sink, byte[] holder, byte[] creator) throws Exception {
        var module = load(0, key, policy, new byte[]{}, sink, holder, creator);
        return new Pair(module, load(1, key, policy, module.getScriptHash(), sink, holder, creator));
    }
    static PlutusV3Script load(int role, KeyPair key, byte[] policy, byte[] module, Address sink, byte[] holder, byte[] creator) {
        return JulcScriptLoader.load(BindingCheckpointProbe.class,
                data(PlutusData.integer(role)), data(PlutusData.bytes(DOMAIN)), data(PlutusData.bytes(policy)),
                data(PlutusData.integer(0)), data(PlutusData.bytes(ProbeFixtures.publicKey(key))), data(PlutusData.bytes(module)),
                data(sink.toPlutusData()), data(PlutusData.bytes(holder)), data(PlutusData.bytes(creator)), data(PlutusData.bytes(StateProbeFixtures.DOMAIN)));
    }
    private static com.bloxbean.cardano.client.plutus.spec.PlutusData data(PlutusData data) {
        return PlutusDataAdapter.toClientLib(data);
    }
    static PlutusData envelope(byte[] policy, TxOutRef consumed, Pair pair) throws Exception {
        return PlutusData.constr(0, PlutusData.bytes(DOMAIN), PlutusData.bytes(policy), PlutusData.integer(0), PlutusData.integer(0),
                consumed.toPlutusData(), PlutusData.bytes(pair.core.getScriptHash()), PlutusData.bytes(pair.module.getScriptHash()));
    }
    static PlutusData authorization(KeyPair key, PlutusData envelope, long coreReceipt, long moduleReceipt) throws Exception {
        var signature = ((PlutusData.ConstrData) ProbeFixtures.authorize(key, envelope)).fields().get(1);
        return PlutusData.constr(0, envelope, signature, PlutusData.integer(coreReceipt), PlutusData.integer(moduleReceipt));
    }
}
