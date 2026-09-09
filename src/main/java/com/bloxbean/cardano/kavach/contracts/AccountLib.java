package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;

/**
 * Shared immutable identity, wire-shape and receipt checks. Methods execute in UPLC;
 * casts between typed records and Data are compiler boundary operations, not JVM APIs.
 * Configuration authorization is deliberately delegated to the installed module.
 */
@OnchainLibrary
public class AccountLib {
    /**
     * Checks only a wire constructor's exact tag and arity. Every field's type and semantic
     * constraints must still be checked by the caller; this is never complete datum validation.
     *
     * @param data   untrusted wire record (non-constructors fail script evaluation)
     * @param tag    required constructor index
     * @param fields required exact field count, including rejection of trailing fields
     * @return whether the outer constructor has exactly the required shape
     */
    public static boolean shape(PlutusData data, long tag, long fields) {
        return Builtins.constrTag(data) == tag
                && Builtins.lengthOfArray(Builtins.listToArray(Builtins.constrFields(data))) == fields;
    }

    /**
     * Shares the pinned compiler's three-way BigInteger comparison lowering at one call site.
     *
     * @param left  arbitrary-precision left operand; never narrowed to a Java primitive
     * @param right arbitrary-precision right operand
     * @return whether left is strictly less than right
     */
    public static boolean lessThan(BigInteger left, BigInteger right) {
        return left.compareTo(right) < 0;
    }

    /**
     * Shared inclusive integer comparison; equality remains accepted at protocol boundaries.
     *
     * @param left  arbitrary-precision left operand
     * @param right arbitrary-precision right operand
     * @return whether left is less than or equal to right
     */
    public static boolean atMost(BigInteger left, BigInteger right) {
        return left.compareTo(right) <= 0;
    }

    /**
     * Exact integer equality, without computing an intermediate sign.
     *
     * @param left  arbitrary-precision left operand
     * @param right arbitrary-precision right operand
     * @return whether the integers are equal
     */
    public static boolean equalInteger(BigInteger left, BigInteger right) {
        return left.equals(right);
    }

    /**
     * Counts a complete list through the PV11 immutable-array length builtin. Elements are never
     * decoded, skipped or trusted here; callers retain their existing per-element checks.
     *
     * @param values complete ledger or protocol list, with element types erased only for counting
     * @return exact number of list elements
     */
    public static long listSize(JulcList<PlutusData> values) {
        return values.toArray().length();
    }

    /**
     * Counts every entry of a ledger Value without constructing flattened asset triples.
     * Keys and quantities are checked separately by the owning validator. Empty policy maps
     * contribute zero, matching flattening semantics; this helper never counts only named assets.
     *
     * @param value ledger-supplied complete Value map
     * @return number of policy/name entries, including ADA when present
     */
    public static long assetCount(Value value) {
        var policies = (JulcMap<byte[], JulcMap<byte[], BigInteger>>) (Object) value;
        long count = 0;
        for (var tokens : policies.values()) count = count + tokens.size();
        return count;
    }

    /**
     * Requires a strictly positive canonical ADA-only ledger value without flattening it.
     *
     * @param value complete ledger output value
     * @return whether the value contains exactly ADA with positive quantity
     */
    public static boolean positiveAdaOnly(Value value) {
        var amount = ValuesLib.lovelaceOf(value);
        return lessThan(BigInteger.ZERO, amount)
                && Builtins.equalsData((PlutusData) (Object) value, (PlutusData) (Object) Value.lovelace(amount));
    }

    /**
     * Unsigned protocol arithmetic never exceeds signed 63-bit quantities.
     *
     * @param n integer to check, inclusive range 0 through 2^63 - 1
     * @return whether the integer fits the protocol unsigned 63-bit range
     */
    public static boolean uint63(BigInteger n) {
        return AccountLib.atMost(BigInteger.ZERO, n) && AccountLib.atMost(n, BigInteger.valueOf(9223372036854775807L));
    }

    /**
     * Constructs the only supported ordinary account address, without staking.
     *
     * @param hash 28-byte script hash already validated by the caller
     * @return script payment address with no stake credential
     */
    public static Address enterprise(byte[] hash) {
        return new Address(new Credential.ScriptCredential(new ScriptHash(hash)), Optional.empty());
    }

    /**
     * Script credential used for an exact Rewarding purpose lookup.
     *
     * @param hash 28-byte script hash already validated by the caller
     * @return script credential for exact purpose and withdrawal lookups
     */
    public static Credential script(byte[] hash) {
        return new Credential.ScriptCredential(new ScriptHash(hash));
    }

