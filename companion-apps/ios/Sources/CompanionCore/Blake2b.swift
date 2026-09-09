import Foundation

/// RFC 7693 unkeyed BLAKE2b. Digest length is part of initialization (not truncation).
/// Restricted to the 224/256-bit hashes needed by the Cardano profiles.
public enum Blake2b {
    static let iv: [UInt64] = [0x6a09e667f3bcc908,0xbb67ae8584caa73b,0x3c6ef372fe94f82b,0xa54ff53a5f1d36f1,
        0x510e527fade682d1,0x9b05688c2b3e6c1f,0x1f83d9abfb41bd6b,0x5be0cd19137e2179]
    static let sigma = [
        [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15], [14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3],
        [11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4], [7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8],
        [9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13], [2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9],
        [12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11], [13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10],
        [6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5], [10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0]]
    public static func hash(_ data: Data, length: Int = 32) -> Data {
        precondition(length == 28 || length == 32)
        var h = iv; h[0] ^= 0x01010000 ^ UInt64(length)
        let bytes = [UInt8](data); var offset = 0
        repeat {
            let count = min(128, bytes.count - offset)
            var block = [UInt8](repeating: 0, count: 128)
            if count > 0 { block.replaceSubrange(0..<count, with: bytes[offset..<offset+count]) }
            var words = [UInt64](repeating: 0, count: 16)
            for i in 0..<16 { for j in 0..<8 { words[i] |= UInt64(block[i*8+j]) << (j*8) } }
            var v = h + iv; v[12] ^= UInt64(offset + count)
            if offset + count == bytes.count { v[14] = ~v[14] }
            func rotate(_ x: UInt64, _ n: Int) -> UInt64 { (x >> n) | (x << (64-n)) }
            func mix(_ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
                v[a] = v[a] &+ v[b] &+ x; v[d] = rotate(v[d] ^ v[a], 32)
                v[c] = v[c] &+ v[d]; v[b] = rotate(v[b] ^ v[c], 24)
                v[a] = v[a] &+ v[b] &+ y; v[d] = rotate(v[d] ^ v[a], 16)
                v[c] = v[c] &+ v[d]; v[b] = rotate(v[b] ^ v[c], 63)
            }
            for round in 0..<12 {
                let s = sigma[round % 10]
                mix(0,4,8,12,words[s[0]],words[s[1]]); mix(1,5,9,13,words[s[2]],words[s[3]])
                mix(2,6,10,14,words[s[4]],words[s[5]]); mix(3,7,11,15,words[s[6]],words[s[7]])
                mix(0,5,10,15,words[s[8]],words[s[9]]); mix(1,6,11,12,words[s[10]],words[s[11]])
                mix(2,7,8,13,words[s[12]],words[s[13]]); mix(3,4,9,14,words[s[14]],words[s[15]])
            }
            for i in 0..<8 { h[i] ^= v[i] ^ v[i+8] }
            offset += count
        } while offset < bytes.count
        return Data(h.flatMap { word in (0..<8).map { UInt8(truncatingIfNeeded: word >> ($0*8)) } }.prefix(length))
    }
}
