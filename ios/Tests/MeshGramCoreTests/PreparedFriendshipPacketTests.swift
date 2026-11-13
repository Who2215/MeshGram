import CryptoKit
import Dispatch
import Foundation
import XCTest
@testable import MeshGramCore

final class PreparedFriendshipPacketTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private let requestId = "wire-request-1234567890"
    private let alias = "private-own-profile"
    private struct Fixture {
        let owner: LocalIdentity, remote: LocalIdentity
        let keys: TestStateKeys, blobs: TestStateBlobs, repo: FriendStateRepository
        let hello: HelloPacket, pins: ContactBook
    }
    private func pin(_ identity: LocalIdentity) -> ConfirmedContact {
        ConfirmedContact(version: 1, nodeId: identity.nodeId,
            encryptionPublicKey: identity.encryptionKey.publicKey.derRepresentation.base64EncodedString(),
            signingPublicKey: identity.signingKey.publicKey.derRepresentation.base64EncodedString(),
            fingerprint: identity.publicFingerprint, confirmedAtMs: now)
    }
    private func fixture(intent: Bool = true) throws -> Fixture {
        let owner = LocalIdentity(nodeId: "owner"), remote = LocalIdentity(nodeId: "peer")
        let keys = TestStateKeys(), blobs = TestStateBlobs()
        let repo = FriendStateRepository(keys: keys, blobs: blobs, localNodeId: owner.nodeId, ownFingerprint: owner.publicFingerprint)
        try repo.initializeNew()
        if intent {
            _ = try repo.request(peer: pin(remote), peerAlias: "Peer", peerDiscoverable: false, nearby: false,
                inviteToken: "peer-invite", requestId: requestId, now: now)
        }
        return try Fixture(owner: owner, remote: remote, keys: keys, blobs: blobs, repo: repo,
                       hello: PacketCrypto.makeHello(identity: remote, createdAtMs: now), pins: ContactBook(contacts: [pin(remote)]))
    }
    private func prepare(_ f: Fixture, action: FriendPacket.Action = .request, time: Int64? = nil,
                         avatar: String = "") throws -> PreparedFriendshipPacket {
        try f.repo.prepareFriendship(action, identity: f.owner, recipient: f.hello, pins: f.pins,
                                     ownAlias: alias, ownAvatarData: avatar, now: time ?? now)
    }
    private func decode(_ prepared: PreparedFriendshipPacket, _ f: Fixture) throws -> FriendPacket {
        let packet = try JSONDecoder().decode(SecureMessagePacket.self, from: prepared.wireData)
        let clear = try PacketCrypto.decrypt(packet, identity: f.remote,
            trustedNodeId: f.owner.nodeId, trustedFingerprint: f.owner.publicFingerprint)
        return try JSONDecoder().decode(FriendPacket.self, from: clear)
    }
    private func incoming(_ f: Fixture) throws -> AuthenticatedPayload {
        let packet = try FriendPacket(action: .request, requestId: requestId, alias: "Peer", inviteToken: "local-invite")
        let encrypted = try PacketCrypto.encrypt(JSONEncoder().encode(packet), identity: f.remote,
            recipient: PacketCrypto.makeHello(identity: f.owner, createdAtMs: now),
            trustedNodeId: f.owner.nodeId, trustedFingerprint: f.owner.publicFingerprint, createdAtMs: now)
        return try AuthenticatedPayload.open(encrypted, identity: f.owner, pins: f.pins)
    }
    func testNoLoadedStateOrDurableIntentCannotProducePacketOrInitialize() throws {
        let f = try fixture(intent: false), untouched = FriendStateRepository(keys: TestStateKeys(), blobs: TestStateBlobs(),
            localNodeId: f.owner.nodeId, ownFingerprint: f.owner.publicFingerprint)
        XCTAssertThrowsError(try untouched.prepareFriendship(.request, identity: f.owner, recipient: f.hello,
            pins: f.pins, ownAlias: alias, now: now)); XCTAssertNil(untouched.state)
        XCTAssertThrowsError(try prepare(f)); XCTAssertEqual(f.keys.creations, 1); XCTAssertEqual(f.blobs.writes, 1)
    }
    func testRequestRoundTripsToActualRecipientAndFitsShortGatt() throws {
        let f = try fixture(), writes = f.blobs.writes, ciphertext = f.blobs.value
        let result = try prepare(f), payload = try decode(result, f)
        XCTAssertEqual(payload.action, .request); XCTAssertEqual(payload.requestId, requestId)
        XCTAssertEqual(payload.inviteToken, "peer-invite"); XCTAssertEqual(payload.alias, alias)
        XCTAssertEqual(result.peerNodeId, f.remote.nodeId); XCTAssertEqual(result.peerFingerprint, f.remote.publicFingerprint)
        XCTAssertEqual(result.requestId, requestId); XCTAssertEqual(result.createdAtMs, now)
        XCTAssertLessThanOrEqual(result.wireData.count, MeshWire.shortGattMaximumPacketBytes)
        let frames = try FrameCodec.packetize(result.wireData, frameKey: 42, chunkSize: 12)
        XCTAssertTrue(frames.allSatisfy { $0.count <= 20 }); XCTAssertLessThanOrEqual(frames.count, 255)
        XCTAssertFalse(String(decoding: result.wireData, as: UTF8.self).contains(alias))
        XCTAssertEqual(f.blobs.value, ciphertext); XCTAssertEqual(f.blobs.writes, writes); XCTAssertEqual(f.keys.creations, 1)
        XCTAssertEqual(try FriendStateRepository(keys: f.keys, blobs: f.blobs, localNodeId: f.owner.nodeId,
            ownFingerprint: f.owner.publicFingerprint).load(), f.repo.state)
    }
    func testAcceptNeedsExplicitDurableConsentAndDecryptsWithOriginalRequestId() throws {
        let f = try fixture(intent: false)
        _ = try f.repo.issueInvite(token: "local-invite", now: now)
        _ = try f.repo.receive(incoming(f), nearby: false, now: now)
        XCTAssertThrowsError(try prepare(f, action: .accept))
        _ = try f.repo.accept(peer: pin(f.remote), now: now)
        let writes = f.blobs.writes, result = try prepare(f, action: .accept), payload = try decode(result, f)
        XCTAssertEqual(payload.action, .accept); XCTAssertEqual(payload.requestId, requestId)
        XCTAssertEqual(payload.inviteToken, ""); XCTAssertEqual(payload.alias, alias)
        XCTAssertEqual(f.blobs.writes, writes); XCTAssertEqual(f.keys.creations, 1)
    }
    func testReprepareUsesNewFrameNonceAndCipherButSameLogicalIntentWithoutWrites() throws {
        let f = try fixture(), a = try prepare(f), b = try prepare(f)
        let first = try JSONDecoder().decode(SecureMessagePacket.self, from: a.wireData)
        let second = try JSONDecoder().decode(SecureMessagePacket.self, from: b.wireData)
        XCTAssertEqual(a.requestId, b.requestId); XCTAssertNotEqual(a.frameId, b.frameId)
        XCTAssertNotEqual(first.nonce, second.nonce); XCTAssertNotEqual(first.ephemeralPublicKey, second.ephemeralPublicKey)
        XCTAssertNotEqual(first.ciphertext, second.ciphertext); XCTAssertEqual(try decode(a, f), try decode(b, f))
        XCTAssertEqual(f.blobs.writes, 2); XCTAssertEqual(f.keys.creations, 1)
    }
    func testWrongOwnerNodeOrKeysCannotPrepareAndDoNotInvalidateValidSnapshot() throws {
        let f = try fixture(), original = f.repo.state
        for owner in [LocalIdentity(nodeId: "wrong"), LocalIdentity(nodeId: f.owner.nodeId)] {
            XCTAssertThrowsError(try f.repo.prepareFriendship(.request, identity: owner, recipient: f.hello,
                pins: f.pins, ownAlias: alias, now: now))
            XCTAssertEqual(f.repo.state, original)
        }
        XCTAssertEqual(f.blobs.writes, 2); XCTAssertEqual(f.keys.creations, 1)
    }
    func testMissingPinAndRotatedSignedHelloCannotReplaceTrust() throws {
        let f = try fixture(), rotated = LocalIdentity(nodeId: f.remote.nodeId)
        XCTAssertThrowsError(try f.repo.prepareFriendship(.request, identity: f.owner, recipient: f.hello,
            pins: ContactBook(), ownAlias: alias, now: now))
        XCTAssertThrowsError(try f.repo.prepareFriendship(.request, identity: f.owner,
            recipient: PacketCrypto.makeHello(identity: rotated, createdAtMs: now),
            pins: f.pins, ownAlias: alias, now: now))
        XCTAssertThrowsError(try f.repo.prepareFriendship(.request, identity: f.owner, recipient: f.hello,
            pins: ContactBook(contacts: [pin(rotated)]), ownAlias: alias, now: now))
        XCTAssertNotNil(f.repo.state); XCTAssertEqual(f.blobs.writes, 2)
    }
    func testForgedHelloSignatureCannotProduceEncryptedFallback() throws {
        let f = try fixture()
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(f.hello)) as? [String: Any])
        object["signature"] = Data(repeating: 0, count: 72).base64EncodedString()
        let forged = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try f.repo.prepareFriendship(.request, identity: f.owner, recipient: forged,
            pins: f.pins, ownAlias: alias, now: now))
        XCTAssertNotNil(f.repo.state); XCTAssertEqual(f.blobs.writes, 2)
    }
    func testBlockedAndAcceptedRecordsCannotPrepareNewRequest() throws {
        for blocked in [true, false] {
            let f = try fixture()
            if blocked { _ = try f.repo.block(f.remote.nodeId) }
            else {
                let payload = try FriendPacket(action: .accept, requestId: requestId, alias: "Peer")
                _ = try f.repo.receive(AuthenticatedPayload(senderNodeId: f.remote.nodeId, fingerprint: f.remote.publicFingerprint,
                    createdAtMs: now, hops: 0, data: JSONEncoder().encode(payload)), nearby: false, now: now)
            }
            let writes = f.blobs.writes
            XCTAssertThrowsError(try prepare(f)); XCTAssertEqual(f.blobs.writes, writes)
        }
    }
    func testAgeLimitsFutureToleranceAndOverflowFailClosed() throws {
        let f = try fixture()
        for time in [Int64.min, 0, Int64.max, now - 300_001, now + FriendState.requestLifetimeMs + 1] {
            XCTAssertThrowsError(try prepare(f, time: time))
        }
        XCTAssertNoThrow(try prepare(f, time: now - 300_000))
        XCTAssertNoThrow(try prepare(f, time: now + FriendState.requestLifetimeMs))
        XCTAssertEqual(f.blobs.writes, 2); XCTAssertEqual(f.keys.creations, 1)
    }
    func testLockedReadKeyRotationOrExternalCiphertextInvalidatesWithoutWrites() throws {
        for failure in 0..<4 {
            let f = try fixture(), key = try XCTUnwrap(f.keys.value), previous = try XCTUnwrap(f.repo.state)
            switch failure {
            case 0: f.keys.failRead = true
            case 1: f.blobs.failReadAt = f.blobs.reads + 1
            case 2: f.keys.value = SymmetricKey(size: .bits256)
            default: f.blobs.value = try EncryptedFriendState.seal(previous.settingDiscoverable(true), key: key,
                localNodeId: f.owner.nodeId, ownFingerprint: f.owner.publicFingerprint)
            }
            let ciphertext = f.blobs.value
            XCTAssertThrowsError(try prepare(f)); XCTAssertNil(f.repo.state)
            XCTAssertEqual(f.blobs.value, ciphertext); XCTAssertEqual(f.blobs.writes, 2); XCTAssertEqual(f.keys.creations, 1)
            XCTAssertThrowsError(try prepare(f))
        }
    }
    func testMissingStorageHalfDoesNotCreateReplacement() throws {
        for keyMissing in [true, false] {
            let f = try fixture()
            if keyMissing { f.keys.value = nil } else { f.blobs.value = nil }
            XCTAssertThrowsError(try prepare(f)); XCTAssertNil(f.repo.state)
            XCTAssertEqual(f.blobs.writes, 2); XCTAssertEqual(f.keys.creations, 1)
        }
    }
    func testOversizedProfileRejectsInsteadOfTruncationAndKeepsIntent() throws {
        let f = try fixture(), original = f.repo.state, ciphertext = f.blobs.value
        XCTAssertThrowsError(try prepare(f, avatar: String(repeating: "a", count: 4_000)))
        XCTAssertEqual(f.repo.state, original); XCTAssertEqual(f.blobs.value, ciphertext)
        XCTAssertNoThrow(try prepare(f)); XCTAssertEqual(f.blobs.writes, 2)
    }
    func testUnsupportedProfileMissingIncomingIdAndSelfTargetRejectWithoutMutation() throws {
        let f = try fixture()
        XCTAssertThrowsError(try prepare(f, action: .profile))
        XCTAssertThrowsError(try prepare(f, action: .accept))
        XCTAssertThrowsError(try f.repo.prepareFriendship(.request, identity: f.owner,
            recipient: PacketCrypto.makeHello(identity: f.owner, createdAtMs: now),
            pins: ContactBook(contacts: [pin(f.owner)]), ownAlias: alias, now: now))
        XCTAssertNotNil(f.repo.state); XCTAssertEqual(f.blobs.writes, 2)
    }
    func testPrepareWaitsForConcurrentBlockReadbackAndCannotEscapeBlockedState() throws {
        let f = try fixture(), committed = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let attempted = DispatchSemaphore(value: 0), group = DispatchGroup(), results = NSMutableArray(), resultLock = NSLock()
        f.blobs.afterCommit = { committed.signal(); _ = release.wait(timeout: .now() + 5) }
        group.enter()
        DispatchQueue.global().async {
            defer { group.leave() }
            do { _ = try f.repo.block(f.remote.nodeId); resultLock.lock(); results.add("blocked"); resultLock.unlock() }
            catch { resultLock.lock(); results.add("mutation-failed"); resultLock.unlock() }
        }
        XCTAssertEqual(committed.wait(timeout: .now() + 5), .success)
        group.enter()
        DispatchQueue.global().async {
            defer { group.leave() }
            attempted.signal()
            do { _ = try self.prepare(f); resultLock.lock(); results.add("unexpected-packet"); resultLock.unlock() }
            catch { resultLock.lock(); results.add("prepare-rejected"); resultLock.unlock() }
        }
        XCTAssertEqual(attempted.wait(timeout: .now() + 5), .success); release.signal()
        XCTAssertEqual(group.wait(timeout: .now() + 10), .success)
        XCTAssertEqual(Set(results.compactMap { $0 as? String }), Set(["blocked", "prepare-rejected"]))
        XCTAssertEqual(f.repo.state?.records.first?.blocked, true)
        XCTAssertEqual(f.blobs.writes, 3); XCTAssertEqual(f.keys.creations, 1)
    }
}
