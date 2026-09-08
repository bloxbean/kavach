package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.protocol.WireFormat;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.KeyEntry;
import com.bloxbean.cardano.kavach.auth.ed25519.Ed25519Lib.Ed25519Config;
import static org.junit.jupiter.api.Assertions.*;

/** Genesis proofs and one-shot mint policy are tested together as emitted UPLC. */
class AccountCreationTest {
    private final AccountFixtures f = new AccountFixtures();
    AccountCreationTest() throws Exception {}
    /** Evaluates an adversarial genesis context against this fixture's parameterized module. */
    EvalResult evaluateModule(PlutusData context) { return f.evaluate("module", context); }
    GenesisModuleRedeemer genesis() throws Exception {
        var envelope = new GenesisProofEnvelope("KAVACH_GENESIS_POSSESSION_V1".getBytes(StandardCharsets.UTF_8), f.deployment,
                f.state.accountId(), f.state.coreBinding(), f.state.authModule(), WireFormat.digest(f.state.authConfig()));
        var digest = WireFormat.digest(AccountCodec.data(envelope));
        var evidence = AccountFixtures.list(AccountFixtures.sign(0, f.keys.get(0), digest), AccountFixtures.sign(1, f.keys.get(1), digest), AccountFixtures.sign(2, f.keys.get(2), digest));
        return new GenesisModuleRedeemer(BigInteger.ONE, f.state, evidence, JulcList.empty());
    }
    ScriptContextTestBuilder context(String role, GenesisModuleRedeemer genesis) {
        var policy = new PolicyId(f.state.accountId().policy());
        return (role.equals("nft") ? ScriptContextTestBuilder.minting(policy) : ScriptContextTestBuilder.rewarding(f.module))
                .redeemer(role.equals("nft") ? PlutusData.integer(0) : AccountCodec.data(genesis))
                .input(new TxInInfo(f.seed, AccountFixtures.output(f.sink, 20000000))).signer(new byte[28])
                .output(f.stateInput().resolved()).mint(Value.singleton(policy, TokenName.EMPTY, BigInteger.ONE))
                .withdrawal(f.module, BigInteger.ZERO)
                .redeemerEntry(new ScriptPurpose.Rewarding(f.module), AccountCodec.data(genesis));
    }
    @Test void creationRequiresCreatorAndAllKeyPossession() throws Exception {
        var genesis = genesis();
        for (var role : List.of("nft", "module")) {
            var evaluated = f.evaluate(role, context(role, genesis));
            var result = assertInstanceOf(EvalResult.Success.class, evaluated, role + ": " + evaluated);
            System.out.println("Phase 1 creation " + role + " budget " + result.consumed());
        }
    }
    @Test void maximumRegistryAllKeyPossessionFitsCombinedCreationBudget() throws Exception {
        var keys = new ArrayList<KeyPair>(); var entries = new ArrayList<KeyEntry>();
        for (int id = 0; id < 16; id++) {
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); keys.add(key);
            entries.add(new KeyEntry(BigInteger.valueOf(id), AccountFixtures.publicKey(key)));
        }
        var config = new Ed25519Config(BigInteger.ONE, AccountFixtures.list(entries.toArray(KeyEntry[]::new)),
                AccountFixtures.policy(8, 0,1,2,3,4,5,6,7), AccountFixtures.policy(1, 8), AccountFixtures.policy(1, 9),
                AccountFixtures.policy(1, 15), AccountFixtures.policy(1, 14), AccountFixtures.policy(1, 15));
        var state = new AccountState(BigInteger.ONE, f.state.accountId(), f.deployment, f.state.coreBinding(), BigInteger.ZERO,
                f.state.authModule(), AccountCodec.data(config), BigInteger.ZERO, f.state.recoveryDelayMillis(),
                f.state.recoveryCooldownMillis(), BigInteger.ZERO, new Normal());
        WireFormat.validateState(AccountCodec.data(state));
        var preimage = new GenesisProofEnvelope("KAVACH_GENESIS_POSSESSION_V1".getBytes(StandardCharsets.UTF_8), f.deployment,
                state.accountId(), state.coreBinding(), state.authModule(), WireFormat.digest(state.authConfig()));
        var digest = WireFormat.digest(AccountCodec.data(preimage));
        var proofs = new ArrayList<Signature>();
        for (int id = 0; id < 16; id++) proofs.add(AccountFixtures.sign(id, keys.get(id), digest));
        var genesis = new GenesisModuleRedeemer(BigInteger.ONE, state, AccountFixtures.list(proofs.toArray(Signature[]::new)), JulcList.empty());
        long memory = 0; long cpu = 0;
        for (var role : List.of("nft", "module")) {
            var ctx = context(role, genesis).buildPlutusData();
            ctx = AccountAdversarialTest.field(ctx, AccountCodec.data(state), 0, 2, 0, 2, 0);
            var result = assertInstanceOf(EvalResult.Success.class, f.evaluate(role, ctx), role);
            memory += result.consumed().memoryUnits(); cpu += result.consumed().cpuSteps();
        }
        System.out.println("Phase 1 maximum genesis memory=" + memory + " cpu=" + cpu);
        assertTrue(memory <= 16500000, "Combined creation memory " + memory);
        assertTrue(cpu <= 10000000000L, "Combined creation CPU " + cpu);
    }
    @ParameterizedTest @ValueSource(strings={"seed", "creator", "quantity", "burn", "extra-mint", "state-address", "state-nft-name", "module-missing", "wrong-genesis-state"})
    void mintRejectsUnboundInitialization(String attack) throws Exception {
        var genesis = genesis(); var builder = context("nft", genesis); var ctx = builder.buildPlutusData();
        var empty = new PlutusData.ListData(List.of());
        ctx = switch (attack) {
            case "seed" -> AccountAdversarialTest.field(ctx, AccountFixtures.ref(999).toPlutusData(), 0, 0, 0, 0);
            case "creator" -> AccountAdversarialTest.field(ctx, empty, 0, 8);
            case "quantity" -> builder.mint(Value.singleton(new PolicyId(f.state.accountId().policy()), TokenName.EMPTY, BigInteger.TWO)).buildPlutusData();
            case "burn" -> builder.mint(Value.singleton(new PolicyId(f.state.accountId().policy()), TokenName.EMPTY, BigInteger.ONE.negate())).buildPlutusData();
            case "extra-mint" -> builder.mint(Value.singleton(new PolicyId(f.state.accountId().policy()), TokenName.EMPTY, BigInteger.ONE)
                    .merge(Value.singleton(new PolicyId(new byte[28]), TokenName.EMPTY, BigInteger.ONE))).buildPlutusData();
            case "state-address" -> AccountAdversarialTest.field(ctx, f.sink.toPlutusData(), 0, 2, 0, 0);
            case "state-nft-name" -> AccountAdversarialTest.field(ctx, PlutusData.bytes(new byte[]{1}), 0, 2, 0, 2, 0, 1, 1);
            case "module-missing" -> AccountAdversarialTest.field(ctx, new PlutusData.MapData(List.of()), 0, 6);
            case "wrong-genesis-state" -> AccountAdversarialTest.field(ctx, PlutusData.integer(1), 0, 2, 0, 2, 0, 4);
            default -> throw new AssertionError(attack);
        };
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("nft", ctx), attack);
    }
    @ParameterizedTest
    @ValueSource(strings={"alias-key", "duplicate-id", "unknown-member", "duplicate-member", "descending-members", "zero-threshold", "recovery-unfreeze", "recovery-cancel", "spend-admin", "spend-unfreeze", "config-tag", "key-tag", "policy-tag", "config-extra", "key-extra", "policy-extra", "schema-type", "key-id-type", "public-key-type", "threshold-type", "member-type"})
    void genesisRejectsInvalidConfigurationDespitePossessionEvidence(String attack) throws Exception {
        var config = AccountCodec.data(f.config);
        config = switch (attack) {
            case "alias-key" -> AccountAdversarialTest.field(config, PlutusData.bytes(AccountFixtures.publicKey(f.keys.get(0))), 1, 1, 1);
            case "duplicate-id" -> AccountAdversarialTest.field(config, PlutusData.integer(0), 1, 1, 0);
            case "duplicate-member" -> AccountAdversarialTest.field(config, AccountCodec.data(AccountFixtures.policy(1, 1, 1)), 4);
            case "descending-members" -> AccountAdversarialTest.field(config, AccountCodec.data(AccountFixtures.policy(2, 1, 0)), 3);
            case "unknown-member" -> AccountAdversarialTest.field(config, AccountCodec.data(AccountFixtures.policy(1, 15)), 4);
            case "zero-threshold" -> AccountAdversarialTest.field(config, PlutusData.integer(0), 2, 0);
            case "recovery-unfreeze" -> AccountAdversarialTest.field(config, AccountCodec.data(f.config.recovery()), 5);
            case "recovery-cancel" -> AccountAdversarialTest.field(config, AccountCodec.data(f.config.recovery()), 7);
            case "spend-admin" -> AccountAdversarialTest.field(config, AccountCodec.data(f.config.spend()), 3);
            case "spend-unfreeze" -> AccountAdversarialTest.field(config, AccountCodec.data(f.config.spend()), 5);
            case "config-extra" -> extraField(config);
            case "key-extra" -> AccountAdversarialTest.field(config, extraField(AccountCodec.data(f.config.keys().head())), 1, 0);
            case "policy-extra" -> AccountAdversarialTest.field(config, extraField(AccountCodec.data(f.config.spend())), 2);
            case "schema-type" -> AccountAdversarialTest.field(config, PlutusData.bytes(new byte[]{1}), 0);
            case "key-id-type" -> AccountAdversarialTest.field(config, PlutusData.bytes(new byte[]{0}), 1, 0, 0);
            case "public-key-type" -> AccountAdversarialTest.field(config, PlutusData.integer(0), 1, 0, 1);
            case "threshold-type" -> AccountAdversarialTest.field(config, PlutusData.bytes(new byte[]{1}), 2, 0);
            case "member-type" -> AccountAdversarialTest.field(config, PlutusData.bytes(new byte[]{0}), 2, 1, 0);
            case "config-tag" -> new PlutusData.ConstrData(1, ((PlutusData.ConstrData)config).fields());
            case "key-tag" -> AccountAdversarialTest.field(config, new PlutusData.ConstrData(1, ((PlutusData.ConstrData)AccountCodec.data(f.config.keys().head())).fields()), 1, 0);
            case "policy-tag" -> AccountAdversarialTest.field(config, new PlutusData.ConstrData(1, ((PlutusData.ConstrData)AccountCodec.data(f.config.spend())).fields()), 2);
            default -> throw new AssertionError(attack);
        };
        var state = new AccountState(BigInteger.ONE, f.state.accountId(), f.deployment, f.state.coreBinding(), BigInteger.ZERO,
                f.state.authModule(), config, BigInteger.ZERO, f.state.recoveryDelayMillis(), f.state.recoveryCooldownMillis(), BigInteger.ZERO, new Normal());
        var preimage = new GenesisProofEnvelope("KAVACH_GENESIS_POSSESSION_V1".getBytes(StandardCharsets.UTF_8), f.deployment,
                state.accountId(), state.coreBinding(), state.authModule(), WireFormat.digest(config));
        var digest = WireFormat.digest(AccountCodec.data(preimage));
        var proofs = AccountFixtures.list(AccountFixtures.sign(0, f.keys.get(0), digest),
                AccountFixtures.sign(1, f.keys.get(attack.equals("alias-key") ? 0 : 1), digest), AccountFixtures.sign(2, f.keys.get(2), digest));
        var genesis = new GenesisModuleRedeemer(BigInteger.ONE, state, proofs, JulcList.empty());
        var ctx = AccountAdversarialTest.field(context("module", genesis).buildPlutusData(), AccountCodec.data(state), 0, 2, 0, 2, 0);
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("module", ctx), attack);
    }
    /** Adds a trailing field without changing the original fields or constructor tag. */
    private static PlutusData extraField(PlutusData original) {
        var record = (PlutusData.ConstrData)original;
        var fields = new ArrayList<>(record.fields()); fields.add(PlutusData.integer(0));
        return new PlutusData.ConstrData(record.tag(), fields);
    }
    @Test void missingPossessionAndOrdinaryProofCannotInitialize() throws Exception {
        var good = genesis();
        var fewer = new GenesisModuleRedeemer(BigInteger.ONE, f.state, AccountFixtures.list(good.configPossession().head()), JulcList.empty());
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("module", context("module", fewer)));
        var intent = f.spend(0); var wrong = f.authorization(intent).operationProof().get().signatures().head();
        var foreign = new GenesisModuleRedeemer(BigInteger.ONE, f.state, AccountFixtures.list(wrong, good.configPossession().get(1), good.configPossession().get(2)), JulcList.empty());
        assertInstanceOf(EvalResult.Failure.class, f.evaluate("module", context("module", foreign)));
    }
}
