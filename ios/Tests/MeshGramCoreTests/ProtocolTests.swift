import CryptoKit
import Foundation
import XCTest
@testable import MeshGramCore

final class ProtocolTests: XCTestCase {
    private struct Vector: Decodable {
        let hello: HelloPacket
        let packet: SecureMessagePacket
        let privateScalar: String
        let plaintext: String
        let helloBytes: String
        let messageBytes: String
    }
    private struct StoredTestIdentity: Codable {
        let nodeId: String
        let encryption: Data
        let signing: Data
    }
    func testLocalIdentityPublicFingerprintRoundTrip() throws {
        let original = LocalIdentity(nodeId: "round-trip-test-only")
        let encoded = try JSONEncoder().encode(StoredTestIdentity(
            nodeId: original.nodeId, encryption: original.encryptionKey.rawRepresentation,
            signing: original.signingKey.rawRepresentation))
        let stored = try JSONDecoder().decode(StoredTestIdentity.self, from: encoded)
        let restored = try LocalIdentity(nodeId: stored.nodeId,
            encryptionKey: P256.KeyAgreement.PrivateKey(rawRepresentation: stored.encryption),
            signingKey: P256.Signing.PrivateKey(rawRepresentation: stored.signing))
        XCTAssertEqual(restored.nodeId, original.nodeId)
        XCTAssertEqual(restored.encryptionKey.publicKey.derRepresentation, original.encryptionKey.publicKey.derRepresentation)
        XCTAssertEqual(restored.signingKey.publicKey.derRepresentation, original.signingKey.publicKey.derRepresentation)
        XCTAssertEqual(restored.publicFingerprint, original.publicFingerprint)
        XCTAssertEqual(restored.publicFingerprint, PacketCrypto.fingerprint(
            encryptionKey: restored.encryptionKey.publicKey.derRepresentation.base64EncodedString(),
            signingKey: restored.signingKey.publicKey.derRepresentation.base64EncodedString()))
        XCTAssertNotNil(restored.publicFingerprint.range(of: "^[0-9a-f]{64}$", options: .regularExpression))
        let replacedSigningKey = LocalIdentity(nodeId: original.nodeId, encryptionKey: original.encryptionKey)
        XCTAssertNotEqual(replacedSigningKey.publicFingerprint, original.publicFingerprint)
    }
    func testMakeHelloSelfSignature() throws {
        let identity = LocalIdentity()
        let packet = try PacketCrypto.makeHello(identity: identity, createdAtMs: 1_700_000_000_000)
        try PacketCrypto.verifyHello(packet)
        XCTAssertEqual(packet.type, "HELLO_V1")
        XCTAssertEqual(packet.originNodeId, identity.nodeId)
        XCTAssertEqual(packet.relayNodeId, identity.nodeId)
        XCTAssertEqual(packet.createdAtMs, 1_700_000_000_000)
        XCTAssertEqual(packet.hops, 0)
        // Android's anonymous HELLO admission requires a positive hop budget.
        XCTAssertEqual(packet.maxHops, 1)
        XCTAssertNotNil(UUID(uuidString: packet.frameId))
        XCTAssertEqual(packet.frameId, packet.frameId.lowercased())
        let second = try PacketCrypto.makeHello(identity: identity, createdAtMs: packet.createdAtMs,
                                                maxHops: MeshWire.helloMaxHops)
        XCTAssertNotEqual(second.frameId, packet.frameId)
        XCTAssertEqual(second.maxHops, MeshWire.helloMaxHops)
        try PacketCrypto.verifyHello(second)
    }
    func testMakeHelloPublicProfile() throws {
        let identity = LocalIdentity()
        let packet = try PacketCrypto.makeHello(identity: identity, createdAtMs: 1_700_000_000_000)
        XCTAssertEqual(packet.alias, "Узел MeshGram")
        XCTAssertEqual(packet.profileVersion, 2)
        XCTAssertEqual(packet.discoverable, false)
        XCTAssertNil(packet.avatarData)
        XCTAssertEqual(packet.encryptionPublicKey, identity.encryptionKey.publicKey.derRepresentation.base64EncodedString())
        XCTAssertEqual(packet.signingPublicKey, identity.signingKey.publicKey.derRepresentation.base64EncodedString())
        XCTAssertEqual(packet.fingerprint, identity.publicFingerprint)
        let json = try XCTUnwrap(String(data: JSONEncoder().encode(packet), encoding: .utf8))
        XCTAssertFalse(json.contains(identity.encryptionKey.rawRepresentation.base64EncodedString()))
        XCTAssertFalse(json.contains(identity.signingKey.rawRepresentation.base64EncodedString()))
    }
    func testMakeHelloSerializationRoundTrip() throws {
        let packet = try PacketCrypto.makeHello(identity: LocalIdentity(), createdAtMs: 1_700_000_000_000)
        let restored = try JSONDecoder().decode(HelloPacket.self, from: JSONEncoder().encode(packet))
        XCTAssertEqual(restored.signingBytes, packet.signingBytes)
        XCTAssertEqual(restored.signature, packet.signature)
        try PacketCrypto.verifyHello(restored)
    }
    func testMakeHelloTamperingRejected() throws {
        let packet = try PacketCrypto.makeHello(identity: LocalIdentity(), createdAtMs: 1_700_000_000_000)
        let original = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(packet)) as? [String: Any])
        let mutations: [String: Any] = ["alias": "changed", "originNodeId": "another-node",
            "frameId": UUID().uuidString, "createdAtMs": packet.createdAtMs + 1, "maxHops": 2,
            "discoverable": true, "avatarData": "changed", "signature": "AA==",
            "fingerprint": String(repeating: "0", count: 64)]
        for (field, value) in mutations {
            var changed = original
            changed[field] = value
            let altered = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: changed))
            XCTAssertThrowsError(try PacketCrypto.verifyHello(altered), field)
        }
    }
    func testMakeHelloInvalidInputs() throws {
        for nodeId in ["", " ", "bad|node", "bad\nnode", String(repeating: "a", count: 129)] {
            XCTAssertThrowsError(try PacketCrypto.makeHello(identity: LocalIdentity(nodeId: nodeId), createdAtMs: 1))
        }
        let identity = LocalIdentity()
        for timestamp: Int64 in [0, -1] {
            XCTAssertThrowsError(try PacketCrypto.makeHello(identity: identity, createdAtMs: timestamp))
        }
        for hops in [-1, 0, MeshWire.helloMaxHops + 1] {
            XCTAssertThrowsError(try PacketCrypto.makeHello(identity: identity, createdAtMs: 1, maxHops: hops))
        }
    }
    private func fixture() throws -> Vector {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "android-v1", withExtension: "json", subdirectory: "Fixtures"))
        return try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
    }
    func testSignedHelloUsesAndroidCanonicalBytesAndDER() throws {
        let vector = try fixture()
        XCTAssertEqual(vector.hello.signingBytes, Data(base64Encoded: vector.helloBytes))
        try PacketCrypto.verifyHello(vector.hello)
        let data = try JSONEncoder().encode(vector.hello)
        var changed = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        changed["discoverable"] = false
        let altered = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: changed))
        XCTAssertThrowsError(try PacketCrypto.verifyHello(altered))
    }
    func testDecryptAndroidWireFormatAndRejectUntrustedSender() throws {
        let vector = try fixture()
        let bytes = try XCTUnwrap(Data(base64Encoded: vector.privateScalar))
        let identity = try LocalIdentity(nodeId: vector.packet.targetNodeId,
                                        encryptionKey: P256.KeyAgreement.PrivateKey(rawRepresentation: bytes))
        XCTAssertEqual(vector.packet.signingBytes, Data(base64Encoded: vector.messageBytes))
        let clear = try PacketCrypto.decrypt(vector.packet, identity: identity,
                                             trustedNodeId: vector.packet.originNodeId, trustedFingerprint: vector.hello.fingerprint)
        XCTAssertEqual(clear, Data(base64Encoded: vector.plaintext))
        XCTAssertThrowsError(try PacketCrypto.decrypt(vector.packet, identity: identity,
                                                     trustedNodeId: vector.packet.originNodeId, trustedFingerprint: "untrusted"))
        XCTAssertThrowsError(try PacketCrypto.decrypt(vector.packet, identity: identity,
                                                     trustedNodeId: "another-node", trustedFingerprint: vector.hello.fingerprint))
        XCTAssertThrowsError(try PacketCrypto.decrypt(vector.packet, identity: LocalIdentity(),
                                                     trustedNodeId: vector.packet.originNodeId, trustedFingerprint: vector.hello.fingerprint))
    }
    func testAndroidHeaderAndOutOfOrderAssembly() throws {
        let bytes = Data((0..<100).map(UInt8.init))
        let packets = try FrameCodec.packetize(bytes, frameKey: 0x01020304)
        XCTAssertEqual(Array(packets[0].prefix(8)), [0x4d, 1, 2, 3, 4, 9, 0, 0])
        var assembler = FrameAssembler()
        var result: Data?
        for packet in packets.reversed() { result = try assembler.receive(packet, now: 10) ?? result }
        XCTAssertEqual(result, bytes)
    }
    func testMalformedConflictingAndOversizedFramesAreRejected() throws {
        var assembler = FrameAssembler()
        XCTAssertThrowsError(try assembler.receive(Data([0x4d]), now: 10))
        let payload = Data(repeating: 1, count: 30)
        let packets = try FrameCodec.packetize(payload, frameKey: 1)
        XCTAssertNil(try assembler.receive(packets[0], now: 10))
        XCTAssertNil(try assembler.receive(packets[0], now: 10))
        var conflicting = packets[0]; conflicting[8] = 2
        XCTAssertThrowsError(try assembler.receive(conflicting, now: 10))
        XCTAssertThrowsError(try FrameCodec.packetize(Data(), frameKey: 1))
        XCTAssertThrowsError(try FrameCodec.packetize(Data(repeating: 0, count: 45_901), frameKey: 1))
        XCTAssertThrowsError(try FrameCodec.packetize(payload, frameKey: 1, chunkSize: 11))
    }
    func testExpiredFragmentsCannotCompleteAFrame() throws {
        let packets = try FrameCodec.packetize(Data(repeating: 3, count: 20), frameKey: 1)
        var assembler = FrameAssembler()
        XCTAssertNil(try assembler.receive(packets[0], now: 10))
        XCTAssertNil(try assembler.receive(packets[1], now: 41))
        XCTAssertNotNil(try assembler.receive(packets[0], now: 42))
    }
}
