import CryptoKit
import Dispatch
import Foundation
import XCTest
@testable import MeshGramCore

final class DurableConsentTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private let fp = String(repeating: "a", count: 64)
    private let requestId = "request-1234567890"
    private func repository(_ keys: TestStateKeys, _ blobs: TestStateBlobs) -> FriendStateRepository {
        FriendStateRepository(keys: keys, blobs: blobs, localNodeId: "local", ownFingerprint: fp)
    }
    private func peer(_ node: String = "peer") -> ConfirmedContact {
        let identity = LocalIdentity(nodeId: node)
        return ConfirmedContact(version: 1, nodeId: node,
            encryptionPublicKey: identity.encryptionKey.publicKey.derRepresentation.base64EncodedString(),
            signingPublicKey: identity.signingKey.publicKey.derRepresentation.base64EncodedString(),
            fingerprint: identity.publicFingerprint, confirmedAtMs: now)
    }
    private func incoming(_ contact: ConfirmedContact, action: FriendPacket.Action = .request) throws -> AuthenticatedPayload {
        let packet = try FriendPacket(action: action, requestId: requestId, alias: "Peer")
        return AuthenticatedPayload(senderNodeId: contact.nodeId, fingerprint: contact.fingerprint,
            createdAtMs: now, hops: 0, data: try JSONEncoder().encode(packet))
    }
    private func initialized() throws -> (FriendStateRepository, TestStateKeys, TestStateBlobs) {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs)
        try repo.initializeNew()
        return (repo, keys, blobs)
    }
    func testEveryCommandBeforeExplicitLoadOrInitializationDoesNotCreateData() throws {
        let keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(keys, blobs), contact = peer()
        XCTAssertThrowsError(try repo.setDiscoverable(true))
        XCTAssertThrowsError(try repo.issueInvite(now: now))
        XCTAssertThrowsError(try repo.revokeInvite())
        XCTAssertThrowsError(try repo.receive(incoming(contact), nearby: true, now: now))
        XCTAssertThrowsError(try repo.request(peer: contact, peerAlias: "Peer", peerDiscoverable: true, nearby: true, now: now))
        XCTAssertThrowsError(try repo.accept(peer: contact, now: now))
        XCTAssertThrowsError(try repo.block(contact.nodeId))
        XCTAssertNil(repo.state); XCTAssertNil(keys.value); XCTAssertNil(blobs.value)
        XCTAssertEqual(keys.creations, 0); XCTAssertEqual(blobs.writes, 0)
    }
    func testSettingsAndInviteCommitBeforeReturnAndRestoreOnFreshRepository() throws {
        let (repo, keys, blobs) = try initialized()
        var returned = false
        blobs.afterCommit = { XCTAssertFalse(returned) }
        let visible = try repo.setDiscoverable(true)
        returned = true; blobs.afterCommit = nil
        XCTAssertEqual(visible.outcome, .changed)
        XCTAssertEqual(try repository(keys, blobs).load(), visible.state)
        let invited = try repo.issueInvite(token: "invite-token", now: now)
        XCTAssertEqual(try repository(keys, blobs).load(), invited.state)
        XCTAssertEqual(try repo.revokeInvite().state.inviteToken, "")
        XCTAssertEqual(keys.creations, 1); XCTAssertEqual(blobs.writes, 4)
    }
    func testRejectedIgnoredAndInvalidCommandsDoNotWriteOrInvalidateValidState() throws {
        let (repo, keys, blobs) = try initialized(), contact = peer()
        let old = try XCTUnwrap(repo.state), ciphertext = blobs.value
        XCTAssertEqual(try repo.receive(incoming(contact), nearby: true, now: now).outcome, .rejected)
        XCTAssertEqual(try repo.setDiscoverable(false).outcome, .ignored)
        XCTAssertEqual(try repo.revokeInvite().outcome, .ignored)
        XCTAssertEqual(try repo.block(contact.nodeId).outcome, .ignored)
        XCTAssertEqual(try repo.accept(peer: contact, now: now).outcome, .rejected)
        XCTAssertThrowsError(try repo.issueInvite(token: "", now: now))
        let malformed = AuthenticatedPayload(senderNodeId: contact.nodeId, fingerprint: contact.fingerprint,
            createdAtMs: now, hops: 0, data: Data([0]))
        XCTAssertThrowsError(try repo.receive(malformed, nearby: true, now: now))
        XCTAssertEqual(repo.state, old); XCTAssertEqual(blobs.value, ciphertext)
        XCTAssertEqual(blobs.writes, 1); XCTAssertEqual(keys.creations, 1)
    }
    func testOutgoingRequestAndMatchingAcceptArePersistedNotJustPublished() throws {
        let (repo, keys, blobs) = try initialized(), contact = peer()
        _ = try repo.setDiscoverable(true)
        let requested = try repo.request(peer: contact, peerAlias: "Peer", peerDiscoverable: true,
            nearby: true, requestId: requestId, now: now)
        XCTAssertEqual(requested.state.records.first?.outgoingId, requestId)
        XCTAssertFalse(requested.state.isFriend(contact.nodeId, fingerprint: contact.fingerprint))
        XCTAssertEqual(try repository(keys, blobs).load(), requested.state)
        let accepted = try repo.receive(incoming(contact, action: .accept), nearby: false, now: now)
        XCTAssertTrue(accepted.state.isFriend(contact.nodeId, fingerprint: contact.fingerprint))
        XCTAssertEqual(try repository(keys, blobs).load(), accepted.state)
        XCTAssertEqual(keys.creations, 1); XCTAssertEqual(blobs.writes, 4)
    }
    func testIncomingRequestRequiresSeparateOwnerAcceptAndBlockPersists() throws {
        let (repo, keys, blobs) = try initialized(), contact = peer()
        _ = try repo.setDiscoverable(true)
        let pending = try repo.receive(incoming(contact), nearby: true, now: now)
        XCTAssertFalse(pending.state.isFriend(contact.nodeId, fingerprint: contact.fingerprint))
        XCTAssertEqual(try repository(keys, blobs).load(), pending.state)
        let accepted = try repo.accept(peer: contact, now: now)
        XCTAssertTrue(accepted.state.isFriend(contact.nodeId, fingerprint: contact.fingerprint))
        XCTAssertEqual(try repository(keys, blobs).load(), accepted.state)
        let blocked = try repo.block(contact.nodeId), writes = blobs.writes
        XCTAssertTrue(blocked.state.records[0].blocked)
        XCTAssertFalse(blocked.state.isFriend(contact.nodeId, fingerprint: contact.fingerprint))
        XCTAssertEqual(try repository(keys, blobs).load(), blocked.state)
        XCTAssertEqual(try repo.block(contact.nodeId).outcome, .ignored)
        XCTAssertEqual(try repo.receive(incoming(contact), nearby: true, now: now).outcome, .rejected)
        XCTAssertEqual(blobs.writes, writes); XCTAssertEqual(keys.creations, 1)
    }
    func testRepositoryBoundNodeRejectsSelfRequestAndRepeatedRequestDoesNotWrite() throws {
        let (repo, _, blobs) = try initialized()
        _ = try repo.setDiscoverable(true)
        XCTAssertEqual(try repo.request(peer: peer("local"), peerAlias: "Self", peerDiscoverable: true,
            nearby: true, requestId: requestId, now: now).outcome, .rejected)
        let contact = peer()
        _ = try repo.request(peer: contact, peerAlias: "Peer", peerDiscoverable: true, nearby: true, requestId: requestId, now: now)
        let writes = blobs.writes
        XCTAssertEqual(try repo.request(peer: contact, peerAlias: "Peer", peerDiscoverable: true,
            nearby: true, requestId: requestId, now: now).outcome, .ignored)
        XCTAssertEqual(blobs.writes, writes)
    }
    func testFailBeforeCommitReturnsNoTransitionAndKeepsOldCiphertext() throws {
        let (repo, keys, blobs) = try initialized(), old = try XCTUnwrap(repo.state), ciphertext = blobs.value
        blobs.failBefore = true
        XCTAssertThrowsError(try repo.setDiscoverable(true))
        XCTAssertNil(repo.state); XCTAssertEqual(blobs.value, ciphertext); XCTAssertEqual(blobs.writes, 1)
        XCTAssertThrowsError(try repo.issueInvite(now: now))
        blobs.failBefore = false
        XCTAssertEqual(try repo.load(), old)
        XCTAssertEqual(try repository(keys, blobs).load(), old)
        XCTAssertEqual(keys.creations, 1)
    }
    func testFailAfterCommitRequiresReloadWithoutRollbackOrSuccessResult() throws {
        let (repo, keys, blobs) = try initialized()
        blobs.failAfter = true
        XCTAssertThrowsError(try repo.setDiscoverable(true)); XCTAssertNil(repo.state)
        XCTAssertEqual(blobs.writes, 2); XCTAssertNotNil(blobs.value)
        blobs.failAfter = false
        XCTAssertTrue(try repository(keys, blobs).load().discoverable)
        XCTAssertThrowsError(try repo.revokeInvite())
        XCTAssertTrue(try repo.load().discoverable); XCTAssertEqual(keys.creations, 1)
    }
    func testReadbackFailureDoesNotReturnChangedState() throws {
        let (repo, keys, blobs) = try initialized()
        blobs.failReadAt = blobs.reads + 2
        XCTAssertThrowsError(try repo.setDiscoverable(true)); XCTAssertNil(repo.state)
        XCTAssertEqual(blobs.writes, 2)
        blobs.failReadAt = nil
        XCTAssertTrue(try repository(keys, blobs).load().discoverable)
        XCTAssertEqual(keys.creations, 1)
    }
    func testCorruptReadbackOrRotatedKeyDoesNotPublishTransition() throws {
        for corrupt in [true, false] {
            let (repo, keys, blobs) = try initialized()
            blobs.afterCommit = {
                if corrupt { blobs.value = Data(repeating: 0, count: 33) }
                else { keys.value = SymmetricKey(size: .bits256) }
            }
            XCTAssertThrowsError(try repo.setDiscoverable(true)); XCTAssertNil(repo.state)
            XCTAssertEqual(blobs.writes, 2); XCTAssertEqual(keys.creations, 1)
            XCTAssertThrowsError(try repository(keys, blobs).load())
        }
    }
    func testExternalBaselineOrKeyChangeCannotBeOverwrittenByCommand() throws {
        for rotateKey in [true, false] {
            let (repo, keys, blobs) = try initialized(), old = try XCTUnwrap(repo.state)
            if rotateKey { keys.value = SymmetricKey(size: .bits256) }
            else { blobs.value = try EncryptedFriendState.seal(old.settingDiscoverable(true),
                key: XCTUnwrap(keys.value), localNodeId: "local", ownFingerprint: fp) }
            let ciphertext = blobs.value
            XCTAssertThrowsError(try repo.issueInvite(token: "new-invite", now: now))
            XCTAssertNil(repo.state); XCTAssertEqual(blobs.value, ciphertext)
            XCTAssertEqual(blobs.writes, 1); XCTAssertEqual(keys.creations, 1)
            if rotateKey { XCTAssertThrowsError(try repository(keys, blobs).load()) }
            else { XCTAssertTrue(try repository(keys, blobs).load().discoverable) }
        }
    }
    func testConcurrentIndependentIntentsBothSurviveWithoutLostUpdate() throws {
        let (repo, keys, blobs) = try initialized(), errors = NSMutableArray(), errorLock = NSLock()
        blobs.afterCommit = { Thread.sleep(forTimeInterval: 0.01) }
        DispatchQueue.concurrentPerform(iterations: 2) { index in
            do {
                if index == 0 { _ = try repo.setDiscoverable(true) }
                else { _ = try repo.issueInvite(token: "parallel-invite", now: now) }
            } catch { errorLock.lock(); errors.add(error); errorLock.unlock() }
        }
        XCTAssertEqual(errors.count, 0)
        let restored = try repository(keys, blobs).load()
        XCTAssertTrue(restored.discoverable); XCTAssertEqual(restored.inviteToken, "parallel-invite")
        XCTAssertEqual(repo.state, restored); XCTAssertEqual(blobs.writes, 3); XCTAssertEqual(keys.creations, 1)
    }
}
