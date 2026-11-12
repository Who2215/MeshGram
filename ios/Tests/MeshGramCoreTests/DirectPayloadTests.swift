import Foundation
import XCTest
@testable import MeshGramCore

final class DirectPayloadTests: XCTestCase {
    private let fp = String(repeating: "a", count: 64)
    private func verified(_ payload: DirectPayload, node: String = "peer") throws -> AuthenticatedPayload {
        AuthenticatedPayload(senderNodeId: node, fingerprint: fp, createdAtMs: 1_800_000_000_000,
            hops: 0, data: try JSONEncoder().encode(payload))
    }
    private func accepted() throws -> FriendState {
        try FriendState(records: [FriendRecord(nodeId: "peer", fingerprint: fp, alias: "Peer", accepted: true)])
    }
    func testCanonicalDmIsOrderIndependentAndUsesKotlinUtf16Order() throws {
        XCTAssertEqual(try DirectPayload.conversationId(" z ", "a"), "dm:a:z")
        XCTAssertEqual(try DirectPayload.conversationId("a", "z"), DirectPayload.conversationId("z", "a"))
        XCTAssertEqual(try DirectPayload.conversationId("😀", "\u{e000}"), "dm:😀:\u{e000}")
        for bad in ["", " ", "a:b", "a|b", "a\nb"] { XCTAssertThrowsError(try DirectPayload.conversationId(bad, "peer")) }
        XCTAssertThrowsError(try DirectPayload.conversationId("peer", "peer"))
    }
    func testTextRoundTripKeepsUserContentAndReplyFields() throws {
        let body = "Привет, report-English.pdf 🙂"
        let p = try DirectPayload(chatId: "dm:local:peer", memberNodeIds: ["local", "peer"], text: body,
            messageId: "logical-message", replyToMessageId: "reply", replyToPreview: "preview", sentAtMs: 1)
        let decoded = try JSONDecoder().decode(DirectPayload.self, from: JSONEncoder().encode(p))
        XCTAssertEqual(p, decoded)
        XCTAssertEqual(decoded.text, body)
        guard case .direct = try KnownPayload.decode(JSONEncoder().encode(p)) else { return XCTFail("Expected typed direct payload") }
    }
    func testMissingAndroidDefaultsAreHandledWithoutLegacyPlaintextFallback() throws {
        let raw = Data("{\"type\":\"CHAT_PAYLOAD_V1\",\"chatId\":\"dm:local:peer\",\"chatType\":\"direct\",\"text\":\"Привет\",\"unknown\":1}".utf8)
        let p = try JSONDecoder().decode(DirectPayload.self, from: raw)
        XCTAssertEqual(p.payloadKind, .text); XCTAssertEqual(p.sentAtMs, 0)
        XCTAssertNil(p.messageId); XCTAssertTrue(p.memberNodeIds.isEmpty)
        XCTAssertThrowsError(try KnownPayload.decode(Data("untyped plaintext".utf8)))
        XCTAssertThrowsError(try KnownPayload.decode(Data("{\"type\":\"OTHER\"}".utf8)))
    }
    func testPinAndSuccessfulDecodeDoNotAuthorizeText() throws {
        let p = try DirectPayload(chatId: "dm:local:peer", text: "hello", messageId: "logical")
        let value = try verified(p)
        XCTAssertThrowsError(try KnownPayload.authorizedDirect(value, localNodeId: "local", state: FriendState()))
        XCTAssertEqual(try KnownPayload.authorizedDirect(value, localNodeId: "local", state: accepted()), p)
        XCTAssertThrowsError(try KnownPayload.authorizedDirect(value, localNodeId: "local", state: accepted().ownerBlock("peer").state))
    }
    func testWrongConversationMembersOrSenderCannotBeAuthorized() throws {
        for p in [try DirectPayload(chatId: "dm:attacker:peer", text: "x"),
                  try DirectPayload(chatId: "dm:local:peer", memberNodeIds: ["attacker", "peer"], text: "x"),
                  try DirectPayload(chatId: "dm:local:peer", memberNodeIds: ["peer", "peer"], text: "x")] {
            XCTAssertThrowsError(try KnownPayload.authorizedDirect(verified(p), localNodeId: "local", state: accepted()))
        }
        let p = try DirectPayload(chatId: "dm:local:peer", text: "x")
        XCTAssertThrowsError(try KnownPayload.authorizedDirect(verified(p, node: "attacker"), localNodeId: "local", state: accepted()))
    }
    func testDeliveryAndRelayAcksHaveDifferentNamespacesAndDoNotDeliverAnything() throws {
        let delivery = try DirectPayload(chatId: "dm:local:peer", payloadKind: .deliveryAck, ackMessageId: "logical-message")
        let relay = try DirectPayload(chatId: "relay:encrypted-frame", payloadKind: .relayAck, ackMessageId: "encrypted-frame")
        XCTAssertEqual(delivery.payloadKind.rawValue, "message_delivery_ack")
        XCTAssertEqual(relay.payloadKind.rawValue, "message_relay_ack")
        XCTAssertEqual(try KnownPayload.authorizedDirect(verified(delivery), localNodeId: "local", state: accepted()), delivery)
        XCTAssertThrowsError(try KnownPayload.authorizedDirect(verified(relay), localNodeId: "local", state: accepted()))
        XCTAssertThrowsError(try DirectPayload(chatId: "dm:local:peer", payloadKind: .relayAck, ackMessageId: "encrypted-frame"))
        XCTAssertThrowsError(try DirectPayload(chatId: "dm:local:peer", payloadKind: .deliveryAck))
        XCTAssertThrowsError(try DirectPayload(chatId: "dm:local:peer", payloadKind: .deliveryAck, ackMessageId: "   "))
        XCTAssertThrowsError(try DirectPayload(chatId: "dm:local:peer", text: "fake receipt text", payloadKind: .deliveryAck, ackMessageId: "id"))
    }
    func testUnknownGroupFileOrInvalidKindsAreNotSilentlyText() throws {
        let p = try DirectPayload(chatId: "dm:local:peer", text: "x")
        let original = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(p)) as? [String: Any])
        for (field, value) in ["chatType": "group", "payloadKind": "file_chunk", "type": "OTHER"] {
            var changed = original; changed[field] = value
            XCTAssertThrowsError(try KnownPayload.decode(JSONSerialization.data(withJSONObject: changed)))
        }
        XCTAssertThrowsError(try DirectPayload(chatId: "dm:local:peer", text: " \n"))
        XCTAssertThrowsError(try DirectPayload(chatId: "   ", text: "x"))
        XCTAssertThrowsError(try KnownPayload.decode(Data(repeating: 0, count: MeshWire.maximumFrameBytes + 1)))
    }
    func testActualEncryptedJsonMustFitShortGattNotJustPlaintext() throws {
        XCTAssertEqual(MeshWire.shortGattMaximumPacketBytes, 3060)
        let a = LocalIdentity(), b = LocalIdentity()
        let hello = try PacketCrypto.makeHello(identity: b, createdAtMs: 1)
        func encrypt(_ size: Int) throws -> SecureMessagePacket {
            let p = try DirectPayload(chatId: DirectPayload.conversationId(a.nodeId, b.nodeId),
                text: String(repeating: "x", count: size), messageId: "message", sentAtMs: 1)
            return try PacketCrypto.encrypt(JSONEncoder().encode(p), identity: a, recipient: hello,
                trustedNodeId: b.nodeId, trustedFingerprint: b.publicFingerprint, createdAtMs: 1)
        }
        let short = try KnownPayload.shortGattData(encrypt(20))
        XCTAssertLessThanOrEqual(short.count, 3060)
        XCTAssertLessThanOrEqual(try FrameCodec.packetize(short, frameKey: 1).count, 255)
        XCTAssertThrowsError(try KnownPayload.shortGattData(encrypt(3000)))
    }
}
