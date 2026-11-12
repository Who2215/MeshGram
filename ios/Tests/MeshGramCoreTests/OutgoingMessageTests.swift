import CryptoKit
import Foundation
import XCTest
@testable import MeshGramCore

final class OutgoingMessageTests: XCTestCase {
    private let timestamp: Int64 = 1_700_000_000_000

    private struct PublicInteropVector: Codable {
        let note: String
        let hello: HelloPacket
        let packet: SecureMessagePacket
        let receiverPKCS8: String
        let plaintext: String
    }
    private struct PublicReceiver: Decodable {
        let note: String
        let packet: SecureMessagePacket
        let receiverPKCS8: String
        let privateScalar: String
        let plaintext: String
    }

    func testGeneratedPacketForIndependentJCEVerification() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "android-v1", withExtension: "json", subdirectory: "Fixtures"))
        let fixture = try JSONDecoder().decode(PublicReceiver.self, from: Data(contentsOf: url))
        XCTAssertEqual(fixture.note, "PUBLIC TEST FIXTURE: receiver key is intentionally public, never use for real messages.")
        let scalar = try XCTUnwrap(Data(base64Encoded: fixture.privateScalar))
        let receiver = try LocalIdentity(nodeId: fixture.packet.targetNodeId,
                                        encryptionKey: P256.KeyAgreement.PrivateKey(rawRepresentation: scalar))
        let sender = LocalIdentity()
        let clear = try XCTUnwrap(Data(base64Encoded: fixture.plaintext))
        let packet = try make(clear, sender: sender, receiver: receiver)
        let vector = PublicInteropVector(note: fixture.note,
            hello: try PacketCrypto.makeHello(identity: sender, createdAtMs: timestamp), packet: packet,
            receiverPKCS8: fixture.receiverPKCS8, plaintext: fixture.plaintext)
        XCTAssertEqual(try PacketCrypto.decrypt(packet, identity: receiver,
            trustedNodeId: sender.nodeId, trustedFingerprint: sender.publicFingerprint), clear)
        // Optional test-only export: the receiver key is the existing public fixture, never a device key.
        if let path = ProcessInfo.processInfo.environment["MESHGRAM_INTEROP_OUTPUT"] {
            XCTAssertTrue(path.hasPrefix("/"))
            try JSONEncoder().encode(vector).write(to: URL(fileURLWithPath: path), options: .withoutOverwriting)
        }
    }

    private func make(_ clear: Data, sender: LocalIdentity, receiver: LocalIdentity,
                      node: String? = nil, fingerprint: String? = nil, time: Int64? = nil,
                      hops: Int = 8) throws -> SecureMessagePacket {
        try PacketCrypto.encrypt(clear, identity: sender,
            recipient: PacketCrypto.makeHello(identity: receiver, createdAtMs: timestamp),
            trustedNodeId: node ?? receiver.nodeId, trustedFingerprint: fingerprint ?? receiver.publicFingerprint,
            createdAtMs: time ?? timestamp, maxHops: hops)
    }

    func testRoundTripAndWireMetadata() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity()
        let clear = Data("Привет с iPhone!".utf8)
        let packet = try make(clear, sender: sender, receiver: receiver)
        XCTAssertEqual(packet.type, "SECURE_MESSAGE_V1")
        XCTAssertEqual(packet.originNodeId, sender.nodeId)
        XCTAssertEqual(packet.targetNodeId, receiver.nodeId)
        XCTAssertEqual(packet.relayNodeId, sender.nodeId)
        XCTAssertEqual(packet.hops, 0)
        XCTAssertEqual(packet.maxHops, 8)
        XCTAssertEqual(packet.createdAtMs, timestamp)
        XCTAssertEqual(packet.senderFingerprint, sender.publicFingerprint)
        XCTAssertEqual(packet.senderAlias, "Узел MeshGram")
        XCTAssertNotNil(UUID(uuidString: packet.id))
        XCTAssertEqual(try XCTUnwrap(Data(base64Encoded: packet.nonce)).count, 12)
        XCTAssertEqual(try XCTUnwrap(Data(base64Encoded: packet.ciphertext)).count, clear.count + 16)
        let decoded = try JSONDecoder().decode(SecureMessagePacket.self, from: JSONEncoder().encode(packet))
        XCTAssertEqual(try PacketCrypto.decrypt(decoded, identity: receiver,
            trustedNodeId: sender.nodeId, trustedFingerprint: sender.publicFingerprint), clear)
        XCTAssertThrowsError(try PacketCrypto.decrypt(decoded, identity: LocalIdentity(nodeId: receiver.nodeId),
            trustedNodeId: sender.nodeId, trustedFingerprint: sender.publicFingerprint))
    }

    func testEveryMessageUsesFreshEphemeralMaterial() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity(), clear = Data("same".utf8)
        let a = try make(clear, sender: sender, receiver: receiver)
        let b = try make(clear, sender: sender, receiver: receiver)
        XCTAssertNotEqual(a.id, b.id)
        XCTAssertNotEqual(a.ephemeralPublicKey, b.ephemeralPublicKey)
        XCTAssertNotEqual(a.nonce, b.nonce)
        XCTAssertNotEqual(a.ciphertext, b.ciphertext)
    }

    func testRecipientRequiresPreviouslyConfirmedNodeAndFingerprint() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity(), clear = Data([1])
        for pin in ["", "untrusted", LocalIdentity().publicFingerprint] {
            XCTAssertThrowsError(try make(clear, sender: sender, receiver: receiver, fingerprint: pin))
        }
        XCTAssertThrowsError(try make(clear, sender: sender, receiver: receiver, node: "another-node"))
        XCTAssertThrowsError(try make(clear, sender: sender, receiver: sender))
        let hello = try PacketCrypto.makeHello(identity: receiver, createdAtMs: timestamp)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(hello)) as? [String: Any])
        object["encryptionPublicKey"] = LocalIdentity().encryptionKey.publicKey.derRepresentation.base64EncodedString()
        let altered = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try PacketCrypto.encrypt(clear, identity: sender, recipient: altered,
            trustedNodeId: receiver.nodeId, trustedFingerprint: receiver.publicFingerprint, createdAtMs: timestamp))
        object["encryptionPublicKey"] = hello.encryptionPublicKey
        object["signature"] = "invalid"
        let unsigned = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try PacketCrypto.encrypt(clear, identity: sender, recipient: unsigned,
            trustedNodeId: receiver.nodeId, trustedFingerprint: receiver.publicFingerprint, createdAtMs: timestamp))
    }

    func testBoundsAndInvalidHeadersAreRejected() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity()
        for count in [0, PacketCrypto.maximumOutgoingPlaintextBytes + 1] {
            XCTAssertThrowsError(try make(Data(repeating: 1, count: count), sender: sender, receiver: receiver))
        }
        for hops in [-1, 0, MeshWire.messageMaxHops + 1, Int.max] {
            XCTAssertThrowsError(try make(Data([1]), sender: sender, receiver: receiver, hops: hops))
        }
        for time in [Int64(0), Int64(-1)] {
            XCTAssertThrowsError(try make(Data([1]), sender: sender, receiver: receiver, time: time))
        }
        for node in ["", " ", "a|b", "a\nb", String(repeating: "x", count: 129)] {
            XCTAssertThrowsError(try make(Data([1]), sender: LocalIdentity(nodeId: node), receiver: receiver))
            XCTAssertThrowsError(try make(Data([1]), sender: sender, receiver: receiver, node: node))
        }
    }

    func testMaximumPayloadAndHopBoundariesRoundTrip() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity()
        let clear = Data(repeating: 0xa5, count: PacketCrypto.maximumOutgoingPlaintextBytes)
        for hops in [1, MeshWire.messageMaxHops] {
            let packet = try make(clear, sender: sender, receiver: receiver, hops: hops)
            XCTAssertLessThanOrEqual(try JSONEncoder().encode(packet).count, MeshWire.maximumFrameBytes)
            XCTAssertEqual(try PacketCrypto.decrypt(packet, identity: receiver,
                trustedNodeId: sender.nodeId, trustedFingerprint: sender.publicFingerprint), clear)
        }
    }

    func testSignedFieldsRejectTampering() throws {
        let sender = LocalIdentity(), receiver = LocalIdentity()
        let packet = try make(Data("private".utf8), sender: sender, receiver: receiver)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(packet)) as? [String: Any])
        let changes: [String: Any] = ["id": "other", "senderAlias": "another", "createdAtMs": timestamp + 1,
            "maxHops": 7, "nonce": Data(repeating: 0, count: 12).base64EncodedString(),
            "ciphertext": Data(repeating: 0, count: 24).base64EncodedString(),
            "ephemeralPublicKey": receiver.encryptionKey.publicKey.derRepresentation.base64EncodedString()]
        for (field, value) in changes {
            var altered = object; altered[field] = value
            let changed = try JSONDecoder().decode(SecureMessagePacket.self, from: JSONSerialization.data(withJSONObject: altered))
            XCTAssertThrowsError(try PacketCrypto.decrypt(changed, identity: receiver,
                trustedNodeId: sender.nodeId, trustedFingerprint: sender.publicFingerprint), field)
        }
    }
}
