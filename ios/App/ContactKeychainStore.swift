import Foundation
import MeshGramCore
import Security

struct ContactKeychainStore: ContactPinStorage {
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.meshgram.ios.contacts.pins-v1",
         kSecAttrSynchronizable as String: false]
    }
    private func decode(_ item: [String: Any]) throws -> ConfirmedContact {
        guard let data = item[kSecValueData as String] as? Data, data.count <= 4096,
              let account = item[kSecAttrAccount as String] as? String,
              item[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String
        else { throw ContactPinError.invalidRecord }
        let record = try JSONDecoder().decode(ConfirmedContact.self, from: data)
        guard record.nodeId == account else { throw ContactPinError.invalidRecord }
        _ = try ContactBook(contacts: [record])
        return record
    }
    func read() throws -> [ConfirmedContact] {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecReturnAttributes as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitAll
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        guard let items = result as? [[String: Any]], items.count <= ContactBook.maximumContacts else {
            throw ContactPinError.invalidRecord
        }
        return try items.map(decode)
    }
    func add(_ contact: ConfirmedContact) throws -> ConfirmedContact {
        _ = try ContactBook(contacts: [contact])
        let data = try JSONEncoder().encode(contact)
        guard data.count <= 4096 else { throw ContactPinError.capacity }
        var insert = query
        insert[kSecAttrAccount as String] = contact.nodeId
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(insert as CFDictionary, nil)
        if status == errSecSuccess { return contact }
        guard status == errSecDuplicateItem else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        // Append-only: a duplicate is read and compared, never replaced or deleted.
        var existing = query
        existing[kSecAttrAccount as String] = contact.nodeId
        existing[kSecReturnData as String] = true
        existing[kSecReturnAttributes as String] = true
        existing[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let readStatus = SecItemCopyMatching(existing as CFDictionary, &result)
        guard readStatus == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(readStatus)) }
        guard let item = result as? [String: Any] else { throw ContactPinError.invalidRecord }
        let saved = try decode(item)
        guard saved.hasSameKeys(as: contact) else { throw ContactPinError.changedKey }
        return saved
    }
}
