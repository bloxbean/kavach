# Companion apps

Native apps that review and approve Kavach requests. They keep signing keys on the user's
device and remain separate from the dashboard backend.

- [iOS — Yano Companion](ios/README.md): SwiftUI iPhone app, shared Swift signing core,
  request/response tools and conformance tests. Development candidate, DevKit only.

The platform folder is `ios`; the installed product remains **Yano Companion** so it can
also serve Yano Wallet integrations. Platform apps share protocol definitions and public
fixtures with Kavach while keeping platform-specific build tooling here.

From this repository root:

```sh
open companion-apps/ios/YanoCompanion.xcodeproj
cd companion-apps/ios
swift test
```

Build output, pairing identities, Xcode user state and local signing configuration are
ignored. Importing this app does not alter an installed phone's keys or app identity.
