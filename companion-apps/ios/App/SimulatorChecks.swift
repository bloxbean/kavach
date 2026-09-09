#if DEBUG && targetEnvironment(simulator)
import Foundation
import CryptoKit

/// Explicit simulator-only integration check. Never compiled into an iPhone build.
/// Uses a synthetic fixture, does not enroll keys or submit a transaction.
enum SimulatorChecks {
    static func run() throws -> String {
        let publicKey = try KeyVault.publicKey() ?? KeyVault.create()
        let url = Bundle.main.url(forResource: "sample-intent", withExtension: "json")!
        struct Sample: Decodable { let cbor: String }
        let sample = try JSONDecoder().decode(Sample.self, from: Data(contentsOf: url))
        var fields = try PData.decode(Data(hex: sample.cbor)).fields(0, 4)
        let now = UInt64(Date().timeIntervalSince1970*1000)
        fields[2] = .constr(0, [.uint(now-1000), .uint(now+299000)])
        let desktop = Curve25519.Signing.PrivateKey()
        let body = RequestBody(id: UUID(), profile: "kavach-cose-spend-v1", intentCBOR: PData.constr(0, fields).encoded().hex, signerPublicKey: publicKey, credentialID: 0)
        let bytes = try JSONEncoder().encode(body)
        let peer = Pairing(name: "Simulator test desktop", publicKey: desktop.publicKey.rawRepresentation.hex)
        let envelope = RequestEnvelope(senderPublicKey: peer.publicKey, body: bytes.base64EncodedString(), signature: try desktop.signature(for: ReviewedRequest.domain+bytes).hex)
        let request = try ReviewedRequest.parse(JSONEncoder().encode(envelope), peers: [peer], ownKey: publicKey, usedDigests: [])
        let key = try Data(hex: publicKey), signature = try KeyVault.sign(request.signingBytes(publicKey: key), expectedKey: publicKey)
        let response = try request.response(publicKey: key, signature: signature)
        try request.verify(response)
        let report = "{\"result\":\"passed\",\"checks\":[\"Keychain identity\",\"authenticated request\",\"intent review\",\"Ed25519 signing\",\"COSE response verification\"],\"publicKey\":\"\(publicKey)\"}"
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try report.write(to: folder.appendingPathComponent("simulator-check.json"), atomically: true, encoding: .utf8)
        try response.json().write(to: folder.appendingPathComponent("simulator-response.json"), atomically: true, encoding: .utf8)
        return publicKey
    }
}
#endif
