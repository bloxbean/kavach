package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Composable ordinary transfer witness preparation for CCL QuickTx.
 * Checks the supplied ledger state, complete account input identity, signed domain and
 * spend evidence before attaching the asset/core/module invocations. The caller resolves
 * UTxOs from a trusted ledger backend and supplies outputs, script references, slot validity,
 * sponsor fees and key-locked collateral. Those outputs and the final balanced transaction
 * still require script evaluation and node validation; this helper is not a signing UI.
 */
public final class AccountTransfer {
    private AccountTransfer() {}

    /**
     * Attaches the complete ordinary authorization chain to a caller-owned transaction.
     * All validation is performed before mutating {@code tx}. Output positions and receipt
     * sinks remain immutable-validator obligations. Use the same signed envelope for display;
     * do not authorize a caller-selected digest. Only raw Ed25519 scheme 0 is supported.
     *
     * @param tx transaction containing the intended recipient/change/receipt outputs
     * @param scripts script graph independently derived with {@link AccountDeployment#derive}
     * @param state expected complete state, authenticated against the supplied NFT-bearing UTxO
     * @param stateInput current resolved state UTxO, used only as a reference
     * @param accountInputs resolved account inputs in canonical TxOutRef order
     * @param intent complete signed Spend or TransferWholeUtxo envelope
     * @param proof raw Ed25519 spend-role evidence over that envelope's digest
     * @param receipts credential-ordered allocations for all positive checkpoint withdrawals
     * @param coreRewards complete current core reward balance, in lovelace
     * @param moduleRewards complete current module reward balance, in lovelace
     * @return the same transaction with inputs, state reference and both withdrawals attached
     * @throws IllegalArgumentException if supplied state, inputs, domain, receipts or proofs are inconsistent
     * @throws CborSerializationException if script identities cannot be computed
     * @throws CborDeserializationException if the ledger inline datum cannot be decoded
     */
    public static Tx attach(Tx tx, AccountDeployment.Scripts scripts, AccountState state, Utxo stateInput,
            List<Utxo> accountInputs, IntentEnvelope intent, Proof proof, JulcList<RewardReceipt> receipts,
            BigInteger coreRewards, BigInteger moduleRewards) throws CborSerializationException, CborDeserializationException {
        return attachInternal(tx, scripts, state, stateInput, accountInputs, intent, proof, receipts, coreRewards, moduleRewards, null);
    }

    /** Browser-profile transfer; required payment-key signers must be included in the final transaction body. */
    public static Tx attach(Tx tx, AccountDeployment.Scripts scripts, AccountState state, Utxo stateInput,
            List<Utxo> accountInputs, IntentEnvelope intent, Proof proof, JulcList<RewardReceipt> receipts,
            BigInteger coreRewards, BigInteger moduleRewards, BrowserAuthorization authorization) throws CborSerializationException, CborDeserializationException {
        if (authorization == null) throw new IllegalArgumentException("Missing browser profile");
        return attachInternal(tx, scripts, state, stateInput, accountInputs, intent, proof, receipts, coreRewards, moduleRewards, authorization);
    }

