import SwiftUI
import UniformTypeIdentifiers

@main struct YanoCompanionApp: App {
    var body: some Scene { WindowGroup { CompanionRoot() } }
}
struct CompanionRoot: View {
    @StateObject private var store = CompanionStore()
    @Environment(\.scenePhase) private var phase
    @State private var route = "import"
    @State private var showSheet = false
    @State private var showFile = false
    @State private var sample: ReviewedRequest?
    private func present(_ value: String) { route = value; showSheet = true }
    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Brand().padding(.top, 8)
                    if store.publicKey == nil { onboarding }
                    else if store.tab == 0 { home }
                    else if store.tab == 1 { devices }
                    else { activity }
                }.padding(24).padding(.bottom, 20)
            }
            if store.publicKey != nil { tabBar }
        }
        .background(Palette.background).foregroundStyle(Palette.ink).preferredColorScheme(.dark)
        .tint(Palette.accent)
        .task {
            #if DEBUG && targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--simulator-check") {
                do { store.publicKey = try SimulatorChecks.run() } catch { store.error = error.localizedDescription }
            }
            if ProcessInfo.processInfo.arguments.contains("--preview-review") {
                sample = store.sampleReview(); present("sample")
            }
            #endif
        }
        .sheet(isPresented: $showSheet, onDismiss: { if !store.busy { store.request = nil; store.pairing = nil; store.response = nil } }) {
            sheetContent.interactiveDismissDisabled(store.busy)
        }
        .onChange(of: store.request?.id) { _, value in if value != nil { present("review") } }
        .onChange(of: store.pairing?.id) { _, value in if value != nil { present("pair") } }
        .onChange(of: store.response) { _, value in if value != nil { present("response") } }
        .alert("Couldn't continue", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
            Button("OK") { store.error = nil }
        } message: { Text(store.error ?? "") }
        .fileImporter(isPresented: $showFile, allowedContentTypes: [.json, .plainText]) { result in
            do {
                let url = try result.get(), access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 8192 else { throw CompanionError("Request exceeds 8 KB.") }
                store.ingest(try String(contentsOf: url, encoding: .utf8))
            } catch { store.error = error.localizedDescription }
        }
        .overlay { if phase == .background { Palette.background.overlay(Brand().padding(40)).ignoresSafeArea() } }
    }
    private var onboarding: some View {
        VStack(alignment: .leading, spacing: 30) {
            HStack { Eyebrow(text: "An independent second key"); Spacer(); Image(systemName: "sparkle").foregroundStyle(Palette.accent) }
            Text("Your approval.\nYour device.").font(.system(size: 48, weight: .medium, design: .serif)).tracking(-2).lineSpacing(-2)
            ZStack {
                Circle().stroke(Palette.line, lineWidth: 1).frame(width: 224, height: 224)
                Circle().stroke(Palette.accent.opacity(0.2), style: StrokeStyle(lineWidth: 1, dash: [3, 8])).frame(width: 180, height: 180)
                RoundedRectangle(cornerRadius: 34).fill(Palette.accent).frame(width: 110, height: 130).rotationEffect(.degrees(-9))
                Image(systemName: "checkmark.shield.fill").font(.system(size: 52, weight: .light)).foregroundStyle(Palette.background)
                Text("ON YOUR TERMS").font(.system(size: 9, weight: .bold, design: .monospaced)).tracking(3).offset(y: 100)
            }.frame(maxWidth: .infinity).padding(.vertical, 6).accessibilityHidden(true)
            Text("A little distance.\nA lot more control.").font(.system(size: 24, weight: .medium, design: .serif))
            Text("Review on your iPhone before your desktop can act. Your signing key stays on this device.").font(.system(size: 15)).foregroundStyle(Palette.muted).lineSpacing(5)
            PrimaryButton(title: KeyVault.simulator ? "Create simulator key" : "Create my device key", icon: "key.fill") { store.createKey() }
            Text(KeyVault.simulator ? "SIMULATOR · Development key without biometric protection." : "Uses your device passcode or biometrics. This key does not sync to iCloud. Set up independent recovery before funding an account.")
                .font(.system(size: 11)).foregroundStyle(Palette.muted).lineSpacing(4)
            Button("Explore a sample approval") { sample = store.sampleReview(); present("sample") }.font(.system(size: 13)).frame(maxWidth: .infinity)
        }
    }
    private var home: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack { Label("YACI DEVKIT", systemImage: "circle.fill").font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent); Spacer(); Text("DEVELOPMENT").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(Palette.muted) }
            Text("A second look.\nA safer yes.").font(.system(size: 43, weight: .medium, design: .serif)).tracking(-1.8)
            Surface {
                HStack { Image(systemName: "checkmark.shield").font(.system(size: 28)).foregroundStyle(Palette.accent); Spacer(); Text(KeyVault.simulator ? "SIMULATOR KEY" : "DEVICE KEY READY").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent) }
                Text("Ready when you are.").font(.system(size: 23, weight: .medium, design: .serif))
                Text("Scan an approval from your paired desktop. You’ll see exactly what you’re signing.").font(.system(size: 14)).foregroundStyle(Palette.muted).lineSpacing(4)
                PrimaryButton(title: "Scan a request", icon: "qrcode.viewfinder") { present("scan") }
                HStack { Button("Paste request") { present("import") }; Spacer(); Button("Import file") { showFile = true } }.font(.system(size: 12, weight: .medium))
            }
            HStack { Eyebrow(text: "Your connections"); Spacer(); Button("Manage") { store.tab = 1 }.font(.system(size: 12)) }
            HStack(spacing: 12) {
                connectionCard("Kavach", subtitle: "Account approvals", icon: "shield.lefthalf.filled", active: true)
                connectionCard("Yano Wallet", subtitle: "Adapter planned", icon: "wallet.pass", active: false)
            }
            Button { present("identity") } label: {
                HStack { Image(systemName: "key.horizontal").foregroundStyle(Palette.accent); VStack(alignment: .leading, spacing: 5) { Text("Your device identity").font(.system(size: 14)); Text(short(store.publicKey ?? "")).font(.system(size: 11, design: .monospaced)).foregroundStyle(Palette.muted) }; Spacer(); Image(systemName: "arrow.up.right") }.padding(.vertical, 6)
            }.buttonStyle(.plain)
            Button("Explore a sample approval") { sample = store.sampleReview(); present("sample") }.font(.system(size: 12)).foregroundStyle(Palette.muted)
        }
    }
    private func connectionCard(_ name: String, subtitle: String, icon: String, active: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: icon).font(.system(size: 24)).foregroundStyle(active ? Palette.accent : Palette.muted)
            VStack(alignment: .leading, spacing: 5) { Text(name).font(.system(size: 14, weight: .semibold)); Text(subtitle).font(.system(size: 10)).foregroundStyle(Palette.muted) }
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(Palette.panel, in: RoundedRectangle(cornerRadius: 20))
    }
    private var devices: some View {
        VStack(alignment: .leading, spacing: 24) {
            Eyebrow(text: "Known connections")
            Text("Your trusted\ndesktops.").font(.system(size: 42, weight: .medium, design: .serif)).tracking(-1.4)
            Text("Pair only with a desktop you control. A matching name is not proof of identity—compare the full key fingerprint.").font(.system(size: 14)).foregroundStyle(Palette.muted).lineSpacing(4)
            if store.state.peers.isEmpty {
                Surface { Image(systemName: "laptopcomputer").font(.system(size: 38)).foregroundStyle(Palette.accent); Text("No desktops paired yet.").font(.system(size: 20, design: .serif)); Text("Scan a pairing QR code to pin its public key on this device.").font(.system(size: 14)).foregroundStyle(Palette.muted) }
            }
            ForEach(store.state.peers) { peer in
                Surface { Label(peer.name, systemImage: "laptopcomputer").font(.headline); DetailRow(title: "Desktop key fingerprint", value: peer.fingerprint)
                    Button("Unpair desktop", role: .destructive) { store.unpair(peer) }.font(.system(size: 12)) }
            }
            PrimaryButton(title: "Pair a desktop", icon: "qrcode") { present("scan") }
            Button("Paste pairing code") { present("import") }.frame(maxWidth: .infinity).font(.system(size: 13))
            Button("Show my public key") { present("identity") }.frame(maxWidth: .infinity).font(.system(size: 13))
        }
    }
    private var activity: some View {
        VStack(alignment: .leading, spacing: 24) {
            Eyebrow(text: "Only what you approved")
            Text("A clear\npaper trail.").font(.system(size: 42, weight: .medium, design: .serif)).tracking(-1.4)
            Text("Approvals are signatures, not confirmation that a transaction was submitted or settled.").font(.system(size: 14)).foregroundStyle(Palette.muted)
            if store.state.receipts.isEmpty { Surface { Image(systemName: "clock.arrow.circlepath").font(.system(size: 32)).foregroundStyle(Palette.accent); Text("Nothing signed yet.").font(.system(size: 22, design: .serif)); Text("Your completed approvals will appear here.").font(.system(size: 14)).foregroundStyle(Palette.muted) } }
            ForEach(store.state.receipts) { receipt in
                Button { store.response = receipt.response } label: {
                    Surface { HStack { Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.accent); Text(receipt.approvalKind ?? (receipt.amount == 0 ? "Key enrollment" : "\(ada(receipt.amount)) ADA")).font(.headline); Spacer(); Image(systemName: "arrow.up.right") }; Text(receipt.peer).font(.system(size: 13)); Text(receipt.date, style: .date).font(.system(size: 12)).foregroundStyle(Palette.muted) }
                }.buttonStyle(.plain)
            }
        }
    }
    private var tabBar: some View {
        HStack {
            tab("Approve", icon: "checkmark.shield", index: 0)
            tab("Devices", icon: "square.stack.3d.up", index: 1)
            tab("Activity", icon: "clock", index: 2)
        }.padding(.top, 16).padding(.bottom, 10).background(Palette.background).overlay(alignment: .top) { Rectangle().fill(Palette.line).frame(height: 1) }
    }
    private func tab(_ title: String, icon: String, index: Int) -> some View {
        Button { store.tab = index } label: { VStack(spacing: 7) { Image(systemName: icon).font(.system(size: 19)); Text(title).font(.system(size: 10, weight: .medium)) }.frame(maxWidth: .infinity).foregroundStyle(store.tab == index ? Palette.accent : Palette.muted) }
    }
    @ViewBuilder private var sheetContent: some View {
        switch route {
        case "import": ImportSheet { store.ingest($0) } dismiss: { showSheet = false }
        case "scan": SheetFrame(title: "Scan a QR code", dismiss: { showSheet = false }) {
            Text("Bring your desktop\na little closer.").font(.system(size: 32, design: .serif))
            ScannerView { store.ingest($0) }.frame(height: 360).clipShape(RoundedRectangle(cornerRadius: 24))
            Text("Scan a pairing code or a signed request. Nothing is approved by scanning.").font(.system(size: 14)).foregroundStyle(Palette.muted)
            Button("Paste a code instead") { route = "import" }
        }
        case "pair": if let peer = store.pairing { PairSheet(peer: peer, confirm: { store.confirmPairing(); if store.pairing == nil { showSheet = false } }, dismiss: { showSheet = false }) }
        case "review": if let request = store.request { ReviewSheet(request: request, preview: false, busy: store.busy, approve: { store.approve() }, dismiss: { if !store.busy { showSheet = false } }) }
        case "sample": if let sample { ReviewSheet(request: sample, preview: true, busy: false, approve: {}, dismiss: { showSheet = false }) }
        case "response": if let response = store.response { ResponseSheet(response: response, dismiss: { showSheet = false }) }
        case "identity": SheetFrame(title: "Device identity", dismiss: { showSheet = false }) {
            Text("One device.\nOne independent key.").font(.system(size: 32, design: .serif))
            if let key = store.publicKey {
                QRCode(text: key).frame(maxWidth: 260).frame(maxWidth: .infinity)
                Surface { DetailRow(title: "Ed25519 public key", value: key) }
                ShareLink(item: key) { Label("Share public key", systemImage: "square.and.arrow.up") }
                Button("Copy public key") { UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: key]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]) }
            }
            Text("Enroll this public key in Kavach using the existing account's authorized configuration flow. Pairing a desktop does not enroll the key on-chain.").font(.system(size: 14)).foregroundStyle(Palette.muted)
            Text(KeyVault.simulator ? "Simulator identity: no biometric protection. Never enroll it in a funded account." : "Protected at rest by Keychain and user presence. Ed25519 signing occurs in app memory; this is not a Secure Enclave signing key.").font(.system(size: 12)).foregroundStyle(Palette.muted)
        }
        default: EmptyView()
        }
    }
}
