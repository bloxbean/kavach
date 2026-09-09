import Foundation
import CryptoKit
import Security
import LocalAuthentication

enum KeyVault {
    private static let service = "com.bloxbean.yano.companion"
    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: account, kSecAttrSynchronizable as String: false]
    }
    static var simulator: Bool {
        #if targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }
    static func create() throws -> String {
        // Never overwrite an existing signing identity.
        var lookup = query("device.ed25519"); lookup[kSecReturnAttributes as String] = true
        let existing = SecItemCopyMatching(lookup as CFDictionary, nil)
        guard existing == errSecItemNotFound else { throw CompanionError("A device key already exists or Keychain is unavailable. Existing identity was preserved.") }
        let key = Curve25519.Signing.PrivateKey()
        var item = query("device.ed25519")
        item[kSecValueData as String] = key.rawRepresentation
        #if targetEnvironment(simulator)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        #else
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, &error) else {
            throw CompanionError("Enable a device passcode before creating your key.")
        }
        item[kSecAttrAccessControl as String] = access
        #endif
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw CompanionError("Could not protect the device key. Set a device passcode and try again.") }
        let publicKey = key.publicKey.rawRepresentation.hex
        try write(Data(publicKey.utf8), account: "device.public")
        return publicKey
    }
    static func publicKey() -> String? { (try? read("device.public")).flatMap { String(data: $0, encoding: .utf8) } }
    static func sign(_ bytes: Data, expectedKey: String) throws -> Data {
        var q = query("device.ed25519"); q[kSecReturnData as String] = true
        let context = LAContext(); context.localizedReason = "Approve the payment you just reviewed in Yano Companion."
        context.touchIDAuthenticationAllowableReuseDuration = 0
        q[kSecUseAuthenticationContext as String] = context
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        guard status == errSecSuccess, var raw = result as? Data else { throw CompanionError("Approval cancelled or the device key is unavailable.") }
        defer { raw.resetBytes(in: 0..<raw.count); context.invalidate() }
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: raw)
        guard key.publicKey.rawRepresentation.hex == expectedKey else { throw CompanionError("Device identity mismatch. Signing blocked.") }
        return try key.signature(for: bytes)
    }
    static func read(_ account: String) throws -> Data? {
        var q = query(account); q[kSecReturnData as String] = true
        var result: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw CompanionError("Protected local storage is unavailable.") }
        return data
    }
    static func write(_ data: Data, account: String) throws {
        let q = query(account)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = q; item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw CompanionError("Could not save protected local state.") }
        } else if status != errSecSuccess { throw CompanionError("Could not update protected local state.") }
    }
}
