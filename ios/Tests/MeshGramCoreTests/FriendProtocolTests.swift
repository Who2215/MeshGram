import Foundation
import XCTest
@testable import MeshGramCore

final class FriendProtocolTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private let fp = String(repeating: "a", count: 64)
    private let requestId = "request-1234567890"
    private func packet(_ action: FriendPacket.Action = .request, id: String? = nil, token: String = "", avatar: String = "photo") throws -> FriendPacket {
        try FriendPacket(action: action, requestId: id ?? requestId, alias: "Peer", avatarData: avatar, inviteToken: token)
    }
    private func receive(_ state: FriendState, _ p: FriendPacket? = nil, nearby: Bool = true,
                         node: String = "friend-node", fingerprint: String? = nil, sent: Int64? = nil, hops: Int = 0) throws -> FriendState.Transition {
        let value = try p ?? packet()
        let verified = AuthenticatedPayload(senderNodeId: node, fingerprint: fingerprint ?? fp,
            createdAtMs: sent ?? now, hops: hops, data: try JSONEncoder().encode(value))
        return try state.receive(verified, nearby: nearby, now: now)
    }
    private func record(outgoing: String? = nil, incoming: String? = nil, accepted: Bool = false,
                        blocked: Bool = false, updated: Int64? = nil) throws -> FriendRecord {
        try FriendRecord(nodeId: "friend-node", fingerprint: fp, alias: "Peer", avatarData: "photo",
            outgoingId: outgoing, incomingId: incoming, accepted: accepted, blocked: blocked, updatedAtMs: updated ?? now)
    }
    private func pin(_ identity: LocalIdentity) -> ConfirmedContact {
        ConfirmedContact(version: 1, nodeId: identity.nodeId,
            encryptionPublicKey: identity.encryptionKey.publicKey.derRepresentation.base64EncodedString(),
            signingPublicKey: identity.signingKey.publicKey.derRepresentation.base64EncodedString(),
            fingerprint: identity.publicFingerprint, confirmedAtMs: now)
    }
    func testDiscoveryOffByDefaultAndInternetCannotPretendToBeNearby() throws {
        let state = try FriendState()
        XCTAssertFalse(state.discoverable)
        XCTAssertEqual(try receive(state).state, state)
        let visible = state.settingDiscoverable(true)
        XCTAssertEqual(try receive(visible, nearby: false).state, visible)
        XCTAssertEqual(try receive(visible, hops: 1).state, visible)
    }
    func testNearbyRequestCreatesPendingNotFriendshipAndIsIdempotent() throws {
        let state = try receive(FriendState(discoverable: true)).state
        XCTAssertEqual(state.records.first?.incomingId, requestId)
        XCTAssertFalse(state.isFriend("friend-node", fingerprint: fp))
        XCTAssertEqual(try receive(state).state, state)
    }
    func testSingleUseInviteAllowsOnlyPendingAndCannotBeReused() throws {
        let state = try FriendState().issuingInvite(token: "test-invite-token", now: now)
        let pending = try receive(state, packet(token: "test-invite-token"), nearby: false).state
        XCTAssertEqual(pending.inviteToken, "")
        XCTAssertEqual(pending.inviteExpiresAtMs, 0)
        XCTAssertFalse(pending.isFriend("friend-node", fingerprint: fp))
        XCTAssertEqual(try receive(pending, packet(token: "test-invite-token"), node: "other-node").state, pending)
    }
    func testExpiredRevokedAndWrongInvitesAreRejected() throws {
        for state in [try FriendState(), try FriendState(inviteToken: "token", inviteExpiresAtMs: now),
                      try FriendState(inviteToken: "other", inviteExpiresAtMs: now + 1),
                      try FriendState().issuingInvite(token: "token", now: now).revokingInvite()] {
            XCTAssertEqual(try receive(state, packet(token: "token")).state, state)
        }
    }
    func testUnsolicitedOrMismatchedAcceptDoesNotCreateFriendship() throws {
        let empty = try FriendState()
        XCTAssertEqual(try receive(empty, packet(.accept)).state, empty)
        let pending = try FriendState(records: [record(outgoing: "different-request-123")])
        XCTAssertEqual(try receive(pending, packet(.accept)).state, pending)
    }
    func testMatchingAcceptNeedsSameFingerprintAndUnexpiredOutgoingRequest() throws {
        let state = try FriendState(records: [record(outgoing: requestId)])
        let accepted = try receive(state, packet(.accept)).state
        XCTAssertTrue(accepted.isFriend("friend-node", fingerprint: fp))
        XCTAssertFalse(accepted.isFriend("friend-node", fingerprint: String(repeating: "b", count: 64)))
        XCTAssertEqual(try receive(state, packet(.accept), fingerprint: String(repeating: "b", count: 64)).state, state)
        let stale = try FriendState(records: [record(outgoing: requestId, updated: now - FriendState.requestLifetimeMs - 1)])
        XCTAssertEqual(try receive(stale, packet(.accept)).state, stale)
    }
    func testBlockedAndRotatedPeersCannotBeAdmitted() throws {
        let state = try FriendState(discoverable: true, records: [record(incoming: requestId, blocked: true)])
        XCTAssertEqual(try receive(state, packet(id: "other-request-123456")).state, state)
        XCTAssertFalse(state.isFriend("friend-node", fingerprint: fp))
        let pending = try receive(FriendState(discoverable: true)).state
        XCTAssertEqual(try receive(pending, fingerprint: String(repeating: "b", count: 64)).state, pending)
    }
    func testProfileRequiresFriendshipAndCanClearPhotoAndIncomingId() throws {
        let empty = try FriendState()
        XCTAssertEqual(try receive(empty, packet(.profile)).state, empty)
        let state = try FriendState(records: [record(incoming: requestId, accepted: true)])
        let updated = try receive(state, packet(.profile, avatar: "")).state
        XCTAssertEqual(updated.records.first?.avatarData, "")
        XCTAssertNil(updated.records.first?.incomingId)
    }
    func testPendingAndTotalRecordLimitsDoNotEvictAcceptedPeers() throws {
        for (count, accepted) in [(FriendState.maximumPending, false), (FriendState.maximumRecords, true)] {
            let records = try (0..<count).map { try FriendRecord(nodeId: "node-\($0)", fingerprint: fp, alias: "Peer", accepted: accepted, updatedAtMs: now) }
            let state = try FriendState(discoverable: true, records: records)
            XCTAssertEqual(try receive(state).state, state)
        }
    }
    func testOwnerRequestAndAcceptAreSeparateFromPinAndDiscovery() throws {
        let identity = LocalIdentity(nodeId: "friend-node"), peer = pin(identity)
        let state = try FriendState()
        XCTAssertFalse(state.isFriend(peer.nodeId, fingerprint: peer.fingerprint))
        XCTAssertEqual(try state.ownerRequest(peer: peer, localNodeId: "local", peerAlias: "Peer", peerDiscoverable: true, nearby: true, now: now).outcome, .rejected)
        XCTAssertEqual(try state.settingDiscoverable(true).ownerRequest(peer: peer, localNodeId: peer.nodeId,
            peerAlias: "Peer", peerDiscoverable: true, nearby: true, now: now).outcome, .rejected)
        let outgoing = try state.settingDiscoverable(true).ownerRequest(peer: peer, localNodeId: "local", peerAlias: "Peer",
            peerDiscoverable: true, nearby: true, requestId: requestId, now: now).state
        XCTAssertEqual(outgoing.records.first?.outgoingId, requestId)
        XCTAssertFalse(outgoing.isFriend(peer.nodeId, fingerprint: peer.fingerprint))
        let incoming = try receive(state.settingDiscoverable(true), fingerprint: peer.fingerprint).state
        XCTAssertTrue(try incoming.ownerAccept(peer: peer, now: now).state.isFriend(peer.nodeId, fingerprint: peer.fingerprint))
        XCTAssertFalse(incoming.ownerBlock(peer.nodeId).state.isFriend(peer.nodeId, fingerprint: peer.fingerprint))
    }
    func testOuterPacketAgeAndClockOverflowFailClosed() throws {
        let state = try FriendState(discoverable: true)
        for sent in [Int64.min, -1, 0, now + 300_001, now - FriendState.requestLifetimeMs - 1] {
            XCTAssertEqual(try receive(state, sent: sent).state, state)
        }
        XCTAssertThrowsError(try state.issuingInvite(now: Int64.max))
    }
    func testKotlinUtf16LimitsAndDefaultsRoundTrip() throws {
        XCTAssertNoThrow(try FriendPacket(action: .request, requestId: requestId, alias: String(repeating: "🙂", count: 16)))
        XCTAssertThrowsError(try FriendPacket(action: .request, requestId: requestId, alias: String(repeating: "🙂", count: 17)))
        XCTAssertThrowsError(try FriendPacket(action: .request, requestId: "short", alias: "Peer"))
        XCTAssertThrowsError(try FriendPacket(action: .request, requestId: requestId, alias: "Peer", avatarData: String(repeating: "x", count: 24_001)))
        let value = try packet()
        XCTAssertEqual(try JSONDecoder().decode(FriendPacket.self, from: JSONEncoder().encode(value)), value)
        XCTAssertFalse(try JSONDecoder().decode(FriendState.self, from: Data("{}".utf8)).discoverable)
        let malformed = "{\"type\":\"MESH_FRIEND_V1\",\"action\":\"grant-admin\",\"requestId\":\"\(requestId)\",\"alias\":\"Peer\"}"
        XCTAssertThrowsError(try KnownPayload.decode(Data(malformed.utf8)))
    }
    func testAuthenticatedEntryRequiresActualPinSignatureAndRecipient() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity()
        let clear = try JSONEncoder().encode(packet())
        let hello = try PacketCrypto.makeHello(identity: receiver, createdAtMs: now)
        let encrypted = try PacketCrypto.encrypt(clear, identity: sender, recipient: hello,
            trustedNodeId: receiver.nodeId, trustedFingerprint: receiver.publicFingerprint, createdAtMs: now)
        let pins = try ContactBook(contacts: [pin(sender)])
        let verified = try AuthenticatedPayload.open(encrypted, identity: receiver, pins: pins)
        XCTAssertEqual(verified.packetId, encrypted.id)
        XCTAssertEqual(verified.data, clear)
        XCTAssertFalse(try FriendState().receive(verified, nearby: true, now: now).state.isFriend(sender.nodeId, fingerprint: sender.publicFingerprint))
        XCTAssertThrowsError(try AuthenticatedPayload.open(encrypted, identity: receiver, pins: ContactBook()))
        XCTAssertThrowsError(try AuthenticatedPayload.open(encrypted, identity: LocalIdentity(), pins: pins))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(encrypted)) as? [String: Any])
        object["signature"] = "invalid"
        let bad = try JSONDecoder().decode(SecureMessagePacket.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try AuthenticatedPayload.open(bad, identity: receiver, pins: pins))
    }
    func testStateSerializationPreservesConsentWithoutCreatingIt() throws {
        let state = try FriendState(records: [record(outgoing: requestId, accepted: true)])
        XCTAssertEqual(try JSONDecoder().decode(FriendState.self, from: JSONEncoder().encode(state)), state)
        XCTAssertThrowsError(try FriendState(records: state.records + state.records))
    }
}