    private static Tx attachInternal(Tx tx, AccountDeployment.Scripts scripts, AccountState state, Utxo stateInput,
            List<Utxo> accountInputs, IntentEnvelope intent, Proof proof, JulcList<RewardReceipt> receipts,
            BigInteger coreRewards, BigInteger moduleRewards, BrowserAuthorization authorization) throws CborSerializationException, CborDeserializationException {
        WireFormat.validateState(AccountCodec.data(state));
        require(state.mode() instanceof Normal, "Transfers require Normal state");
        var network = authenticateState(scripts, state, stateInput);
        String account = AddressProvider.getEntAddress(scripts.asset(), network).toBech32();
        var resolvedState = AccountCodec.data(state);
        require(!accountInputs.isEmpty() && accountInputs.size() <= 8, "Account input count");
        var refs = new ArrayList<TxOutRef>(); var unique = new HashSet<TxOutRef>();
        for (var input : accountInputs) {
            require(account.equals(input.getAddress()), "Wrong full account input address");
            quantities(input.getAmount());
            var ref = reference(input); require(unique.add(ref), "Duplicate account input"); refs.add(ref);
        }
        PlutusData wholeValue = null;
        if (intent.action() instanceof Spend spend) {
            require(AccountCodec.list(spend.accountInputs()).equals(AccountCodec.list(toJulcList(refs))), "Signed input set/order differs from resolved inputs");
        } else if (intent.action() instanceof TransferWholeUtxo whole) {
            require(refs.size() == 1 && refs.getFirst().equals(whole.accountInput()), "Whole transfer requires its exact sole input");
            wholeValue = ledgerValue(accountInputs.getFirst().getAmount());
        } else throw new IllegalArgumentException("Transfers require Spend or TransferWholeUtxo");
        WireFormat.renderSigningRequest(AccountCodec.data(intent), resolvedState, reference(stateInput).toPlutusData(), wholeValue);
        byte[] digest = AccountCodec.intentDigest(intent, wholeValue);
        if (authorization == null) verifySpendEvidence(state.authConfig(), proof, digest);
        else authorization.verifySpend(state, proof, digest, intent.action());
        var core = new Credential.ScriptCredential(new ScriptHash(scripts.checkpoint().getScriptHash()));
        var module = new Credential.ScriptCredential(new ScriptHash(scripts.module().getScriptHash()));
        checkReceipts(receipts, core, module, coreRewards, moduleRewards);
        var coreRedeemer = new CoreRedeemer(BigInteger.ONE, intent, receipts);
        var moduleRedeemer = new ModuleRedeemer(BigInteger.ONE, intent, Optional.of(proof), JulcList.empty(), receipts);
        // No builder mutations occur before all preparation checks above have succeeded.
        return tx.attachSpendingValidator(scripts.asset())
                .collectFrom(List.copyOf(accountInputs), PlutusDataAdapter.toClientLib(AccountCodec.data(new AssetRedeemer(BigInteger.ONE, digest))))
                .readFrom(stateInput)
                .attachRewardValidator(scripts.checkpoint()).withdraw(AddressProvider.getRewardAddress(scripts.checkpoint(), network).toBech32(), coreRewards, PlutusDataAdapter.toClientLib(AccountCodec.data(coreRedeemer)))
                .attachRewardValidator(scripts.module()).withdraw(AddressProvider.getRewardAddress(scripts.module(), network).toBech32(), moduleRewards, PlutusDataAdapter.toClientLib(AccountCodec.data(moduleRedeemer)));
    }

