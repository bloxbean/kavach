package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * Authenticated offline companion transport. Its local identity never authorizes ledger spending.
 */
final class CompanionExchange {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] DOMAIN = "YANO_COMPANION_REQUEST_V1\0".getBytes(StandardCharsets.UTF_8);
    private final Path identity;

    CompanionExchange(Path identity) {
        this.identity = identity;
    }

    private synchronized byte[] key() throws Exception {
        if (!Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(identity.toAbsolutePath().getParent());
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            try {
                Files.createFile(identity, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(identity, secret);
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }
        if (Files.isSymbolicLink(identity) || !Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)
                || Files.size(identity) != 32
                || Files.getPosixFilePermissions(identity).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("Companion identity must be a private, regular 32-byte file (0600)");
        return Files.readAllBytes(identity);
    }

    Map<String, Object> pairing() throws Exception {
        byte[] secret = key();
        try {
            byte[] pub = new byte[32];
            Ed25519.generatePublicKey(secret, 0, pub, 0);
            var pair = Map.of("version", 1, "kind", "pair", "name", "Kavach dashboard", "publicKey", hex(pub));
            byte[] fingerprint = new byte[32];
            var hash = new Blake2bDigest(256);
            hash.update(pub, 0, pub.length);
            hash.doFinal(fingerprint, 0);
            return Map.of("pairing", pair, "qr", JSON.writeValueAsString(pair), "fingerprint", hex(fingerprint));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    Map<String, Object> request(UUID ticket, String profile, String cbor, String stateCbor,
                                byte[] publicKey, int credentialId, long expiresAt) throws Exception {
        return request(ticket, profile, cbor, stateCbor, publicKey, credentialId, expiresAt, null);
    }

    Map<String, Object> request(UUID ticket, String profile, String cbor, String stateCbor,
                                byte[] publicKey, int credentialId, long expiresAt, String proofPurpose) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", ticket.toString());
        body.put("profile", profile);
        body.put("intentCBOR", cbor);
        body.put("signerPublicKey", hex(publicKey));
        body.put("credentialID", credentialId);
        body.put("expiresAt", expiresAt);
        if (stateCbor != null) body.put("stateCBOR", stateCbor);
        if (proofPurpose != null) body.put("proofPurpose", proofPurpose);
        byte[] bytes = JSON.writeValueAsBytes(body);
        if (bytes.length > 4096) throw new IllegalArgumentException("Companion request exceeds its bounded profile");
        byte[] secret = key();
        try {
            byte[] pub = new byte[32];
            Ed25519.generatePublicKey(secret, 0, pub, 0);
            byte[] message = new byte[DOMAIN.length + bytes.length];
            System.arraycopy(DOMAIN, 0, message, 0, DOMAIN.length);
            System.arraycopy(bytes, 0, message, DOMAIN.length, bytes.length);
            byte[] signature = new byte[64];
            Ed25519.sign(secret, 0, message, 0, message.length, signature, 0);
            var envelope = Map.of("version", 1, "kind", "request", "senderPublicKey", hex(pub),
                    "body", Base64.getEncoder().encodeToString(bytes), "signature", hex(signature));
            byte[] encoded = JSON.writeValueAsBytes(envelope);
            if (encoded.length > 8192) throw new IllegalArgumentException("Companion envelope too large");
            var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            try {
                deflater.setInput(encoded);
                deflater.finish();
                byte[] compressed = new byte[9216];
                int count = deflater.deflate(compressed);
                if (!deflater.finished()) throw new IllegalArgumentException("Companion compression failed");
                return Map.of("request", envelope, "qr", "YANO1:" + Base64.getEncoder().encodeToString(Arrays.copyOf(compressed, count)),
                        "expiresAt", expiresAt, "publicKey", hex(publicKey), "credentialID", credentialId);
            } finally {
                deflater.end();
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    static byte[] verify(Map<String, Object> response, UUID ticket, String profile, int id,
                         byte[] key, byte[] digest, long expiresAt) {
        if (expiresAt <= Instant.now().toEpochMilli())
            throw new IllegalArgumentException("Companion request expired; export a fresh request");
        if (!"1".equals(String.valueOf(response.get("version"))) || !"approval".equals(response.get("kind"))
                || !ticket.toString().equalsIgnoreCase(String.valueOf(response.get("requestID")))
                || !profile.equals(response.get("profile")) || !String.valueOf(id).equals(String.valueOf(response.get("credentialID")))
                || !hex(key).equals(response.get("publicKey")) || !hex(digest).equals(response.get("digest")))
            throw new IllegalArgumentException("Phone response does not match this plan, credential and proof domain");
        return BrowserSignatures.fromCip30(key, digest, DemoServer.string(response, "signature"), DemoServer.string(response, "key"), 0);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
