import CryptoKit
import Foundation
import XCTest
@testable import MeshGramCore

final class EncryptedFriendStateTests: XCTestCase {
    private let key = SymmetricKey(size: .bits256)
    private let fp = String(repeating: "a", count: 64)
    private func seal(_ state: FriendState) throws -> Data {
        try EncryptedFriendState.seal(state, key: key, localNodeId: "local", ownFingerprint: fp)
    }
    private func open(_ blob: Data) throws -> FriendState {
        try EncryptedFriendState.open(blob, key: key, localNodeId: "local", ownFingerprint: fp)
    }
    // Independent formatter tests the documented wire/AAD layout, not the codec's own helper.
    private func wrap(_ clear: Data, domain: String = "com.meshgram.ios.friendstate.v1") throws -> Data {
        let header = Data([77, 71, 70, 83, 1])
        var aad = Data()
        for field in [Data(domain.utf8), header, Data("local".utf8), Data(fp.utf8)] {
            let n = field.count
            aad.append(contentsOf: [UInt8((n >> 24) & 255), UInt8((n >> 16) & 255), UInt8((n >> 8) & 255), UInt8(n & 255)])
            aad.append(field)
        }
        return header + (try XCTUnwrap(AES.GCM.seal(clear, using: key, authenticating: aad).combined))
    }
    private func record(_ node: String, avatar: String = "") throws -> FriendRecord {
        try FriendRecord(nodeId: node, fingerprint: fp, alias: "MeshGramSecretAlias012345", avatarData: avatar,
            outgoingId: "outgoing-123456789", outgoingInviteToken: "secret-invite-0123456789", incomingId: "incoming-123456789",
            accepted: true, blocked: node == "blocked", updatedAtMs: 1_800_000_000_000)
    }

