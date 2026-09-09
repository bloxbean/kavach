import Foundation
import Compression

/// Optional QR transport compression; signed bytes are unchanged after decoding.
/// The fixed output buffer bounds decompression before any JSON parsing/allocation.
public enum QRTransport {
    public static func encode(_ data: Data) throws -> String {
        try require(!data.isEmpty && data.count <= 8192, "Request exceeds 8 KB.")
        var output = [UInt8](repeating: 0, count: data.count + 1024)
        let count = data.withUnsafeBytes { input in
            compression_encode_buffer(&output, output.count, input.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB)
        }
        try require(count > 0, "Could not encode QR request.")
        return "YANO1:" + Data(output.prefix(count)).base64EncodedString()
    }
    public static func decode(_ text: String) throws -> Data {
        try require(text.utf8.count <= 12000, "QR request is too large.")
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("YANO1:") else {
            let data = Data(text.utf8)
            try require(data.count <= 8192, "Request exceeds 8 KB.")
            return data
        }
        guard let compressed = Data(base64Encoded: String(trimmed.dropFirst(6))), !compressed.isEmpty else {
            throw CompanionError("Invalid compressed QR request.")
        }
        var output = [UInt8](repeating: 0, count: 8193)
        let count = compressed.withUnsafeBytes { input in
            compression_decode_buffer(&output, output.count, input.bindMemory(to: UInt8.self).baseAddress!, compressed.count, nil, COMPRESSION_ZLIB)
        }
        try require(count > 0 && count <= 8192, "Invalid or oversized compressed QR request.")
        return Data(output.prefix(count))
    }
}

public struct CompanionError: LocalizedError, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}
func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw CompanionError(message) }
}
public extension Data {
    init(hex: String) throws {
        try require(hex.count % 2 == 0 && hex.count <= 32768, "Invalid hex encoding.")
        var bytes = [UInt8](); let chars = Array(hex.utf8)
        func nibble(_ c: UInt8) throws -> UInt8 {
            switch c { case 48...57: return c - 48; case 97...102: return c - 87
            default: throw CompanionError("Hex must use lowercase hexadecimal characters.") }
        }
        for i in stride(from: 0, to: chars.count, by: 2) {
            bytes.append(try nibble(chars[i]) * 16 + nibble(chars[i + 1]))
        }
        self.init(bytes)
    }
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
public func short(_ value: String) -> String { value.count > 22 ? "\(value.prefix(10))…\(value.suffix(8))" : value }
public func ada(_ amount: UInt64) -> String {
    let whole = amount / 1_000_000, remainder = amount % 1_000_000
    if remainder == 0 { return "\(whole)" }
    var fraction = String(format: "%06llu", remainder)
    while fraction.last == "0" { fraction.removeLast() }
    return "\(whole).\(fraction)"
}
