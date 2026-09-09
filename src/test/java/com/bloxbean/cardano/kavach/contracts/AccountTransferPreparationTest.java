package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountDeployment;
import com.bloxbean.cardano.kavach.sdk.AccountTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDK checks are tested separately from compiled validators and real ledger acceptance.
 */
class AccountTransferPreparationTest {
    private final AccountFixtures f = new AccountFixtures();
    private final Network network = new Network(0, 42);
    private final AccountDeployment.Scripts scripts;

    AccountTransferPreparationTest() throws Exception {
        scripts = new AccountDeployment.Scripts(f.stateScript, f.coreScript, f.moduleScript, f.nftScript, f.assetScript,
                f.state.accountId(), f.state.coreBinding(), f.state.authModule());
    }

    private Utxo utxo(TxOutRef ref, String address, List<Amount> amounts) {
        return Utxo.builder().txHash(HexUtil.encodeHexString(ref.txId().hash())).outputIndex(ref.index().intValueExact()).address(address).amount(amounts).build();
    }

    private Utxo state() throws Exception {
        var result = utxo(f.stateRef, AddressProvider.getEntAddress(f.stateScript, network).toBech32(), List.of(Amount.ada(10), Amount.asset(HexUtil.encodeHexString(f.state.accountId().policy()), BigInteger.ONE)));
        result.setInlineDatum(PlutusDataAdapter.toClientLib(AccountCodec.data(f.state)).serializeToHex());
        return result;
    }

    private Utxo input() throws Exception {
        return utxo(f.assetRef, AddressProvider.getEntAddress(f.assetScript, network).toBech32(), List.of(Amount.ada(10)));
    }

    @Test
    void authenticatedInputAndRawSpendProofPrepareTheCompleteInvocationChain() throws Exception {
        var intent = f.spend(0);
        var tx = new Tx();
        assertSame(tx, AccountTransfer.attach(tx, scripts, f.state, state(), List.of(input()), intent,
                f.authorization(intent).operationProof().orElseThrow(), JulcList.empty(), BigInteger.ZERO, BigInteger.ZERO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nft", "quantity", "datum", "custody", "state-ref", "input-address", "duplicate-input", "wrong-key", "wrong-role", "scheme"})
    void mismatchedResolvedStateInputsAndProofsRejectBeforePreparation(String attack) throws Exception {
        var state = state();
        var input = input();
        var intent = f.spend(0);
        var proof = f.authorization(intent).operationProof().orElseThrow();
        switch (attack) {
            case "nft" -> state.setAmount(List.of(Amount.ada(10)));
            case "quantity" ->
                    state.setAmount(List.of(Amount.ada(10), Amount.asset(HexUtil.encodeHexString(f.state.accountId().policy()), BigInteger.TWO)));
            case "datum" -> state.setInlineDatum("00");
            case "custody" -> state.setAddress(input.getAddress());
            case "state-ref" -> state.setOutputIndex(999);
            case "input-address" -> input.setAddress(state.getAddress());
            case "duplicate-input" -> {
            }
            case "wrong-key" ->
                    proof = new Proof(BigInteger.ZERO, AccountFixtures.list(AccountFixtures.sign(0, f.keys.get(1), AccountCodec.intentDigest(intent, null))));
            case "wrong-role" ->
                    proof = new Proof(BigInteger.ZERO, AccountFixtures.list(AccountFixtures.sign(1, f.keys.get(1), AccountCodec.intentDigest(intent, null))));
            case "scheme" -> proof = new Proof(BigInteger.ONE, proof.signatures());
            default -> throw new AssertionError(attack);
        }
        var evidence = proof;
        var inputs = attack.equals("duplicate-input") ? List.of(input, input) : List.of(input);
        assertThrows(IllegalArgumentException.class, () -> AccountTransfer.attach(new Tx(), scripts, f.state, state, inputs, intent, evidence, JulcList.empty(), BigInteger.ZERO, BigInteger.ZERO));
    }

    private JulcList<RewardReceipt> receipts(int moduleIndex) throws Exception {
        var core = new RewardReceipt(f.core, BigInteger.TWO);
        var module = new RewardReceipt(f.module, BigInteger.valueOf(moduleIndex));
        return Arrays.compareUnsigned(f.coreScript.getScriptHash(), f.moduleScript.getScriptHash()) < 0 ? AccountFixtures.list(core, module) : AccountFixtures.list(module, core);
    }

    @Test
    void positiveWithdrawalsRequireCompleteDistinctReceipts() throws Exception {
        var intent = f.spend(0);
        var proof = f.authorization(intent).operationProof().orElseThrow();
        assertNotNull(AccountTransfer.attach(new Tx(), scripts, f.state, state(), List.of(input()), intent, proof, receipts(3), BigInteger.ONE, BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> AccountTransfer.attach(new Tx(), scripts, f.state, state(), List.of(input()), intent, proof, receipts(2), BigInteger.ONE, BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> AccountTransfer.attach(new Tx(), scripts, f.state, state(), List.of(input()), intent, proof, JulcList.empty(), BigInteger.ONE, BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> AccountTransfer.attach(new Tx(), scripts, f.state, state(), List.of(input()), intent, proof, receipts(3), BigInteger.ZERO, BigInteger.ONE));
    }

    @Test
    void wholeInputDigestUsesCanonicalCompleteMapDespiteBackendAmountOrder() throws Exception {
        var input = input();
        var quantity = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        var nativeAmount = Amount.asset("00".repeat(28) + "01", quantity);
        input.setAmount(List.of(nativeAmount, Amount.ada(10)));
        var value = Value.lovelace(BigInteger.valueOf(10000000)).merge(Value.singleton(new PolicyId(new byte[28]), new TokenName(new byte[]{1}), quantity));
        var ordinary = f.spend(0);
        var intent = new IntentEnvelope(ordinary.protocolTag(), ordinary.domain(), ordinary.validity(),
                new TransferWholeUtxo(f.assetRef, BigInteger.ZERO, f.sink, WireFormat.ledgerValueDigest(value.toPlutusData())));
        var digest = AccountCodec.intentDigest(intent, value.toPlutusData());
        var proof = new Proof(BigInteger.ZERO, AccountFixtures.list(AccountFixtures.sign(0, f.keys.get(0), digest)));
        assertNotNull(AccountTransfer.attach(new Tx(), scripts, f.state, state(), List.of(input), intent, proof, JulcList.empty(), BigInteger.ZERO, BigInteger.ZERO));
        input.setAmount(List.of(Amount.ada(10)));
        assertThrows(IllegalArgumentException.class, () -> AccountTransfer.attach(new Tx(), scripts, f.state, state(), List.of(input), intent, proof, JulcList.empty(), BigInteger.ZERO, BigInteger.ZERO));
    }
}
