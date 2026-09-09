import Foundation

/// Canonical policy review for admin approval and destination possession. No desktop-supplied
/// description or digest is trusted. Live state/NFT authentication remains an integration boundary.
public struct KavachPolicyChange: Sendable {
    public let digest: Data
    public let accountID: String, stateReference: String, purpose: String, deploymentID: String
    public let coreHashes: [String], stateVersion: UInt64
    public let oldModule: String, newModule: String
    public let previous: PolicySummary, target: PolicySummary
    public let notBefore: UInt64, expiresAt: UInt64

    public init(intentCBOR: Data, stateCBOR: Data, purpose: String, ownKey: String, credentialID: Int) throws {
        try require((0...15).contains(credentialID), "Invalid credential ID.")
        try require(intentCBOR.count <= 1536 && stateCBOR.count <= 4096, "Policy request exceeds protocol bounds.")
        let root = try PData.decode(intentCBOR).fields(0, 4)
        try require(try root[0].bytes(count: 14) == Data("KAVACH_INTENT\u{01}".utf8), "Wrong intent domain.")
        let domain = try root[1].fields(0, 6), state = try PData.decode(stateCBOR).fields(0, 12)
        try require(try domain[0].integer() == 1 && state[0].integer() == 1, "Unsupported account version.")
        try require(domain[1] == state[2] && domain[2] == state[1] && domain[3] == state[3] && domain[4] == state[4], "Intent does not match the supplied account state.")
        try require(state[11] == .constr(0, []), "Policy changes require a normal account.")
        let deployment = try domain[1].fields(0, 3)
        try require(try deployment[0].integer() == 0 && deployment[1].integer() == 42, "Only DevKit 42 policy changes are supported.")
        deploymentID = try deployment[2].bytes(count: 32).hex
        let account = try domain[2].fields(0, 2)
        accountID = try account[0].bytes(count: 28).hex; _ = try account[1].bytes(count: 0)
        let core = try domain[3].fields(0, 3)
        coreHashes = try core.map { try $0.bytes(count: 28).hex }
        stateVersion = try domain[4].integer()
        let ref = try domain[5].fields(0, 2)
        stateReference = "\(try ref[0].bytes(count: 32).hex)#\(try ref[1].integer(max: 65535))"
        let validity = try root[2].fields(0, 2)
        notBefore = try validity[0].integer(); expiresAt = try validity[1].integer()
        try require(expiresAt > notBefore && expiresAt - notBefore <= 300_000, "Invalid policy request validity.")
        let oldReference = try state[5].fields(0, 2)
        oldModule = try oldReference[0].bytes(count: 28).hex
        try require(try oldReference[1].integer() == 1, "Unsupported old module ABI.")
        let destination: PData, configuration: PData
        guard case let .constr(tag, fields) = root[3] else { throw CompanionError("Invalid policy action.") }
        if tag == 1 {
            try require(fields.count == 1 && (purpose == "operation" || purpose == "possession"), "Invalid configuration proof purpose.")
            destination = state[5]; configuration = fields[0]
        } else if tag == 2 {
            try require(fields.count == 2 && (purpose == "operation" || purpose == "candidate"), "Invalid module proof purpose.")
            destination = fields[0]; configuration = fields[1]
            try require(destination != state[5], "Module replacement must change the module.")
        } else { throw CompanionError("This phone review supports configuration and module replacement only.") }
        let nextReference = try destination.fields(0, 2)
        newModule = try nextReference[0].bytes(count: 28).hex
        try require(try nextReference[1].integer() == 1 && nextReference[0] != core[2], "Invalid destination module.")
        previous = try PolicySummary(data: state[6]); target = try PolicySummary(data: configuration)
        let selected = purpose == "operation" ? previous : target
        try require(selected.keys.contains { $0.id == UInt64(credentialID) && $0.publicKey == ownKey }, "Phone key is not the requested policy credential.")
        if let cose = selected.coseIDs { try require(cose.contains(UInt64(credentialID)), "This credential is configured for a transaction witness, not phone COSE.") }
        if purpose == "operation" { try require(previous.policies[1].ids.contains(UInt64(credentialID)), "This phone is not an admin authority.") }
        self.purpose = purpose
        let intentDigest = Blake2b.hash(intentCBOR)
        digest = purpose == "operation" ? intentDigest : Blake2b.hash(PData.constr(0, [
            .bytes(Data("KAVACH_CONFIG_POSSESSION_V1".utf8)), .bytes(intentDigest), destination,
            .bytes(Blake2b.hash(configuration.encoded()))]).encoded())
    }
    public func validateTime(now: Date = Date()) throws {
        let ms = now.timeIntervalSince1970 * 1000
        try require(ms >= Double(notBefore) && ms < Double(expiresAt), "Policy request is expired or not valid yet.")
    }
}

