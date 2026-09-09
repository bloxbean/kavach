import SwiftUI

struct ImportSheet: View {
    let ingest: (String) -> Void, dismiss: () -> Void
    @State private var text = ""
    var body: some View {
        SheetFrame(title: "Import a request", dismiss: dismiss) {
            Text("From your desktop.\nFor your eyes.").font(.system(size: 32, design: .serif))
            Text("Paste a pairing code or signed request JSON. Your phone will verify it before opening a review.").font(.system(size: 14)).foregroundStyle(Palette.muted)
            TextEditor(text: $text).font(.system(size: 12, design: .monospaced)).frame(height: 240).scrollContentBackground(.hidden).padding(14).background(Palette.panel, in: RoundedRectangle(cornerRadius: 18)).accessibilityLabel("Request JSON")
                .autocorrectionDisabled().textInputAutocapitalization(.never)
            PrimaryButton(title: "Open for review", disabled: text.isEmpty || text.utf8.count > 8192) { ingest(text) }
            Text("8 KB maximum · Importing does not sign anything.").font(.system(size: 11)).foregroundStyle(Palette.muted)
        }
    }
}
struct PairSheet: View {
    let peer: Pairing, confirm: () -> Void, dismiss: () -> Void
    @State private var compared = false
    var body: some View {
        SheetFrame(title: "Pair a desktop", dismiss: dismiss) {
            Image(systemName: "laptopcomputer.and.iphone").font(.system(size: 48)).foregroundStyle(Palette.accent)
            Text("Know who's\nasking.").font(.system(size: 38, design: .serif))
            Surface { Text(peer.name).font(.title2); DetailRow(title: "Compare this full fingerprint on your desktop", value: peer.fingerprint) }
            Text("The name can be chosen by anyone. Compare the fingerprint on the desktop you intended to pair, through a screen you trust. This pins its request-signing key; it grants no spending permission.").font(.system(size: 14)).foregroundStyle(Palette.muted).lineSpacing(4)
            Toggle("I compared the fingerprint on both devices", isOn: $compared).font(.system(size: 14)).tint(Palette.accent)
            PrimaryButton(title: "Trust this desktop", icon: "link", disabled: !compared) { confirm() }
        }
    }
}
struct SpendReviewSheet: View {
    let intent: KavachIntent
    let request: ReviewedRequest, preview: Bool, busy: Bool, approve: () -> Void, dismiss: () -> Void
    @State private var checked = false
    @State private var details = false
    var body: some View {
        SheetFrame(title: preview ? "Sample · No signature possible" : "Review before you approve", dismiss: dismiss) {
            HStack { Image(systemName: "shield.lefthalf.filled").foregroundStyle(Palette.accent); Text(request.sender.name).font(.system(size: 13, weight: .medium)); Spacer(); Text("DEVKIT 42").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(Palette.accent) }
            VStack(alignment: .leading, spacing: 10) {
                Text("Send payment").font(.system(size: 17)).foregroundStyle(Palette.muted)
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(ada(intent.lovelace)).font(.system(size: 64, weight: .medium, design: .rounded)).minimumScaleFactor(0.5)
                    Text("ADA").font(.system(size: 22)).foregroundStyle(Palette.muted)
                }
                Text("Your phone decoded this amount from the signed intent.").font(.system(size: 12)).foregroundStyle(Palette.muted)
            }.padding(.vertical, 8)
            Surface {
                DetailRow(title: "To · full recipient address", value: intent.recipient)
                Divider().overlay(Palette.line)
                HStack { Text("Maximum account fee").font(.system(size: 13)).foregroundStyle(Palette.muted); Spacer(); Text("\(ada(intent.maxFee)) ADA").font(.system(size: 14, weight: .medium)) }
                HStack { Text("Account").font(.system(size: 13)).foregroundStyle(Palette.muted); Spacer(); Text(short(intent.accountID)).font(.system(size: 12, design: .monospaced)) }
                if !preview {
                    HStack { Text("Expires").font(.system(size: 13)).foregroundStyle(Palette.muted); Spacer(); Text(Date(timeIntervalSince1970: Double(intent.expiresAt)/1000), style: .time).font(.system(size: 13)) }
                }
            }
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "eye").foregroundStyle(Palette.accent)
                Text(preview ? "Published conformance fixture. This sample does not represent a live account or request." : "Request verified against your paired desktop. This phone has not independently checked the account's current on-chain state.").font(.system(size: 12)).foregroundStyle(Palette.muted).lineSpacing(4)
            }
            DisclosureGroup("Inspect signed details", isExpanded: $details) {
                VStack(alignment: .leading, spacing: 18) {
                    DetailRow(title: "Account policy ID", value: intent.accountID)
                    DetailRow(title: "Deployment ID", value: intent.deploymentID)
                    DetailRow(title: "State version", value: String(intent.stateVersion))
                    DetailRow(title: "State reference", value: intent.stateReference)
                    DetailRow(title: "Account inputs", value: intent.inputs.joined(separator: "\n"))
                    DetailRow(title: "Core bindings · state / assets / checkpoint", value: intent.coreHashes.joined(separator: "\n"))
                    DetailRow(title: "Intent digest · BLAKE2b-256", value: intent.digest.hex)
                    DetailRow(title: "Signing profile", value: request.body.profile)
                    DetailRow(title: "Credential ID", value: String(request.body.credentialID))
                    DetailRow(title: "Validity · milliseconds since Unix epoch", value: "[\(intent.notBefore), \(intent.expiresAt))")
                }.padding(.top, 18)
            }.font(.system(size: 13))
            if preview {
                PrimaryButton(title: "Sample only · Close review", icon: "checkmark") { dismiss() }
                Text("A published Kavach conformance fixture. No signing or transaction submission happens here.").font(.system(size: 11)).foregroundStyle(Palette.muted)
            } else {
                Toggle("I checked the recipient, amount and account", isOn: $checked).font(.system(size: 13)).disabled(busy)
                PrimaryButton(title: busy ? "Waiting for device approval…" : KeyVault.simulator ? "Sign with simulator key" : "Approve on this iPhone", icon: "faceid", disabled: !checked || busy) { approve() }
                Text("This authorizes the displayed intent. Your desktop still needs to assemble and submit the transaction.").font(.system(size: 11)).foregroundStyle(Palette.muted)
                Button("Reject request", role: .destructive) { dismiss() }.font(.system(size: 13)).frame(maxWidth: .infinity).disabled(busy)
            }
        }
    }
}
struct ResponseSheet: View {
    let response: String, dismiss: () -> Void
    var body: some View {
        SheetFrame(title: "Approval ready", dismiss: dismiss) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 54)).foregroundStyle(Palette.accent)
            Text("Signed here.\nReady to return.").font(.system(size: 38, design: .serif))
            Text("Scan this response from your desktop, or share the JSON. This is an approval—not a submitted transaction.").font(.system(size: 14)).foregroundStyle(Palette.muted).lineSpacing(4)
            QRCode(text: response).frame(maxWidth: 310).frame(maxWidth: .infinity)
            ShareLink(item: response) { Label("Share approval", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity).padding(18).background(Palette.panel, in: RoundedRectangle(cornerRadius: 18)) }
            Button("Copy approval JSON") { UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: response]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]) }.frame(maxWidth: .infinity)
            PrimaryButton(title: "Done", icon: "checkmark") { dismiss() }
        }
    }
}

