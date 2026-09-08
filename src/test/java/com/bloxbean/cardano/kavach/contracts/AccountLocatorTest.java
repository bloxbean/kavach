package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import com.bloxbean.cardano.kavach.sdk.AccountLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Discovery needs a locator and current provider, not the creation reference or old private keys. */
class AccountLocatorTest {
    private final AccountFixtures f = new AccountFixtures();
    private final AccountLocator backup = AccountLocator.fromState(f.state);
    AccountLocatorTest() throws Exception {}

    private Utxo output(AccountState state) throws Exception {
        return Utxo.builder().txHash("42".repeat(32)).outputIndex(3)
                .address(AddressProvider.getEntAddress(f.stateScript, new Network(0, 42)).toBech32())
                .amount(List.of(Amount.ada(12), Amount.asset(HexUtil.encodeHexString(state.accountId().policy()), BigInteger.ONE)))
                .inlineDatum(PlutusDataAdapter.toClientLib(AccountCodec.data(state)).serializeToHex()).build();
    }
    @ParameterizedTest
    @ValueSource(strings = {"normal", "frozen", "pending", "new-module"})
    void originalLocatorResolvesCurrentStateAcrossLifecycleAndModuleChanges(String mode) throws Exception {
        var candidate = f.moduleScript.getScriptHash().clone(); candidate[0] ^= 1;
        AccountMode lifecycle = switch (mode) {
            case "frozen" -> new Frozen();
            case "pending" -> new RecoveryPending(new byte[32], BigInteger.valueOf(86400000), f.state.authConfig());
            default -> new Normal();
        };
        var state = new AccountState(f.state.schemaVersion(), f.state.accountId(), f.deployment, f.state.coreBinding(), BigInteger.TEN,
                mode.equals("new-module") ? new AuthModuleRef(candidate, BigInteger.ONE) : f.state.authModule(), f.state.authConfig(),
                BigInteger.ONE, f.state.recoveryDelayMillis(), f.state.recoveryCooldownMillis(), BigInteger.valueOf(3600000), lifecycle);
        var current = output(state);
        var restoredBackup = AccountLocator.parse(backup.backup());
        var calls = new AtomicInteger();
        var restored = restoredBackup.restore((address, unit) -> {
            calls.incrementAndGet(); assertEquals(current.getAddress(), address);
            assertEquals(HexUtil.encodeHexString(f.state.accountId().policy()), unit);
            return List.of(current);
        });
        assertEquals(1, calls.get());
        assertEquals(AccountCodec.data(state), AccountCodec.data(restored.state()));
        assertEquals(current.getTxHash(), restored.input().getTxHash());
        assertEquals(backup.backup(), AccountLocator.fromState(restored.state()).backup());
        assertEquals(AccountCodec.data(state), AccountCodec.data(AccountCodec.decodeState(AccountCodec.data(state))));
        for (var key : f.keys) assertFalse(backup.backup().contains(HexUtil.encodeHexString(AccountFixtures.publicKey(key))));
    }
    @ParameterizedTest
    @ValueSource(strings = {"missing", "ambiguous", "quantity", "nft", "address", "datum", "reference-script", "reference", "domain", "core"})
    void untrustedProviderClaimsMustMatchCompleteLocatorAndCustody(String attack) throws Exception {
        var input = output(f.state);
        switch (attack) {
            case "quantity" -> input.setAmount(List.of(Amount.ada(12), Amount.asset(HexUtil.encodeHexString(f.state.accountId().policy()), BigInteger.TWO)));
            case "nft" -> input.setAmount(List.of(Amount.ada(12), Amount.asset("ff".repeat(28), BigInteger.ONE)));
            case "address" -> input.setAddress(AddressProvider.getEntAddress(f.assetScript, new Network(0, 42)).toBech32());
            case "datum" -> input.setInlineDatum("00");
            case "reference-script" -> input.setReferenceScriptHash("ab".repeat(28));
            case "reference" -> input.setTxHash("42");
            case "domain", "core" -> {
                var changed = AccountAdversarialTest.field(AccountCodec.data(f.state), PlutusData.bytes(new byte[attack.equals("domain") ? 32 : 28]),
                        attack.equals("domain") ? 2 : 3, attack.equals("domain") ? 2 : 2);
                if (attack.equals("domain")) changed = AccountAdversarialTest.field(AccountCodec.data(f.state), PlutusData.bytes(HexUtil.decodeHexString("01".repeat(32))), 2, 2);
                input.setInlineDatum(PlutusDataAdapter.toClientLib(changed).serializeToHex());
            }
            default -> { }
        }
        var results = attack.equals("missing") ? List.<Utxo>of() : attack.equals("ambiguous") ? List.of(input, input) : List.of(input);
        assertThrows(IllegalArgumentException.class, () -> AccountLocator.parse(backup.backup()).restore((address, unit) -> results), attack);
    }
    @Test void backupRejectsTrailingBytesUnknownVersionAndUnboundedInput() {
        String text = backup.backup();
        assertEquals(text, AccountLocator.parse(text).backup());
        assertThrows(IllegalArgumentException.class, () -> AccountLocator.parse(text + "00"));
        assertThrows(IllegalArgumentException.class, () -> AccountLocator.parse(text.replace("locator-v1:", "locator-v2:")));
        assertThrows(IllegalArgumentException.class, () -> AccountLocator.parse(text + "00".repeat(1024)));
        assertThrows(IllegalArgumentException.class, () -> AccountCodec.decodeState(PlutusData.integer(0)));
    }
}