/// Validated role/method/amount/budget details reconstructed from signed configuration data.
public struct PolicySummary: Sendable {
    public let keys: [KavachGenesis.Key], policies: [KavachGenesis.Policy]
    public let coseIDs: Set<UInt64>?
    public let details: [String]
    public init(data: PData) throws {
        try require(data.encoded().count <= 1024, "Policy configuration is too large.")
        var authorization = data, information: [String] = []
        if case let .constr(0, outer) = data, outer.count == 3 {
            try require(try outer[0].integer() == 1, "Unsupported periodic configuration.")
            authorization = outer[2]
            if outer[1] == .constr(1, []) { information.append("Periodic budget: disabled; no counter") }
            else {
                let option = try outer[1].fields(0, 1), budget = try option[0].fields(0, 3)
                let counter = try budget[0].fields(0, 2), period = try budget[1].integer(max: 2), limit = try budget[2].integer()
                let counterID = try counter[0].bytes(count: 28).hex; _ = try counter[1].bytes(count: 0)
                try require(period == 1 || period == 2, "Unsupported budget period.")
                information.append("Periodic budget: \(limit == 0 ? "disabled (counter retained)" : "\(limit) lovelace") · \(period == 1 ? "daily at midnight UTC" : "weekly Monday midnight UTC")")
                information.append("Counter NFT: \(counterID)")
            }
        }
        var roles = authorization
        var methods: Set<UInt64>? = nil
        var small: PData? = nil
        if case let .constr(0, mixed) = authorization, mixed.count == 5 {
            try require(try mixed[0].integer() == 1, "Unsupported mixed policy schema.")
            roles = mixed[1]
            let ids = try mixed[2].items(min: 0, max: 16).map { try $0.integer(max: 15) }
            try require(ids == ids.sorted() && Set(ids).count == ids.count, "Duplicate or unordered signing methods.")
            methods = Set(ids); small = mixed[4]
            information.append("Small-payment threshold: \(try mixed[3].integer()) lovelace, including signed maximum account fee")
        } else {
            try require(authorization == data, "Periodic profile requires a mixed authorization policy.")
        }
        let f = try roles.fields(0, 8)
        try require(try f[0].integer() == 1, "Unsupported authority schema.")
        keys = try f[1].items(min: 3, max: 16).map {
            let k = try $0.fields(0, 2)
            return KavachGenesis.Key(id: try k[0].integer(max: 15), publicKey: try k[1].bytes(count: 32).hex)
        }
        let ids = keys.map(\.id), registered = Set(ids)
        try require(ids == ids.sorted() && registered.count == keys.count && Set(keys.map(\.publicKey)).count == keys.count, "Duplicate or unordered authorities.")
        if let methods { try require(methods.isSubset(of: registered), "Unknown COSE credential.") }
        let names = ["Strong spend", "Admin", "Freeze", "Unfreeze", "Recovery", "Cancel recovery"]
        policies = try names.enumerated().map { index, name in try Self.policy(f[index + 2], role: name, registered: registered) }
        let sets = policies.map { Set($0.ids) }
        try require(sets[4].isDisjoint(with: sets[3]) && sets[4].isDisjoint(with: sets[5]) && sets[0].isDisjoint(with: sets[3]) && sets[0].isDisjoint(with: sets[5]) && sets[0].intersection(sets[1]).count < policies[1].threshold, "Authority separation is not preserved.")
        if let small {
            let low = try Self.policy(small, role: "Small spend", registered: registered)
            try require(Set(low.ids).isSubset(of: sets[0]), "Small approval must be a subset of strong spend.")
            let extra = UInt64(sets[0].count - low.ids.count)
            try require(policies[0].threshold >= extra && policies[0].threshold - extra >= low.threshold, "Strong policy does not imply small approval.")
            information.append("Small spend: \(low.threshold) of keys \(low.ids.map(String.init).joined(separator: ", "))")
        }
        coseIDs = methods; details = information
    }
    private static func policy(_ data: PData, role: String, registered: Set<UInt64>) throws -> KavachGenesis.Policy {
        let f = try data.fields(0, 2), members = try f[1].items(min: 1, max: 8).map { try $0.integer(max: 15) }, threshold = try f[0].integer(max: 8)
        try require(threshold > 0 && threshold <= members.count && members == members.sorted() && Set(members).count == members.count && Set(members).isSubset(of: registered), "Invalid authority threshold.")
        return KavachGenesis.Policy(role: role, threshold: threshold, ids: members)
    }
}