    /**
     * Rejects output datums and reference scripts on plain transfers and reward receipts.
     *
     * @param output ledger output to inspect
     * @return whether the output has neither datum nor reference script
     */
    public static boolean plain(TxOut output) {
        return output.referenceScript().isEmpty() && switch (output.datum()) {
            case OutputDatum.NoOutputDatum none -> true;
            default -> false;
        };
    }

    /**
     * Global bounds include sponsor entries; unsupported ledger operations cannot hide behind authorization.
     *
     * @param ctx ledger-supplied script context
     * @return whether global counts and unsupported ledger fields satisfy the transfer profile
     */
    public static boolean transactionShape(ScriptContext ctx) {
        var tx = ctx.txInfo();
        return AccountLib.listSize((JulcList<PlutusData>) (Object) tx.inputs()) > 0 && AccountLib.listSize((JulcList<PlutusData>) (Object) tx.inputs()) <= 16 && AccountLib.listSize((JulcList<PlutusData>) (Object) tx.outputs()) > 0 && AccountLib.listSize((JulcList<PlutusData>) (Object) tx.outputs()) <= 16
                && AccountLib.listSize((JulcList<PlutusData>) (Object) tx.referenceInputs()) <= 4 && AccountLib.listSize((JulcList<PlutusData>) (Object) tx.signatories()) <= 16 && tx.withdrawals().size() <= 3
                && tx.certificates().isEmpty() && tx.votes().isEmpty() && tx.proposalProcedures().isEmpty()
                && tx.currentTreasuryAmount().isEmpty() && tx.treasuryDonation().isEmpty() && uint63(tx.fee());
    }

    /**
     * Canonicalizes a complete immutable binding rather than trusting record field projection.
     *
     * @param binding untrusted immutable core binding
     * @return whether the binding has exact canonical fields and 28-byte hashes
     */
    public static boolean coreShape(CoreBinding binding) {
        return Builtins.lengthOfByteString(binding.stateValidator()) == 28 && Builtins.lengthOfByteString(binding.assetValidator()) == 28
                && Builtins.lengthOfByteString(binding.checkpoint()) == 28
                && shape((PlutusData) (Object) binding, 0, 3);
    }

    /**
     * Full NFT identity, with the protocol's fixed empty asset name.
     *
     * @param id untrusted full state NFT identity
     * @return whether the NFT identity has its canonical shape and empty name
     */
    public static boolean accountShape(AccountId id) {
        return Builtins.lengthOfByteString(id.policy()) == 28 && Builtins.lengthOfByteString(id.name()) == 0
                && shape((PlutusData) (Object) id, 0, 2);
    }

    /**
     * Supported module ABI, separate from the immutable core checkpoint.
     *
     * @param module untrusted module reference
     * @return whether the module reference has canonical shape and supported ABI
     */
    public static boolean moduleShape(AuthModuleRef module) {
        return module.abiVersion().equals(BigInteger.ONE) && Builtins.lengthOfByteString(module.scriptHash()) == 28
                && shape((PlutusData) (Object) module, 0, 2);
    }

    /**
     * Validates normal-mode state structure and immutable deployment identity.
     * Does not interpret opaque configuration; genesis module establishes its validity.
     *
     * @param state          state datum resolved from the candidate NFT-bearing output
     * @param expectedDomain immutable deployment parameter
     * @param stateHash      immutable expected state custody script hash
     * @return whether state has a canonical Normal-mode shape and matches immutable parameters
     */
    public static boolean normalState(AccountState state, DeploymentDomain expectedDomain, byte[] stateHash) {
        return stateFields(state, expectedDomain, stateHash)
                && Builtins.lengthOfByteString(Builtins.serialiseData((PlutusData) (Object) state)) <= 1536
                && Builtins.equalsData((PlutusData) (Object) state.mode(), (PlutusData) (Object) new Normal());
    }

