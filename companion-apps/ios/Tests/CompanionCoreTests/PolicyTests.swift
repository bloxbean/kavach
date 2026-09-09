import XCTest
import CryptoKit
@testable import CompanionCore

final class PolicyTests: XCTestCase {
    struct Evidence: Decodable { let request: RequestEnvelope; let qr: String; let digest: String }
    func evidence() throws -> [Evidence] {
        let url = Bundle.module.url(forResource: "policy-requests", withExtension: "json", subdirectory: "Fixtures")!
        return try JSONDecoder().decode([Evidence].self, from: Data(contentsOf: url))
    }
    func testJavaPolicyProofDomainsAndCompressedRequests() throws {
        var purposes = Set<String>()
        for sample in try evidence() {
            let raw = try QRTransport.decode(sample.qr)
            let body = try JSONDecoder().decode(RequestBody.self, from: Data(base64Encoded: sample.request.body)!)
            let fields = try PData.decode(Data(hex: body.intentCBOR)).fields(0, 4)
            let start = try fields[2].fields(0, 2)[0].integer()
            let now = Date(timeIntervalSince1970: Double(start + 1) / 1000)
            let peer = Pairing(name: "Java DevKit fixture", publicKey: sample.request.senderPublicKey)
            let reviewed = try ReviewedRequest.parse(raw, peers: [peer], ownKey: body.signerPublicKey, usedDigests: [], now: now)
            XCTAssertEqual(reviewed.digest.hex, sample.digest)
            XCTAssertNotNil(reviewed.policyChange); XCTAssertNil(reviewed.spend); XCTAssertNil(reviewed.genesis)
            purposes.insert(body.proofPurpose!)
            XCTAssertThrowsError(try ReviewedRequest.parse(raw, peers: [], ownKey: body.signerPublicKey, usedDigests: [], now: now))
            XCTAssertThrowsError(try ReviewedRequest.parse(raw, peers: [peer], ownKey: body.signerPublicKey, usedDigests: [sample.digest], now: now))
            XCTAssertThrowsError(try ReviewedRequest.parse(raw, peers: [peer], ownKey: body.signerPublicKey, usedDigests: [], now: Date(timeIntervalSince1970: Double(reviewed.expiresAt) / 1000)))
        }
        XCTAssertEqual(purposes, ["operation", "candidate", "possession"])
    }
    func testPolicyReviewRejectsWrongPurposeStateMethodAndBudget() throws {
        let sample = try evidence().first { sample in
            let body = try JSONDecoder().decode(RequestBody.self, from: Data(base64Encoded: sample.request.body)!)
            return body.proofPurpose == "candidate"
        }!
        let body = try JSONDecoder().decode(RequestBody.self, from: Data(base64Encoded: sample.request.body)!)
        let intent = try Data(hex: body.intentCBOR), state = try Data(hex: body.stateCBOR!)
        func parse(_ intent: Data, _ state: Data, _ purpose: String = "candidate") throws -> KavachPolicyChange {
            try KavachPolicyChange(intentCBOR: intent, stateCBOR: state, purpose: purpose, ownKey: body.signerPublicKey, credentialID: body.credentialID)
        }
        XCTAssertThrowsError(try parse(intent, state, "possession"))
        var stateFields = try PData.decode(state).fields(0, 12)
        stateFields[4] = .uint(999)
        XCTAssertThrowsError(try parse(intent, PData.constr(0, stateFields).encoded()))
        var root = try PData.decode(intent).fields(0, 4)
        root[3] = .constr(3, []) // Never label freeze as policy replacement.
        XCTAssertThrowsError(try parse(PData.constr(0, root).encoded(), state))
        root = try PData.decode(intent).fields(0, 4)
        var action = try root[3].fields(2, 2), wrapper = try action[1].fields(0, 3)
        var mixed = try wrapper[2].fields(0, 5)
        mixed[2] = .list([]) // Phone credential changed to transaction witness.
        wrapper[2] = .constr(0, mixed); action[1] = .constr(0, wrapper); root[3] = .constr(2, action)
        XCTAssertThrowsError(try parse(PData.constr(0, root).encoded(), state))
        var budget = try wrapper[1].fields(0, 1)[0].fields(0, 3)
        budget[1] = .uint(3); wrapper[1] = .constr(0, [.constr(0, budget)])
        XCTAssertThrowsError(try PolicySummary(data: .constr(0, wrapper)))
    }
    func testPhoneSignsPolicyApprovalWithFreshDeviceKey() throws {
        let sample = try evidence().first!
        let original = try JSONDecoder().decode(RequestBody.self, from: Data(base64Encoded: sample.request.body)!)
        let device = Curve25519.Signing.PrivateKey(), desktop = Curve25519.Signing.PrivateKey()
        let publicKey = device.publicKey.rawRepresentation
        // Recursively substitute only this credential's public bytes in state and intent.
        func replace(_ data: PData) throws -> PData {
            switch data {
            case .bytes(let b): return b.hex == original.signerPublicKey ? .bytes(publicKey) : data
            case .constr(let tag, let fields): return .constr(tag, try fields.map(replace))
            case .list(let fields): return .list(try fields.map(replace))
            default: return data
            }
        }
        let intent = try replace(PData.decode(Data(hex: original.intentCBOR))).encoded()
        let state = try replace(PData.decode(Data(hex: original.stateCBOR!))).encoded()
        let body = RequestBody(id: UUID(), profile: original.profile, intentCBOR: intent.hex, signerPublicKey: publicKey.hex, credentialID: original.credentialID, stateCBOR: state.hex, expiresAt: original.expiresAt, proofPurpose: original.proofPurpose)
        let raw = try JSONEncoder().encode(body), peer = Pairing(name: "Test", publicKey: desktop.publicKey.rawRepresentation.hex)
        let envelope = RequestEnvelope(senderPublicKey: peer.publicKey, body: raw.base64EncodedString(), signature: try desktop.signature(for: ReviewedRequest.domain + raw).hex)
        let start = try PData.decode(intent).fields(0, 4)[2].fields(0, 2)[0].integer()
        let reviewed = try ReviewedRequest.parse(JSONEncoder().encode(envelope), peers: [peer], ownKey: publicKey.hex, usedDigests: [], now: Date(timeIntervalSince1970: Double(start + 1) / 1000))
        let response = try reviewed.response(publicKey: publicKey, signature: device.signature(for: reviewed.signingBytes(publicKey: publicKey)))
        XCTAssertNoThrow(try reviewed.verify(response))
    }
}
