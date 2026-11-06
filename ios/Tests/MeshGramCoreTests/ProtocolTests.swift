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
