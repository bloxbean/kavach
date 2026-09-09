package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.ToPlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import java.math.BigInteger;
import com.bloxbean.cardano.kavach.auth.policy.PolicyLib.PolicyConfig;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Configuration;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Budget;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Usage;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Explicit off-chain CDDL encoder for shared typed records. Constructor indices are
 * written explicitly, never inferred from reflection or Java sealed-class ordering.
 * Encoding is not ledger authentication: callers must resolve and verify addresses/NFTs.
 */
public final class AccountCodec {
    private AccountCodec() {}
    /**
     * Encodes a supported protocol record, primitive, optional or ledger value using explicit tags.
     * This conversion does not validate protocol ranges or authenticate ledger state.
     * @param value supported non-null value; an existing PlutusData value is passed through
     * @return its explicit Plutus Data representation
     * @throws IllegalArgumentException if the Java type is unsupported
     */
    public static PlutusData data(Object value) {
        return switch (value) {
            case PlutusData d -> d;
            case ToPlutusData ledger -> ledger.toPlutusData();
            case byte[] bytes -> PlutusData.bytes(bytes.clone());
            case BigInteger integer -> PlutusData.integer(integer);
            case Optional<?> optional -> optional.isPresent() ? record(0, optional.get()) : record(1);
            case AccountId a -> record(0, a.policy(), a.name());
            case DeploymentDomain d -> record(0, d.networkId(), d.networkMagic(), d.deploymentId());
            case CoreBinding b -> record(0, b.stateValidator(), b.assetValidator(), b.checkpoint());
            case AuthModuleRef m -> record(0, m.scriptHash(), m.abiVersion());
            case Normal n -> record(0);
            case Frozen f -> record(1);
            case RecoveryPending p -> record(2, p.proposalCommitment(), p.executeAfter(), p.targetConfig());
            case AccountState s -> record(0, s.schemaVersion(), s.accountId(), s.deploymentDomain(), s.coreBinding(),
                    s.stateVersion(), s.authModule(), s.authConfig(), s.recoverySequence(), s.recoveryDelayMillis(),
                    s.recoveryCooldownMillis(), s.recoveryNotBefore(), s.mode());
            case IntentDomain d -> record(0, d.protocolVersion(), d.deploymentDomain(), d.accountId(), d.coreBinding(), d.stateVersion(), d.stateRef());
            case Validity v -> record(0, v.notBefore(), v.expiresAt());
            case Asset a -> record(0, a.policy(), a.name(), a.quantity());
            case Recipient r -> record(0, r.outputIndex(), r.address(), list(r.value()));
            case Spend s -> record(0, list(s.accountInputs()), list(s.recipients()), s.maxAccountFee());
            case ReplaceConfig c -> record(1, c.newConfig());
            case ReplaceModule m -> record(2, m.newModule(), m.newConfig());
            case Freeze f -> record(3);
            case Unfreeze u -> record(4);
            case StartRecovery r -> record(5, r.nextSequence(), r.replacementConfig());
            case CancelRecovery r -> record(6, r.currentSequence(), r.proposalCommitment());
            case CompleteRecovery r -> record(7, r.currentSequence(), r.replacementConfig());
            case TransferWholeUtxo t -> record(8, t.accountInput(), t.recipientIndex(), t.recipientAddress(), t.inputValueDigest());
            case IntentEnvelope e -> record(0, e.protocolTag(), e.domain(), e.validity(), e.action());
            case Signature s -> record(0, s.credentialId(), s.signature());
            case Proof p -> record(0, p.scheme(), list(p.signatures()));
            case RewardReceipt r -> record(0, r.rewardCredential(), r.outputIndex());
            case CoreRedeemer c -> record(0, c.abiVersion(), c.intent(), list(c.receipts()));
            case CoreRegistration r -> record(1);
            case ModuleRedeemer m -> record(0, m.abiVersion(), m.intent(), m.operationProof(), list(m.configPossession()), list(m.receipts()));
            case ModuleRegistration r -> record(1);
            case GenesisModuleRedeemer g -> record(2, g.abiVersion(), g.state(), list(g.configPossession()), list(g.receipts()));
            case AssetRedeemer a -> record(0, a.abiVersion(), a.intentDigest());
            case GenesisProofEnvelope g -> record(0, g.tag(), g.deploymentDomain(), g.accountId(), g.coreBinding(), g.authModule(), g.configDigest());
            case KeyEntry k -> record(0, k.credentialId(), k.publicKey());
            case ThresholdPolicy p -> record(0, p.threshold(), list(p.credentialIds()));
            case Ed25519Config c -> record(0, c.schemaVersion(), list(c.keys()), c.spend(), c.admin(), c.freeze(), c.unfreeze(), c.recovery(), c.cancel());
            case PolicyConfig c -> record(0, c.schemaVersion(), c.roles(), list(c.coseIds()), c.smallPaymentLimit(), c.smallSpend());
            case Configuration c -> record(0, c.schemaVersion(), c.budget(), c.authorization());
            case Budget b -> record(0, b.counter(), b.period(), b.limit());
            case Usage u -> record(0, u.schemaVersion(), u.period(), u.windowStart(), u.spent());
            default -> throw new IllegalArgumentException("Unsupported protocol value: " + value.getClass().getName());
        };
    }
    /**
     * Strictly decodes a complete first-module state after validating every normative field.
     * This validates wire/schema/configuration only; callers must still authenticate NFT
     * custody and deployment identity against the ledger and a trusted locator.
     * @param encoded untrusted inline datum Data
     * @return typed state with the same canonical encoding
     * @throws IllegalArgumentException if the wire state or first-module configuration is invalid
     */
    public static AccountState decodeState(PlutusData encoded) {
        WireFormat.validateState(encoded);
        var f = ((PlutusData.ConstrData)encoded).fields();
        var id = ((PlutusData.ConstrData)f.get(1)).fields();
        var deployment = ((PlutusData.ConstrData)f.get(2)).fields();
        var core = ((PlutusData.ConstrData)f.get(3)).fields();
        var module = ((PlutusData.ConstrData)f.get(5)).fields();
        var mode = (PlutusData.ConstrData)f.get(11);
        AccountMode decodedMode = switch (mode.tag()) {
            case 0 -> new Normal();
            case 1 -> new Frozen();
            case 2 -> new RecoveryPending(rawBytes(mode.fields().get(0)), rawInteger(mode.fields().get(1)), mode.fields().get(2));
            default -> throw new IllegalArgumentException("Unsupported state mode");
        };
        return new AccountState(rawInteger(f.get(0)), new AccountId(rawBytes(id.get(0)), rawBytes(id.get(1))),
                new DeploymentDomain(rawInteger(deployment.get(0)), rawInteger(deployment.get(1)), rawBytes(deployment.get(2))),
                new CoreBinding(rawBytes(core.get(0)), rawBytes(core.get(1)), rawBytes(core.get(2))), rawInteger(f.get(4)),
                new AuthModuleRef(rawBytes(module.get(0)), rawInteger(module.get(1))), f.get(6), rawInteger(f.get(7)),
                rawInteger(f.get(8)), rawInteger(f.get(9)), rawInteger(f.get(10)), decodedMode);
    }
    private static byte[] rawBytes(PlutusData data) { return ((PlutusData.BytesData)data).value(); }
    private static BigInteger rawInteger(PlutusData data) { return ((PlutusData.IntData)data).value(); }

