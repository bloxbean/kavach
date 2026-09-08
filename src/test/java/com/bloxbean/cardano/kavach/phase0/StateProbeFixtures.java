package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.TxOutRef;

import java.nio.charset.StandardCharsets;

final class StateProbeFixtures {
    static final byte[] DOMAIN = "KAVACH_PHASE0_STATE_V0".getBytes(StandardCharsets.UTF_8);

    static PlutusData state(byte[] creator) {
        return PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(DOMAIN), PlutusData.bytes(creator));
    }

    static PlutusV3Script holder() { return JulcScriptLoader.load(SealedStateProbe.class); }

    static PlutusV3Script mint(TxOutRef seed, byte[] creator, byte[] holderHash) {
        return JulcScriptLoader.load(StateNftMintProbe.class, PlutusDataAdapter.toClientLib(seed.toPlutusData()),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(creator)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(holderHash)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(DOMAIN)));
    }

    static PlutusV3Script reader(byte[] policy, byte[] holderHash, byte[] creator) {
        return JulcScriptLoader.load(StateReferenceProbe.class, PlutusDataAdapter.toClientLib(PlutusData.bytes(policy)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(holderHash)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(DOMAIN)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(creator)));
    }
}
