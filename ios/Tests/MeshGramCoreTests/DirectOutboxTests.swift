import Foundation
import XCTest
@testable import MeshGramCore

final class DirectOutboxTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private struct Context {
        let local: LocalIdentity, peer: LocalIdentity
        let localHello: HelloPacket, peerHello: HelloPacket
        let pins: ContactBook, localPin: ContactBook
        let friends: FriendState
    }
    private func pin(_ hello: HelloPacket) -> ConfirmedContact {
        ConfirmedContact(version: 1, nodeId: hello.originNodeId, encryptionPublicKey: hello.encryptionPublicKey,
            signingPublicKey: hello.signingPublicKey, fingerprint: hello.fingerprint, confirmedAtMs: now)
    }
    private func context() throws -> Context {
        let local = LocalIdentity(nodeId: "local"), peer = LocalIdentity(nodeId: "peer")
        let a = try PacketCrypto.makeHello(identity: local, createdAtMs: now)
        let b = try PacketCrypto.makeHello(identity: peer, createdAtMs: now)
        return Context(local: local, peer: peer, localHello: a, peerHello: b,
            pins: try ContactBook(contacts: [pin(b)]), localPin: try ContactBook(contacts: [pin(a)]),
            friends: try FriendState(records: [FriendRecord(nodeId: "peer", fingerprint: b.fingerprint, alias: "Друг", accepted: true)]))
    }
    private func prepared(_ c: Context, id: String = "logical-1", text: String = "Привет 🙂") throws -> PreparedDirectMessage {
        try PreparedDirectMessage.make(text: text, messageId: id, identity: c.local, recipient: c.peerHello,
                                        pins: c.pins, friendship: c.friends, nowMs: now)
    }
    private func queued(_ c: Context) throws -> DirectOutbox {
        var box = try DirectOutbox(localNodeId: "local")
        try box.enqueue(prepared(c), friendship: c.friends, nowMs: now)
        return box
    }
    private func ack(_ c: Context, id: String = "logical-1", kind: DirectPayload.Kind = .deliveryAck,
                     chat: String? = nil, members: [String] = [], time: Int64? = nil) throws -> AuthenticatedPayload {
        let payload = try DirectPayload(chatId: chat ?? (kind == .relayAck ? "relay:\(id)" : "dm:local:peer"),
            memberNodeIds: members, text: kind == .text ? "Привет" : "", payloadKind: kind,
            ackMessageId: kind == .text ? nil : id, sentAtMs: now)
        let packet = try PacketCrypto.encrypt(JSONEncoder().encode(payload), identity: c.peer, recipient: c.localHello,
            trustedNodeId: "local", trustedFingerprint: c.localHello.fingerprint, createdAtMs: time ?? now)
        return try AuthenticatedPayload.open(packet, identity: c.local, pins: c.pins)
    }
    private func changedHello(_ hello: HelloPacket, field: String, value: String) throws -> HelloPacket {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(hello)) as? [String: Any])
        object[field] = value
        return try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: object))
    }

    func testFactoryEncryptsCanonicalPayloadWithoutKeepingPlaintext() throws {
        let c = try context(), message = try prepared(c)
        let packet = try JSONDecoder().decode(SecureMessagePacket.self, from: message.wireData)
        let clear = try PacketCrypto.decrypt(packet, identity: c.peer, trustedNodeId: "local", trustedFingerprint: c.localHello.fingerprint)
        let payload = try JSONDecoder().decode(DirectPayload.self, from: clear)
        XCTAssertEqual(payload.chatId, "dm:local:peer")
        XCTAssertEqual(Set(payload.memberNodeIds), Set(["local", "peer"]))
        XCTAssertEqual(payload.messageId, "logical-1")
        XCTAssertEqual(payload.text, "Привет 🙂")
        XCTAssertNotEqual(message.frameId, message.logicalMessageId)
        XCTAssertEqual(packet.id, message.frameId)
        XCTAssertLessThanOrEqual(message.wireData.count, 3060)
        let fields = Set(Mirror(reflecting: message).children.compactMap(\.label))
        XCTAssertEqual(fields, Set(["localNodeId", "peerNodeId", "peerFingerprint", "chatId", "logicalMessageId", "frameId", "createdAtMs", "wireData"]))
        XCTAssertNil((try JSONSerialization.jsonObject(with: message.wireData) as? [String: Any])?["text"])
    }
    func testPinIsNotConsentAndBlockedChangedOrMissingFriendIsRejected() throws {
        let c = try context()
        for state in [try FriendState(),
                      try FriendState(records: [FriendRecord(nodeId: "peer", fingerprint: c.peerHello.fingerprint, alias: "Друг")]),
                      try FriendState(records: [FriendRecord(nodeId: "peer", fingerprint: c.peerHello.fingerprint, alias: "Друг", accepted: true, blocked: true)]),
                      try FriendState(records: [FriendRecord(nodeId: "peer", fingerprint: String(repeating: "a", count: 64), alias: "Друг", accepted: true)])] {
            XCTAssertThrowsError(try PreparedDirectMessage.make(text: "Привет", messageId: "m", identity: c.local,
                recipient: c.peerHello, pins: c.pins, friendship: state, nowMs: now))
        }
    }
    func testFactoryRequiresStoredExactKeysSignedHelloAndNonSelfPeer() throws {
        let c = try context()
        XCTAssertThrowsError(try PreparedDirectMessage.make(text: "Привет", messageId: "m", identity: c.local,
            recipient: c.peerHello, pins: ContactBook(), friendship: c.friends, nowMs: now))
        for (field, value) in [("signature", ""), ("originNodeId", "other"),
                               ("encryptionPublicKey", c.localHello.encryptionPublicKey), ("signingPublicKey", c.localHello.signingPublicKey)] {
            let hello = try changedHello(c.peerHello, field: field, value: value)
            XCTAssertThrowsError(try PreparedDirectMessage.make(text: "Привет", messageId: "m", identity: c.local,
                recipient: hello, pins: c.pins, friendship: c.friends, nowMs: now))
        }
        XCTAssertThrowsError(try PreparedDirectMessage.make(text: "Привет", messageId: "m", identity: c.peer,
            recipient: c.peerHello, pins: c.pins, friendship: c.friends, nowMs: now))
    }
    func testInvalidIdsTextClockAndOversizedEncryptedPacketAreRejected() throws {
        let c = try context()
        for id in ["", " ", "a\nb", String(repeating: "a", count: 129)] { XCTAssertThrowsError(try prepared(c, id: id)) }
        for text in ["", " \n", String(repeating: "🙂", count: 2500)] { XCTAssertThrowsError(try prepared(c, text: text)) }
        for time in [Int64(0), -1, Int64.min] {
            XCTAssertThrowsError(try PreparedDirectMessage.make(text: "Привет", messageId: "m", identity: c.local,
                recipient: c.peerHello, pins: c.pins, friendship: c.friends, nowMs: time))
        }
        for id in ["", " local", "a:b", "a|b", "a\nb"] { XCTAssertThrowsError(try DirectOutbox(localNodeId: id)) }
    }
    func testExactDuplicateIsIdempotentButLogicalOrFrameCollisionIsRejected() throws {
        let c = try context(), m = try prepared(c)
        var box = try DirectOutbox(localNodeId: "local")
        XCTAssertEqual(try box.enqueue(m, friendship: c.friends, nowMs: now), .changed)
        XCTAssertEqual(try box.enqueue(m, friendship: c.friends, nowMs: now), .ignored)
        let before = box.entries
        XCTAssertThrowsError(try box.enqueue(prepared(c), friendship: c.friends, nowMs: now))
        let collision = PreparedDirectMessage(localNodeId: m.localNodeId, peerNodeId: m.peerNodeId, peerFingerprint: m.peerFingerprint,
            chatId: m.chatId, logicalMessageId: "different", frameId: m.frameId, createdAtMs: m.createdAtMs, wireData: m.wireData)
        XCTAssertThrowsError(try box.enqueue(collision, friendship: c.friends, nowMs: now))
        XCTAssertEqual(box.entries, before)
    }
    func testCapacityNeverEvictsAndExactRepeatStillWorksAtCapacity() throws {
        let c = try context()
        var box = try queued(c)
        let first = try XCTUnwrap(box.entries.first?.message)
        for i in 1..<DirectOutbox.maximumEntries { try box.enqueue(prepared(c, id: "m\(i)"), friendship: c.friends, nowMs: now) }
        let before = box.entries
        XCTAssertThrowsError(try box.enqueue(prepared(c, id: "overflow"), friendship: c.friends, nowMs: now))
        XCTAssertEqual(try box.enqueue(first, friendship: c.friends, nowMs: now), .ignored)
        XCTAssertEqual(box.entries, before)
    }
    func testAttOnlyMarksMatchingPacketWrittenNotDelivered() throws {
        let c = try context()
        var box = try queued(c)
        let m = try XCTUnwrap(box.entries.first?.message)
        for (frame, id, peer, success) in [("unknown", m.logicalMessageId, "peer", true),
            (m.frameId, "unknown", "peer", true), (m.frameId, m.logicalMessageId, "other", true), (m.frameId, m.logicalMessageId, "peer", false)] {
            XCTAssertEqual(box.markATTWritten(frameId: frame, logicalMessageId: id, peerNodeId: peer, succeeded: success), .ignored)
        }
        XCTAssertEqual(box.entries[0].phase, .prepared)
        XCTAssertEqual(box.markATTWritten(frameId: m.frameId, logicalMessageId: m.logicalMessageId, peerNodeId: "peer", succeeded: true), .changed)
        XCTAssertEqual(box.entries[0].phase, .written)
        XCTAssertEqual(box.markATTWritten(frameId: m.frameId, logicalMessageId: m.logicalMessageId, peerNodeId: "peer", succeeded: true), .ignored)
    }
    func testRealEncryptedDeliveryAckCanPrecedeAttAndDuplicatesNeverRegress() throws {
        let c = try context(), receipt = try ack(c, members: ["peer", "local"])
        var box = try queued(c)
        let m = box.entries[0].message
        XCTAssertEqual(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now), .changed)
        XCTAssertEqual(box.entries[0].phase, .delivered)
        XCTAssertEqual(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now), .ignored)
        XCTAssertEqual(box.markATTWritten(frameId: m.frameId, logicalMessageId: m.logicalMessageId, peerNodeId: "peer", succeeded: true), .ignored)
        XCTAssertEqual(try box.enqueue(m, friendship: c.friends, nowMs: now), .ignored)
        XCTAssertEqual(box.entries[0].phase, .delivered)
    }
    func testWrittenMessageNeedsOwnLogicalAckNotFrameOrUnknownId() throws {
        let c = try context()
        var box = try queued(c)
        let m = box.entries[0].message
        _ = box.markATTWritten(frameId: m.frameId, logicalMessageId: m.logicalMessageId, peerNodeId: "peer", succeeded: true)
        for id in [m.frameId, "unknown"] { XCTAssertEqual(try box.acknowledgeDelivery(ack(c, id: id), friendship: c.friends, nowMs: now), .ignored) }
        XCTAssertEqual(box.entries[0].phase, .written)
        XCTAssertEqual(try box.acknowledgeDelivery(ack(c), friendship: c.friends, nowMs: now), .changed)
    }
    func testWrongChatMembersPeerAndFingerprintCannotDeliver() throws {
        let c = try context()
        var box = try queued(c)
        let before = box.entries, good = try ack(c)
        for receipt in [try ack(c, chat: "dm:local:other"), try ack(c, members: ["peer", "other"])] {
            XCTAssertThrowsError(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now))
        }
        let other = AuthenticatedPayload(senderNodeId: "other", fingerprint: good.fingerprint, createdAtMs: now, hops: 0, data: good.data)
        let changed = AuthenticatedPayload(senderNodeId: "peer", fingerprint: String(repeating: "b", count: 64), createdAtMs: now, hops: 0, data: good.data)
        for receipt in [other, changed] { XCTAssertThrowsError(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now)) }
        XCTAssertEqual(box.entries, before)
    }
    func testRelayTextFriendAndMalformedPayloadCannotDeliver() throws {
        let c = try context()
        var box = try queued(c)
        let before = box.entries
        for kind in [DirectPayload.Kind.relayAck, .text] { XCTAssertEqual(try box.acknowledgeDelivery(ack(c, kind: kind), friendship: c.friends, nowMs: now), .ignored) }
        for raw in ["{}", "broken", "{\"type\":\"UNKNOWN\"}"] {
            let receipt = AuthenticatedPayload(senderNodeId: "peer", fingerprint: c.peerHello.fingerprint, createdAtMs: now, hops: 0, data: Data(raw.utf8))
            XCTAssertThrowsError(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now))
        }
        let friend = try FriendPacket(action: .profile, requestId: "1234567890123456", alias: "Друг")
        let receipt = AuthenticatedPayload(senderNodeId: "peer", fingerprint: c.peerHello.fingerprint, createdAtMs: now, hops: 0, data: try JSONEncoder().encode(friend))
        XCTAssertEqual(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now), .ignored)
        XCTAssertEqual(box.entries, before)
    }
    func testAnotherAcceptedFriendCannotAcknowledgePeersLogicalId() throws {
        let c = try context(), other = LocalIdentity(nodeId: "other")
        let hello = try PacketCrypto.makeHello(identity: other, createdAtMs: now)
        let pins = try ContactBook(contacts: [pin(c.peerHello), pin(hello)])
        let friends = try FriendState(records: c.friends.records + [FriendRecord(nodeId: "other", fingerprint: hello.fingerprint, alias: "Другой", accepted: true)])
        let payload = try DirectPayload(chatId: "dm:local:other", payloadKind: .deliveryAck, ackMessageId: "logical-1")
        let packet = try PacketCrypto.encrypt(JSONEncoder().encode(payload), identity: other, recipient: c.localHello,
            trustedNodeId: "local", trustedFingerprint: c.localHello.fingerprint, createdAtMs: now)
        let receipt = try AuthenticatedPayload.open(packet, identity: c.local, pins: pins)
        var box = try queued(c)
        let before = box.entries
        XCTAssertEqual(try box.acknowledgeDelivery(receipt, friendship: friends, nowMs: now), .ignored)
        XCTAssertEqual(box.entries, before)
    }
    func testClockSkewAndLifetimeBoundariesAreInclusive() throws {
        let c = try context()
        for time in [now - 300_000, now + 300_000] {
            var box = try queued(c)
            XCTAssertEqual(try box.acknowledgeDelivery(ack(c, time: time), friendship: c.friends, nowMs: now), .changed)
        }
        let old = try PreparedDirectMessage.make(text: "Привет", messageId: "logical-1", identity: c.local,
            recipient: c.peerHello, pins: c.pins, friendship: c.friends, nowMs: now - FriendState.requestLifetimeMs)
        var box = try DirectOutbox(localNodeId: "local")
        XCTAssertEqual(try box.enqueue(old, friendship: c.friends, nowMs: now), .changed)
        XCTAssertEqual(try box.acknowledgeDelivery(ack(c), friendship: c.friends, nowMs: now), .changed)
    }
    func testExpiredFutureOverflowAndPredatingReceiptsLeaveStateUnchanged() throws {
        let c = try context(), good = try ack(c)
        var box = try queued(c)
        let before = box.entries
        for time in [Int64.min, Int64.max, 0, now - FriendState.requestLifetimeMs - 1, now + 300_001, now - 300_001] {
            let receipt = AuthenticatedPayload(senderNodeId: "peer", fingerprint: good.fingerprint, createdAtMs: time, hops: 0, data: good.data)
            XCTAssertThrowsError(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now))
        }
        for clock in [Int64.min, Int64.max, 0] { XCTAssertThrowsError(try box.acknowledgeDelivery(good, friendship: c.friends, nowMs: clock)) }
        XCTAssertEqual(box.entries, before)
    }
    func testRevokedConsentAndDifferentLocalIdentityFailAtEnqueueAndAck() throws {
        let c = try context(), m = try prepared(c), receipt = try ack(c)
        var box = try queued(c), other = try DirectOutbox(localNodeId: "other")
        let before = box.entries
        XCTAssertThrowsError(try other.enqueue(m, friendship: c.friends, nowMs: now))
        for state in [try FriendState(), try FriendState(records: [FriendRecord(nodeId: "peer", fingerprint: c.peerHello.fingerprint, alias: "Друг", accepted: true, blocked: true)])] {
            XCTAssertThrowsError(try box.enqueue(m, friendship: state, nowMs: now))
            XCTAssertThrowsError(try box.acknowledgeDelivery(receipt, friendship: state, nowMs: now))
        }
        XCTAssertThrowsError(try box.enqueue(m, friendship: c.friends, nowMs: now + FriendState.requestLifetimeMs + 1))
        XCTAssertThrowsError(try box.acknowledgeDelivery(receipt, friendship: c.friends, nowMs: now + FriendState.requestLifetimeMs + 1))
        XCTAssertEqual(box.entries, before)
    }
}
