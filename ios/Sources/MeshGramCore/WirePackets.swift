import Foundation

public enum MeshWire {
    public static let service = "88a7588a-8869-4453-91f8-f53db3564f06"
    public static let write = "4db27553-b5d3-4626-95f6-b2e67a4b3597"
    public static let notify = "0ecae0e1-53f6-4df0-b0e8-01e1478196ab"
    public static let maximumFrameBytes = 255 * 180
    public static let helloMaxHops = 8
    public static let messageMaxHops = 24
}

public struct HelloPacket: Codable, Sendable {
    public let type: String
    public let frameId: String
    public let originNodeId: String
    public let relayNodeId: String
    public let hops: Int
    public let maxHops: Int
    public let createdAtMs: Int64
    public let alias: String
    public let encryptionPublicKey: String
    public let signingPublicKey: String
    public let fingerprint: String
    public let signature: String
    public let avatarData: String?
    public let profileVersion: Int?
    public let discoverable: Bool?

    public var signingBytes: Data {
        let fields = [frameId, originNodeId, alias, encryptionPublicKey, signingPublicKey,
                      fingerprint, String(createdAtMs), String(maxHops)]
        let legacy = fields.joined(separator: "|")
        if profileVersion == 2 {
            return Data((legacy + "|" + (avatarData ?? "") + "|profile-v2|" + String(discoverable ?? false)).utf8)
        }
        return Data((legacy + "|" + (avatarData ?? "")).utf8)
    }

    public var legacySigningBytes: Data {
        Data([frameId, originNodeId, alias, encryptionPublicKey, signingPublicKey,
              fingerprint, String(createdAtMs), String(maxHops)].joined(separator: "|").utf8)
    }
}

public struct SecureMessagePacket: Codable, Sendable {
    public let type: String
    public let id: String
    public let originNodeId: String
    public let senderAlias: String
    public let senderEncryptionPublicKey: String
    public let senderSigningPublicKey: String
    public let senderFingerprint: String
    public let targetNodeId: String
    public let relayNodeId: String
    public let hops: Int
    public let maxHops: Int
    public let createdAtMs: Int64
    public let ephemeralPublicKey: String
    public let nonce: String
    public let ciphertext: String
    public let signature: String

    public var signingBytes: Data {
        Data([id, originNodeId, senderAlias, senderEncryptionPublicKey, senderSigningPublicKey,
              senderFingerprint, targetNodeId, String(createdAtMs), String(maxHops),
              ephemeralPublicKey, nonce, ciphertext].joined(separator: "|").utf8)
    }
    public var salt: Data { Data("salt|\(id)|\(createdAtMs)|\(targetNodeId)".utf8) }
    public var authenticatedData: Data {
        Data("aad|\(id)|\(originNodeId)|\(targetNodeId)|\(createdAtMs)|\(maxHops)".utf8)
    }
}
