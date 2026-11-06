import CryptoKit
import Foundation
import MeshGramCore
import Security

enum IdentityStore {
    private struct StoredIdentity: Codable {
        let nodeId: String
        let encryption: Data
        let signing: Data
    }
    static func loadOrCreate() throws -> LocalIdentity {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: "com.meshgram.ios.identity",
                                    kSecAttrAccount as String: "identity-v1"]
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data else { throw MeshProtocolError.invalidIdentity }
            let stored = try JSONDecoder().decode(StoredIdentity.self, from: data)
            guard !stored.nodeId.isEmpty else { throw MeshProtocolError.invalidIdentity }
            return try LocalIdentity(nodeId: stored.nodeId,
                encryptionKey: P256.KeyAgreement.PrivateKey(rawRepresentation: stored.encryption),
                signingKey: P256.Signing.PrivateKey(rawRepresentation: stored.signing))
        }
        // Locked, corrupt, or inaccessible storage must never silently replace the identity.
        guard status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        let identity = LocalIdentity()
        let stored = StoredIdentity(nodeId: identity.nodeId, encryption: identity.encryptionKey.rawRepresentation,
                                    signing: identity.signingKey.rawRepresentation)
        var insert = query
        insert[kSecValueData as String] = try JSONEncoder().encode(stored)
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let saved = SecItemAdd(insert as CFDictionary, nil)
        guard saved == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(saved)) }
        return identity
    }
}