    /**
     * Encodes elements in their supplied order, without JVM record-conversion stubs.
     * @param values supported elements in their intended wire order
     * @return a Data list; ordering and uniqueness remain the caller's responsibility
     */
    public static PlutusData list(JulcList<?> values) {
        var entries = new ArrayList<PlutusData>();
        for (var value : values) entries.add(data(value));
        return new PlutusData.ListData(entries);
    }
    /** Constructs an explicitly tagged record from already supported field types. */
    private static PlutusData record(int tag, Object... fields) {
        var encoded = new ArrayList<PlutusData>();
        for (var field : fields) encoded.add(data(field));
        return new PlutusData.ConstrData(tag, encoded);
    }
    /**
     * Strictly validates and renders the typed intent before hashing its canonical Data encoding.
     * Validation is structural: callers must still authenticate referenced UTxOs and present
     * the corresponding signing request to the signer. This is raw Ed25519 scheme 0, not CIP-8.
     * @param intent complete typed operation and signature domain
     * @param resolvedWholeInputValue authenticated input Value for a whole-UTxO transfer;
     *                                null for an ordinary Spend
     * @return 32-byte Blake2b digest to sign
     * @throws IllegalArgumentException if the intent or required input Value is invalid
     */
    public static byte[] intentDigest(IntentEnvelope intent, PlutusData resolvedWholeInputValue) {
        var encoded = data(intent);
        WireFormat.renderIntent(encoded, resolvedWholeInputValue);
        return WireFormat.digest(encoded);
    }
}
