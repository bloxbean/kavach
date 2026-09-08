package com.bloxbean.cardano.kavach.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Inflater;

class CompanionExchangeTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    @Test void pinnedIdentityAndCompressedAuthenticatedRequest() throws Exception {
        var exchange = new CompanionExchange(directory.resolve("identity.bin"));
        var pair = exchange.pairing();
        assertEquals(pair, new CompanionExchange(directory.resolve("identity.bin")).pairing());
        UUID ticket = UUID.randomUUID();
        var result = exchange.request(ticket, "kavach-cose-genesis-v1", "", "d87980", new byte[32], 0, 2000);
        var inflater = new Inflater(true);
        try {
            inflater.setInput(Base64.getDecoder().decode(((String) result.get("qr")).substring(6)));
            byte[] decoded = new byte[8193]; int n = inflater.inflate(decoded);
            assertTrue(inflater.finished());
            var envelope = JSON.readTree(decoded, 0, n);
            byte[] body = Base64.getDecoder().decode(envelope.get("body").asText());
            byte[] domain = "YANO_COMPANION_REQUEST_V1\0".getBytes(StandardCharsets.UTF_8);
            byte[] message = new byte[domain.length + body.length];
            System.arraycopy(domain, 0, message, 0, domain.length);
            System.arraycopy(body, 0, message, domain.length, body.length);
            byte[] key = HEX.parseHex(envelope.get("senderPublicKey").asText());
            byte[] sig = HEX.parseHex(envelope.get("signature").asText());
            assertTrue(Ed25519.verify(sig, 0, key, 0, message, 0, message.length));
            assertEquals(ticket.toString(), JSON.readTree(body).get("id").asText());
            message[message.length - 1] ^= 1;
            assertFalse(Ed25519.verify(sig, 0, key, 0, message, 0, message.length));
        } finally { inflater.end(); }
        Files.write(directory.resolve("identity.bin"), new byte[1]);
        assertThrows(IllegalArgumentException.class, exchange::pairing);
    }

    @Test void rejectsMismatchedExpiredAndForgedApproval() throws Exception {
        byte[] secret = new byte[32], key = new byte[32], digest = new byte[32];
        new SecureRandom().nextBytes(secret); Ed25519.generatePublicKey(secret, 0, key, 0);
        byte[] address = new byte[29]; address[0] = 0x60;
        System.arraycopy(BrowserSignatures.keyHash(key), 0, address, 1, 28);
        var signed = CIP30DataSigner.INSTANCE.signData(address, digest, secret, key);
        var ticket = UUID.randomUUID(); String profile = "kavach-cose-genesis-v1";
        long expires = Instant.now().toEpochMilli() + 300_000;
        var response = new HashMap<String, Object>(Map.of("version", 1, "kind", "approval", "requestID", ticket.toString(),
                "profile", profile, "credentialID", 0, "publicKey", HEX.formatHex(key), "digest", HEX.formatHex(digest),
                "signature", signed.signature(), "key", signed.key()));
        assertTrue(BrowserSignatures.verify(key, digest, CompanionExchange.verify(response, ticket, profile, 0, key, digest, expires), 0));
        assertThrows(IllegalArgumentException.class, () -> CompanionExchange.verify(response, ticket, profile, 0, key, digest, 0));
        for (String field : new String[]{"requestID", "profile", "credentialID", "publicKey", "digest", "version", "kind", "signature", "key"}) {
            var changed = new HashMap<>(response); changed.put(field, "00");
            assertThrows(Exception.class, () -> CompanionExchange.verify(changed, ticket, profile, 0, key, digest, expires), field);
        }
    }
}
