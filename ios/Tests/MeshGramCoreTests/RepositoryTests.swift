import CryptoKit
import Dispatch
import Foundation
import XCTest
@testable import MeshGramCore

enum TestStoreFailure: Error { case injected }
final class TestStateKeys: FriendStateKeyStorage {
    var value: SymmetricKey?
    var failRead = false, failCreate = false, dropCreated = false
    var creations = 0
    func read() throws -> SymmetricKey? { if failRead { throw TestStoreFailure.injected }; return value }
    func createIfAbsent(_ key: SymmetricKey) throws {
        guard !failCreate else { throw TestStoreFailure.injected }
        guard value == nil else { throw FriendStoreError.alreadyExists }
        creations += 1
        if !dropCreated { value = key }
    }
}
final class TestStateBlobs: FriendStateBlobStorage {
    var value: Data?
    var reads = 0, writes = 0
    var failReadAt: Int?, failBefore = false, failAfter = false
    var afterCommit: (() -> Void)?
    func read() throws -> Data? {
        reads += 1
        if failReadAt == reads { throw TestStoreFailure.injected }
        return value
    }
    func commit(new: Data, expected: Data?) throws {
        guard !failBefore else { throw TestStoreFailure.injected }
        guard value == expected else { throw FriendStoreError.conflict }
        value = new; writes += 1; afterCommit?()
        if failAfter { throw TestStoreFailure.injected }
    }
}

