package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.kavach.phase0.SealedStateProbe;
import com.bloxbean.cardano.kavach.phase0.StateNftMintProbe;
import com.bloxbean.cardano.kavach.phase0.StateReferenceProbe;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.bloxbean.cardano.kavach.protocol.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Golden parameter-application graph for the isolated identity experiment, not V1 release hashes. */
class DeploymentVectorTest {
    private com.bloxbean.cardano.client.plutus.spec.PlutusData data(PlutusData value) { return PlutusDataAdapter.toClientLib(value); }
    @Test void nftAndDependentScriptCanBeDerivedWithoutAHashCycle() throws Exception {
        var holder = JulcScriptLoader.load(SealedStateProbe.class);
        var creator = bytes(28, 10); var domain = PlutusData.bytes(Builtins.serialiseData(deployment()));
        var policy = JulcScriptLoader.load(StateNftMintProbe.class, data(input(0)), data(creator), data(PlutusData.bytes(holder.getScriptHash())), data(domain));
        var accountId = rec(PlutusData.bytes(policy.getScriptHash()), bytes(0, 0));
        var reader = JulcScriptLoader.load(StateReferenceProbe.class, data(PlutusData.bytes(policy.getScriptHash())), data(PlutusData.bytes(holder.getScriptHash())), data(domain), data(creator));
        var changedPolicy = JulcScriptLoader.load(StateNftMintProbe.class, data(input(1)), data(creator), data(PlutusData.bytes(holder.getScriptHash())), data(domain));
        assertNotEquals(HexFormat.of().formatHex(policy.getScriptHash()), HexFormat.of().formatHex(changedPolicy.getScriptHash()));
        var changedReader = JulcScriptLoader.load(StateReferenceProbe.class, data(PlutusData.bytes(changedPolicy.getScriptHash())), data(PlutusData.bytes(holder.getScriptHash())), data(domain), data(creator));
        assertNotEquals(reader.getCborHex(), changedReader.getCborHex());
        var vector = new LinkedHashMap<String, Object>();
        vector.put("scope", "Isolated identity probe dependency graph; holder is sealed and reader is a withdrawal validator, not a production state/asset pair");
        vector.put("seedCbor", data(input(0)).serializeToHex()); vector.put("creatorCbor", data(creator).serializeToHex()); vector.put("domainCbor", data(domain).serializeToHex());
        vector.put("holder", Map.of("cbor", holder.getCborHex(), "hash", HexFormat.of().formatHex(holder.getScriptHash())));
        vector.put("mint", Map.of("cbor", policy.getCborHex(), "hash", HexFormat.of().formatHex(policy.getScriptHash())));
        vector.put("accountIdCbor", data(accountId).serializeToHex());
        vector.put("dependentReader", Map.of("cbor", reader.getCborHex(), "hash", HexFormat.of().formatHex(reader.getScriptHash())));
        Files.createDirectories(Path.of("build/phase0")); Files.writeString(Path.of("build/phase0/deployment-vector.json"), JsonUtil.getPrettyJson(vector));
        assertEquals(JsonUtil.parseJson(Files.readString(Path.of("conformance/v1/deployment-probe.json"))), JsonUtil.parseJson(JsonUtil.getPrettyJson(vector)));
    }
}