    /**
     * Shared mode-independent datum fields. Callers must separately validate the exact
     * lifecycle variant and its total serialized size before accepting any state.
     *
     * @param state          untrusted state datum
     * @param expectedDomain immutable deployment
     * @param stateHash      immutable state custody hash
     * @return whether all fields other than mode and total state size satisfy V1
     */
    public static boolean stateFields(AccountState state, DeploymentDomain expectedDomain, byte[] stateHash) {
        return state.schemaVersion().equals(BigInteger.ONE) && accountShape(state.accountId()) && coreShape(state.coreBinding())
                && moduleShape(state.authModule()) && uint63(state.stateVersion()) && uint63(state.recoverySequence())
                && AccountLib.atMost(state.recoverySequence(), state.stateVersion()) && uint63(state.recoveryNotBefore())
                && AccountLib.atMost(BigInteger.valueOf(86400000), state.recoveryDelayMillis())
                && AccountLib.atMost(state.recoveryDelayMillis(), BigInteger.valueOf(7776000000L))
                && AccountLib.atMost(BigInteger.valueOf(3600000), state.recoveryCooldownMillis())
                && AccountLib.atMost(state.recoveryCooldownMillis(), BigInteger.valueOf(2592000000L))
                && Builtins.equalsData((PlutusData) (Object) state.deploymentDomain(), (PlutusData) (Object) expectedDomain)
                && Builtins.equalsByteString(state.coreBinding().stateValidator(), stateHash)
                && !Builtins.equalsByteString(state.authModule().scriptHash(), state.coreBinding().checkpoint())
                && Builtins.lengthOfByteString(Builtins.serialiseData(state.authConfig())) <= 1024
                && shape((PlutusData) (Object) state, 0, 12);
    }

    /**
     * Checks the NFT-bearing output, including unrelated assets and the complete inline datum.
     *
     * @param output    ledger output to inspect
     * @param state     state datum resolved from the candidate NFT-bearing output
     * @param stateHash immutable expected state custody script hash
     * @return whether the output holds exactly the expected NFT, datum and custody address
     */
    public static boolean stateOutput(TxOut output, AccountState state, byte[] stateHash) {
        boolean datumMatches = switch (output.datum()) {
            case OutputDatum.OutputDatumInline inline ->
                    Builtins.equalsData(inline.datum(), (PlutusData) (Object) state);
            default -> false;
        };
        return output.address().equals(enterprise(stateHash)) && output.referenceScript().isEmpty() && datumMatches
                && AccountLib.assetCount(output.value()) == 2 && AccountLib.lessThan(BigInteger.ZERO, ValuesLib.lovelaceOf(output.value()))
                && ValuesLib.assetOf(output.value(), state.accountId().policy(), new byte[]{}).equals(BigInteger.ONE);
    }

    /**
     * Resolves the signed reference; failure raises a script error rather than returning an unauthenticated default.
     *
     * @param domain signed intent domain
     * @param ctx    ledger-supplied script context
     * @return the candidate inline datum; callers must authenticate it before trusting its fields
     */
    public static AccountState resolve(IntentDomain domain, ScriptContext ctx) {
        return resolveFrom(domain, ctx.txInfo().referenceInputs());
    }

    /**
     * Resolves an exact reference within the caller-selected ledger input set.
     *
     * @param domain     signed state domain
     * @param candidates consumed inputs for mutations or reference inputs for transfers
     * @return untrusted inline datum, never an authenticated state by itself
     */
    public static AccountState resolveFrom(IntentDomain domain, JulcList<TxInInfo> candidates) {
        if (candidates.isEmpty()) return (AccountState) (Object) Builtins.error();
        var candidate = candidates.head();
        if (!candidate.outRef().equals(domain.stateRef())) return resolveFrom(domain, candidates.tail());
        return switch (candidate.resolved().datum()) {
            case OutputDatum.OutputDatumInline inline -> (AccountState) (Object) inline.datum();
            default -> (AccountState) (Object) Builtins.error();
        };
    }

    /**
     * Authenticates the unique NFT-bearing reference, signed state version and immutable binding.
     * The one-shot policy and sealed state validator establish NFT supply and custody; this
     * method rejects spending the signed state reference and does not rescan every input value.
     * The required asset anchor additionally rejects foreign script inputs. Any future state
     * transition must preserve NFT custody and re-establish configuration validity.
     *
     * @param state      state datum resolved from the candidate NFT-bearing output
     * @param domain     signed intent domain
     * @param deployment immutable deployment parameter
     * @param stateHash  immutable expected state custody script hash
     * @param ctx        ledger-supplied script context
     * @return whether the candidate state and reference satisfy the account and signature-domain binding
     */
    public static boolean authenticate(AccountState state, IntentDomain domain, DeploymentDomain deployment, byte[] stateHash, ScriptContext ctx) {
        if (!normalState(state, deployment, stateHash)) return false;
        if (!authenticateFrom(state, domain, deployment, stateHash, ctx.txInfo().referenceInputs())) return false;
        boolean valid = true;
        for (var input : ctx.txInfo().inputs()) {
            if (input.outRef().equals(domain.stateRef())) valid = false;
        }
        return valid;
    }

