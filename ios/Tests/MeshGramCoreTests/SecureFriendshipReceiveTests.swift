import CryptoKit
import Dispatch
import Foundation
import XCTest
@testable import MeshGramCore

final class SecureFriendshipReceiveTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private let requestId = "secure-request-1234567890"
    private struct Fixture {
        let owner: LocalIdentity, peer: LocalIdentity
        let keys: TestStateKeys, blobs: TestStateBlobs, repo: FriendStateRepository
        let pins: ContactBook
    }
    private func pin(_ identity: LocalIdentity) -> ConfirmedContact {
        ConfirmedContact(version: 1, nodeId: identity.nodeId,
            encryptionPublicKey: identity.encryptionKey.publicKey.derRepresentation.base64EncodedString(),
            signingPublicKey: identity.signingKey.publicKey.derRepresentation.base64EncodedString(),
            fingerprint: identity.publicFingerprint, confirmedAtMs: now)
    }
    private func fixture() throws -> Fixture {
        let owner = LocalIdentity(nodeId: "owner"), peer = LocalIdentity(nodeId: "peer")
        let keys = TestStateKeys(), blobs = TestStateBlobs()
        let repo = repository(owner, keys, blobs)
        try repo.initializeNew()
        return try Fixture(owner: owner, peer: peer, keys: keys, blobs: blobs, repo: repo, pins: ContactBook(contacts: [pin(peer)]))
    }
    private func repository(_ identity: LocalIdentity, _ keys: TestStateKeys, _ blobs: TestStateBlobs) -> FriendStateRepository {
        FriendStateRepository(keys: keys, blobs: blobs, localNodeId: identity.nodeId, ownFingerprint: identity.publicFingerprint)
    }
    private func encrypted(_ f: Fixture, action: FriendPacket.Action = .request, token: String = "",
                           sender: LocalIdentity? = nil, receiver: LocalIdentity? = nil, sent: Int64? = nil,
                           clear: Data? = nil, alias: String = "Peer", avatar: String = "") throws -> SecureMessagePacket {
        let from = sender ?? f.peer, to = receiver ?? f.owner
        let payload = try clear ?? JSONEncoder().encode(FriendPacket(action: action, requestId: requestId,
                                                                    alias: alias, avatarData: avatar, inviteToken: token))
        return try PacketCrypto.encrypt(payload, identity: from, recipient: PacketCrypto.makeHello(identity: to, createdAtMs: now),
            trustedNodeId: to.nodeId, trustedFingerprint: to.publicFingerprint, createdAtMs: sent ?? now)
    }
    private func receive(_ f: Fixture, _ packet: SecureMessagePacket, nearby: Bool = true, time: Int64? = nil) throws -> FriendState.Transition {
        try f.repo.receiveFriendship(packet, identity: f.owner, pins: f.pins, nearby: nearby, now: time ?? now)
    }
    private func changed(_ packet: SecureMessagePacket, field: String, value: Any,
                         resign: LocalIdentity? = nil) throws -> SecureMessagePacket {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(packet)) as? [String: Any])
        object[field] = value
        var result = try JSONDecoder().decode(SecureMessagePacket.self, from: JSONSerialization.data(withJSONObject: object))
        if let signer = resign {
            object["signature"] = try signer.signingKey.signature(for: result.signingBytes).derRepresentation.base64EncodedString()
            result = try JSONDecoder().decode(SecureMessagePacket.self, from: JSONSerialization.data(withJSONObject: object))
        }
        return result
    }
    func testAbsentOrUnloadedStorageNeverAutoInitializes() throws {
        let f = try fixture(), keys = TestStateKeys(), blobs = TestStateBlobs(), repo = repository(f.owner, keys, blobs)
        XCTAssertThrowsError(try repo.receiveFriendship(encrypted(f), identity: f.owner, pins: f.pins, nearby: true, now: now))
        XCTAssertNil(repo.state); XCTAssertNil(keys.value); XCTAssertNil(blobs.value)
        XCTAssertEqual(keys.creations, 0); XCTAssertEqual(blobs.writes, 0)
    }
    func testRealEncryptedNearbyRequestPersistsPendingAndFreshRestore() throws {
        let f = try fixture(); _ = try f.repo.setDiscoverable(true)
        let result = try receive(f, encrypted(f))
        XCTAssertEqual(result.outcome, .changed); XCTAssertEqual(result.state.records.first?.incomingId, requestId)
        XCTAssertFalse(result.state.isFriend(f.peer.nodeId, fingerprint: f.peer.publicFingerprint))
        XCTAssertEqual(try repository(f.owner, f.keys, f.blobs).load(), result.state)
        XCTAssertEqual(f.blobs.writes, 3); XCTAssertEqual(f.keys.creations, 1)
    }
    func testInviteConsumptionIsDurableSingleUseAndDoesNotAutoAccept() throws {
        let f = try fixture(); _ = try f.repo.issueInvite(token: "single-invite", now: now)
        let packet = try encrypted(f, token: "single-invite"), result = try receive(f, packet, nearby: false)
        XCTAssertEqual(result.outcome, .changed); XCTAssertEqual(result.state.inviteToken, "")
        XCTAssertFalse(result.state.records[0].accepted)
        XCTAssertEqual(try repository(f.owner, f.keys, f.blobs).load(), result.state)
        let writes = f.blobs.writes
        XCTAssertEqual(try receive(f, packet, nearby: false).outcome, .ignored)
        let other = LocalIdentity(nodeId: "other"), pins = try ContactBook(contacts: [pin(other)])
        XCTAssertEqual(try f.repo.receiveFriendship(encrypted(f, token: "single-invite", sender: other),
            identity: f.owner, pins: pins, nearby: false, now: now).outcome, .rejected)
        XCTAssertEqual(f.blobs.writes, writes)
    }
    func testAcceptRequiresMatchingDurableOutgoingRequest() throws {
        let f = try fixture(), packet = try encrypted(f, action: .accept)
        XCTAssertEqual(try receive(f, packet).outcome, .rejected)
        _ = try f.repo.request(peer: pin(f.peer), peerAlias: "Peer", peerDiscoverable: false, nearby: false,
            inviteToken: "peer-invite", requestId: requestId, now: now)
        let result = try receive(f, packet, nearby: false)
        XCTAssertTrue(result.state.isFriend(f.peer.nodeId, fingerprint: f.peer.publicFingerprint))
        XCTAssertEqual(try repository(f.owner, f.keys, f.blobs).load(), result.state)
        XCTAssertEqual(f.blobs.writes, 3)
    }
    func testProfileRequiresFriendshipAndPersistsWithoutChangingProtocolRules() throws {
        let f = try fixture(), profile = try encrypted(f, action: .profile, alias: "Private Profile", avatar: "photo")
        XCTAssertEqual(try receive(f, profile).outcome, .rejected)
        _ = try f.repo.setDiscoverable(true); _ = try receive(f, encrypted(f))
        _ = try f.repo.accept(peer: pin(f.peer), now: now)
        let updated = try receive(f, profile, time: now + 1)
        XCTAssertEqual(updated.state.records[0].alias, "Private Profile")
        XCTAssertEqual(try repository(f.owner, f.keys, f.blobs).load(), updated.state)
        XCTAssertFalse(String(decoding: try XCTUnwrap(f.blobs.value), as: UTF8.self).contains("Private Profile"))
        _ = try f.repo.block(f.peer.nodeId); let writes = f.blobs.writes
        XCTAssertEqual(try receive(f, profile).outcome, .rejected); XCTAssertEqual(f.blobs.writes, writes)
    }
    func testRequestReplayIsIgnoredButAcceptWithLaterClockKeepsExistingUpdateSemantics() throws {
        let f = try fixture(); _ = try f.repo.setDiscoverable(true)
        let request = try encrypted(f); _ = try receive(f, request)
        let writes = f.blobs.writes
        XCTAssertEqual(try receive(f, request, time: now + 1).outcome, .ignored)
        XCTAssertEqual(f.blobs.writes, writes)
        _ = try f.repo.request(peer: pin(f.peer), peerAlias: "Peer", peerDiscoverable: true, nearby: true,
            requestId: requestId, now: now)
        let accept = try encrypted(f, action: .accept); _ = try receive(f, accept)
        let before = f.blobs.writes
        XCTAssertEqual(try receive(f, accept, time: now + 1).outcome, .changed)
        XCTAssertEqual(f.blobs.writes, before + 1)
    }
    func testMissingOrRotatedPinRejectsWithoutLosingValidSnapshot() throws {
        let f = try fixture(), original = f.repo.state, packet = try encrypted(f)
        for pins in [try ContactBook(), try ContactBook(contacts: [pin(LocalIdentity(nodeId: f.peer.nodeId))])] {
            XCTAssertThrowsError(try f.repo.receiveFriendship(packet, identity: f.owner, pins: pins, nearby: true, now: now))
            XCTAssertEqual(f.repo.state, original); XCTAssertEqual(f.blobs.writes, 1)
        }
    }
    func testInvalidSignatureAndAuthenticatedBadGcmTagDoNotMutate() throws {
        let f = try fixture(), packet = try encrypted(f), original = f.repo.state
        let badSignature = try changed(packet, field: "signature", value: Data(repeating: 0, count: 72).base64EncodedString())
        XCTAssertThrowsError(try receive(f, badSignature))
        var cipher = try XCTUnwrap(Data(base64Encoded: packet.ciphertext)); cipher[cipher.count - 1] ^= 1
        let badTag = try changed(packet, field: "ciphertext", value: cipher.base64EncodedString(), resign: f.peer)
        XCTAssertThrowsError(try receive(f, badTag))
        XCTAssertEqual(f.repo.state, original); XCTAssertEqual(f.blobs.writes, 1)
    }
    func testWrongOwnerRecipientAndSelfOriginDoNotGrantAdmission() throws {
        let f = try fixture(), packet = try encrypted(f), original = f.repo.state
        for identity in [LocalIdentity(nodeId: "other"), LocalIdentity(nodeId: f.owner.nodeId)] {
            XCTAssertThrowsError(try f.repo.receiveFriendship(packet, identity: identity, pins: f.pins, nearby: true, now: now))
        }
        XCTAssertThrowsError(try receive(f, encrypted(f, receiver: LocalIdentity(nodeId: "other"))))
        XCTAssertThrowsError(try receive(f, changed(packet, field: "originNodeId", value: f.owner.nodeId)))
        XCTAssertEqual(f.repo.state, original); XCTAssertEqual(f.blobs.writes, 1)
    }
    func testWrongPayloadKindAndMalformedDecryptedJsonNeverReachState() throws {
        let f = try fixture(), original = f.repo.state
        for clear in [Data([0]), Data("{\"type\":\"CHAT_PAYLOAD_V1\"}".utf8)] {
            XCTAssertThrowsError(try receive(f, encrypted(f, clear: clear)))
            XCTAssertEqual(f.repo.state, original); XCTAssertEqual(f.blobs.writes, 1)
        }
    }
    func testOversizedStringsAndJsonEscapingAreBoundedBeforeAuthentication() throws {
        let f = try fixture(), packet = try encrypted(f), original = f.repo.state
        for alias in [String(repeating: "a", count: MeshWire.maximumFrameBytes + 1),
                      String(repeating: "\n", count: MeshWire.maximumFrameBytes / 2)] {
            XCTAssertThrowsError(try receive(f, changed(packet, field: "senderAlias", value: alias)))
        }
        XCTAssertEqual(f.repo.state, original); XCTAssertEqual(f.blobs.writes, 1)
    }
    func testAgeOverflowAndRelayedOrNonNearbyDiscoveryAreRejectedWithoutWrites() throws {
        let f = try fixture(); _ = try f.repo.setDiscoverable(true)
        for sent in [now - FriendState.requestLifetimeMs - 1, now + 300_001, Int64.max] {
            XCTAssertEqual(try receive(f, encrypted(f, sent: sent)).outcome, .rejected)
        }
        let packet = try encrypted(f)
        for time in [Int64.min, 0, Int64.max] { XCTAssertEqual(try receive(f, packet, time: time).outcome, .rejected) }
        XCTAssertEqual(try receive(f, packet, nearby: false).outcome, .rejected)
        XCTAssertEqual(try receive(f, changed(packet, field: "hops", value: 1)).outcome, .rejected)
        XCTAssertEqual(f.blobs.writes, 2)
    }
    func testStorageReadKeyOrBaselineFailureInvalidatesAndDoesNotOverwrite() throws {
        for failure in 0..<4 {
            let f = try fixture(), packet = try encrypted(f), state = try XCTUnwrap(f.repo.state)
            switch failure {
            case 0: f.keys.failRead = true
            case 1: f.blobs.failReadAt = f.blobs.reads + 1
            case 2: f.keys.value = SymmetricKey(size: .bits256)
            default: f.blobs.value = try EncryptedFriendState.seal(state.settingDiscoverable(true),
                key: XCTUnwrap(f.keys.value), localNodeId: f.owner.nodeId, ownFingerprint: f.owner.publicFingerprint)
            }
            let ciphertext = f.blobs.value
            XCTAssertThrowsError(try receive(f, packet)); XCTAssertNil(f.repo.state)
            XCTAssertEqual(f.blobs.value, ciphertext); XCTAssertEqual(f.blobs.writes, 1); XCTAssertEqual(f.keys.creations, 1)
        }
    }
    func testCommitAndReadbackFailuresCannotReturnUnverifiedIncomingState() throws {
        for failure in 0..<4 {
            let f = try fixture(); _ = try f.repo.setDiscoverable(true)
            let packet = try encrypted(f), ciphertext = f.blobs.value
            switch failure {
            case 0: f.blobs.failBefore = true
            case 1: f.blobs.failAfter = true
            case 2: f.blobs.failReadAt = f.blobs.reads + 3
            default: f.blobs.afterCommit = { f.blobs.value = Data(repeating: 0, count: 33) }
            }
            XCTAssertThrowsError(try receive(f, packet)); XCTAssertNil(f.repo.state)
            XCTAssertEqual(f.blobs.writes, failure == 0 ? 2 : 3); XCTAssertEqual(f.keys.creations, 1)
            f.blobs.failBefore = false; f.blobs.failAfter = false; f.blobs.failReadAt = nil
            if failure == 0 { XCTAssertEqual(f.blobs.value, ciphertext); XCTAssertTrue(try f.repo.load().records.isEmpty) }
            else if failure == 3 { XCTAssertThrowsError(try f.repo.load()) }
            else { XCTAssertEqual(try f.repo.load().records.first?.incomingId, requestId) }
        }
    }
    func testReceiveWaitsForBlockReadbackAndCannotOverrideIt() throws {
        let f = try fixture(); _ = try f.repo.setDiscoverable(true); _ = try receive(f, encrypted(f))
        let packet = try encrypted(f), entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let attempted = DispatchSemaphore(value: 0), group = DispatchGroup(), results = NSMutableArray(), resultLock = NSLock()
        f.blobs.afterCommit = { entered.signal(); _ = release.wait(timeout: .now() + 5) }
        group.enter()
        DispatchQueue.global().async {
            defer { group.leave() }
            do { _ = try f.repo.block(f.peer.nodeId); resultLock.lock(); results.add("blocked"); resultLock.unlock() }
            catch { resultLock.lock(); results.add("failed"); resultLock.unlock() }
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 5), .success)
        group.enter()
        DispatchQueue.global().async {
            defer { group.leave() }; attempted.signal()
            do {
                let outcome = try self.receive(f, packet).outcome
                resultLock.lock(); results.add(outcome == .rejected ? "rejected" : "unexpected"); resultLock.unlock()
            } catch { resultLock.lock(); results.add("failed"); resultLock.unlock() }
        }
        XCTAssertEqual(attempted.wait(timeout: .now() + 5), .success); release.signal()
        XCTAssertEqual(group.wait(timeout: .now() + 10), .success)
        XCTAssertEqual(Set(results.compactMap { $0 as? String }), Set(["blocked", "rejected"]))
        XCTAssertEqual(f.repo.state?.records.first?.blocked, true); XCTAssertEqual(f.blobs.writes, 4)
    }
    func testTwoEncryptedPeerRequestsAreSerializedWithoutLostUpdate() throws {
        let f = try fixture(); _ = try f.repo.setDiscoverable(true)
        let other = LocalIdentity(nodeId: "other"), pins = try ContactBook(contacts: [pin(f.peer), pin(other)])
        let packets = try [encrypted(f), encrypted(f, sender: other)], errors = NSMutableArray(), errorLock = NSLock()
        DispatchQueue.concurrentPerform(iterations: 2) { index in
            do { _ = try f.repo.receiveFriendship(packets[index], identity: f.owner, pins: pins, nearby: true, now: self.now) }
            catch { errorLock.lock(); errors.add(error); errorLock.unlock() }
        }
        XCTAssertEqual(errors.count, 0)
        let state = try repository(f.owner, f.keys, f.blobs).load()
        XCTAssertEqual(Set(state.records.map(\.nodeId)), Set([f.peer.nodeId, other.nodeId]))
        XCTAssertTrue(state.records.allSatisfy { !$0.accepted }); XCTAssertEqual(f.blobs.writes, 4)
    }
}