    func testCompleteStateRoundTripKeepsConsentAndDoesNotExposePlaintext() throws {
        let state = try FriendState(discoverable: true, inviteToken: "secret-invite-0123456789", inviteExpiresAtMs: 1_800_000_000_001,
            records: [record("friend", avatar: "Аватар 🙂"), record("blocked")])
        let blob = try seal(state)
        XCTAssertEqual(try open(blob), state)
        for secret in ["MeshGramSecretAlias012345", "secret-invite-0123456789", "Аватар 🙂", "accepted", "records"] {
            XCTAssertNil(blob.range(of: Data(secret.utf8)))
        }
        XCTAssertEqual(blob.prefix(5), Data([77, 71, 70, 83, 1]))
    }
    func testEmptyStateRemainsPrivateAndOldValidBlobIsNotRollbackProtected() throws {
        let state = try FriendState(), old = try seal(state)
        XCTAssertEqual(try open(old), state)
        XCTAssertFalse(try open(old).discoverable)
        _ = try seal(state.settingDiscoverable(true))
        XCTAssertEqual(try open(old), state)
    }
    func testEverySealUsesFreshNonceAndCiphertext() throws {
        let state = try FriendState(records: [record("peer")])
        let first = try seal(state), second = try seal(state)
        XCTAssertNotEqual(first, second)
        XCTAssertNotEqual(first.subdata(in: 5..<17), second.subdata(in: 5..<17))
        XCTAssertEqual(try open(first), try open(second))
    }
    func testWrongKeyAndUnsupportedKeySizesThrowWithoutReset() throws {
        let state = try FriendState(records: [record("peer")]), blob = try seal(state)
        for candidate in [SymmetricKey(size: .bits128), SymmetricKey(size: .bits192), SymmetricKey(size: .bits256)] {
            XCTAssertThrowsError(try EncryptedFriendState.open(blob, key: candidate, localNodeId: "local", ownFingerprint: fp))
        }
        for candidate in [SymmetricKey(size: .bits128), SymmetricKey(size: .bits192)] {
            XCTAssertThrowsError(try EncryptedFriendState.seal(state, key: candidate, localNodeId: "local", ownFingerprint: fp))
        }
        XCTAssertEqual(try open(blob), state)
    }
    func testAnotherNodeFingerprintOrDomainCannotOpenBlob() throws {
        let blob = try seal(FriendState())
        XCTAssertThrowsError(try EncryptedFriendState.open(blob, key: key, localNodeId: "other", ownFingerprint: fp))
        XCTAssertThrowsError(try EncryptedFriendState.open(blob, key: key, localNodeId: "local", ownFingerprint: String(repeating: "b", count: 64)))
        XCTAssertThrowsError(try open(wrap(Data("{}".utf8), domain: "different-domain")))
    }
    func testInvalidContextIsRejectedOnBothPaths() throws {
        let state = try FriendState(), blob = try seal(state)
        for node in ["", " local", "local ", "a|b", "a\nb", String(repeating: "a", count: 129)] {
            XCTAssertThrowsError(try EncryptedFriendState.seal(state, key: key, localNodeId: node, ownFingerprint: fp))
            XCTAssertThrowsError(try EncryptedFriendState.open(blob, key: key, localNodeId: node, ownFingerprint: fp))
        }
        for fingerprint in ["", String(repeating: "a", count: 63), String(repeating: "A", count: 64), String(repeating: "g", count: 64), fp + "\n"] {
            XCTAssertThrowsError(try EncryptedFriendState.seal(state, key: key, localNodeId: "local", ownFingerprint: fingerprint))
            XCTAssertThrowsError(try EncryptedFriendState.open(blob, key: key, localNodeId: "local", ownFingerprint: fingerprint))
        }
    }
    func testHeaderNonceCiphertextTagTamperingTruncationAndEmptyAreRejected() throws {
        let blob = try seal(FriendState(records: [record("peer")]))
        for offset in [0, 4, 5, 16, 17, blob.count - 1] {
            var changed = blob
            changed[offset] ^= 1
            XCTAssertThrowsError(try open(changed))
        }
        for count in [0, 1, 4, 5, 16, 32, blob.count - 1] { XCTAssertThrowsError(try open(Data(blob.prefix(count)))) }
        XCTAssertThrowsError(try open(blob + Data([0])))
    }
    func testAuthenticatedMalformedJsonAndInvalidRecordsThrowNotEmptyState() throws {
        for raw in ["broken", "[]", "{\"records\":\"wrong\"}", "{\"inviteExpiresAtMs\":-1}"] {
            XCTAssertThrowsError(try open(wrap(Data(raw.utf8))))
        }
        let good = try record("peer"), encoded = try JSONEncoder().encode(good)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        let duplicate = try JSONSerialization.data(withJSONObject: ["records": [object, object]])
        XCTAssertThrowsError(try open(wrap(duplicate)))
        var bad = object
        bad["nodeId"] = "bad|node"
        XCTAssertThrowsError(try open(wrap(JSONSerialization.data(withJSONObject: ["records": [bad]]))))
        var tooMany: [[String: Any]] = []
        for i in 0...1000 { var row = object; row["nodeId"] = "p\(i)"; tooMany.append(row) }
        XCTAssertThrowsError(try open(wrap(JSONSerialization.data(withJSONObject: ["records": tooMany]))))
    }
    func testExactBlobLimitAndOneByteAboveWithoutTruncation() throws {
        var records = try (0..<43).map { try record("p\($0)", avatar: String(repeating: "a", count: 23_500)) }
        records.append(try record("last"))
        let base = try FriendState(records: records)
        let remaining = EncryptedFriendState.maximumPlaintextBytes - (try JSONEncoder().encode(base).count)
        guard (0..<24_000).contains(remaining) else { return XCTFail("Unexpected fixture size") }
        records[43] = try record("last", avatar: String(repeating: "a", count: remaining))
        let exact = try FriendState(records: records), blob = try seal(exact)
        XCTAssertEqual(try JSONEncoder().encode(exact).count, EncryptedFriendState.maximumPlaintextBytes)
        XCTAssertEqual(blob.count, EncryptedFriendState.maximumBlobBytes)
        XCTAssertEqual(try open(blob), exact)
        records[43] = try record("last", avatar: String(repeating: "a", count: remaining + 1))
        XCTAssertThrowsError(try seal(FriendState(records: records)))
        XCTAssertThrowsError(try open(blob + Data([0])))
    }
    func testOversizedStateAndBlobFailWithoutDroppingRecords() throws {
        let state = try FriendState(records: (0..<50).map { try record("p\($0)", avatar: String(repeating: "a", count: 24_000)) })
        XCTAssertThrowsError(try seal(state))
        XCTAssertEqual(state.records.count, 50)
        XCTAssertThrowsError(try open(Data(repeating: 0, count: EncryptedFriendState.maximumBlobBytes + 1)))
    }
}
