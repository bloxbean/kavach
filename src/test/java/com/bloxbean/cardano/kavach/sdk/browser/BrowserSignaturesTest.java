package com.bloxbean.cardano.kavach.sdk.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.model.Networks;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Adversarial tests at the untrusted wallet-response boundary, independent of ledger submission.
 */
class BrowserSignaturesTest {
    private static final HexFormat HEX = HexFormat.of();
    private final byte[] secret =
            new byte[32]; // Disposable deterministic test vector, never funded.
    private final byte[] key = new byte[32];
    private final byte[] payload = new byte[32];

    BrowserSignaturesTest() {
        Arrays.fill(secret, (byte) 17);
        Arrays.fill(payload, (byte) 42);
        Ed25519.generatePublicKey(secret, 0, key, 0);
    }

    private String coseKey() {
        return "a4010103272006215820" + HEX.formatHex(key);
    }

    private byte[] address(int type, int network) {
        byte[] value = new byte[type == 6 ? 29 : 57];
        value[0] = (byte) (type * 16 + network);
        System.arraycopy(BrowserSignatures.keyHash(key), 0, value, 1, 28);
        return value;
    }

    private String signed(byte[] address) {
        byte[] signature = new byte[64];
        byte[] message = BrowserSignatures.signingStructure(address, payload);
        Ed25519.sign(secret, 0, message, 0, message.length, signature, 0);
        String header =
                "a20127676164647265737358"
                        + String.format("%02x", address.length)
                        + HEX.formatHex(address);
        return "8458"
                + String.format("%02x", header.length() / 2)
                + header
                + "a166686173686564f45820"
                + HEX.formatHex(payload)
                + "5840"
                + HEX.formatHex(signature);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 6})
    void acceptsSupportedPaymentAddressesAndOptionalTag(int type) {
        for (int network = 0; network <= 1; network++) {
            String response = signed(address(type, network));
            byte[] evidence =
                    BrowserSignatures.fromCip30(key, payload, response, coseKey(), network);
            assertTrue(BrowserSignatures.verify(key, payload, evidence, network));
            assertArrayEquals(
                    evidence,
                    BrowserSignatures.fromCip30(key, payload, "d2" + response, coseKey(), network));
            assertArrayEquals(
                    evidence,
                    BrowserSignatures.fromCip30(
                            key,
                            payload,
                            response.replace("a166686173686564f4", "a0"),
                            coseKey(),
                            network));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 6})
    void acceptsCclSignerUsedByYano(int type) throws Exception {
        for (int network = 0; network <= 1; network++) {
            var response = CIP30DataSigner.INSTANCE.signData(address(type, network), payload, secret, key);
            assertTrue(CIP30DataSigner.INSTANCE.verify(response));
            assertArrayEquals(key, BrowserSignatures.publicKey(response.key()));
            byte[] evidence = BrowserSignatures.fromCip30(key, payload, response.signature(), response.key(), network);
            assertTrue(BrowserSignatures.verify(key, payload, evidence, network));
        }
    }

    @Test
    void acceptsExtendedAccountKeyUsedByYano() throws Exception {
        var account = new Account(Networks.testnet()); // Disposable, never funded or logged.
        var response = CIP30DataSigner.INSTANCE.signData(new Address(account.baseAddress()).getBytes(), payload, account);
        assertTrue(BrowserSignatures.verify(account.publicKeyBytes(), payload,
                BrowserSignatures.fromCip30(account.publicKeyBytes(), payload, response.signature(), response.key(), 0), 0));
    }

    @Test
    void rejectsAlteredCclHeadersAndVariantSelectors() throws Exception {
        var address = address(6, 0);
        var response = CIP30DataSigner.INSTANCE.signData(address, payload, secret, key);
        String addressHex = HEX.formatHex(address);
        String wrongAddress = "61" + addressHex.substring(2);
        for (String badKey : new String[]{
                response.key().replace(addressHex, wrongAddress),
                response.key().replace("02581d" + addressHex, "").replaceFirst("a5", "a4"),
                response.key().replace("02581d", "04581d"),
                response.key().replaceFirst("a5", "a6") + "02581d" + addressHex
        }) {
            assertNotEquals(response.key(), badKey);
            assertThrows(IllegalArgumentException.class,
                    () -> BrowserSignatures.fromCip30(key, payload, response.signature(), badKey, 0));
        }
        // Even matching unsigned key metadata cannot authorize a substituted signed kid/address.
        assertThrows(IllegalArgumentException.class, () -> BrowserSignatures.fromCip30(key, payload,
                response.signature().replace(addressHex, wrongAddress), response.key().replace(addressHex, wrongAddress), 0));
        for (int length = 0; length < response.signature().length(); length += 2) {
            String truncated = response.signature().substring(0, length);
            assertThrows(IllegalArgumentException.class,
                    () -> BrowserSignatures.fromCip30(key, payload, truncated, response.key(), 0));
        }
        byte[] packed = BrowserSignatures.fromCip30(key, payload, response.signature(), response.key(), 0);
        assertFalse(BrowserSignatures.verify(key, payload, Arrays.copyOf(packed, packed.length - 1), 0));
        packed[packed.length - 1] = 2;
        assertFalse(BrowserSignatures.verify(key, payload, packed, 0));
    }

    @Test
    void rejectsEveryTruncationAndTrailingBytes() {
        String response = signed(address(6, 0));
        for (int length = 0; length < response.length(); length += 2) {
            String truncated = response.substring(0, length);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BrowserSignatures.fromCip30(key, payload, truncated, coseKey(), 0));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserSignatures.fromCip30(key, payload, response + "00", coseKey(), 0));
    }

    @Test
    void rejectsMismatchedPayloadKeyNetworkAndSignature() {
        String response = signed(address(6, 0));
        byte[] wrong = payload.clone();
        wrong[0]++;
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserSignatures.fromCip30(key, wrong, response, coseKey(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserSignatures.fromCip30(new byte[32], payload, response, coseKey(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserSignatures.fromCip30(key, payload, response, coseKey(), 1));
        byte[] altered = HEX.parseHex(response);
        altered[altered.length - 1] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BrowserSignatures.fromCip30(
                                key, payload, HEX.formatHex(altered), coseKey(), 0));
    }

    @Test
    void rejectsUnsupportedAndAmbiguousHeaders() {
        String response = signed(address(6, 0));
        for (String altered :
                new String[]{
                        response.replace("a166686173686564f4", "a166686173686564f5"),
                        response.replace("a2012767", "a2012667"),
                        response.replaceFirst("8458", "9f58"),
                        response.replaceFirst("84582a", "8459002a")
                }) {
            assertNotEquals(response, altered);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BrowserSignatures.fromCip30(key, payload, altered, coseKey(), 0));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BrowserSignatures.fromCip30(
                                key, payload, signed(address(1, 0)), coseKey(), 0));
    }

    @Test
    void keyParserRejectsDuplicateExtraWrongCurveAndTrailingFields() {
        for (String malformed :
                new String[]{
                        coseKey().replace("0327", "0101"),
                        coseKey().replace("2006", "2001"),
                        coseKey().replaceFirst("a4", "a5"),
                        coseKey() + "00",
                        "00",
                        "a4",
                        "g0"
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> BrowserSignatures.publicKey(malformed));
        }
        assertArrayEquals(
                key, BrowserSignatures.publicKey("a4215820" + HEX.formatHex(key) + "200603270101"));
        assertFalse(BrowserSignatures.verify(key, payload, new byte[94], 0));
    }
}
