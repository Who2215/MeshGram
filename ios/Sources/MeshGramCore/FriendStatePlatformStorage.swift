import CryptoKit
import Darwin
import Foundation
import Security

public final class KeychainFriendStateKey: FriendStateKeyStorage {
    private let account: String
    public init(localNodeId: String, ownFingerprint: String) throws {
        guard !localNodeId.isEmpty, localNodeId == localNodeId.trimmingCharacters(in: .whitespacesAndNewlines),
              localNodeId.utf8.count <= 128, !localNodeId.contains("|"), localNodeId.rangeOfCharacter(from: .controlCharacters) == nil,
              ownFingerprint.utf8.count == 64, ownFingerprint.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
            throw StateCodecError.invalidContext
        }
        account = localNodeId + "|" + ownFingerprint
    }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.meshgram.ios.friendstate.key-v1",
         kSecAttrAccount as String: account, kSecAttrSynchronizable as String: false]
    }
    public func read() throws -> SymmetricKey? {
        var request = query
        request[kSecReturnData as String] = true; request[kSecReturnAttributes as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        request[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        guard let item = result as? [String: Any], let data = item[kSecValueData as String] as? Data, data.count == 32,
              item[kSecAttrAccount as String] as? String == account,
              item[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String else {
            throw FriendStoreError.invalidKey
        }
        return SymmetricKey(data: data)
    }
    public func createIfAbsent(_ key: SymmetricKey) throws {
        guard key.bitCount == 256 else { throw FriendStoreError.invalidKey }
        var insert = query
        insert[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(insert as CFDictionary, nil)
        if status == errSecDuplicateItem { throw FriendStoreError.alreadyExists }
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}

// Use one instance in the private app container. Ancestors must be trusted, not user-supplied.
public final class FileFriendStateBlob: FriendStateBlobStorage {
    private let directory: URL
    private let lock = NSLock()
    private let filename = "friends-v1.bin"
    public init(directory: URL) throws {
        guard directory.isFileURL, !directory.path.utf8.contains(0) else { throw FriendStoreError.invalidStorage }
        self.directory = directory.standardizedFileURL
    }
    private func posixError() -> NSError { NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    private func root(create: Bool) throws -> Int32? {
        var fd = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        if fd < 0 && errno == ENOENT && create {
            guard mkdir(directory.path, 0o700) == 0 || errno == EEXIST else { throw posixError() }
            fd = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        }
        if fd < 0 && errno == ENOENT && !create { return nil }
        guard fd >= 0 else { throw posixError() }
        do {
            var info = stat()
            guard fstat(fd, &info) == 0 else { throw posixError() }
            guard (info.st_mode & mode_t(S_IFMT)) == mode_t(S_IFDIR) else { throw FriendStoreError.invalidStorage }
            if create { guard fchmod(fd, 0o700) == 0 else { throw posixError() } }
            else { guard (info.st_mode & 0o077) == 0 else { throw FriendStoreError.invalidStorage } }
            return fd
        } catch { Darwin.close(fd); throw error }
    }
    private func readAt(_ root: Int32) throws -> Data? {
        let fd = openat(root, filename, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK)
        if fd < 0 && errno == ENOENT { return nil }
        guard fd >= 0 else { throw posixError() }
        defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0 else { throw posixError() }
        guard (info.st_mode & mode_t(S_IFMT)) == mode_t(S_IFREG), info.st_nlink == 1,
              info.st_size >= 0, info.st_size <= EncryptedFriendState.maximumBlobBytes else { throw FriendStoreError.invalidStorage }
        var result = Data(), buffer = [UInt8](repeating: 0, count: 65_536)
        while true {
            let count = buffer.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress!, $0.count) }
            if count < 0 && errno == EINTR { continue }
            guard count >= 0 else { throw posixError() }
            if count == 0 { return result }
            guard result.count <= EncryptedFriendState.maximumBlobBytes - count else { throw StateCodecError.capacity }
            result.append(contentsOf: buffer.prefix(count))
        }
    }
    public func read() throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard let fd = try root(create: false) else { return nil }
        defer { Darwin.close(fd) }
        return try readAt(fd)
    }
    public func commit(new blob: Data, expected: Data?) throws {
        lock.lock(); defer { lock.unlock() }
        guard !blob.isEmpty, blob.count <= EncryptedFriendState.maximumBlobBytes else { throw StateCodecError.capacity }
        guard let directoryFD = try root(create: true) else { throw FriendStoreError.invalidStorage }
        defer { Darwin.close(directoryFD) }
        guard try readAt(directoryFD) == expected else { throw FriendStoreError.conflict }
        var url = directory, values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], atPath: directory.path)
        #endif
        let temporary = ".state-\(UUID().uuidString.lowercased()).tmp"
        let fd = openat(directoryFD, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode_t(0o600))
        guard fd >= 0 else { throw posixError() }
        defer { Darwin.close(fd); unlinkat(directoryFD, temporary, 0) }
        guard fchmod(fd, 0o600) == 0 else { throw posixError() }
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], atPath: directory.appendingPathComponent(temporary).path)
        #endif
        try blob.withUnsafeBytes { bytes in
            var offset = 0
            while offset < bytes.count {
                let count = Darwin.write(fd, bytes.baseAddress!.advanced(by: offset), bytes.count - offset)
                if count < 0 && errno == EINTR { continue }
                guard count > 0 else { throw posixError() }
                offset += count
            }
        }
        guard fsync(fd) == 0 else { throw posixError() }
        guard try readAt(directoryFD) == expected else { throw FriendStoreError.conflict }
        guard renameat(directoryFD, temporary, directoryFD, filename) == 0 else { throw posixError() }
        // A post-rename failure invalidates the repository; never delete/roll back the saved blob.
        guard fsync(directoryFD) == 0 else { throw posixError() }
    }
}
