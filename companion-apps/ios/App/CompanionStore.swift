import SwiftUI
import CryptoKit

struct Receipt: Codable, Identifiable {
    var approvalKind: String? = nil
    let id: UUID, date: Date, digest: String, peer: String, amount: UInt64, expiresAt: UInt64, response: String
}
struct LocalState: Codable { var peers: [Pairing] = []; var receipts: [Receipt] = [] }

@MainActor final class CompanionStore: ObservableObject {
    @Published var publicKey: String?
    @Published var state = LocalState()
    @Published var request: ReviewedRequest?
    @Published var pairing: Pairing?
    @Published var response: String?
    @Published var error: String?
    @Published var busy = false
    @Published var tab = 0
    private var storageReady = false
    init() {
        publicKey = KeyVault.publicKey()
        do {
            if let data = try KeyVault.read("state.v1") { state = try JSONDecoder().decode(LocalState.self, from: data) }
            storageReady = true
        } catch { self.error = "Local records could not be loaded. Signing is blocked until storage is available." }
    }
    func createKey() {
        do { publicKey = try KeyVault.create() } catch { self.error = error.localizedDescription }
    }
    func persist(_ next: LocalState) throws {
        guard storageReady else { throw CompanionError("Protected storage is unavailable.") }
        try KeyVault.write(JSONEncoder().encode(next), account: "state.v1"); state = next
    }
    func ingest(_ text: String) {
        guard !busy, request == nil, pairing == nil else { error = "Finish the current review before importing another request."; return }
        do {
            guard let publicKey else { throw CompanionError("Create a device key first.") }
            let data = try QRTransport.decode(text)
            guard data.count <= 8192 else { throw CompanionError("Request exceeds 8 KB.") }
            let kind = try JSONDecoder().decode(Header.self, from: data)
            if kind.kind == "pair" {
                let candidate = try Pairing.parse(data)
                guard !state.peers.contains(where: { $0.id == candidate.id }) else { throw CompanionError("This desktop is already paired.") }
                guard state.peers.count < 8 else { throw CompanionError("Unpair an unused desktop before adding another.") }
                pairing = candidate
            } else {
                guard storageReady else { throw CompanionError("Protected storage is unavailable.") }
                request = try ReviewedRequest.parse(data, peers: state.peers, ownKey: publicKey,
                    usedDigests: Set(state.receipts.map(\.digest)))
            }
        } catch { self.error = error.localizedDescription }
    }
    private struct Header: Decodable { let kind: String }
    func confirmPairing() {
        guard let pairing else { return }
        do { var next = state; next.peers.append(pairing); try persist(next); self.pairing = nil; tab = 1 }
        catch { self.error = error.localizedDescription }
    }
    func unpair(_ peer: Pairing) {
        do { var next = state; next.peers.removeAll { $0.id == peer.id }; try persist(next) }
        catch { self.error = error.localizedDescription }
    }
    func approve() {
        guard let request, let publicKey, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try request.validateTime()
                guard state.peers.contains(request.sender), !state.receipts.contains(where: { $0.digest == request.digest.hex }) else {
                    throw CompanionError("Request is no longer eligible for approval.")
                }
                let key = try Data(hex: publicKey), bytes = request.signingBytes(publicKey: key)
                let signature = try await Task.detached(priority: .userInitiated) { try KeyVault.sign(bytes, expectedKey: publicKey) }.value
                // Authentication can take time. Recheck expiry before releasing evidence.
                try request.validateTime()
                let json = try request.response(publicKey: key, signature: signature).json()
                var next = state
                if next.receipts.count >= 500 {
                    next.receipts.removeAll { Double($0.expiresAt) < Date().timeIntervalSince1970 * 1000 && $0.date < Date().addingTimeInterval(-86400) }
                }
                guard next.receipts.count < 500 else { throw CompanionError("Approval history is full. Try again after older requests expire.") }
                next.receipts.insert(Receipt(approvalKind: request.policyChange != nil ? "Policy change" : nil, id: request.id, date: Date(), digest: request.digest.hex,
                    peer: request.sender.name, amount: request.spend?.lovelace ?? 0, expiresAt: request.expiresAt, response: json), at: 0)
                try persist(next) // Persist replay record before exposing signature.
                self.request = nil; self.response = json
            } catch { self.error = error.localizedDescription }
        }
    }
    func sampleReview() -> ReviewedRequest? {
        guard let url = Bundle.main.url(forResource: "sample-intent", withExtension: "json"), let data = try? Data(contentsOf: url),
              let sample = try? JSONDecoder().decode(Sample.self, from: data), let intent = try? KavachIntent(cbor: Data(hex: sample.cbor)) else { return nil }
        let peer = try? Pairing.parse(Data("{\"version\":1,\"kind\":\"pair\",\"name\":\"Kavach dashboard\",\"publicKey\":\"\(sample.publicKey)\"}".utf8))
        guard let peer else { return nil }
        return ReviewedRequest(body: RequestBody(id: UUID(), profile: "kavach-cose-spend-v1", intentCBOR: sample.cbor,
            signerPublicKey: publicKey ?? "", credentialID: 0), intent: intent, sender: peer)
    }
    private struct Sample: Decodable { let cbor: String; let publicKey: String }
}
