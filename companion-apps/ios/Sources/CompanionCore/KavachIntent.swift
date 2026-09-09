import Foundation

public struct KavachIntent: Sendable {
    public let cbor: Data, digest: Data
    public let accountID: String, deploymentID: String, stateVersion: UInt64
    public let coreHashes: [String], stateReference: String, inputs: [String]
    public let recipient: String, lovelace: UInt64, maxFee: UInt64
    public let notBefore: UInt64, expiresAt: UInt64

    /// Structural verification only. Ledger NFT ownership and current state must
    /// still be authenticated by the integrating wallet. No unsigned review text is used.
    public init(cbor: Data) throws {
        let root = try PData.decode(cbor).fields(0, 4)
        try require(try root[0].bytes(count: 14) == Data("KAVACH_INTENT\u{01}".utf8), "Wrong protocol domain.")
        let domain = try root[1].fields(0, 6)
        try require(try domain[0].integer() == 1, "Unsupported protocol version.")
        let deployment = try domain[1].fields(0, 3)
        try require(try deployment[0].integer() == 0 && deployment[1].integer() == 42, "Only Yaci DevKit (network magic 42) is supported.")
        deploymentID = try deployment[2].bytes(count: 32).hex
        let account = try domain[2].fields(0, 2)
        accountID = try account[0].bytes(count: 28).hex
        _ = try account[1].bytes(count: 0)
        let core = try domain[3].fields(0, 3)
        coreHashes = try core.map { try $0.bytes(count: 28).hex }
        stateVersion = try domain[4].integer()
        stateReference = try Self.reference(domain[5])
        let validity = try root[2].fields(0, 2)
        notBefore = try validity[0].integer(); expiresAt = try validity[1].integer()
        try require(expiresAt > notBefore && expiresAt - notBefore <= 300_000, "Invalid or excessively long validity window.")
        let spend = try root[3].fields(0, 3)
        let refs = try spend[0].items(min: 1, max: 8)
        inputs = try refs.map(Self.reference)
        try require(Set(inputs).count == inputs.count && !inputs.contains(stateReference), "Duplicate or conflicting inputs.")
        // Compare fixed-width hashes and numeric indices, not decimal strings.
        var previous: (String, UInt64)?
        for ref in refs {
            let f = try ref.fields(0, 2), hash = try f[0].bytes(count: 32).hex, index = try f[1].integer(max: 65535)
            if let old = previous { try require(hash > old.0 || (hash == old.0 && index > old.1), "Inputs are not canonically ordered.") }
            previous = (hash, index)
        }
        let recipients = try spend[1].items(min: 1, max: 1)
        let output = try recipients[0].fields(0, 3)
        _ = try output[0].integer(max: 15)
        let address = try output[1].fields(0, 2)
        let payment = try address[0].fields(0, 1) // key payment only
        let paymentHash = try payment[0].bytes(count: 28)
        var addressBytes = Data([0x60]) + paymentHash
        if address[1] != .constr(1, []) {
            let just = try address[1].fields(0, 1)
            let staking = try just[0].fields(0, 1)
            let stakeKey = try staking[0].fields(0, 1)
            addressBytes = Data([0x00]) + paymentHash + (try stakeKey[0].bytes(count: 28))
        }
        recipient = bech32(hrp: "addr_test", bytes: addressBytes)
        let assets = try output[2].items(min: 1, max: 1)
        let ada = try assets[0].fields(0, 3)
        _ = try ada[0].bytes(count: 0); _ = try ada[1].bytes(count: 0)
        lovelace = try ada[2].integer(); maxFee = try spend[2].integer(max: 5_000_000)
        try require(lovelace > 0, "Payment amount must be positive.")
        self.cbor = cbor; digest = Blake2b.hash(cbor)
    }
    public func validateTime(now: Date = Date()) throws {
        let ms = now.timeIntervalSince1970 * 1000
        try require(ms >= Double(notBefore) && ms < Double(expiresAt), "This request is expired or is not valid yet. Request a fresh approval.")
    }
    private static func reference(_ data: PData) throws -> String {
        let f = try data.fields(0, 2)
        return "\(try f[0].bytes(count: 32).hex)#\(try f[1].integer(max: 65535))"
    }
}

