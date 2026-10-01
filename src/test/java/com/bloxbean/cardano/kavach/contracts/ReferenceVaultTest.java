package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.AccountId;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.ReferenceVaultDeployment;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-emitted V3 custody authorization, wire boundaries and bounded execution. */
class ReferenceVaultTest {
    private final byte[] publisher = new byte[28];
    private final AccountId account = new AccountId(new byte[28], new byte[0]);
    private final PlutusData request = PlutusDataAdapter.fromClientLib(ReferenceVaultDeployment.reclaim(account));

    private EvalResult evaluate(PlutusV3Script script, ScriptContextTestBuilder context) {
        return JulcVm.create("Java").evaluateWithArgs(JulcScriptAdapter.toProgram(script.getCborHex()),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(context.buildPlutusData()),
                new ExBudget(1_000_000_000, 4_000_000), EvalOptions.DEFAULT);
    }

    private ScriptContextTestBuilder spending(PlutusData data) {
        return ScriptContextTestBuilder.spending(AccountFixtures.ref(4)).redeemer(data).signer(publisher);
    }

    private PlutusV3Script unchecked(byte[] owner, byte[] purpose, PlutusData id) {
        return JulcScriptLoader.load(ReferenceVault.class,
                PlutusDataAdapter.toClientLib(PlutusData.bytes(owner)),
                PlutusDataAdapter.toClientLib(PlutusData.bytes(purpose)), PlutusDataAdapter.toClientLib(id));
    }

    @Test
    void ownerCanReclaimWithoutDatumOrContinuingOutputWithinBudget() throws Exception {
        var script = ReferenceVaultDeployment.derive(publisher, account);
        var success = assertInstanceOf(EvalResult.Success.class, evaluate(script, spending(request)));
        assertTrue(script.getCborHex().length() / 2 < 1500);
        assertTrue(success.consumed().cpuSteps() < 100_000_000);
        assertTrue(success.consumed().memoryUnits() < 300_000);
        System.out.println("REFERENCE_VAULT bytes=" + script.getCborHex().length() / 2 + " hash=" + HexFormat.of().formatHex(script.getScriptHash()) + " cbor=" + script.getCborHex() + " budget=" + success.consumed());
    }

    @Test
    void rejectsMissingWrongPublisherAndNonSpendingPurpose() {
        var script = ReferenceVaultDeployment.derive(publisher, account);
        var wrong = publisher.clone(); wrong[0] = 1;
        for (var context : List.of(
                ScriptContextTestBuilder.spending(AccountFixtures.ref(4)).redeemer(request),
                ScriptContextTestBuilder.spending(AccountFixtures.ref(4)).redeemer(request).signer(wrong),
                ScriptContextTestBuilder.rewarding(new Credential.ScriptCredential(new ScriptHash(new byte[28])))
                        .redeemer(request).signer(publisher)))
            assertInstanceOf(EvalResult.Failure.class, evaluate(script, context));
    }

    @Test
    void rejectsMalformedAndCrossAccountRedeemers() {
        var id = AccountCodec.data(account);
        var changedPolicy = new byte[28]; changedPolicy[0] = 1;
        var malformed = List.of(
                PlutusData.constr(1, PlutusData.integer(1), id),
                PlutusData.constr(0, PlutusData.integer(1), id, PlutusData.integer(0)),
                PlutusData.constr(0, PlutusData.integer(1)),
                PlutusData.constr(0, PlutusData.integer(0), id),
                PlutusData.integer(1),
                PlutusData.constr(0, PlutusData.bytes(new byte[0]), id),
                PlutusData.constr(0, PlutusData.integer(1), AccountCodec.data(new AccountId(changedPolicy, new byte[0]))),
                PlutusData.constr(0, PlutusData.integer(1), AccountCodec.data(new AccountId(new byte[28], new byte[]{1}))),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(1, PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[0]))),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(0, PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[0]), PlutusData.integer(0))));
        for (var data : malformed)
            assertInstanceOf(EvalResult.Failure.class, evaluate(ReferenceVaultDeployment.derive(publisher, account), spending(data)), data.toString());
    }

    @Test
    void rejectsMalformedParametersEvenWhenRequestMatches() {
        var purpose = "kavach-acc-ref".getBytes(StandardCharsets.US_ASCII);
        for (var id : List.of(
                PlutusData.constr(1, PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[0])),
                PlutusData.constr(0, PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[0]), PlutusData.integer(0)),
                AccountCodec.data(new AccountId(new byte[27], new byte[0])),
                AccountCodec.data(new AccountId(new byte[28], new byte[33])))) {
            var data = PlutusData.constr(0, PlutusData.integer(1), id);
            assertInstanceOf(EvalResult.Failure.class, evaluate(unchecked(publisher, purpose, id), spending(data)));
        }
        assertInstanceOf(EvalResult.Failure.class, evaluate(unchecked(new byte[27], purpose, AccountCodec.data(account)), spending(request)));
        assertInstanceOf(EvalResult.Failure.class, evaluate(unchecked(publisher, new byte[0], AccountCodec.data(account)), spending(request)));
    }

    @Test
    void fullIdentityChangesArtifactAndSdkRejectsInvalidWidths() throws Exception {
        var original = ReferenceVaultDeployment.derive(publisher, account).getScriptHash();
        var other = publisher.clone(); other[0] = 1;
        assertFalse(Arrays.equals(original, ReferenceVaultDeployment.derive(other, account).getScriptHash()));
        assertFalse(Arrays.equals(original, ReferenceVaultDeployment.derive(publisher, new AccountId(other, new byte[0])).getScriptHash()));
        assertFalse(Arrays.equals(original, ReferenceVaultDeployment.derive(publisher, new AccountId(publisher, new byte[]{1})).getScriptHash()));
        assertInstanceOf(EvalResult.Success.class, evaluate(ReferenceVaultDeployment.derive(publisher, new AccountId(publisher, new byte[32])),
                spending(PlutusDataAdapter.fromClientLib(ReferenceVaultDeployment.reclaim(new AccountId(publisher, new byte[32]))))));
        assertThrows(IllegalArgumentException.class, () -> ReferenceVaultDeployment.derive(null, account));
        assertThrows(IllegalArgumentException.class, () -> ReferenceVaultDeployment.derive(new byte[29], account));
        assertThrows(IllegalArgumentException.class, () -> ReferenceVaultDeployment.reclaim(null));
        assertThrows(IllegalArgumentException.class, () -> ReferenceVaultDeployment.reclaim(new AccountId(new byte[27], new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> ReferenceVaultDeployment.reclaim(new AccountId(publisher, new byte[33])));
    }

    @Test
    void canonicalReclaimGoldenVector() throws Exception {
        assertEquals("581c" + "00".repeat(28), PlutusDataAdapter.toClientLib(PlutusData.bytes(publisher)).serializeToHex());
        assertEquals("4e6b61766163682d6163632d726566", PlutusDataAdapter.toClientLib(PlutusData.bytes("kavach-acc-ref".getBytes(StandardCharsets.US_ASCII))).serializeToHex());
        assertEquals("d8799f581c" + "00".repeat(28) + "40ff", PlutusDataAdapter.toClientLib(AccountCodec.data(account)).serializeToHex());
        assertEquals("d8799f01d8799f581c" + "00".repeat(28) + "40ffff", ReferenceVaultDeployment.reclaim(account).serializeToHex());
    }
}
