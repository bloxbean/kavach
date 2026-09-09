package com.bloxbean.cardano.kavach.sdk.browser;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Strict adapter for the initial bounded CIP-30 COSE profile. It verifies wallet output before
 * converting it to bounded on-chain evidence. No private key is accepted. Unsupported
 * header/address encodings fail explicitly; this is not a general COSE parser.
 */
public final class BrowserSignatures {
    /**
     * Immutable evidence mode; values are distinct from raw Ed25519 scheme zero.
     */
    public enum Mode {
        /**
         * Transaction-body required signers, with ledger-verified payment-key witnesses.
         */
        TRANSACTION(1),
        /**
         * CIP-30 COSE intent approval with the bounded protected-header profile.
         */
        COSE(2);
        private final int id;

        Mode(int id) {
            this.id = id;
        }

        /**
         * @return normative browser proof scheme and applied module mode
         */
        public int id() {
            return id;
        }
    }

    /**
     * Verifies a complete CIP-30 response and returns the bounded on-chain proof bytes.
     *
     * @param registeredKey authenticated 32-byte registry key, not a key trusted from the response
     * @param payload       expected 32-byte domain-specific Kavach digest
     * @param coseSign1Hex  wallet's complete COSE_Sign1 CBOR
     * @param coseKeyHex    wallet's COSE_Key CBOR
     * @param network       expected address network ID, zero or one
     * @return address and signature, with a trailing 1 for the protected address-valued kid variant
     * @throws IllegalArgumentException for malformed, unsupported, mismatched or invalid evidence
     */
    public static byte[] fromCip30(
            byte[] registeredKey,
            byte[] payload,
            String coseSign1Hex,
            String coseKeyHex,
            int network) {
        var responseKey = readKey(coseKeyHex);
        require(
                Arrays.equals(registeredKey, responseKey.key()),
                "Wallet key differs from registered authority");
        var reader = new Reader(hex(coseSign1Hex, 512));
        if (reader.peek() == 0xd2) reader.take(0xd2);
        reader.take(0x84);
        byte[] protectedBytes = reader.bytes();
        // Empty unprotected map or the CIP-8 hashed:false annotation. Neither is signed authority.
        if (reader.peek() == 0xa0) reader.take(0xa0);
        else {
            reader.take(0xa1);
            reader.literal(new byte[]{0x66, 'h', 'a', 's', 'h', 'e', 'd', (byte) 0xf4});
        }
        require(
                Arrays.equals(payload, reader.bytes()),
                "COSE payload differs from canonical request");
        byte[] signature = reader.bytes();
        reader.end();
        var headers = new Reader(protectedBytes);
        boolean withKid = headers.peek() == 0xa3;
        headers.take(withKid ? 0xa3 : 0xa2);
        headers.literal(new byte[]{1, 0x27});
        byte[] kid = null;
        if (withKid) {
            headers.take(4);
            kid = headers.bytes();
        }
        headers.literal(new byte[]{0x67, 'a', 'd', 'd', 'r', 'e', 's', 's'});
        byte[] address = headers.bytes();
        headers.end();
        require(Arrays.equals(kid, responseKey.kid()), "COSE key identifier differs between key and signature");
        require(!withKid || Arrays.equals(kid, address), "Unsupported COSE key identifier: expected signing address");
        byte[] packed = withKid ? concat(address, signature, new byte[]{1}) : concat(address, signature);
        require(
                verify(registeredKey, payload, packed, network),
                "Invalid COSE address or signature");
        return packed;
    }

    /**
     * Reads an Ed25519 COSE_Key with kty, alg, crv, x and optional address-sized kid.
     * Duplicate and other extra fields reject; fromCip30 additionally binds kid to signed headers.
     * This identifies a candidate key only. Enrollment still requires authenticated possession.
     *
     * @param coseKeyHex bounded wallet COSE_Key CBOR
     * @return 32-byte candidate public key
     */
    public static byte[] publicKey(String coseKeyHex) {
        return readKey(coseKeyHex).key();
    }

    private record ResponseKey(byte[] key, byte[] kid) {
    }