struct ReviewSheet: View {
    let request: ReviewedRequest, preview: Bool, busy: Bool, approve: () -> Void, dismiss: () -> Void
    var body: some View {
        if let intent = request.spend {
            SpendReviewSheet(intent: intent, request: request, preview: preview, busy: busy, approve: approve, dismiss: dismiss)
        } else if let genesis = request.genesis {
            GenesisReviewSheet(request: request, genesis: genesis, busy: busy, approve: approve, dismiss: dismiss)
        } else if let change = request.policyChange {
            PolicyChangeReviewSheet(request: request, change: change, busy: busy, approve: approve, dismiss: dismiss)
        }
    }
}

struct PolicyChangeReviewSheet: View {
    let request: ReviewedRequest, change: KavachPolicyChange, busy: Bool, approve: () -> Void, dismiss: () -> Void
    @State private var checked = false
    var body: some View {
        SheetFrame(title: "Policy change · DevKit 42", dismiss: dismiss) {
            Text(change.purpose == "operation" ? "Approve new rules." : "Prove your key.").font(.system(size: 36, design: .serif))
            Text(change.purpose == "operation" ? "Your existing admin authority is approving this configuration change." : "This proves possession for the exact destination configuration. Existing admin approval is still required.").foregroundStyle(Palette.muted)
            summary(change.previous, title: "Current configuration")
            summary(change.target, title: "Proposed configuration")
            Surface {
                DetailRow(title: "Account", value: change.accountID)
                DetailRow(title: "Current state", value: "Version \(change.stateVersion) · \(change.stateReference)")
                DetailRow(title: "Deployment", value: change.deploymentID)
                DetailRow(title: "Core bindings", value: change.coreHashes.joined(separator: "\n"))
                DetailRow(title: "Current module", value: change.oldModule)
                DetailRow(title: "Proposed module", value: change.newModule)
                DetailRow(title: "Proof digest", value: change.digest.hex)
            }
            Text("Changing the budget period starts a fresh period counter on the next spend. Disabling retains recorded usage; removing its module removes the budget. This phone validates the signed data but does not independently query the live state NFT.").font(.system(size: 12)).foregroundStyle(Palette.muted)
            Text("Expires \(Date(timeIntervalSince1970: Double(request.expiresAt)/1000).formatted(date: .omitted, time: .standard))").font(.system(size: 12))
            Toggle("I checked both configurations and the account identity", isOn: $checked).disabled(busy)
            PrimaryButton(title: busy ? "Waiting for device approval…" : "Approve policy proof", icon: "faceid", disabled: !checked || busy) { approve() }
            Button("Reject request", role: .destructive) { dismiss() }.disabled(busy)
        }
    }
    private func summary(_ value: PolicySummary, title: String) -> some View {
        Surface {
            Text(title).font(.headline)
            ForEach(value.keys, id: \.id) { key in
                DetailRow(title: "Key \(key.id) · " + (value.coseIDs.map { $0.contains(key.id) ? "COSE" : "Transaction witness" } ?? "Legacy profile"), value: key.publicKey)
            }
            ForEach(value.policies, id: \.role) { policy in
                DetailRow(title: policy.role, value: "\(policy.threshold) of keys " + policy.ids.map(String.init).joined(separator: ", "))
            }
            ForEach(value.details, id: \.self) { line in Text(line).font(.system(size: 12, design: .monospaced)) }
        }
    }
}
struct GenesisReviewSheet: View {
    let request: ReviewedRequest, genesis: KavachGenesis, busy: Bool, approve: () -> Void, dismiss: () -> Void
    @State private var checked = false
    var body: some View {
        SheetFrame(title: "Enroll this iPhone · DevKit 42", dismiss: dismiss) {
            Text("Your key.\nA new account.").font(.system(size: 38, design: .serif))
            Text("From \(request.sender.name). Prove possession of key \(request.body.credentialID) for this new account. Review every key and authority policy.").foregroundStyle(Palette.muted)
            Surface {
                ForEach(genesis.keys, id: \.id) { key in
                    DetailRow(title: "Key \(key.id) · " + (genesis.policySummary.coseIDs.map { $0.contains(key.id) ? "COSE" : "Transaction signature" } ?? "COSE") + (key.publicKey == request.body.signerPublicKey ? " · This iPhone" : ""), value: key.publicKey)
                }
            }
            Surface {
                ForEach(genesis.policySummary.details, id: \.self) { Text($0).font(.system(size: 12)) }
                ForEach(genesis.policies, id: \.role) { policy in
                    DetailRow(title: policy.role, value: "\(policy.threshold) of \(policy.ids.count) · Keys " + policy.ids.map(String.init).joined(separator: ", "))
                }
            }
            Surface {
                DetailRow(title: "Account policy ID", value: genesis.accountID)
                DetailRow(title: "Deployment ID", value: genesis.deploymentID)
                DetailRow(title: "Module hash · ABI 1", value: genesis.moduleHash)
                DetailRow(title: "Core bindings", value: genesis.coreHashes.joined(separator: "\n"))
                DetailRow(title: "Possession proof digest", value: request.digest.hex)
            }
            Text("This signature binds the displayed keys, policies and script identities. Recovery timings are not part of the genesis possession proof. Your desktop must verify the complete initial state and submit the creation transaction.").font(.system(size: 12)).foregroundStyle(Palette.muted)
            Text("Expires \(Date(timeIntervalSince1970: Double(request.expiresAt)/1000).formatted(date: .omitted, time: .standard))").font(.system(size: 12))
            Toggle("I checked all keys, policies and account identity", isOn: $checked).disabled(busy)
            PrimaryButton(title: busy ? "Waiting for device approval…" : "Approve enrollment", icon: "faceid", disabled: !checked || busy) { approve() }
            Button("Reject request", role: .destructive) { dismiss() }.disabled(busy)
        }
    }
}
