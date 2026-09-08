package com.bloxbean.cardano.kavach.phase0;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.eval.SlotConfig;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.Value;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Documents pinned upstream defects, NOT a conformance pass for the client bridge. */
class ClientBridgeDiagnosticsTest {
    @Test void recordsRewardIndexAndValueOrderingDiscrepancies() throws Exception {
        var addresses = new ArrayList<String>(); var expected = new ArrayList<String>();
        for (int marker : List.of(0x1c, 0x2d)) {
            byte[] raw = new byte[29]; Arrays.fill(raw, (byte) marker); raw[0] = (byte) 0xf0;
            addresses.add(new Address(raw).toBech32()); expected.add(HexFormat.of().formatHex(Arrays.copyOfRange(raw, 1, 29)));
        }
        // Diagnostic reflection reads the exact pinned package-private converter; it does not alter it.
        var tx = Transaction.builder().body(TransactionBody.builder().withdrawals(addresses.stream().map(a -> new Withdrawal(a, BigInteger.ZERO)).toList()).build()).build();
        var converterClass = Class.forName("com.bloxbean.cardano.julc.clientlib.eval.CclTxConverter");
        var constructor = converterClass.getDeclaredConstructor(Transaction.class, Set.class, UtxoSupplier.class, SlotConfig.class);
        constructor.setAccessible(true);
        var converter = constructor.newInstance(tx, Set.of(), null, null);
        var sorting = converterClass.getDeclaredMethod("getSortedWithdrawalCredentials"); sorting.setAccessible(true);
        var actual = new ArrayList<String>();
        for (Object item : (List<?>) sorting.invoke(converter)) actual.add(HexFormat.of().formatHex(((Credential.ScriptCredential) item).hash().hash()));
        assertNotEquals(expected, actual, "Pinned defect changed: requalify bridge ordering and remove obsolete workaround");
        String policy = "11".repeat(28);
        var values = Class.forName("com.bloxbean.cardano.julc.clientlib.eval.CclValueConverter");
        var fromAmounts = values.getDeclaredMethod("fromAmounts", List.class); fromAmounts.setAccessible(true);
        var converted = (Value) fromAmounts.invoke(null, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), Amount.asset(policy + "00", BigInteger.ONE), Amount.asset(policy + "01", BigInteger.ONE)));
        var tokenMap = (PlutusData.MapData) ((PlutusData.MapData) converted.toPlutusData()).entries().get(1).value();
        String firstToken = HexFormat.of().formatHex(((PlutusData.BytesData) tokenMap.entries().getFirst().key()).value());
        assertEquals("01", firstToken, "Pinned value-order defect changed: requalify bridge");
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/client-bridge-diagnostics.json"), JsonUtil.getPrettyJson(Map.of(
                "scope", "Confirmed defects in the pinned off-chain converter, not ledger/conformance success",
                "rewardAddresses", addresses, "ledgerCredentialOrder", expected, "converterCredentialOrder", actual,
                "convertedFirstToken", firstToken, "ledgerFirstToken", "00")));
    }
}
