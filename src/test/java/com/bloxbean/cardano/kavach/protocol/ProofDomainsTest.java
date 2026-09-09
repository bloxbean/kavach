package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.client.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static com.bloxbean.cardano.kavach.protocol.WireFixtures.*;

class ProofDomainsTest {
    @Test
    void domainsAndCommitmentHaveStableIndependentPreimages() throws Exception {
        var commitment = ProofDomains.recoveryCommitment(state(), envelope(5));
        assertEquals(Files.readString(Path.of("conformance/v1/pending-state-rendering.txt")),
                WireFormat.renderState(pendingState(WireFormat.digest(commitment))));
        var examples = new LinkedHashMap<String, PlutusData>();
        examples.put("genesis-proof-envelope", ProofDomains.genesis(state()));
        examples.put("configuration-proof-envelope", ProofDomains.configuration(state(), envelope(2)));
        examples.put("recovery-commitment", commitment);
        examples.put("target-proof-envelope", ProofDomains.target(pendingState(WireFormat.digest(commitment)), completion()));
        var encoded = new LinkedHashMap<String, Object>();
        var digests = new HashSet<String>();
        for (var entry : examples.entrySet()) {
            String digest = HexFormat.of().formatHex(WireFormat.digest(entry.getValue()));
            assertTrue(digests.add(digest));
            encoded.put(entry.getKey(), Map.of("cbor", PlutusDataAdapter.toClientLib(entry.getValue()).serializeToHex(), "digest", digest));
        }
        Files.createDirectories(Path.of("build/phase0"));
        Files.writeString(Path.of("build/phase0/proof-domain-vectors.json"), JsonUtil.getPrettyJson(encoded));
        var golden = Path.of("conformance/v1/proof-domains.json");
        assertEquals(JsonUtil.parseJson(Files.readString(golden)), JsonUtil.parseJson(JsonUtil.getPrettyJson(encoded)));
        byte[] altered = WireFormat.digest(commitment);
        altered[0] ^= 1;
        assertFalse(Arrays.equals(WireFormat.digest(examples.get("target-proof-envelope")), WireFormat.digest(ProofDomains.target(pendingState(altered), completion()))));
        assertThrows(IllegalArgumentException.class, () -> ProofDomains.target(pendingState(new byte[32]), envelope(5)));
        assertThrows(IllegalArgumentException.class, () -> ProofDomains.recoveryCommitment(state(), envelope(7)));
        var sameModule = replace(envelope(2), 3, PlutusData.constr(2, module(), config()));
        assertThrows(IllegalArgumentException.class, () -> ProofDomains.configuration(state(), sameModule));
        var alteredConfig = replace(config(), 4, policy(1)); // valid config with a changed freeze authority
        var changedTarget = replace(completion(), 3, PlutusData.constr(7, number(1), alteredConfig));
        assertThrows(IllegalArgumentException.class, () -> ProofDomains.target(pendingState(WireFormat.digest(commitment)), changedTarget));
        assertThrows(IllegalArgumentException.class, () -> ProofDomains.target(state(), completion()));
        Files.writeString(Path.of("build/phase0/pending-state.hex"), PlutusDataAdapter.toClientLib(pendingState(WireFormat.digest(commitment))).serializeToHex() + "\n");
    }
}
