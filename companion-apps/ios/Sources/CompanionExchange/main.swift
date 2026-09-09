import Foundation
import CryptoKit
import CompanionCore

func encode<T: Encodable>(_ value: T) throws -> Data { let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; return try encoder.encode(value) }
func read(_ path: String, max: Int = 16384) throws -> Data {
    let url = URL(fileURLWithPath: path)
    guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= max else { throw CompanionError("File too large.") }
    return try Data(contentsOf: url)
}
func output(_ value: Data) { print(String(decoding: value, as: UTF8.self)) }
func desktopKey(_ directory: String, create: Bool = false) throws -> Curve25519.Signing.PrivateKey {
    let folder = URL(fileURLWithPath: directory), file = folder.appendingPathComponent("desktop-key.bin")
    if FileManager.default.fileExists(atPath: file.path) {
        let attrs = try FileManager.default.attributesOfItem(atPath: file.path)
        guard let permissions = attrs[.posixPermissions] as? NSNumber, permissions.intValue & 0o077 == 0 else { throw CompanionError("Desktop key permissions must be 0600.") }
        return try Curve25519.Signing.PrivateKey(rawRepresentation: read(file.path, max: 32))
    }
    guard create else { throw CompanionError("Run pair first to create a desktop identity.") }
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    let key = Curve25519.Signing.PrivateKey()
    guard FileManager.default.createFile(atPath: file.path, contents: key.rawRepresentation, attributes: [.posixPermissions: 0o600]) else { throw CompanionError("Could not write desktop identity.") }
    return key
}
func request(directory: String, phone: String, cbor: Data, profile: String) throws {
    let key = try desktopKey(directory)
    _ = try Curve25519.Signing.PublicKey(rawRepresentation: Data(hex: phone))
    let intent = try KavachIntent(cbor: cbor); try intent.validateTime()
    let body = RequestBody(id: UUID(), profile: profile, intentCBOR: cbor.hex, signerPublicKey: phone, credentialID: 0)
    let bytes = try encode(body)
    output(try encode(RequestEnvelope(senderPublicKey: key.publicKey.rawRepresentation.hex, body: bytes.base64EncodedString(),
        signature: key.signature(for: ReviewedRequest.domain + bytes).hex)))
}
let args = Array(CommandLine.arguments.dropFirst())
do {
    guard let command = args.first else { throw CompanionError("Usage: companion-exchange pair DIR | request DIR PHONE_KEY INTENT_HEX_FILE [raw|cose] | sample DIR PHONE_KEY FIXTURE_JSON | verify REQUEST_JSON APPROVAL_JSON") }
    switch command {
    case "qr":
        guard args.count == 2 else { throw CompanionError("qr REQUEST_JSON") }
        print(try QRTransport.encode(read(args[1], max: 8192)))
    case "pair":
        guard args.count == 2 else { throw CompanionError("pair DIR") }
        let key = try desktopKey(args[1], create: true)
        let pair = Pairing(name: "Kavach on Mac", publicKey: key.publicKey.rawRepresentation.hex)
        FileHandle.standardError.write(Data("Compare desktop fingerprint: \(pair.fingerprint)\n".utf8)); output(try encode(pair))
    case "request":
        guard (4...5).contains(args.count) else { throw CompanionError("request DIR PHONE_KEY INTENT_HEX_FILE [raw|cose]") }
        let mode = args.count == 5 ? args[4] : "cose"
        guard ["raw", "cose"].contains(mode) else { throw CompanionError("Profile must be raw or cose.") }
        let hex = String(decoding: try read(args[3]), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        try request(directory: args[1], phone: args[2], cbor: Data(hex: hex), profile: "kavach-\(mode)-spend-v1")
    case "sample":
        guard args.count == 4 else { throw CompanionError("sample DIR PHONE_KEY FIXTURE_JSON") }
        struct Sample: Decodable { let cbor: String }
        let fixture = try JSONDecoder().decode(Sample.self, from: read(args[3]))
        var fields = try PData.decode(Data(hex: fixture.cbor)).fields(0, 4)
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        fields[2] = .constr(0, [.uint(now-1000), .uint(now+299000)])
        FileHandle.standardError.write(Data("Synthetic fixture: this account/input does not exist on your ledger. Valid for under five minutes.\n".utf8))
        try request(directory: args[1], phone: args[2], cbor: PData.constr(0, fields).encoded(), profile: "kavach-cose-spend-v1")
    case "verify":
        guard args.count == 3 else { throw CompanionError("verify REQUEST_JSON APPROVAL_JSON") }
        let raw = try read(args[1], max: 8192), envelope = try JSONDecoder().decode(RequestEnvelope.self, from: raw)
        guard let bytes = Data(base64Encoded: envelope.body) else { throw CompanionError("Invalid request.") }
        let body = try JSONDecoder().decode(RequestBody.self, from: bytes)
        // The caller supplies the original, locally trusted request file.
        let peer = Pairing(name: "Original desktop", publicKey: envelope.senderPublicKey)
        let request = try ReviewedRequest.parse(raw, peers: [peer], ownKey: body.signerPublicKey, usedDigests: [])
        try request.verify(JSONDecoder().decode(ApprovalResponse.self, from: read(args[2], max: 4096)))
        print("Valid approval for \(request.digest.hex). No transaction was submitted.")
    default: throw CompanionError("Unknown command.")
    }
} catch { FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8)); exit(1) }