    /** Authenticates exact inline state, full NFT custody and the independently supplied script graph. */
    static Network authenticateState(AccountDeployment.Scripts scripts, AccountState state, Utxo stateInput)
            throws CborSerializationException, CborDeserializationException {
        WireFormat.validateState(AccountCodec.data(state));
        require(Arrays.equals(state.coreBinding().stateValidator(), scripts.state().getScriptHash())
                && Arrays.equals(state.coreBinding().assetValidator(), scripts.asset().getScriptHash())
                && Arrays.equals(state.coreBinding().checkpoint(), scripts.checkpoint().getScriptHash())
                && Arrays.equals(state.authModule().scriptHash(), scripts.module().getScriptHash())
                && Arrays.equals(state.accountId().policy(), scripts.nft().getScriptHash()), "Script graph/state mismatch");
        var network = new Network(state.deploymentDomain().networkId().intValueExact(), state.deploymentDomain().networkMagic().longValueExact());
        String holder = AddressProvider.getEntAddress(scripts.state(), network).toBech32();
        require(holder.equals(stateInput.getAddress()) && stateInput.getReferenceScriptHash() == null, "Wrong state custody output");
        require(stateInput.getInlineDatum() != null, "Inline state datum required");
        var resolvedState = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexUtil.decodeHexString(stateInput.getInlineDatum())));
        require(resolvedState.equals(AccountCodec.data(state)), "Resolved datum differs from proposed state");
        var stateAmounts = quantities(stateInput.getAmount());
        String nft = HexUtil.encodeHexString(state.accountId().policy());
        require(stateAmounts.size() == 2 && BigInteger.ONE.equals(stateAmounts.get(nft)) && stateAmounts.containsKey("lovelace"), "Full state NFT identity or quantity mismatch");
        return network;
    }

    /** Checks every signature and threshold against the already validated configuration; ignores no evidence. */
    private static void verifySpendEvidence(PlutusData configuration, Proof proof, byte[] digest) {
        require(proof.scheme().equals(BigInteger.ZERO) && !proof.signatures().isEmpty() && proof.signatures().size() <= 8, "Unsupported or empty operation proof");
        var fields = ((PlutusData.ConstrData) configuration).fields();
        var registry = new TreeMap<BigInteger, byte[]>();
        for (var entry : ((PlutusData.ListData) fields.get(1)).items()) {
            var key = ((PlutusData.ConstrData)entry).fields(); registry.put(integer(key.get(0)), ((PlutusData.BytesData)key.get(1)).value());
        }
        var policy = ((PlutusData.ConstrData) fields.get(2)).fields();
        var members = new HashSet<BigInteger>();
        for (var member : ((PlutusData.ListData)policy.get(1)).items()) members.add(integer(member));
        int count = 0; BigInteger previous = BigInteger.valueOf(-1);
        for (var signature : proof.signatures()) {
            byte[] publicKey = registry.get(signature.credentialId());
            require(signature.credentialId().compareTo(previous) > 0 && publicKey != null && signature.signature().length == 64, "Malformed or unknown signature evidence");
            require(Ed25519.verify(signature.signature(), 0, publicKey, 0, digest, 0, digest.length), "Invalid intent signature");
            if (members.contains(signature.credentialId())) count++;
            previous = signature.credentialId();
        }
        require(BigInteger.valueOf(count).compareTo(integer(policy.get(0))) >= 0, "Spend threshold not satisfied");
    }
    /** Positive balances have exactly one distinct receipt; zero balances have none. */
    private static void checkReceipts(JulcList<RewardReceipt> receipts, Credential core, Credential module, BigInteger coreRewards, BigInteger moduleRewards) {
        require(coreRewards.signum() >= 0 && moduleRewards.signum() >= 0 && coreRewards.bitLength() <= 63 && moduleRewards.bitLength() <= 63, "Reward balance bounds");
        Set<PlutusData> expected = new HashSet<>();
        if (coreRewards.signum() > 0) expected.add(core.toPlutusData());
        if (moduleRewards.signum() > 0) expected.add(module.toPlutusData());
        var indices = new HashSet<BigInteger>(); byte[] previous = new byte[0];
        for (var receipt : receipts) {
            require(receipt.rewardCredential() instanceof Credential.ScriptCredential, "Script reward credential required");
            byte[] hash = ((Credential.ScriptCredential)receipt.rewardCredential()).hash().hash();
            require(Arrays.compareUnsigned(previous, hash) < 0 && expected.remove(receipt.rewardCredential().toPlutusData()), "Unexpected or duplicate reward receipt");
            require(receipt.outputIndex().signum() >= 0 && receipt.outputIndex().compareTo(BigInteger.valueOf(16)) < 0 && indices.add(receipt.outputIndex()), "Receipt output index or reuse");
            previous = hash;
        }
        require(expected.isEmpty(), "Missing positive reward receipt");
    }
    /** Preserves complete Value maps in canonical raw policy/name byte order for whole-input hashing. */
    private static PlutusData ledgerValue(List<Amount> amounts) {
        var policies = new TreeMap<String, TreeMap<String, BigInteger>>();
        for (var entry : quantities(amounts).entrySet()) {
            String unit = entry.getKey(); String policy = unit.equals("lovelace") ? "" : unit.substring(0, 56);
            String name = unit.equals("lovelace") ? "" : unit.substring(56);
            policies.computeIfAbsent(policy, ignored -> new TreeMap<>()).put(name, entry.getValue());
        }
        var entries = new ArrayList<PlutusData.Pair>();
        for (var policy : policies.entrySet()) {
            var tokens = new ArrayList<PlutusData.Pair>();
            for (var token : policy.getValue().entrySet()) tokens.add(new PlutusData.Pair(PlutusData.bytes(HexUtil.decodeHexString(token.getKey())), PlutusData.integer(token.getValue())));
            entries.add(new PlutusData.Pair(PlutusData.bytes(HexUtil.decodeHexString(policy.getKey())), new PlutusData.MapData(tokens)));
        }
        return new PlutusData.MapData(entries);
    }
    /** Rejects ambiguous duplicate units and malformed backend quantities before any accounting or hashing. */
    static Map<String, BigInteger> quantities(List<Amount> amounts) {
        require(amounts != null, "Missing resolved Value"); var result = new TreeMap<String, BigInteger>();
        for (var amount : amounts) {
            String unit = amount.getUnit();
            require(unit != null && (unit.equals("lovelace") || unit.matches("[0-9a-f]{56}([0-9a-f]{2}){0,32}")), "Malformed asset identifier");
            require(amount.getQuantity() != null && amount.getQuantity().signum() > 0, "Nonpositive resolved quantity");
            require(result.put(unit, amount.getQuantity()) == null, "Duplicate resolved asset unit");
        }
        require(result.containsKey("lovelace"), "Resolved output lacks lovelace"); return result;
    }
    static TxOutRef reference(Utxo input) { return new TxOutRef(new TxId(HexUtil.decodeHexString(input.getTxHash())), BigInteger.valueOf(input.getOutputIndex())); }
    private static BigInteger integer(PlutusData data) { return ((PlutusData.IntData)data).value(); }
    private static <T> JulcList<T> toJulcList(List<T> values) { JulcList<T> result = JulcList.empty(); for (int i = values.size() - 1; i >= 0; i--) result = result.prepend(values.get(i)); return result; }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