final class RepositoryTests: XCTestCase {
    private let fp = String(repeating: "a", count: 64)
    private func repository(_ keys: TestStateKeys, _ blobs: TestStateBlobs) -> FriendStateRepository {
        FriendStateRepository(keys: keys, blobs: blobs, localNodeId: "local", ownFingerprint: fp)
    }
    func testLoadAbsentDoesNotInitializeAnything() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        XCTAssertThrowsError(try repo.load())
        XCTAssertNil(repo.state); XCTAssertNil(keys.value); XCTAssertNil(blobs.value)
        XCTAssertEqual(keys.creations, 0); XCTAssertEqual(blobs.writes, 0)
        XCTAssertThrowsError(try repo.save(FriendState(), basedOn: FriendState()))
    }
    func testExplicitInitializationPersistsReadbackAndRestartRestores() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        let empty = try repo.initializeNew()
        XCTAssertFalse(empty.discoverable); XCTAssertEqual(repo.state, empty)
        let changed = empty.settingDiscoverable(true)
        XCTAssertEqual(try repo.save(changed, basedOn: empty), changed)
        XCTAssertEqual(try repository(keys, blobs).load(), changed)
        XCTAssertEqual(keys.creations, 1); XCTAssertEqual(blobs.writes, 2)
    }
    func testRepeatedInitializeNeverReplacesKeyOrCiphertext() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        try repo.initializeNew()
        let key = keys.value, blob = blobs.value
        XCTAssertThrowsError(try repo.initializeNew())
        XCTAssertEqual(keys.value, key); XCTAssertEqual(blobs.value, blob)
        XCTAssertEqual(keys.creations, 1); XCTAssertEqual(blobs.writes, 1)
        XCTAssertNil(repo.state)
        XCTAssertEqual(try repo.load(), try FriendState())
    }
    func testKeyOnlyAndBlobOnlyNeverGenerateReplacement() throws {
        for keyOnly in [true, false] {
            let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
            if keyOnly { keys.value = SymmetricKey(size: .bits256) } else { blobs.value = Data([1, 2, 3]) }
            let key = keys.value, blob = blobs.value
            XCTAssertThrowsError(try repo.load()); XCTAssertThrowsError(try repo.initializeNew())
            XCTAssertEqual(keys.value, key); XCTAssertEqual(blobs.value, blob)
            XCTAssertEqual(keys.creations, 0); XCTAssertEqual(blobs.writes, 0); XCTAssertNil(repo.state)
        }
    }
    func testReadErrorsNeverBecomeAbsenceOrFreshState() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        keys.failRead = true
        XCTAssertThrowsError(try repo.load()); XCTAssertThrowsError(try repo.initializeNew())
        keys.failRead = false; blobs.failReadAt = blobs.reads + 1
        XCTAssertThrowsError(try repo.initializeNew())
        XCTAssertEqual(keys.creations, 0); XCTAssertEqual(blobs.writes, 0)
    }
    func testFailedKeyCreateAndFailedKeyReadbackDoNotWriteBlob() throws {
        for failCreate in [true, false] {
            let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
            keys.failCreate = failCreate; keys.dropCreated = !failCreate
            XCTAssertThrowsError(try repo.initializeNew())
            XCTAssertNil(repo.state); XCTAssertNil(blobs.value); XCTAssertEqual(blobs.writes, 0)
        }
    }
    func testInitializationWriteFailureLeavesOrphanAndDoesNotAutoRepair() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        blobs.failBefore = true
        XCTAssertThrowsError(try repo.initializeNew())
        XCTAssertNotNil(keys.value); XCTAssertNil(blobs.value); XCTAssertNil(repo.state)
        blobs.failBefore = false
        XCTAssertThrowsError(try repo.load()); XCTAssertThrowsError(try repo.initializeNew())
        XCTAssertEqual(keys.creations, 1); XCTAssertEqual(blobs.writes, 0)
    }
    func testWriteFailureInvalidatesUntilReloadWithoutDestroyingOldState() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        let old = try repo.initializeNew(), originalBlob = blobs.value
        blobs.failBefore = true
        XCTAssertThrowsError(try repo.save(old.settingDiscoverable(true), basedOn: old))
        XCTAssertNil(repo.state); XCTAssertEqual(blobs.value, originalBlob)
        blobs.failBefore = false
        XCTAssertThrowsError(try repo.save(old, basedOn: old))
        XCTAssertEqual(try repo.load(), old)
        XCTAssertEqual(try repo.save(old.settingDiscoverable(true), basedOn: old).discoverable, true)
    }
    func testFailureAfterCommitRetainsNewBlobAndRequiresReload() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        let old = try repo.initializeNew(), changed = old.settingDiscoverable(true)
        blobs.failAfter = true
        XCTAssertThrowsError(try repo.save(changed, basedOn: old)); XCTAssertNil(repo.state)
        blobs.failAfter = false
        XCTAssertEqual(try repo.load(), changed)
        XCTAssertEqual(keys.creations, 1)
    }
    func testReadbackReadFailureDoesNotPublishUnverifiedState() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        let old = try repo.initializeNew(), changed = old.settingDiscoverable(true)
        blobs.failReadAt = blobs.reads + 2
        XCTAssertThrowsError(try repo.save(changed, basedOn: old)); XCTAssertNil(repo.state)
        XCTAssertThrowsError(try repo.save(old, basedOn: old))
        blobs.failReadAt = nil
        XCTAssertEqual(try repo.load(), changed)
    }
    func testChangedKeyAndChangedBaselineAreRejected() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        let old = try repo.initializeNew(), key = try XCTUnwrap(keys.value), original = blobs.value
        keys.value = SymmetricKey(size: .bits256)
        XCTAssertThrowsError(try repo.save(old, basedOn: old)); XCTAssertNil(repo.state); XCTAssertEqual(blobs.value, original)
        keys.value = key; _ = try repo.load()
        let external = old.settingDiscoverable(true)
        blobs.value = try EncryptedFriendState.seal(external, key: key, localNodeId: "local", ownFingerprint: fp)
        let externalBlob = blobs.value
        XCTAssertThrowsError(try repo.save(old, basedOn: old)); XCTAssertEqual(blobs.value, externalBlob); XCTAssertNil(repo.state)
        XCTAssertEqual(try repo.load(), external)
    }
    func testCorruptReadbackAndKeyChangeAfterCommitInvalidate() throws {
        for corruptBlob in [true, false] {
            let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
            let old = try repo.initializeNew()
            blobs.afterCommit = {
                if corruptBlob { blobs.value = Data(repeating: 0, count: 33) }
                else { keys.value = SymmetricKey(size: .bits256) }
            }
            XCTAssertThrowsError(try repo.save(old.settingDiscoverable(true), basedOn: old))
            XCTAssertNil(repo.state); XCTAssertThrowsError(try repo.load()); XCTAssertEqual(keys.creations, 1)
        }
    }
    func testCorruptOrWrongSizeKeyCannotLoadAndNoAutoRecovery() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        try repo.initializeNew()
        keys.value = SymmetricKey(size: .bits128)
        XCTAssertThrowsError(try repo.load()); XCTAssertNil(repo.state)
        keys.value = SymmetricKey(size: .bits256); blobs.value = Data([1])
        XCTAssertThrowsError(try repo.load()); XCTAssertThrowsError(try repo.initializeNew())
        XCTAssertEqual(keys.creations, 1)
    }
    func testOversizedSaveInvalidatesButKeepsExistingCiphertext() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        try repo.initializeNew()
        let original = blobs.value
        let state = try FriendState(records: (0..<50).map { try FriendRecord(nodeId: "p\($0)", fingerprint: fp,
            alias: "Друг", avatarData: String(repeating: "a", count: 24_000)) })
        XCTAssertThrowsError(try repo.save(state, basedOn: FriendState())); XCTAssertNil(repo.state); XCTAssertEqual(blobs.value, original)
        XCTAssertEqual(try repo.load(), try FriendState())
    }
    func testConcurrentStaleSnapshotsCannotOverwriteWinner() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        let initial = try repo.initializeNew(), errors = NSMutableArray(), errorLock = NSLock()
        blobs.afterCommit = { Thread.sleep(forTimeInterval: 0.01) }
        DispatchQueue.concurrentPerform(iterations: 2) { index in
            do { try repo.save(initial.issuingInvite(token: "concurrent-invite-\(index)", now: 1_800_000_000_000), basedOn: initial) }
            catch { errorLock.lock(); errors.add(error); errorLock.unlock() }
        }
        XCTAssertEqual(errors.count, 1); XCTAssertEqual(blobs.writes, 2)
        let winner = try repo.load()
        XCTAssertTrue(winner.inviteToken.hasPrefix("concurrent-invite-"))
        XCTAssertEqual(try repository(keys, blobs).load(), winner)
        XCTAssertEqual(keys.creations, 1)
    }
}
