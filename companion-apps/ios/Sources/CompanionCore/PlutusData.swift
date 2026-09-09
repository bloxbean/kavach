import Foundation

/// Bounded subset: unsigned integers, bytes <= 64, lists and constructors 0...8.
/// Requires the exact canonical serialiseData form used by Kavach's pinned fixtures.
public indirect enum PData: Equatable, Sendable {
    case uint(UInt64), bytes(Data), list([PData]), constr(Int, [PData])
    public func fields(_ tag: Int = 0, _ count: Int) throws -> [PData] {
        guard case let .constr(t, f) = self, t == tag, f.count == count else { throw CompanionError("Unsupported intent structure.") }
        return f
    }
    public func integer(max: UInt64 = UInt64(Int64.max)) throws -> UInt64 {
        guard case let .uint(n) = self, n <= max else { throw CompanionError("Invalid integer in intent.") }; return n
    }
    public func bytes(count: Int) throws -> Data {
        guard case let .bytes(b) = self, b.count == count else { throw CompanionError("Invalid byte field in intent.") }; return b
    }
    public func items(min: Int, max: Int) throws -> [PData] {
        guard case let .list(a) = self, a.count >= min, a.count <= max else { throw CompanionError("Unsupported number of entries.") }; return a
    }
    public func encoded() -> Data {
        switch self {
        case .uint(let n): return cborHead(0, n)
        case .bytes(let b): return cborBytes(b)
        case .list(let a): return a.isEmpty ? Data([0x80]) : Data([0x9f]) + a.reduce(Data()) { $0 + $1.encoded() } + Data([0xff])
        case .constr(let t, let a): return cborHead(6, UInt64(t <= 6 ? 121+t : 1280+t-7)) + PData.list(a).encoded()
        }
    }
    public static func decode(_ data: Data) throws -> PData {
        try require(data.count <= 1536 && !data.isEmpty, "Intent exceeds the supported size.")
        var parser = Parser(bytes: [UInt8](data)); let result = try parser.read(0)
        try require(parser.index == data.count && result.encoded() == data, "Noncanonical or trailing intent data.")
        return result
    }
}
public func cborHead(_ major: UInt8, _ n: UInt64) -> Data {
    let prefix = major << 5
    if n < 24 { return Data([prefix | UInt8(n)]) }
    let width = n <= 255 ? 1 : n <= 65535 ? 2 : n <= UInt32.max ? 4 : 8
    let ai: UInt8 = width == 1 ? 24 : width == 2 ? 25 : width == 4 ? 26 : 27
    return Data([prefix | ai] + (0..<width).reversed().map { UInt8(truncatingIfNeeded: n >> ($0*8)) })
}
public func cborBytes(_ data: Data) -> Data { cborHead(2, UInt64(data.count)) + data }
public func cborText(_ text: String) -> Data { cborHead(3, UInt64(text.utf8.count)) + Data(text.utf8) }
private struct Parser {
    let bytes: [UInt8]; var index = 0; var nodes = 0
    mutating func byte() throws -> UInt8 {
        try require(index < bytes.count, "Truncated intent."); defer { index += 1 }; return bytes[index]
    }
    mutating func read(_ depth: Int) throws -> PData {
        nodes += 1; try require(depth <= 16 && nodes <= 256, "Intent structure is too complex.")
        let first = try byte(), major = first >> 5, ai = first & 31
        if major == 4 && ai == 31 {
            var a = [PData]()
            while index < bytes.count && bytes[index] != 255 { a.append(try read(depth+1)) }
            try require(try byte() == 255, "Unterminated list."); return .list(a)
        }
        var n = UInt64(ai)
        if ai >= 24 {
            try require(ai <= 27, "Unsupported CBOR encoding.")
            n = 0; for _ in 0..<(1 << (ai-24)) { n = (n << 8) | UInt64(try byte()) }
        }
        switch major {
        case 0: return .uint(n)
        case 2:
            try require(n <= 64 && n <= UInt64(bytes.count-index), "Unsupported byte string.")
            let end = index+Int(n); defer { index = end }; return .bytes(Data(bytes[index..<end]))
        case 4:
            try require(n <= 32, "Too many CBOR entries.")
            var a = [PData](); for _ in 0..<n { a.append(try read(depth+1)) }; return .list(a)
        case 6:
            let tag: Int
            if (121...127).contains(n) { tag = Int(n)-121 }
            else if (1280...1281).contains(n) { tag = Int(n)-1280+7 }
            else { throw CompanionError("Unsupported constructor.") }
            guard case let .list(a) = try read(depth+1) else { throw CompanionError("Invalid constructor.") }
            return .constr(tag, a)
        default: throw CompanionError("Unsupported CBOR type.")
        }
    }
}