    /**
     * Shared exact domain and singleton NFT authentication, independent of the selected input set.
     * Callers separately validate mode/schema and whether state is consumed or referenced.
     *
     * @param state      candidate datum already checked for permitted shape/mode
     * @param domain     untrusted signed domain
     * @param deployment immutable deployment
     * @param stateHash  immutable state custody hash
     * @param candidates the required consumed or referenced input set
     * @return whether exactly one authentic NFT state matches the signed reference and domain
     */
    public static boolean authenticateFrom(AccountState state, IntentDomain domain, DeploymentDomain deployment,
                                           byte[] stateHash, JulcList<TxInInfo> candidates) {
        var canonical = new IntentDomain(BigInteger.ONE, deployment, state.accountId(), state.coreBinding(), state.stateVersion(), domain.stateRef());
        if (!Builtins.equalsData((PlutusData) (Object) domain, (PlutusData) (Object) canonical)) return false;
        int matches = 0;
        boolean valid = true;
        for (var candidate : candidates) {
            if (ValuesLib.containsPolicy(candidate.resolved().value(), state.accountId().policy())) {
                matches = matches + 1;
                if (!candidate.outRef().equals(domain.stateRef()) || !stateOutput(candidate.resolved(), state, stateHash))
                    valid = false;
            }
        }
        return valid && matches == 1;
    }

    /**
     * Finite effective ledger interval must be contained in the signed half-open interval.
     *
     * @param window signed finite half-open interval in POSIX milliseconds
     * @param ctx    ledger-supplied script context
     * @return whether the effective ledger interval lies entirely within the signed interval
     */
    public static boolean validity(Validity window, ScriptContext ctx) {
        BigInteger start = window.notBefore();
        BigInteger end = window.expiresAt();
        if (!uint63(start) || !uint63(end) || AccountLib.atMost(end, start) || AccountLib.lessThan(BigInteger.valueOf(300000), end.subtract(start))
                || !shape((PlutusData) (Object) window, 0, 2)) return false;
        var range = ctx.txInfo().validRange();
        BigInteger lower = switch (range.from().boundType()) {
            case IntervalBoundType.Finite finite -> finite.time();
            default -> BigInteger.valueOf(-1);
        };
        BigInteger upper = switch (range.to().boundType()) {
            case IntervalBoundType.Finite finite -> finite.time();
            default -> BigInteger.valueOf(-1);
        };
        return AccountLib.atMost(start, lower) && AccountLib.lessThan(lower, upper)
                && (AccountLib.lessThan(upper, end) || (upper.equals(end) && !range.to().isInclusive()));
    }

    /**
     * Exact top-level signed envelope; action-specific code verifies its nested shape and semantics.
     *
     * @param intent typed intent whose nested action is checked separately
     * @param ctx    ledger-supplied script context
     * @return whether the outer envelope, protocol tag and validity satisfy the profile
     */
    public static boolean envelope(IntentEnvelope intent, ScriptContext ctx) {
        return Builtins.equalsByteString(intent.protocolTag(), new byte[]{75, 65, 86, 65, 67, 72, 95, 73, 78, 84, 69, 78, 84, 1})
                && Builtins.lengthOfByteString(Builtins.serialiseData((PlutusData) (Object) intent)) <= 1536
                && shape((PlutusData) (Object) intent, 0, 4)
                && validity(intent.validity(), ctx);
    }

    /**
     * Computes the signature message exclusively from the supplied typed envelope.
     *
     * @param intent typed intent whose nested action is checked separately
     * @return 32-byte Blake2b digest of serialiseData applied to the supplied envelope
     */
    public static byte[] digest(IntentEnvelope intent) {
        return Builtins.blake2b_256(Builtins.serialiseData((PlutusData) (Object) intent));
    }