    private static ResponseKey readKey(String coseKeyHex) {
        var r = new Reader(hex(coseKeyHex, 128));
        int size = r.next();
        require(size == 0xa4 || size == 0xa5, "Expected COSE key map with four fields or optional kid");
        int count = size - 0xa0;
        byte[] kid = null;
        int fields = 0;
        byte[] key = null;
        for (int i = 0; i < count; i++) {
            int label = r.next();
            int bit;
            switch (label) {
                case 1 -> {
                    bit = 1;
                    r.take(1);
                }
                case 2 -> {
                    bit = 16;
                    kid = r.bytes();
                    require(kid.length == 29 || kid.length == 57, "Unsupported COSE key identifier size");
                }
                case 3 -> {
                    bit = 2;
                    r.take(0x27);
                }
                case 0x20 -> {
                    bit = 4;
                    r.take(6);
                }
                case 0x21 -> {
                    bit = 8;
                    key = r.bytes();
                    require(key.length == 32, "Ed25519 key length");
                }
                default -> throw new IllegalArgumentException("Unsupported COSE key field");
            }
            require((fields & bit) == 0, "Duplicate COSE key field");
            fields |= bit;
        }
        r.end();
        require((fields & 15) == 15 && key != null, "Incomplete COSE key");
        return new ResponseKey(key, kid);
    }

    /**
     * Verifies already packed proof bytes independently of the on-chain implementation.
     */
    public static boolean verify(byte[] key, byte[] payload, byte[] packed, int network) {
        if (key == null
                || key.length != 32
                || payload == null
                || payload.length != 32
                || packed == null
                || (packed.length != 93 && packed.length != 121 && packed.length != 94 && packed.length != 122)
                || (network != 0 && network != 1)) return false;
        boolean withKid = packed.length == 94 || packed.length == 122;
        if (withKid && packed[packed.length - 1] != 1) return false;
        int length = packed.length - 64 - (withKid ? 1 : 0), header = packed[0] & 255, type = header >>> 4;
        if ((header & 15) != network
                || !(length == 29 ? type == 6 : type == 0 || type == 2)
                || !Arrays.equals(Arrays.copyOfRange(packed, 1, 29), keyHash(key))) return false;
        byte[] message = signingStructure(Arrays.copyOf(packed, length), payload, withKid);
        return Ed25519.verify(packed, length, key, 0, message, 0, message.length);
    }

    /**
     * Produces the exact CBOR signing structure for the supported address/header profile.
     */
    public static byte[] signingStructure(byte[] address, byte[] payload) {
        return signingStructure(address, payload, false);
    }

    /**
     * Reconstructs one exact protected map; kid, when enabled, is the same address byte string.
     */
    public static byte[] signingStructure(byte[] address, byte[] payload, boolean withKid) {
        require(
                (address.length == 29 || address.length == 57) && payload.length == 32,
                "COSE profile size");
        byte[] headers =
                concat(
                        new byte[]{(byte) (withKid ? 0xa3 : 0xa2), 1, 0x27},
                        withKid ? concat(new byte[]{4}, bytes(address)) : new byte[0],
                        new byte[]{0x67, 'a', 'd', 'd', 'r', 'e', 's', 's'},
                        bytes(address));
        return concat(
                new byte[]{(byte) 0x84, 0x6a, 'S', 'i', 'g', 'n', 'a', 't', 'u', 'r', 'e', '1'},
                bytes(headers),
                new byte[]{0x40},
                bytes(payload));
    }

    /**
     * @return the Cardano payment-key hash of a raw 32-byte Ed25519 public key
     */
    public static byte[] keyHash(byte[] key) {
        require(key != null && key.length == 32, "Ed25519 key length");
        var hash = new Blake2bDigest(224);
        hash.update(key, 0, key.length);
        byte[] out = new byte[28];
        hash.doFinal(out, 0);
        return out;
    }

    private static byte[] bytes(byte[] value) {
        return concat(new byte[]{0x58, (byte) value.length}, value);
    }

    private static byte[] concat(byte[]... arrays) {
        var out = new ByteArrayOutputStream();
        for (var bytes : arrays) out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static byte[] hex(String value, int max) {
        require(value != null && value.length() <= max * 2 && value.length() % 2 == 0, "CBOR size");
        try {
            return HexFormat.of().parseHex(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid CBOR hex", error);
        }
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data) {
            this.data = data;
        }

        int peek() {
            require(offset < data.length, "Truncated CBOR");
            return data[offset] & 255;
        }

        int next() {
            int value = peek();
            offset++;
            return value;
        }

        void take(int expected) {
            require(next() == expected, "Unsupported CBOR encoding");
        }

        void literal(byte[] expected) {
            for (byte b : expected) take(b & 255);
        }

        byte[] bytes() {
            int prefix = next();
            int length;
            if (prefix >= 0x40 && prefix <= 0x57) length = prefix - 0x40;
            else {
                require(prefix == 0x58, "Unsupported CBOR byte string");
                length = next();
                require(length >= 24, "Nonminimal CBOR length");
            }
            require(length <= data.length - offset, "Truncated byte string");
            byte[] result = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return result;
        }

        void end() {
            require(offset == data.length, "Trailing CBOR");
        }
    }

    private BrowserSignatures() {
    }
}
