package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Serialization upper-shape experiments; deliberately NOT ledger-valid transactions. */
class TransactionSizeTest {
    private byte[] bytes(int n, int marker) { var bytes = new byte[n]; Arrays.fill(bytes, (byte) marker); return bytes; }
    private String hex(int n, int marker) { return HexFormat.of().formatHex(bytes(n, marker)); }
    private PlutusData blob(int cap) {
        int n = cap; PlutusData value;
        do { value = PlutusData.bytes(new byte[n--]); } while (Builtins.serialiseData(value).length > cap);
        return value;
    }
    @Test void maximumShapesFitByOperation() throws Exception {
        var params = new ObjectMapper().treeToValue(JsonUtil.parseJson(Files.readString(
                Path.of("docs/phase0/evidence/binding-2026-09-07.json"))).get("protocolParameters"), ProtocolParams.class);
        var results = new ArrayList<Object>();
        for (String operation : List.of("Spend", "TransferWholeUtxo", "StartRecovery", "ReplaceModule", "CompleteRecovery", "Creation")) results.add(measure(operation, params));
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/transaction-size.json"), JsonUtil.getPrettyJson(Map.of(
                "scope", "Per-operation serialization upper shapes with reference scripts, not ledger-valid execution or production template sizes", "cases", results)));
    }
    private Object measure(String operation, ProtocolParams params) throws Exception {
        boolean creation = operation.equals("Creation");
        boolean whole = operation.equals("TransferWholeUtxo");
        boolean stateMutation = !whole && !operation.equals("Spend");
        int withdrawalCount = creation ? 1 : operation.equals("ReplaceModule") ? 3 : 2;
        int stateCap = operation.equals("StartRecovery") ? WireFormat.MAX_STATE_BYTES : WireFormat.MAX_NORMAL_STATE_BYTES;
        var max = BigInteger.valueOf(Long.MAX_VALUE);
        byte[] rawAddress = bytes(57, 1); rawAddress[0] = 0;
        String address = new Address(rawAddress).toBech32();
        var inputs = new ArrayList<TransactionInput>(); var refs = new ArrayList<TransactionInput>();
        for (int i = 0; i < 16; i++) inputs.add(new TransactionInput(hex(32, i), 65535));
        for (int i = 0; i < 4; i++) refs.add(new TransactionInput(hex(32, i + 20), 65535));
        var outputs = new ArrayList<TransactionOutput>(); var minAda = new MinAdaCalculator(params);
        BigInteger highestMinAda = BigInteger.ZERO; int wholeValueBytes = 0;
        for (int i = 0; i < 16; i++) {
            var assets = new ArrayList<MultiAsset>();
            if (!whole && i < 12) assets.add(new MultiAsset(hex(28, i), List.of(new Asset("0x" + hex(32, i), max))));
            if (whole && i == 0) {
                for (int j = 0; j < 100; j++) {
                    assets.add(new MultiAsset(hex(28, j), List.of(new Asset("0x" + hex(32, j), max))));
                    if (CborSerializationUtil.serialize(new Value(max, assets).serialize()).length > Integer.parseInt(params.getMaxValSize())) { assets.removeLast(); break; }
                }
                wholeValueBytes = CborSerializationUtil.serialize(new Value(max, assets).serialize()).length;
            }
            var output = TransactionOutput.builder().address(address).value(new Value(max, assets)).build();
            if (stateMutation && i == 0) output.setInlineDatum(PlutusDataAdapter.toClientLib(blob(stateCap)));
            highestMinAda = highestMinAda.max(minAda.calculateMinAda(output)); outputs.add(output);
        }
        var withdrawals = new ArrayList<Withdrawal>();
        for (int i = 0; i < withdrawalCount; i++) { byte[] reward = bytes(29, i); reward[0] = (byte) 0xf0; withdrawals.add(new Withdrawal(new Address(reward).toBech32(), max)); }
        var body = TransactionBody.builder().inputs(inputs).referenceInputs(refs).outputs(outputs).withdrawals(withdrawals)
                .mint(creation ? List.of(new MultiAsset(hex(28, 70), List.of(new Asset("", BigInteger.ONE)))) : null)
                .fee(max).ttl(Long.MAX_VALUE).validityStartInterval(Long.MAX_VALUE - 1).scriptDataHash(new byte[32])
                .requiredSigners(IntStream.range(0, 16).mapToObj(i -> bytes(28, i)).toList())
                .collateral(List.of(new TransactionInput(hex(32, 60), 65535), new TransactionInput(hex(32, 61), 65535), new TransactionInput(hex(32, 62), 65535))).totalCollateral(max)
                .collateralReturn(TransactionOutput.builder().address(address).value(new Value(max, List.of())).build()).build();
        var redeemers = new ArrayList<Redeemer>(); var signatures = new ArrayList<PlutusData>();
        for (int i = 0; i < 16; i++) signatures.add(PlutusData.constr(0, PlutusData.integer(i), PlutusData.bytes(new byte[64])));
        for (int i = 0; i < withdrawalCount; i++) {
            int proofs = creation ? 16 : i == 0 ? 0 : i == 2 || operation.equals("CompleteRecovery") ? 16 : 8;
            redeemers.add(new Redeemer(RedeemerTag.Reward, BigInteger.valueOf(i), PlutusDataAdapter.toClientLib(PlutusData.constr(0,
                    PlutusData.integer(1), blob(creation ? WireFormat.MAX_NORMAL_STATE_BYTES : WireFormat.MAX_INTENT_BYTES), new PlutusData.ListData(signatures.subList(0, proofs)), PlutusData.bytes(new byte[160]))),
                    new ExUnits(BigInteger.valueOf(5_000_000), BigInteger.valueOf(3_000_000_000L))));
        }
        int spendCount = stateMutation || whole ? 1 : 8;
        for (int i = 0; i < spendCount; i++) redeemers.add(new Redeemer(creation ? RedeemerTag.Mint : RedeemerTag.Spend, BigInteger.valueOf(i), PlutusDataAdapter.toClientLib(
                stateMutation ? PlutusData.constr(0, PlutusData.integer(1), blob(WireFormat.MAX_INTENT_BYTES), PlutusData.bytes(new byte[160]))
                        : PlutusData.constr(0, PlutusData.integer(1), PlutusData.bytes(new byte[32]))), new ExUnits(BigInteger.valueOf(100_000), BigInteger.valueOf(10_000_000))));
        var witnesses = new ArrayList<VkeyWitness>();
        for (int i = 0; i < 16; i++) witnesses.add(new VkeyWitness(bytes(32, i), bytes(64, i)));
        var tx = Transaction.builder().body(body).witnessSet(TransactionWitnessSet.builder().redeemers(redeemers).vkeyWitnesses(witnesses).build()).build();
        int size = tx.serialize().length;
        var evidence = new LinkedHashMap<String, Object>(); evidence.put("operation", operation); evidence.put("transactionBytes", size);
        evidence.put("ledgerMaximumBytes", params.getMaxTxSize()); evidence.put("maximumOutputMinimumLovelace", highestMinAda);
        evidence.put("stateByteCap", stateMutation ? stateCap : 0); evidence.put("wholeLedgerValueBytes", wholeValueBytes);
        assertTrue(size < params.getMaxTxSize(), operation + " shape uses " + size + " bytes"); return evidence;
    }
}
