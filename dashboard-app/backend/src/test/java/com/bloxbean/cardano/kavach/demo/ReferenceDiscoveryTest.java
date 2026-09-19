package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Preserves legacy discovery without turning provider failures into paid republication. */
class ReferenceDiscoveryTest {
    @Test
    void legacyOutpointHintsAreBoundedAndMalformedHintsDoNotResolve() {
        String hash = "ab".repeat(32);
        var valid = DemoService.referenceHint(hash + "#3\n").orElseThrow();
        assertEquals(hash, valid.getTransactionId());
        assertEquals(3, valid.getIndex());
        assertTrue(DemoService.referenceHint(null).isEmpty());
        for (String hint : List.of("", "../record", hash + "#-1", hash + "#1#2",
                hash + "#2147483648", hash + "#99999999999", "ab#0", " ".repeat(97)))
            assertTrue(DemoService.referenceHint(hint).isEmpty(), hint);
    }

    @Test
    @SuppressWarnings("unchecked") // Pinned CCL Result factories return raw Result.
    void missingReferenceDiffersFromProviderFailureAndWrongScriptOrNetwork() {
        String expected = "cd".repeat(28);
        Result<Utxo> missing = Result.error("Not found").code(404);
        assertTrue(DemoService.referenceResponse(missing, expected).isEmpty());
        for (int code : List.of(400, 401, 429, 500, 503)) {
            Result<Utxo> failure = Result.error("Provider failure").code(code);
            assertThrows(IllegalArgumentException.class, () -> DemoService.referenceResponse(failure, expected));
        }
        Result<Utxo> emptySuccess = Result.success("Malformed response").code(200);
        assertThrows(IllegalArgumentException.class, () -> DemoService.referenceResponse(emptySuccess, expected));
        var output = Utxo.builder().txHash("ab".repeat(32)).outputIndex(3)
                .address(new Account(Networks.testnet()).baseAddress()).referenceScriptHash(expected).build();
        Result<Utxo> available = Result.success("Found").code(200).withValue(output);
        assertSame(output, DemoService.referenceResponse(available, expected).orElseThrow());
        assertTrue(DemoService.referenceResponse(available, "ef".repeat(28)).isEmpty());
        output.setAddress(new Account(Networks.mainnet()).baseAddress());
        assertThrows(IllegalArgumentException.class, () -> DemoService.referenceResponse(available, expected));
    }
}
