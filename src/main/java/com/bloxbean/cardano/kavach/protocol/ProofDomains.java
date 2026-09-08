package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Canonical auxiliary proof preimages; authorization and state transitions remain validator obligations. */
public final class ProofDomains {
    private static PlutusData tag(String value) { return PlutusData.bytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static PlutusData hash(PlutusData data) { return PlutusData.bytes(WireFormat.digest(data)); }
    /**
     * Builds the separate new-configuration possession preimage for a Phase 2 replacement.
     * @param state authenticated current state datum
     * @param intent matching ReplaceConfig or ReplaceModule intent
     * @return canonical preimage Data; hash before signing
     * @throws IllegalArgumentException if the state, domain or replacement action is inconsistent
     */
    public static PlutusData configuration(PlutusData state, PlutusData intent) {
        WireFormat.validateState(state); WireFormat.renderIntent(intent); bind(state, intent);
        var action = (PlutusData.ConstrData) ((PlutusData.ConstrData) intent).fields().get(3);
        if (action.tag() != 1 && action.tag() != 2) throw new IllegalArgumentException("not configuration action");
        var module = action.tag() == 1 ? ((PlutusData.ConstrData) state).fields().get(5) : action.fields().get(0);
        if (action.tag() == 2 && ((PlutusData.ConstrData) module).fields().get(0)
                .equals(((PlutusData.ConstrData) ((PlutusData.ConstrData) state).fields().get(5)).fields().get(0)))
            throw new IllegalArgumentException("same module hash; use ReplaceConfig");
        var config = action.fields().get(action.tag() == 1 ? 0 : 1);
        return PlutusData.constr(0, tag("KAVACH_CONFIG_POSSESSION_V1"), hash(intent), module, hash(config));
    }
    /**
     * Builds the genesis all-key possession preimage after final script identities are known.
     * @param state complete Normal genesis datum with zero state and recovery counters
     * @return canonical genesis preimage Data; hash before signing
     * @throws IllegalArgumentException if the supplied datum is not a supported genesis state
     */
    public static PlutusData genesis(PlutusData state) {
        WireFormat.validateState(state);
        var fields = ((PlutusData.ConstrData) state).fields();
        if (!fields.get(4).equals(PlutusData.integer(0)) || !fields.get(7).equals(PlutusData.integer(0))
                || !fields.get(10).equals(PlutusData.integer(0)) || !fields.get(11).equals(PlutusData.constr(0)))
            throw new IllegalArgumentException("non-genesis state");
        return PlutusData.constr(0, tag("KAVACH_GENESIS_POSSESSION_V1"), fields.get(2), fields.get(1), fields.get(3), fields.get(5), hash(fields.get(6)));
    }
    /**
     * Builds the recovery proposal commitment preimage for the Phase 2 specification.
     * @param state authenticated current state
     * @param initiation matching StartRecovery intent with the next sequence
     * @return proposal preimage Data; this method does not authorize initiation
     * @throws IllegalArgumentException if state binding, action or sequence is invalid
     */
    public static PlutusData recoveryCommitment(PlutusData state, PlutusData initiation) {
        WireFormat.validateState(state); WireFormat.renderIntent(initiation); bind(state, initiation);
        var current = ((PlutusData.ConstrData) state).fields();
        var envelope = ((PlutusData.ConstrData) initiation).fields();
        var action = (PlutusData.ConstrData) envelope.get(3);
        if (action.tag() != 5) throw new IllegalArgumentException("not initiation");
        BigInteger sequence = ((PlutusData.IntData) current.get(7)).value();
        if (sequence.equals(BigInteger.valueOf(Long.MAX_VALUE)) || !action.fields().get(0).equals(PlutusData.integer(sequence.add(BigInteger.ONE))))
            throw new IllegalArgumentException("recovery sequence");
        return PlutusData.constr(0, envelope.get(1), action.fields().get(0), current.get(5), action.fields().get(1), current.get(8), current.get(9));
    }
    /**
     * Builds the independent target-authorization preimage for Phase 2 recovery completion.
     * @param pendingState authenticated RecoveryPending state
     * @param completion matching CompleteRecovery intent and stored target configuration
     * @return canonical target preimage Data; timing and signature checks remain validator obligations
     * @throws IllegalArgumentException if the completion does not match the stored proposal
     */
    public static PlutusData target(PlutusData pendingState, PlutusData completion) {
        WireFormat.validateState(pendingState); WireFormat.renderIntent(completion); bind(pendingState, completion);
        var state = ((PlutusData.ConstrData) pendingState).fields();
        var pending = (PlutusData.ConstrData) state.get(11);
        if (pending.tag() != 2) throw new IllegalArgumentException("not pending");
        var envelope = ((PlutusData.ConstrData) completion).fields();
        var action = (PlutusData.ConstrData) envelope.get(3);
        if (action.tag() != 7) throw new IllegalArgumentException("not completion");
        if (!action.fields().get(0).equals(state.get(7)) || !action.fields().get(1).equals(pending.fields().get(2)))
            throw new IllegalArgumentException("target does not match stored proposal");
        return PlutusData.constr(0, tag("KAVACH_RECOVERY_TARGET_V1"), envelope.get(1), action.fields().get(0), pending.fields().get(0), hash(action.fields().get(1)));
    }
    private static void bind(PlutusData state, PlutusData intent) {
        var current = ((PlutusData.ConstrData) state).fields();
        var domain = ((PlutusData.ConstrData) ((PlutusData.ConstrData) intent).fields().get(1)).fields();
        if (!domain.get(1).equals(current.get(2)) || !domain.get(2).equals(current.get(1))
                || !domain.get(3).equals(current.get(3)) || !domain.get(4).equals(current.get(4)))
            throw new IllegalArgumentException("state/domain mismatch");
    }
    private ProofDomains() {}
}