public func bech32(hrp: String, bytes: Data) -> String {
    let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l"), generators: [UInt32] = [0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3]
    var acc: UInt32 = 0, bits = 0, values = [UInt8]()
    for byte in bytes {
        acc = ((acc << 8) | UInt32(byte)) & 0xfff; bits += 8
        while bits >= 5 { bits -= 5; values.append(UInt8((acc >> bits) & 31)) }
    }
    if bits > 0 { values.append(UInt8((acc << (5-bits)) & 31)) }
    let expanded = hrp.utf8.map { $0 >> 5 } + [0] + hrp.utf8.map { $0 & 31 }
    var chk: UInt32 = 1
    for value in expanded + values + [UInt8](repeating: 0, count: 6) {
        let top = chk >> 25; chk = (chk & 0x1ffffff) << 5 ^ UInt32(value)
        for i in 0..<5 where ((top >> i) & 1) == 1 { chk ^= generators[i] }
    }
    chk ^= 1
    let checksum = (0..<6).map { UInt8((chk >> (5*(5-$0))) & 31) }
    return hrp + "1" + String((values + checksum).map { alphabet[Int($0)] })
}


/// Enrollment review reconstructed from the canonical genesis datum. The proof
/// commits to deployment, account, core, module and configuration, as in ProofDomains.genesis.
public struct KavachGenesis: Sendable {
    public struct Key: Sendable { public let id: UInt64; public let publicKey: String }
    public struct Policy: Sendable { public let role: String; public let threshold: UInt64; public let ids: [UInt64] }
    public let digest: Data, accountID: String, deploymentID: String, moduleHash: String
    public let coreHashes: [String], keys: [Key], policies: [Policy]
    public let policySummary: PolicySummary
    public let recoveryDelay: UInt64, recoveryCooldown: UInt64
    public init(cbor: Data, ownKey: String, credentialID: Int) throws {
        try require((0...15).contains(credentialID), "Invalid credential ID.")
        let f = try PData.decode(cbor).fields(0, 12)
        try require(try f[0].integer() == 1 && f[4].integer() == 0 && f[7].integer() == 0 && f[10].integer() == 0 && f[11] == .constr(0, []), "Not a supported genesis state.")
        let account = try f[1].fields(0, 2), deployment = try f[2].fields(0, 3)
        accountID = try account[0].bytes(count: 28).hex; _ = try account[1].bytes(count: 0)
        try require(try deployment[0].integer() == 0 && deployment[1].integer() == 42, "Only Yaci DevKit 42 enrollment is supported.")
        deploymentID = try deployment[2].bytes(count: 32).hex
        coreHashes = try f[3].fields(0, 3).map { try $0.bytes(count: 28).hex }
        let module = try f[5].fields(0, 2)
        moduleHash = try module[0].bytes(count: 28).hex
        try require(try module[1].integer() == 1 && moduleHash != coreHashes[2], "Invalid module binding.")
        recoveryDelay = try f[8].integer(max: 7_776_000_000)
        recoveryCooldown = try f[9].integer(max: 2_592_000_000)
        try require(recoveryDelay >= 86_400_000 && recoveryCooldown >= 3_600_000, "Invalid recovery timing.")
        try require(f[6].encoded().count <= 1024, "Configuration exceeds the supported size.")
        guard case let .constr(0, configuration) = f[6], configuration.count == 8 || configuration.count == 5 else { throw CompanionError("Unsupported genesis signing configuration.") }
        policySummary = try PolicySummary(data: f[6])
        keys = policySummary.keys; policies = policySummary.policies
        try require(keys.contains { $0.id == UInt64(credentialID) && $0.publicKey == ownKey }, "This phone is not the requested enrolled credential.")
        if let coseIDs = policySummary.coseIDs {
            try require(coseIDs.contains(UInt64(credentialID)), "This phone key must be configured for COSE, not transaction signing.")
        }
        digest = Blake2b.hash(PData.constr(0, [.bytes(Data("KAVACH_GENESIS_POSSESSION_V1".utf8)), f[2], f[1], f[3], f[5], .bytes(Blake2b.hash(f[6].encoded()))]).encoded())
    }
}