    /**
     * Validates the common positive-reward table independently of each sink's owner.
     * Every positive withdrawal has one sorted credential entry and a distinct plain ADA output.
     * The caller additionally binds its own immutable sink and disjoint operation outputs.
     *
     * @param table untrusted reward receipt table shared across invocations
     * @param ctx   ledger-supplied script context
     * @return whether each positive withdrawal has exactly one distinct valid allocation
     */
    public static boolean receipts(JulcList<RewardReceipt> table, ScriptContext ctx) {
        if (AccountLib.listSize((JulcList<PlutusData>) (Object) table) > 3) return false;
        int positives = 0;
        boolean valid = true;
        for (var credential : ctx.txInfo().withdrawals().keys()) {
            BigInteger amount = ctx.txInfo().withdrawals().get(credential);
            if (AccountLib.lessThan(amount, BigInteger.ZERO)) valid = false;
            if (AccountLib.lessThan(BigInteger.ZERO, amount)) positives = positives + 1;
        }
        byte[] previous = new byte[]{};
        for (var receipt : table) {
            byte[] hash = switch (receipt.rewardCredential()) {
                case Credential.ScriptCredential credential -> credential.hash().hash();
                default -> new byte[]{};
            };
            if (Builtins.lengthOfByteString(hash) != 28 || !Builtins.lessThanByteString(previous, hash)
                    || !receipt.rewardCredential().equals(script(hash))
                    || !shape((PlutusData) (Object) receipt, 0, 2)
                    || !ctx.txInfo().withdrawals().containsKey(receipt.rewardCredential()) || !index(receipt.outputIndex(), ctx))
                valid = false;
            else {
                var output = ctx.txInfo().outputs().toArray().get(receipt.outputIndex().longValue());
                var amount = ctx.txInfo().withdrawals().get(receipt.rewardCredential());
                if (AccountLib.atMost(amount, BigInteger.ZERO) || !plain(output) || !positiveAdaOnly(output.value())
                        || AccountLib.lessThan(ValuesLib.lovelaceOf(output.value()), amount)) valid = false;
            }
            previous = hash;
        }
        return valid && uniqueReceiptIndices(table) && positives == AccountLib.listSize((JulcList<PlutusData>) (Object) table);
    }

    /**
     * Bounds-checks output indices before list access.
     *
     * @param position zero-based candidate output index
     * @param ctx      ledger-supplied script context
     * @return whether the index is nonnegative and lies within the transaction outputs
     */
    public static boolean index(BigInteger position, ScriptContext ctx) {
        return AccountLib.atMost(BigInteger.ZERO, position) && AccountLib.lessThan(position, BigInteger.valueOf(AccountLib.listSize((JulcList<PlutusData>) (Object) ctx.txInfo().outputs())));
    }

    /**
     * No reward allocation may also be a signed recipient or account change.
     *
     * @param table    untrusted reward receipt table shared across invocations
     * @param position zero-based candidate output index
     * @return whether the table contains this output index; true indicates an allocation conflict
     */
    public static boolean receiptAt(JulcList<RewardReceipt> table, BigInteger position) {
        if (table.isEmpty()) return false;
        return table.head().outputIndex().equals(position) || receiptAt(table.tail(), position);
    }

    /**
     * Checks each receipt-index pair once after the table's entries have been validated.
     */
    static boolean uniqueReceiptIndices(JulcList<RewardReceipt> table) {
        if (table.isEmpty()) return true;
        return !receiptAt(table.tail(), table.head().outputIndex()) && uniqueReceiptIndices(table.tail());
    }

    /**
     * Checks the calling checkpoint's immutable full key reward sink.
     *
     * @param own   authenticated executing reward credential
     * @param sink  immutable full reward destination address
     * @param table untrusted reward receipt table shared across invocations
     * @param ctx   ledger-supplied script context
     * @return whether this credential has no reward to allocate or its receipt pays the full immutable sink
     */
    public static boolean ownSink(Credential own, Address sink, JulcList<RewardReceipt> table, ScriptContext ctx) {
        boolean keySink = switch (sink.credential()) {
            case Credential.PubKeyCredential key -> true;
            default -> false;
        };
        if (!keySink || !ctx.txInfo().withdrawals().containsKey(own)) return false;
        boolean valid = true;
        for (var receipt : table) {
            if (receipt.rewardCredential().equals(own) && (!index(receipt.outputIndex(), ctx)
                    || !ctx.txInfo().outputs().toArray().get(receipt.outputIndex().longValue()).address().equals(sink)))
                valid = false;
        }
        return valid;
    }

    /**
     * Only deposit-bearing stake registration is witnessed; ledger authenticates the executing credential/deposit.
     *
     * @param ctx ledger-supplied script context
     * @return whether this is the supported deposit-bearing registration certificate
     */
    public static boolean registration(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying -> switch (certifying.cert()) {
                case TxCert.RegStaking registration ->
                        registration.deposit().isPresent() && AccountLib.atMost(BigInteger.ZERO, registration.deposit().get())
                                && switch (registration.credential()) {
                            case Credential.ScriptCredential script -> true;
                            default -> false;
                        };
                default -> false;
            };
            default -> false;
        };
    }
}
