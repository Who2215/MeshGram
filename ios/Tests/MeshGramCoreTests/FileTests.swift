import CryptoKit
import Darwin
import Foundation
import XCTest
@testable import MeshGramCore

final class FileTests: XCTestCase {
    private let fp = String(repeating: "a", count: 64)
    private func temporary(_ body: (URL) throws -> Void) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("meshgram-state-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        try body(root)
    }
    private func root(_ parent: URL) throws -> URL {
        let url = parent.appendingPathComponent("state")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        return url
    }
    private func blob(_ discoverable: Bool = false) throws -> Data {
        try EncryptedFriendState.seal(FriendState(discoverable: discoverable), key: SymmetricKey(size: .bits256), localNodeId: "local", ownFingerprint: fp)
    }
    func testMissingReadDoesNotCreateDirectory() throws {
        try temporary { parent in
            let directory = parent.appendingPathComponent("state"), store = try FileFriendStateBlob(directory: directory)
            XCTAssertNil(try store.read()); XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        }
    }
    func testRealInitializeSaveAndNewRepositoryRestore() throws {
        try temporary { parent in
            let directory = parent.appendingPathComponent("state"), store = try FileFriendStateBlob(directory: directory), keys = TestStateKeys()
            let repo = FriendStateRepository(keys: keys, blobs: store, localNodeId: "local", ownFingerprint: fp)
            let empty = try repo.initializeNew(), state = empty.settingDiscoverable(true)
            XCTAssertEqual(try repo.save(state, basedOn: empty), state)
            let next = FriendStateRepository(keys: keys, blobs: try FileFriendStateBlob(directory: directory), localNodeId: "local", ownFingerprint: fp)
            XCTAssertEqual(try next.load(), state)
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["friends-v1.bin"])
        }
    }
    func testExpectedBaselineConflictsNeverOverwriteOrLeaveTemporaryFiles() throws {
        try temporary { parent in
            let directory = parent.appendingPathComponent("state"), store = try FileFriendStateBlob(directory: directory)
            let first = try blob(), second = try blob(true)
            try store.commit(new: first, expected: nil)
            XCTAssertThrowsError(try store.commit(new: second, expected: nil))
            XCTAssertEqual(try store.read(), first)
            try store.commit(new: second, expected: first)
            XCTAssertThrowsError(try store.commit(new: first, expected: first))
            XCTAssertEqual(try store.read(), second)
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["friends-v1.bin"])
        }
    }
    func testPrivatePermissionsBackupExclusionAndSupportedProtection() throws {
        try temporary { parent in
            let directory = parent.appendingPathComponent("state"), file = directory.appendingPathComponent("friends-v1.bin")
            try FileFriendStateBlob(directory: directory).commit(new: blob(), expected: nil)
            let attrs = try FileManager.default.attributesOfItem(atPath: directory.path)
            let fileAttrs = try FileManager.default.attributesOfItem(atPath: file.path)
            XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o700)
            XCTAssertEqual((fileAttrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
            XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
            #if os(iOS)
            XCTAssertEqual(fileAttrs[.protectionKey] as? FileProtectionType, .complete)
            #endif
        }
    }
    func testSymbolicTargetDoesNotReadOrOverwriteDestination() throws {
        try temporary { parent in
            let directory = try root(parent), target = directory.appendingPathComponent("friends-v1.bin"), outside = parent.appendingPathComponent("outside")
            let original = try blob()
            try original.write(to: outside)
            try FileManager.default.createSymbolicLink(at: target, withDestinationURL: outside)
            let store = try FileFriendStateBlob(directory: directory)
            XCTAssertThrowsError(try store.read()); XCTAssertThrowsError(try store.commit(new: blob(true), expected: nil))
            XCTAssertEqual(try Data(contentsOf: outside), original)
        }
    }
    func testSymbolicRootIsRejectedWithoutChangingItsTarget() throws {
        try temporary { parent in
            let directory = try root(parent), alias = parent.appendingPathComponent("alias"), original = try blob()
            try original.write(to: directory.appendingPathComponent("friends-v1.bin"))
            try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: directory)
            let store = try FileFriendStateBlob(directory: alias)
            XCTAssertThrowsError(try store.read()); XCTAssertThrowsError(try store.commit(new: blob(true), expected: original))
            XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("friends-v1.bin")), original)
        }
    }
    func testHardLinkedTargetIsRejectedWithoutChangingEitherName() throws {
        try temporary { parent in
            let directory = try root(parent), outside = parent.appendingPathComponent("outside"), original = try blob()
            let target = directory.appendingPathComponent("friends-v1.bin")
            try original.write(to: outside)
            XCTAssertEqual(link(outside.path, target.path), 0)
            let store = try FileFriendStateBlob(directory: directory)
            XCTAssertThrowsError(try store.read()); XCTAssertThrowsError(try store.commit(new: blob(true), expected: nil))
            XCTAssertEqual(try Data(contentsOf: outside), original)
        }
    }
    func testNonRegularTargetsAndUnsafeDirectoryAreRejectedWithoutBlocking() throws {
        try temporary { parent in
            let directory = try root(parent), file = directory.appendingPathComponent("friends-v1.bin")
            XCTAssertEqual(mkfifo(file.path, 0o600), 0)
            let store = try FileFriendStateBlob(directory: directory)
            XCTAssertThrowsError(try store.read()); XCTAssertThrowsError(try store.commit(new: blob(), expected: nil))
            try FileManager.default.removeItem(at: file)
            try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
            XCTAssertThrowsError(try store.read())
            try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.path)
            XCTAssertThrowsError(try store.read())
        }
    }
    func testOversizedActualFileAndWritesAreRejected() throws {
        try temporary { parent in
            let directory = try root(parent), file = directory.appendingPathComponent("friends-v1.bin")
            XCTAssertTrue(FileManager.default.createFile(atPath: file.path, contents: Data()))
            let handle = try FileHandle(forWritingTo: file)
            try handle.truncate(atOffset: UInt64(EncryptedFriendState.maximumBlobBytes + 1)); try handle.close()
            let store = try FileFriendStateBlob(directory: directory)
            XCTAssertThrowsError(try store.read()); XCTAssertThrowsError(try store.commit(new: blob(), expected: nil))
            XCTAssertThrowsError(try store.commit(new: Data(repeating: 0, count: EncryptedFriendState.maximumBlobBytes + 1), expected: nil))
        }
    }
    func testInvalidKeychainContextRejectedWithoutAccessingKeychain() throws {
        for node in ["", " local", "a|b", "a\nb"] { XCTAssertThrowsError(try KeychainFriendStateKey(localNodeId: node, ownFingerprint: fp)) }
        for value in ["", fp + "\n", String(repeating: "A", count: 64)] { XCTAssertThrowsError(try KeychainFriendStateKey(localNodeId: "local", ownFingerprint: value)) }
        XCTAssertThrowsError(try FileFriendStateBlob(directory: XCTUnwrap(URL(string: "https://example.invalid/state"))))
    }
}
