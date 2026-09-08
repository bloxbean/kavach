package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CCL attachment of consumed-state administration and recovery transactions. Callers provide
 * the exact successor output computed by {@link AccountAdministration#prepare}, disjoint
 * reward receipts, plain sponsor inputs/change, published script references and key collateral.
 * Supply a real sponsor fee input before CCL's first evaluation so its provisional
 * change output is positive; the validator deliberately rejects zero-ADA outputs.
 * The final transaction still requires evaluator and ledger validation. No account-asset
 * input belongs in a mutation transaction; the immutable state validator enforces this.
 */
public final class AccountMutation {
    private AccountMutation() {}

    /**
     * Effective transaction validity bounds after converting slots to POSIX milliseconds.
     * @param lower finite ledger lower endpoint
     * @param upper finite ledger upper endpoint
     * @param upperInclusive whether the ledger includes the upper endpoint
     */
    public record Window(BigInteger lower, BigInteger upper, boolean upperInclusive) {}

    /**
     * Validates state custody, signatures, lifecycle and reward allocations before touching
     * the caller's builder. The same core redeemer is attached to the consumed state and
     * immutable checkpoint. Candidate approval never substitutes for old administration.
     *
     * @param tx caller-owned transaction with successor and receipt outputs already positioned
     * @param scripts independently verified current script graph, including the installed module
     * @param previous resolved current state datum
     * @param stateInput current state NFT UTxO to consume
     * @param current current-module invocation containing the canonical signed request
     * @param candidate optional candidate invocation, only for ReplaceModule
     * @param candidateScript independently approved candidate artifact, only for ReplaceModule
     * @param rewardBalances complete nonnegative current balances for every required reward credential, including zeros
     * @param window actual ledger time bounds corresponding to the transaction's validity slots
     * @return the same transaction with state input and all required withdrawal invocations attached
     * @throws IllegalArgumentException for mismatched state, scripts, authority, successor semantics or receipt allocations
     * @throws CborSerializationException if script identity computation fails
     * @throws CborDeserializationException if the backend state datum cannot be decoded
     */
    public static Tx attach(Tx tx, AccountDeployment.Scripts scripts, AccountState previous, Utxo stateInput,
            ModuleRedeemer current, Optional<ModuleRedeemer> candidate, Optional<PlutusV3Script> candidateScript,
            Map<Credential, BigInteger> rewardBalances, Window window)
            throws CborSerializationException, CborDeserializationException {
        return attachInternal(tx, scripts, previous, stateInput, current, candidate, candidateScript, rewardBalances, window, null);
    }

    /** Browser-profile attachment; the caller must put the profile's exact required signers in the final body. */
    public static Tx attach(Tx tx, AccountDeployment.Scripts scripts, AccountState previous, Utxo stateInput,
            ModuleRedeemer current, Optional<ModuleRedeemer> candidate, Optional<PlutusV3Script> candidateScript,
            Map<Credential, BigInteger> rewardBalances, Window window, BrowserAuthorization authorization)
            throws CborSerializationException, CborDeserializationException {
        if (authorization == null) throw new IllegalArgumentException("Missing browser profile");
        return attachInternal(tx, scripts, previous, stateInput, current, candidate, candidateScript, rewardBalances, window, authorization);
    }

    private static Tx attachInternal(Tx tx, AccountDeployment.Scripts scripts, AccountState previous, Utxo stateInput,
            ModuleRedeemer current, Optional<ModuleRedeemer> candidate, Optional<PlutusV3Script> candidateScript,
            Map<Credential, BigInteger> rewardBalances, Window window, BrowserAuthorization authorization)
            throws CborSerializationException, CborDeserializationException {
        var network = AccountTransfer.authenticateState(scripts, previous, stateInput);
        WireFormat.renderSigningRequest(AccountCodec.data(current.intent()), AccountCodec.data(previous),
                AccountTransfer.reference(stateInput).toPlutusData(), null);
        var prepared = authorization == null ? AccountAdministration.prepare(previous, current, candidate,
                window.lower(), window.upper(), window.upperInclusive()) : AccountAdministration.prepare(previous, current, candidate,
                window.lower(), window.upper(), window.upperInclusive(), authorization);
        var core = credential(scripts.checkpoint());
        var installed = credential(scripts.module());
        var required = new HashSet<Credential>(List.of(core, installed));
        require(required.size() == 2, "Core and module credentials must differ");
        if (current.intent().action() instanceof ReplaceModule replacement) {
            require(candidateScript.isPresent(), "Missing candidate script");
            require(Arrays.equals(candidateScript.orElseThrow().getScriptHash(), replacement.newModule().scriptHash()), "Candidate script hash mismatch");
            require(required.add(credential(candidateScript.orElseThrow())), "Candidate credential must be distinct");
        } else require(candidateScript.isEmpty(), "Unexpected candidate script");
        require(rewardBalances.keySet().equals(required), "Required withdrawal credential set mismatch");
        checkReceipts(current.receipts(), rewardBalances);
        // Resolve all addresses and Data first; validation failures above never partially mutate tx.
        String coreAddress = AddressProvider.getRewardAddress(scripts.checkpoint(), network).toBech32();
        String moduleAddress = AddressProvider.getRewardAddress(scripts.module(), network).toBech32();
        String candidateAddress = candidateScript.isPresent() ? AddressProvider.getRewardAddress(candidateScript.orElseThrow(), network).toBech32() : null;
        var coreData = PlutusDataAdapter.toClientLib(AccountCodec.data(prepared.core()));
        var moduleData = PlutusDataAdapter.toClientLib(AccountCodec.data(current));
        var candidateData = candidate.isPresent() ? PlutusDataAdapter.toClientLib(AccountCodec.data(candidate.orElseThrow())) : null;
        tx.attachSpendingValidator(scripts.state()).collectFrom(List.of(stateInput), coreData)
                .attachRewardValidator(scripts.checkpoint()).withdraw(coreAddress, rewardBalances.get(core), coreData)
                .attachRewardValidator(scripts.module()).withdraw(moduleAddress, rewardBalances.get(installed), moduleData);
        if (candidateScript.isPresent()) tx.attachRewardValidator(candidateScript.orElseThrow())
                .withdraw(candidateAddress, rewardBalances.get(credential(candidateScript.orElseThrow())), candidateData);
        return tx;
    }

    /** Positive withdrawals appear once, in canonical hash order, at distinct bounded output indices. */
    private static void checkReceipts(JulcList<RewardReceipt> receipts, Map<Credential, BigInteger> balances) {
        var expected = new HashSet<PlutusData>();
        for (var entry : balances.entrySet()) {
            var quantity = entry.getValue();
            require(quantity != null && quantity.signum() >= 0 && quantity.bitLength() <= 63, "Reward balance bound");
            if (quantity.signum() > 0) expected.add(entry.getKey().toPlutusData());
        }
        var indices = new HashSet<BigInteger>(); byte[] previousHash = new byte[0];
        for (var receipt : receipts) {
            require(receipt.rewardCredential() instanceof Credential.ScriptCredential, "Script receipt required");
            byte[] hash = ((Credential.ScriptCredential) receipt.rewardCredential()).hash().hash();
            require(Arrays.compareUnsigned(previousHash, hash) < 0 && expected.remove(receipt.rewardCredential().toPlutusData()), "Unexpected, duplicate or unsorted receipt");
            require(receipt.outputIndex().signum() >= 0 && receipt.outputIndex().compareTo(BigInteger.valueOf(16)) < 0
                    && indices.add(receipt.outputIndex()), "Receipt index or reuse");
            previousHash = hash;
        }
        require(expected.isEmpty(), "Missing positive reward receipt");
    }

    private static Credential credential(PlutusV3Script script) throws CborSerializationException {
        return new Credential.ScriptCredential(new ScriptHash(script.getScriptHash()));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
