import Foundation

public enum MeshProtocolError: Error {
    case invalidFrame, invalidIdentity, invalidSignature, untrustedPeer, wrongRecipient
}

public enum FrameCodec {
    public static func packetize(_ payload: Data, frameKey: UInt32, chunkSize: Int = 12) throws -> [Data] {
        guard !payload.isEmpty, payload.count <= MeshWire.maximumFrameBytes,
              (12...180).contains(chunkSize) else { throw MeshProtocolError.invalidFrame }
        let count = (payload.count + chunkSize - 1) / chunkSize
        guard count <= 255 else { throw MeshProtocolError.invalidFrame }
        return (0..<count).map { index in
            var packet = Data([0x4d, UInt8(truncatingIfNeeded: frameKey >> 24),
                               UInt8(truncatingIfNeeded: frameKey >> 16), UInt8(truncatingIfNeeded: frameKey >> 8),
                               UInt8(truncatingIfNeeded: frameKey), UInt8(count), UInt8(index), 0])
            packet.append(payload.subdata(in: (index * chunkSize)..<min(payload.count, (index + 1) * chunkSize)))
            return packet
        }
    }
}

// An instance belongs to one BLE connection. State is bounded and expires.
public struct FrameAssembler {
    private struct Entry {
        let total: Int
        var parts: [Int: Data]
        var touched: TimeInterval
    }
    private var entries: [UInt32: Entry] = [:]
    public init() {}

    public mutating func receive(_ packet: Data, now: TimeInterval = Date.timeIntervalSinceReferenceDate) throws -> Data? {
        let bytes = [UInt8](packet)
        guard (9...188).contains(bytes.count), bytes[0] == 0x4d, bytes[7] == 0,
              bytes[5] > 0, bytes[6] < bytes[5] else { throw MeshProtocolError.invalidFrame }
        entries = entries.filter { now - $0.value.touched < 30 && now >= $0.value.touched }
        let key = bytes[1...4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        let total = Int(bytes[5]), index = Int(bytes[6])
        let chunk = Data(bytes.dropFirst(8))
        if let existing = entries[key], existing.total != total {
            entries.removeValue(forKey: key)
            throw MeshProtocolError.invalidFrame
        }
        if entries[key] == nil && entries.count >= 32 { throw MeshProtocolError.invalidFrame }
        var entry = entries[key] ?? Entry(total: total, parts: [:], touched: now)
        if let previous = entry.parts[index], previous != chunk {
            entries.removeValue(forKey: key)
            throw MeshProtocolError.invalidFrame
        }
        entry.parts[index] = chunk
        entry.touched = now
        guard entry.parts.count == total else { entries[key] = entry; return nil }
        entries.removeValue(forKey: key)
        var result = Data()
        for part in 0..<total {
            guard let value = entry.parts[part] else { throw MeshProtocolError.invalidFrame }
            result.append(value)
        }
        guard result.count <= MeshWire.maximumFrameBytes else { throw MeshProtocolError.invalidFrame }
        return result
    }
}
