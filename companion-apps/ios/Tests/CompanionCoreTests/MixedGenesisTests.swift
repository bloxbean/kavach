import XCTest
@testable import CompanionCore

final class MixedGenesisTests: XCTestCase {
    func testJavaMixedGenesisDomainsAndPerKeyMethods() throws {
        let url = Bundle.module.url(forResource: "mixed-genesis", withExtension: "json", subdirectory: "Fixtures")!
        let samples = try JSONDecoder().decode([PolicyTests.Evidence].self, from: Data(contentsOf: url))
        XCTAssertGreaterThanOrEqual(samples.count, 2)
        for sample in samples {
            let body = try JSONDecoder().decode(RequestBody.self, from: Data(base64Encoded: sample.request.body)!)
            let peer = Pairing(name: "Mixed creation fixture", publicKey: sample.request.senderPublicKey)
            let reviewed = try ReviewedRequest.parse(QRTransport.decode(sample.qr), peers: [peer], ownKey: body.signerPublicKey, usedDigests: [], now: Date(timeIntervalSince1970: Double(body.expiresAt! - 1000) / 1000))
            XCTAssertEqual(reviewed.digest.hex, sample.digest)
            XCTAssertNotNil(reviewed.genesis?.policySummary.coseIDs)
            var state = try PData.decode(Data(hex: body.stateCBOR!)).fields(0, 12)
            var config = try state[6].fields(0, 5)
            let methods = try config[2].items(min: 0, max: 16).filter { try $0.integer() != UInt64(body.credentialID) }
            config[2] = .list(methods); state[6] = .constr(0, config)
            XCTAssertThrowsError(try KavachGenesis(cbor: PData.constr(0, state).encoded(), ownKey: body.signerPublicKey, credentialID: body.credentialID))
        }
    }
}
