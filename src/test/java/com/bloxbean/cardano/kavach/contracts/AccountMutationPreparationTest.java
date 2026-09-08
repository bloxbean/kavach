package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountDeployment;
import com.bloxbean.cardano.kavach.sdk.AccountMutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** SDK attachment rejection occurs before any mutation of the caller's CCL transaction. */
class AccountMutationPreparationTest {
    private final AccountLifecycleTest lifecycle = new AccountLifecycleTest();
    private final AccountFixtures f = lifecycle.f;
    private final AccountDeployment.Scripts scripts;
    private final AccountMutation.Window window = new AccountMutation.Window(BigInteger.valueOf(1000), BigInteger.valueOf(9999), true);
    AccountMutationPreparationTest() throws Exception {
        scripts = new AccountDeployment.Scripts(f.stateScript, f.coreScript, f.moduleScript, f.nftScript, f.assetScript,
                f.state.accountId(), f.state.coreBinding(), f.state.authModule());
    }
    private Utxo state() throws Exception {
        return Utxo.builder().txHash(HexUtil.encodeHexString(f.stateRef.txId().hash())).outputIndex(f.stateRef.index().intValueExact())
                .address(AddressProvider.getEntAddress(f.stateScript, new Network(0, 42)).toBech32())
                .amount(List.of(Amount.ada(10), Amount.asset(HexUtil.encodeHexString(f.state.accountId().policy()), BigInteger.ONE)))
                .inlineDatum(PlutusDataAdapter.toClientLib(AccountCodec.data(f.state)).serializeToHex()).build();
    }
    @Test void freezeAttachesConsumedStateAndBothWithdrawals() throws Exception {
        var request = lifecycle.intent(f.state, new Freeze(), 1000, 9999);
        var approval = lifecycle.approval(f.state, request, 1);
        var tx = new Tx();
        assertSame(tx, AccountMutation.attach(tx, scripts, f.state, state(), approval, Optional.empty(), Optional.empty(),
                Map.of(f.core, BigInteger.ZERO, f.module, BigInteger.ZERO), window));
        assertFalse(tx.getIntentions().isEmpty());
    }
    @ParameterizedTest
    @ValueSource(strings = {"missing-nft", "wrong-quantity", "different-state", "custody", "state-reference", "reference-script",
            "spend-role", "scheme", "unexpected-candidate", "missing-balance", "negative-balance", "missing-receipt", "zero-receipt", "window"})
    void rejectsBeforeChangingBuilder(String attack) throws Exception {
        var input = state();
        var request = lifecycle.intent(f.state, new Freeze(), 1000, 9999);
        var approval = lifecycle.approval(f.state, request, 1);
        var balances = Map.of(f.core, BigInteger.ZERO, f.module, BigInteger.ZERO);
        var bounds = window;
        Optional<ModuleRedeemer> candidate = Optional.empty();
        switch (attack) {
            case "missing-nft" -> input.setAmount(List.of(Amount.ada(10)));
            case "wrong-quantity" -> input.setAmount(List.of(Amount.ada(10), Amount.asset(HexUtil.encodeHexString(f.state.accountId().policy()), BigInteger.TWO)));
            case "different-state" -> input.setInlineDatum(PlutusDataAdapter.toClientLib(AccountCodec.data(lifecycle.state(f.state, 1, 0, 0, new Frozen()))).serializeToHex());
            case "custody" -> input.setAddress(AddressProvider.getEntAddress(f.assetScript, new Network(0, 42)).toBech32());
            case "state-reference" -> input.setOutputIndex(999);
            case "reference-script" -> input.setReferenceScriptHash("00".repeat(28));
            case "spend-role" -> approval = lifecycle.approval(f.state, request, 0);
            case "scheme" -> approval = new ModuleRedeemer(BigInteger.ONE, request, Optional.of(new Proof(BigInteger.ONE,
                    approval.operationProof().orElseThrow().signatures())), JulcList.empty(), JulcList.empty());
            case "unexpected-candidate" -> candidate = Optional.of(approval);
            case "missing-balance" -> balances = Map.of(f.core, BigInteger.ZERO);
            case "negative-balance" -> balances = Map.of(f.core, BigInteger.ONE.negate(), f.module, BigInteger.ZERO);
            case "missing-receipt" -> balances = Map.of(f.core, BigInteger.ONE, f.module, BigInteger.ZERO);
            case "zero-receipt" -> approval = new ModuleRedeemer(BigInteger.ONE, request, approval.operationProof(), JulcList.empty(),
                    AccountFixtures.list(new RewardReceipt(f.core, BigInteger.ONE)));
            case "window" -> bounds = new AccountMutation.Window(BigInteger.ZERO, BigInteger.valueOf(9999), true);
            default -> throw new AssertionError(attack);
        }
        var supplied = approval; var candidateProof = candidate; var rewards = balances; var range = bounds;
        var tx = new Tx(); var before = List.copyOf(tx.getIntentions());
        assertThrows(IllegalArgumentException.class, () -> AccountMutation.attach(tx, scripts, f.state, input,
                supplied, candidateProof, Optional.empty(), rewards, range), attack);
        assertEquals(before, tx.getIntentions(), "Builder mutated on rejection: " + attack);
    }
}
