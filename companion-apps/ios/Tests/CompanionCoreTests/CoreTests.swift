import XCTest
import CryptoKit
@testable import CompanionCore

final class CoreTests: XCTestCase {
    func testGenesisGoldenAndRejectedStateChanges() throws {
        struct Golden: Decodable { let stateCbor: String; let digest: String }
        let url = Bundle.module.url(forResource: "genesis", withExtension: "json", subdirectory: "Fixtures")!
        let golden = try JSONDecoder().decode(Golden.self, from: Data(contentsOf: url))
        let bytes = try Data(hex: golden.stateCbor)
        var fields = try PData.decode(bytes).fields(0, 12)
        let ownKey = try fields[6].fields(0, 8)[1].items(min: 3, max: 16)[0].fields(0, 2)[1].bytes(count: 32).hex
        XCTAssertEqual(try KavachGenesis(cbor: bytes, ownKey: ownKey, credentialID: 0).digest.hex, golden.digest)
        XCTAssertThrowsError(try KavachGenesis(cbor: bytes, ownKey: ownKey, credentialID: 1))
        for index in [4, 7, 10] {
            var changed = fields; changed[index] = .uint(1)
            XCTAssertThrowsError(try KavachGenesis(cbor: PData.constr(0, changed).encoded(), ownKey: ownKey, credentialID: 0))
        }
        var config = try fields[6].fields(0, 8)
        config[5] = config[2] // Spend may not also authorize Unfreeze.
        fields[6] = .constr(0, config)
        XCTAssertThrowsError(try KavachGenesis(cbor: PData.constr(0, fields).encoded(), ownKey: ownKey, credentialID: 0))
    }
    func testGenesisAuthenticatedExchangeAndExpiry() throws {
        struct Golden: Decodable { let stateCbor: String }
        let url = Bundle.module.url(forResource: "genesis", withExtension: "json", subdirectory: "Fixtures")!
        let golden = try JSONDecoder().decode(Golden.self, from: Data(contentsOf: url))
        let device = Curve25519.Signing.PrivateKey(), desktop = Curve25519.Signing.PrivateKey()
        let pub = device.publicKey.rawRepresentation
        var fields = try PData.decode(Data(hex: golden.stateCbor)).fields(0, 12)
        var config = try fields[6].fields(0, 8), keys = try config[1].items(min: 3, max: 16)
        keys[0] = .constr(0, [.uint(0), .bytes(pub)]); config[1] = .list(keys); fields[6] = .constr(0, config)
        let peer = Pairing(name: "Test", publicKey: desktop.publicKey.rawRepresentation.hex)
        let body = RequestBody(id: UUID(), profile: "kavach-cose-genesis-v1", intentCBOR: "", signerPublicKey: pub.hex, credentialID: 0, stateCBOR: PData.constr(0, fields).encoded().hex, expiresAt: 2000)
        let raw = try JSONEncoder().encode(body)
        let bytes = try JSONEncoder().encode(RequestEnvelope(senderPublicKey: peer.publicKey, body: raw.base64EncodedString(), signature: desktop.signature(for: ReviewedRequest.domain + raw).hex))
        let request = try ReviewedRequest.parse(bytes, peers: [peer], ownKey: pub.hex, usedDigests: [], now: Date(timeIntervalSince1970: 1))
        XCTAssertNil(request.spend); XCTAssertNotNil(request.genesis)
        let response = try request.response(publicKey: pub, signature: device.signature(for: request.signingBytes(publicKey: pub)))
        XCTAssertNotNil(response.key); XCTAssertNoThrow(try request.verify(response))
        XCTAssertThrowsError(try ReviewedRequest.parse(bytes, peers: [peer], ownKey: pub.hex, usedDigests: [], now: Date(timeIntervalSince1970: 2)))
        XCTAssertThrowsError(try ReviewedRequest.parse(bytes, peers: [peer], ownKey: pub.hex, usedDigests: [request.digest.hex], now: Date(timeIntervalSince1970: 1)))
        if let directory = ProcessInfo.processInfo.environment["COMPANION_EVIDENCE_DIR"] {
            try response.json().write(toFile: directory + "/swift-genesis-response.json", atomically: true, encoding: .utf8)
        }
    }
    func testCompressedQRPreservesSignedBytesAndBoundsExpansion() throws {
        let device = Curve25519.Signing.PrivateKey(), desktop = Curve25519.Signing.PrivateKey()
        let (data, peer) = try envelope(device: device, desktop: desktop)
        let packed = try QRTransport.encode(data)
        XCTAssertLessThan(packed.utf8.count, data.count)
        XCTAssertEqual(try QRTransport.decode(packed), data)
        XCTAssertNoThrow(try ReviewedRequest.parse(QRTransport.decode(packed), peers: [peer], ownKey: device.publicKey.rawRepresentation.hex, usedDigests: [], now: Date(timeIntervalSince1970: 1.5)))
        XCTAssertEqual(try QRTransport.decode(String(decoding: data, as: UTF8.self)), data)
        XCTAssertThrowsError(try QRTransport.decode("YANO1:not-base64"))
        XCTAssertThrowsError(try QRTransport.encode(Data(repeating: 0, count: 8193)))
        // Raw DEFLATE expansion of 9000 ASCII 'A' bytes, generated using Python zlib.
        XCTAssertThrowsError(try QRTransport.decode("YANO1:7cEBDQAAAMKgbO9fyh4OKAAAAAAAAAAA+Dc="))
    }
    struct Golden: Decodable { let actionTag: Int; let cbor, digest, publicKey, signature: String }
    func vectors() throws -> [Golden] {
        let url = Bundle.module.url(forResource: "kavach-intents", withExtension: "json", subdirectory: "Fixtures")!
        return try JSONDecoder().decode([Golden].self, from: Data(contentsOf: url))
    }
    func sample() throws -> PData { try PData.decode(Data(hex: vectors()[0].cbor)) }
    func updateRoot(_ root: PData, index: Int, value: PData) throws -> Data {
        var fields = try root.fields(0, 4); fields[index] = value; return PData.constr(0, fields).encoded()
    }
    func testPublishedKavachSpendVectorAndSignature() throws {
        let v = try vectors()[0], intent = try KavachIntent(cbor: Data(hex: v.cbor))
        XCTAssertEqual(intent.digest.hex, v.digest)
        XCTAssertEqual(intent.lovelace, 2_000_000); XCTAssertEqual(intent.maxFee, 500_000)
        XCTAssertEqual(intent.accountID, String(repeating: "01", count: 28))
        XCTAssertTrue(intent.recipient.hasPrefix("addr_test1v"))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: Data(hex: v.publicKey))
        XCTAssertTrue(key.isValidSignature(try Data(hex: v.signature), for: intent.digest))
    }
    func testUnsupportedActionsNeverBecomeSpendApprovals() throws {
        for vector in try vectors().dropFirst() { XCTAssertThrowsError(try KavachIntent(cbor: Data(hex: vector.cbor)), "Action \(vector.actionTag)") }
    }
    func testHashBlockBoundariesAgainstPythonHashlib() throws {
        struct Vector: Decodable { let data: String; let length: Int; let digest: String }
        let url = Bundle.module.url(forResource: "blake2b", withExtension: "json", subdirectory: "Fixtures")!
        for v in try JSONDecoder().decode([Vector].self, from: Data(contentsOf: url)) {
            XCTAssertEqual(Blake2b.hash(try Data(hex: v.data), length: v.length).hex, v.digest)
        }
    }
    func testCanonicalAndMalformedInputs() throws {
        let bytes = try Data(hex: vectors()[0].cbor)
        XCTAssertThrowsError(try KavachIntent(cbor: bytes + Data([0])))
        for length in 0..<bytes.count { XCTAssertThrowsError(try PData.decode(bytes.prefix(length))) }
        XCTAssertThrowsError(try PData.decode(Data([0x18, 0x01]))) // nonminimal integer
        XCTAssertThrowsError(try PData.decode(Data(repeating: 0x9f, count: 1536)))
        XCTAssertThrowsError(try PData.decode(Data(repeating: 0, count: 1537)))
    }
    func testNetworkExpiryAndFeeBounds() throws {
        let root = try sample(); var domain = try root.fields(0, 4)[1].fields(0, 6)
        var deployment = try domain[1].fields(0, 3); deployment[0] = .uint(1); domain[1] = .constr(0, deployment)
        XCTAssertThrowsError(try KavachIntent(cbor: updateRoot(root, index: 1, value: .constr(0, domain))))
        let intent = try KavachIntent(cbor: root.encoded())
        XCTAssertNoThrow(try intent.validateTime(now: Date(timeIntervalSince1970: 1)))
        XCTAssertThrowsError(try intent.validateTime(now: Date(timeIntervalSince1970: 2)))
        XCTAssertThrowsError(try intent.validateTime(now: Date(timeIntervalSince1970: 0.9)))
        XCTAssertThrowsError(try KavachIntent(cbor: updateRoot(root, index: 2, value: .constr(0, [.uint(1000), .uint(301001)]))))
        var spend = try root.fields(0, 4)[3].fields(0, 3); spend[2] = .uint(5_000_001)
        XCTAssertThrowsError(try KavachIntent(cbor: updateRoot(root, index: 3, value: .constr(0, spend))))
    }
    func envelope(profile: String = "kavach-cose-spend-v1", device: Curve25519.Signing.PrivateKey, desktop: Curve25519.Signing.PrivateKey) throws -> (Data, Pairing) {
        let peer = Pairing(version: 1, kind: "pair", name: "Test desktop", publicKey: desktop.publicKey.rawRepresentation.hex)
        let body = RequestBody(id: UUID(), profile: profile, intentCBOR: try vectors()[0].cbor,
            signerPublicKey: device.publicKey.rawRepresentation.hex, credentialID: 0)
        let bytes = try JSONEncoder().encode(body)
        let envelope = RequestEnvelope(version: 1, kind: "request", senderPublicKey: peer.publicKey,
            body: bytes.base64EncodedString(), signature: try desktop.signature(for: ReviewedRequest.domain + bytes).hex)
        return (try JSONEncoder().encode(envelope), peer)
    }
    func testAuthenticatedExchangeAndBothSignatureProfiles() throws {
        let device = Curve25519.Signing.PrivateKey(), desktop = Curve25519.Signing.PrivateKey()
        for profile in ["kavach-cose-spend-v1", "kavach-raw-spend-v1"] {
            let (bytes, peer) = try envelope(profile: profile, device: device, desktop: desktop)
            let request = try ReviewedRequest.parse(bytes, peers: [peer], ownKey: device.publicKey.rawRepresentation.hex, usedDigests: [], now: Date(timeIntervalSince1970: 1.5))
            let signature = try device.signature(for: request.signingBytes(publicKey: device.publicKey.rawRepresentation))
            let response = try request.response(publicKey: device.publicKey.rawRepresentation, signature: signature)
            XCTAssertEqual(response.digest, request.digest.hex)
            XCTAssertEqual(response.key != nil, profile.contains("cose"))
            XCTAssertNoThrow(try request.verify(response))
            if profile.contains("cose"), let directory = ProcessInfo.processInfo.environment["COMPANION_EVIDENCE_DIR"] {
                // Public evidence only; the ephemeral private test key is never written.
                try response.json().write(toFile: directory + "/swift-cose-response.json", atomically: true, encoding: .utf8)
            }
            XCTAssertThrowsError(try request.response(publicKey: desktop.publicKey.rawRepresentation, signature: signature))
            XCTAssertThrowsError(try ReviewedRequest.parse(bytes, peers: [], ownKey: device.publicKey.rawRepresentation.hex, usedDigests: [], now: Date(timeIntervalSince1970: 1.5)))
            XCTAssertThrowsError(try ReviewedRequest.parse(bytes, peers: [peer], ownKey: "wrong", usedDigests: [], now: Date(timeIntervalSince1970: 1.5)))
            XCTAssertThrowsError(try ReviewedRequest.parse(bytes, peers: [peer], ownKey: device.publicKey.rawRepresentation.hex, usedDigests: [request.digest.hex], now: Date(timeIntervalSince1970: 1.5)))
        }
    }
    func testTamperingAndUnsupportedYanoProfile() throws {
        let device = Curve25519.Signing.PrivateKey(), desktop = Curve25519.Signing.PrivateKey()
        let (bytes, peer) = try envelope(device: device, desktop: desktop)
        var object = try JSONSerialization.jsonObject(with: bytes) as! [String: Any]
        object["signature"] = String(repeating: "00", count: 64)
        XCTAssertThrowsError(try ReviewedRequest.parse(JSONSerialization.data(withJSONObject: object), peers: [peer], ownKey: device.publicKey.rawRepresentation.hex, usedDigests: [], now: Date(timeIntervalSince1970: 1.5)))
        let (yano, _) = try envelope(profile: "yano-sign-tx", device: device, desktop: desktop)
        XCTAssertThrowsError(try ReviewedRequest.parse(yano, peers: [peer], ownKey: device.publicKey.rawRepresentation.hex, usedDigests: [], now: Date(timeIntervalSince1970: 1.5)))
    }
    func testPairingBounds() throws {
        let key = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation.hex
        let valid = Pairing(version: 1, kind: "pair", name: "My Mac", publicKey: key)
        XCTAssertEqual(try Pairing.parse(JSONEncoder().encode(valid)), valid)
        XCTAssertThrowsError(try Pairing.parse(JSONEncoder().encode(Pairing(version: 2, kind: "pair", name: "x", publicKey: key))))
        XCTAssertThrowsError(try Pairing.parse(JSONEncoder().encode(Pairing(version: 1, kind: "pair", name: "x", publicKey: "00"))))
    }
}
