package com.bloxbean.cardano.kavach.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Language-neutral hand-encoded constructor vectors, independent of Java record declaration order. */
class PolicyConfigurationTest {
    @Test void canonicalMixedAndPeriodicVectorsRoundTripWithExactDigests() throws Exception {
        try (var stream = getClass().getResourceAsStream("/policy/v1.json")) {
            assertNotNull(stream);
            var entries = JsonUtil.parseJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(8, entries.size());
            for (var entry : entries) {
                var bytes = HexFormat.of().parseHex(entry.get("cbor").asText());
                var data = PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(bytes));
                assertArrayEquals(bytes, Builtins.serialiseData(data), entry.get("name").asText());
                assertEquals(entry.get("digest").asText(), HexFormat.of().formatHex(WireFormat.digest(data)));
                Object decoded = switch (entry.get("kind").asText()) {
                    case "mixed" -> PolicyConfigCodec.decode(data);
                    case "budget" -> PeriodicBudgetCodec.decode(data);
                    case "usage" -> PeriodicBudgetCodec.usage(data);
                    default -> throw new AssertionError("Unknown vector kind");
                };
                assertEquals(data, AccountCodec.data(decoded));
                if (!entry.get("kind").asText().equals("usage")) {
                    WireFormat.validateConfig(data);
                    assertThrows(IllegalArgumentException.class, () -> WireFormat.validateConfig(
                            WireFixtures.replace(data, 0, PlutusData.integer(2))));
                }
            }
        }
    }
}
