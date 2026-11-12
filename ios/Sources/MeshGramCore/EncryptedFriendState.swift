import CryptoKit
import Foundation

public enum StateCodecError: Error { case invalidKey, invalidContext, invalidBlob, unsupportedVersion, capacity }

// Pure format codec. Key custody, atomic storage and rollback protection belong to the adapter.
public enum EncryptedFriendState {
    public static let maximumBlobBytes = 1_048_576
    public static let maximumPlaintextBytes = maximumBlobBytes - 33
    private static let header = Data([0x4d, 0x47, 0x46, 0x53, 1])

    public static func seal(_ state: FriendState, key: SymmetricKey, localNodeId: String, ownFingerprint: String) throws -> Data {
        let aad = try context(key: key, node: localNodeId, fingerprint: ownFingerprint)
        var avatarBytes = 0
        for record in state.records {
            avatarBytes += record.avatarData.utf8.count
            guard avatarBytes <= maximumPlaintextBytes else { throw StateCodecError.capacity }
        }
        let clear = try JSONEncoder().encode(state)
        guard clear.count <= maximumPlaintextBytes else { throw StateCodecError.capacity }
        let sealed = try AES.GCM.seal(clear, using: key, nonce: AES.GCM.Nonce(), authenticating: aad)
        guard let combined = sealed.combined else { throw StateCodecError.invalidBlob }
        let result = header + combined
        guard result.count <= maximumBlobBytes else { throw StateCodecError.capacity }
        return result
    }

    public static func open(_ blob: Data, key: SymmetricKey, localNodeId: String, ownFingerprint: String) throws -> FriendState {
        guard blob.count <= maximumBlobBytes else { throw StateCodecError.capacity }
        guard blob.count >= 33, blob.prefix(4) == header.prefix(4) else { throw StateCodecError.invalidBlob }
        guard blob[blob.startIndex + 4] == 1 else { throw StateCodecError.unsupportedVersion }
        let aad = try context(key: key, node: localNodeId, fingerprint: ownFingerprint)
        let box = try AES.GCM.SealedBox(combined: Data(blob.dropFirst(header.count)))
        let clear = try AES.GCM.open(box, using: key, authenticating: aad)
        guard clear.count <= maximumPlaintextBytes else { throw StateCodecError.capacity }
        return try JSONDecoder().decode(FriendState.self, from: clear)
    }

    private static func context(key: SymmetricKey, node: String, fingerprint: String) throws -> Data {
        guard key.bitCount == 256 else { throw StateCodecError.invalidKey }
        guard !node.isEmpty, node == node.trimmingCharacters(in: .whitespacesAndNewlines), node.utf8.count <= 128,
              !node.contains("|"), node.rangeOfCharacter(from: .controlCharacters) == nil,
              fingerprint.utf8.count == 64,
              fingerprint.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw StateCodecError.invalidContext }
        var aad = Data()
        for field in [Data("com.meshgram.ios.friendstate.v1".utf8), header, Data(node.utf8), Data(fingerprint.utf8)] {
            let count = UInt32(field.count)
            for shift in [24, 16, 8, 0] { aad.append(UInt8((count >> shift) & 255)) }
            aad.append(field)
        }
        return aad
    }
}
