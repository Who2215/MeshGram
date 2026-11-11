import CryptoKit
import Foundation

public struct LocalIdentity {
    public let nodeId: String
    public let encryptionKey: P256.KeyAgreement.PrivateKey
    public let signingKey: P256.Signing.PrivateKey

    public var publicFingerprint: String {
        PacketCrypto.fingerprint(
            encryptionKey: encryptionKey.publicKey.derRepresentation.base64EncodedString(),
            signingKey: signingKey.publicKey.derRepresentation.base64EncodedString())
    }

    public init(nodeId: String = UUID().uuidString.lowercased(), encryptionKey: P256.KeyAgreement.PrivateKey = .init(),
                signingKey: P256.Signing.PrivateKey = .init()) {
        self.nodeId = nodeId
        self.encryptionKey = encryptionKey
        self.signingKey = signingKey
    }
}

public enum PacketCrypto {
    private static func base64(_ value: String) throws -> Data {
        guard let bytes = Data(base64Encoded: value), !bytes.isEmpty else { throw MeshProtocolError.invalidIdentity }
        return bytes
    }

    public static func fingerprint(encryptionKey: String, signingKey: String) -> String {
        SHA256.hash(data: Data("\(encryptionKey)|\(signingKey)".utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func makeHello(identity: LocalIdentity, createdAtMs: Int64,
                                 maxHops: Int = 0) throws -> HelloPacket {
        guard !identity.nodeId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              identity.nodeId.utf8.count <= 128, !identity.nodeId.contains("|"),
              identity.nodeId.rangeOfCharacter(from: .controlCharacters) == nil,
              createdAtMs > 0, (0...MeshWire.helloMaxHops).contains(maxHops) else {
            throw MeshProtocolError.invalidIdentity
        }
        let frameId = UUID().uuidString.lowercased()
        let encryption = identity.encryptionKey.publicKey.derRepresentation.base64EncodedString()
        let signing = identity.signingKey.publicKey.derRepresentation.base64EncodedString()
        func packet(signature: String) -> HelloPacket {
            HelloPacket(type: "HELLO_V1", frameId: frameId, originNodeId: identity.nodeId,
                        relayNodeId: identity.nodeId, hops: 0, maxHops: maxHops, createdAtMs: createdAtMs,
                        alias: "Узел MeshGram", encryptionPublicKey: encryption, signingPublicKey: signing,
                        fingerprint: identity.publicFingerprint, signature: signature,
                        avatarData: nil, profileVersion: 2, discoverable: false)
        }
        let signature = try identity.signingKey.signature(for: packet(signature: "").signingBytes)
        return packet(signature: signature.derRepresentation.base64EncodedString())
    }

    public static func verifyHello(_ packet: HelloPacket) throws {
        guard packet.type == "HELLO_V1", !packet.originNodeId.isEmpty, packet.alias.utf8.count <= 256,
              (0...MeshWire.helloMaxHops).contains(packet.maxHops), (0...packet.maxHops).contains(packet.hops),
              (packet.avatarData?.utf8.count ?? 0) <= 24_000,
              fingerprint(encryptionKey: packet.encryptionPublicKey, signingKey: packet.signingPublicKey) == packet.fingerprint
        else { throw MeshProtocolError.invalidIdentity }
        let key = try P256.Signing.PublicKey(derRepresentation: base64(packet.signingPublicKey))
        _ = try P256.KeyAgreement.PublicKey(derRepresentation: base64(packet.encryptionPublicKey))
        let signature = try P256.Signing.ECDSASignature(derRepresentation: base64(packet.signature))
        let version = packet.profileVersion ?? 1
        guard version == 2 || (version == 1 && packet.discoverable != true) else { throw MeshProtocolError.invalidSignature }
        let valid = key.isValidSignature(signature, for: packet.signingBytes)
        let legacyValid = version == 1 && key.isValidSignature(signature, for: packet.legacySigningBytes)
        guard valid || legacyValid else { throw MeshProtocolError.invalidSignature }
    }

    // Caller must supply a previously confirmed fingerprint, never trust packet keys by themselves.
    public static func decrypt(_ packet: SecureMessagePacket, identity: LocalIdentity,
                               trustedNodeId: String, trustedFingerprint: String) throws -> Data {
        guard packet.type == "SECURE_MESSAGE_V1", packet.targetNodeId == identity.nodeId else {
            throw MeshProtocolError.wrongRecipient
        }
        guard (0...MeshWire.messageMaxHops).contains(packet.maxHops), (0...packet.maxHops).contains(packet.hops),
              packet.ciphertext.utf8.count <= 262_144 else { throw MeshProtocolError.invalidFrame }
        let fingerprint = fingerprint(encryptionKey: packet.senderEncryptionPublicKey, signingKey: packet.senderSigningPublicKey)
        guard !trustedFingerprint.isEmpty, packet.originNodeId == trustedNodeId,
              fingerprint == trustedFingerprint, packet.senderFingerprint == trustedFingerprint else {
            throw MeshProtocolError.untrustedPeer
        }
        let publicKey = try P256.Signing.PublicKey(derRepresentation: base64(packet.senderSigningPublicKey))
        let signature = try P256.Signing.ECDSASignature(derRepresentation: base64(packet.signature))
        guard publicKey.isValidSignature(signature, for: packet.signingBytes) else { throw MeshProtocolError.invalidSignature }
        let peerKey = try P256.KeyAgreement.PublicKey(derRepresentation: base64(packet.ephemeralPublicKey))
        let shared = try identity.encryptionKey.sharedSecretFromKeyAgreement(with: peerKey)
        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: packet.salt,
                                               sharedInfo: Data("mesh-e2e-v1".utf8), outputByteCount: 32)
        let nonce = try base64(packet.nonce), combined = try base64(packet.ciphertext)
        guard nonce.count == 12, combined.count >= 16 else { throw MeshProtocolError.invalidFrame }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: combined.dropLast(16), tag: combined.suffix(16))
        return try AES.GCM.open(box, using: key, authenticating: packet.authenticatedData)
    }
}
