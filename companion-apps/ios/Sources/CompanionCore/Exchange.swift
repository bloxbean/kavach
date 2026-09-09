import Foundation
import CryptoKit

public struct Pairing: Codable, Identifiable, Equatable, Sendable {
    public let version: Int
    public let kind: String
    public let name: String
    public let publicKey: String
    public init(version: Int = 1, kind: String = "pair", name: String, publicKey: String) {
        self.version = version; self.kind = kind; self.name = name; self.publicKey = publicKey
    }
    public var id: String { publicKey }
    public var fingerprint: String { (try? Data(hex: publicKey)).map { Blake2b.hash($0).hex } ?? "Invalid key" }
    public static func parse(_ data: Data) throws -> Pairing {
        try require(data.count <= 1024, "Pairing code is too large.")
        let pair = try JSONDecoder().decode(Pairing.self, from: data)
        try require(pair.version == 1 && pair.kind == "pair", "Unsupported pairing code.")
        try require((1...40).contains(pair.name.count) && pair.name.unicodeScalars.allSatisfy({ (32...126).contains($0.value) }), "Device names must use printable ASCII characters.")
        _ = try Curve25519.Signing.PublicKey(rawRepresentation: Data(hex: pair.publicKey))
        return pair
    }
}
public struct RequestBody: Codable, Sendable {
    public let id: UUID
    public let profile: String
    public let intentCBOR: String
    public let signerPublicKey: String
    public let credentialID: Int
    public let stateCBOR: String?
    public let expiresAt: UInt64?
    public let proofPurpose: String?
    public init(id: UUID, profile: String, intentCBOR: String, signerPublicKey: String, credentialID: Int, stateCBOR: String? = nil, expiresAt: UInt64? = nil, proofPurpose: String? = nil) {
        self.id = id; self.profile = profile; self.intentCBOR = intentCBOR; self.signerPublicKey = signerPublicKey; self.credentialID = credentialID
        self.stateCBOR = stateCBOR; self.expiresAt = expiresAt; self.proofPurpose = proofPurpose
    }
}
public struct RequestEnvelope: Codable, Sendable {
    public let version: Int
    public let kind: String
    public let senderPublicKey: String
    public let body: String
    public let signature: String
    public init(version: Int = 1, kind: String = "request", senderPublicKey: String, body: String, signature: String) {
        self.version = version; self.kind = kind; self.senderPublicKey = senderPublicKey; self.body = body; self.signature = signature
    }
}
public struct ReviewedRequest: Sendable, Identifiable {
    public let body: RequestBody
    public let spend: KavachIntent?
    public let policyChange: KavachPolicyChange?
    public let genesis: KavachGenesis?
    public var digest: Data { spend?.digest ?? policyChange?.digest ?? genesis!.digest }
    public var expiresAt: UInt64 { min(spend?.expiresAt ?? policyChange?.expiresAt ?? UInt64.max, body.expiresAt ?? UInt64.max) }
    public func validateTime(now: Date = Date()) throws {
        if let spend { try spend.validateTime(now: now) }
        if let policyChange { try policyChange.validateTime(now: now) }
        let ms = now.timeIntervalSince1970 * 1000
        try require(ms < Double(expiresAt), "This request has expired. Export a fresh request.")
    }
    public let sender: Pairing
    public init(body: RequestBody, intent: KavachIntent, sender: Pairing) { self.body = body; self.spend = intent; self.genesis = nil; self.policyChange = nil; self.sender = sender }
    private init(body: RequestBody, genesis: KavachGenesis, sender: Pairing) { self.body = body; self.spend = nil; self.genesis = genesis; self.policyChange = nil; self.sender = sender }
    private init(body: RequestBody, policyChange: KavachPolicyChange, sender: Pairing) { self.body = body; self.spend = nil; self.genesis = nil; self.policyChange = policyChange; self.sender = sender }
    public var id: UUID { body.id }
    public static let domain = Data("YANO_COMPANION_REQUEST_V1\0".utf8)
    public static func parse(_ data: Data, peers: [Pairing], ownKey: String, usedDigests: Set<String>, now: Date = Date()) throws -> ReviewedRequest {
        try require(data.count <= 8192, "Request exceeds 8 KB.")
        let envelope = try JSONDecoder().decode(RequestEnvelope.self, from: data)
        try require(envelope.version == 1 && envelope.kind == "request", "Unsupported request version.")
        guard let peer = peers.first(where: { $0.publicKey == envelope.senderPublicKey }) else { throw CompanionError("Pair with this desktop before importing its request.") }
        guard let raw = Data(base64Encoded: envelope.body), raw.count <= 4096 else { throw CompanionError("Invalid request body.") }
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: Data(hex: peer.publicKey))
        try require(key.isValidSignature(try Data(hex: envelope.signature), for: domain + raw), "Desktop signature does not match. Request rejected.")
        let body = try JSONDecoder().decode(RequestBody.self, from: raw)
        try require(body.profile == "kavach-cose-spend-v1" || body.profile == "kavach-raw-spend-v1" || body.profile == "kavach-cose-genesis-v1" || body.profile == "kavach-cose-policy-v1", "This signing profile is not supported. Ordinary Yano transactions are not enabled.")
        try require(body.signerPublicKey == ownKey && (0...15).contains(body.credentialID), "Request is not addressed to this device key.")
        let reviewed: ReviewedRequest
        if body.profile == "kavach-cose-policy-v1" {
            guard let state = body.stateCBOR, let purpose = body.proofPurpose else { throw CompanionError("Missing policy state or proof purpose.") }
            reviewed = ReviewedRequest(body: body, policyChange: try KavachPolicyChange(intentCBOR: Data(hex: body.intentCBOR), stateCBOR: Data(hex: state), purpose: purpose, ownKey: ownKey, credentialID: body.credentialID), sender: peer)
        } else if body.profile == "kavach-cose-genesis-v1" {
            guard let state = body.stateCBOR, let expiry = body.expiresAt else { throw CompanionError("Missing genesis state or transport expiry.") }
            try require(body.intentCBOR.isEmpty && Double(expiry) <= now.timeIntervalSince1970 * 1000 + 300_000, "Invalid genesis request validity.")
            reviewed = ReviewedRequest(body: body, genesis: try KavachGenesis(cbor: Data(hex: state), ownKey: ownKey, credentialID: body.credentialID), sender: peer)
        } else {
            try require(body.stateCBOR == nil, "Unexpected enrollment data in a payment request.")
            reviewed = ReviewedRequest(body: body, intent: try KavachIntent(cbor: Data(hex: body.intentCBOR)), sender: peer)
        }
        try reviewed.validateTime(now: now)
        try require(!usedDigests.contains(reviewed.digest.hex), "This proof was already approved. Return its saved approval from Activity.")
        return reviewed
    }

    public func signingBytes(publicKey: Data) -> Data {
        if body.profile == "kavach-raw-spend-v1" { return digest }
        let protected = protectedHeader(publicKey: publicKey)
        return Data([0x84]) + cborText("Signature1") + cborBytes(protected) + cborBytes(Data()) + cborBytes(digest)
    }
    public func protectedHeader(publicKey: Data) -> Data {
        let address = Data([0x60]) + Blake2b.hash(publicKey, length: 28)
        return Data([0xa2, 0x01, 0x27]) + cborText("address") + cborBytes(address)
    }
    public func response(publicKey: Data, signature: Data) throws -> ApprovalResponse {
        try require(publicKey.hex == body.signerPublicKey, "Response key does not match the requested credential.")
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: publicKey)
        try require(key.isValidSignature(signature, for: signingBytes(publicKey: publicKey)), "Local signature verification failed.")
        let isCOSE = body.profile != "kavach-raw-spend-v1"
        let sign1 = Data([0x84]) + cborBytes(protectedHeader(publicKey: publicKey)) + Data([0xa0]) + cborBytes(digest) + cborBytes(signature)
        // COSE_Key: {1: 1, 3: -8, -1: 6, -2: publicKey}.
        let coseKey = Data([0xa4, 0x01, 0x01, 0x03, 0x27, 0x20, 0x06, 0x21]) + cborBytes(publicKey)
        return ApprovalResponse(version: 1, kind: "approval", requestID: id, profile: body.profile,
            credentialID: body.credentialID, publicKey: publicKey.hex, digest: digest.hex,
            signature: isCOSE ? sign1.hex : signature.hex, key: isCOSE ? coseKey.hex : nil)
    }
    public func verify(_ response: ApprovalResponse) throws {
        try require(response.version == 1 && response.kind == "approval" && response.requestID == id &&
            response.profile == body.profile && response.credentialID == body.credentialID &&
            response.publicKey == body.signerPublicKey && response.digest == digest.hex, "Response does not match this request.")
        let bytes = try Data(hex: response.signature)
        let signature: Data
        if body.profile != "kavach-raw-spend-v1" {
            try require(bytes.count >= 66 && bytes.suffix(66).prefix(2) == Data([0x58,0x40]), "Invalid COSE response.")
            signature = Data(bytes.suffix(64))
        } else { signature = bytes }
        let expected = try self.response(publicKey: Data(hex: response.publicKey), signature: signature)
        try require(expected.signature == response.signature && expected.key == response.key, "Response does not use the expected signature encoding.")
    }
}
public struct ApprovalResponse: Codable, Sendable {
    public let version: Int, kind: String, requestID: UUID, profile: String, credentialID: Int
    public let publicKey: String, digest: String, signature: String, key: String?
    public func json() throws -> String {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        return String(decoding: try encoder.encode(self), as: UTF8.self)
    }
}
