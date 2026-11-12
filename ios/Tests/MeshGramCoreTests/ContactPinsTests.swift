import CryptoKit
import Foundation
import XCTest
@testable import MeshGramCore

final class ContactPinsTests: XCTestCase {
    private let time: Int64 = 1_700_000_000_000
    private let local = "local-iphone-test"
    private enum StorageFailure: Error { case locked }
    private final class Memory: ContactPinStorage {
        var records: [ConfirmedContact] = []
        var addCalls = 0
        var failRead = false
        var failAdd = false
        var failReadback = false
        func read() throws -> [ConfirmedContact] {
            if failRead || (failReadback && addCalls > 0) { throw StorageFailure.locked }
            return records
        }
        func add(_ contact: ConfirmedContact) throws -> ConfirmedContact {
            addCalls += 1
            if failAdd { throw StorageFailure.locked }
            if let existing = records.first(where: { $0.nodeId == contact.nodeId }) {
                guard existing.hasSameKeys(as: contact) else { throw ContactPinError.changedKey }
                return existing
            }
            records.append(contact)
            return contact
        }
    }
    private func observe(_ identity: LocalIdentity, gate: inout ContactConfirmationGate,
                         peer: UUID = UUID(), at: Int64? = nil) throws -> ContactConfirmationGate.Candidate {
        let hello = try PacketCrypto.makeHello(identity: identity, createdAtMs: at ?? time)
        try gate.observe(hello, peerId: peer, localNodeId: local, nowMs: time)
        return try XCTUnwrap(gate.candidate(for: peer))
    }
    private func confirm(_ candidate: ContactConfirmationGate.Candidate, gate: ContactConfirmationGate,
                         storage: Memory, at: Int64? = nil) throws -> ContactBook {
        try ContactPins.confirm(candidate, gate: gate, storage: storage, confirmedAtMs: at ?? time)
    }

    func testObservationDoesNotPersistWithoutExplicitConfirmation() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        let candidate = try observe(LocalIdentity(), gate: &gate)
        XCTAssertTrue(try ContactPins.load(from: storage).contacts.isEmpty)
        XCTAssertNotNil(gate.candidate(for: candidate.peerId))
        XCTAssertEqual(storage.addCalls, 0)
    }
    func testExplicitConfirmationPersistsAndRestoresExactKeys() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        let identity = LocalIdentity(), candidate = try observe(identity, gate: &gate)
        let book = try confirm(candidate, gate: gate, storage: storage)
        let record = try XCTUnwrap(book.contacts.first)
        XCTAssertEqual(record.nodeId, identity.nodeId)
        XCTAssertEqual(record.fingerprint, identity.publicFingerprint)
        XCTAssertEqual(record.confirmedAtMs, time)
        XCTAssertEqual(try ContactBook(encoded: book.encoded()).contacts, book.contacts)
        XCTAssertEqual(try ContactPins.load(from: storage).contacts, book.contacts)
    }
    func testDuplicateConfirmationPreservesOriginalTimestamp() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        let candidate = try observe(LocalIdentity(), gate: &gate)
        let original = try confirm(candidate, gate: gate, storage: storage)
        XCTAssertEqual(try confirm(candidate, gate: gate, storage: storage, at: time + 1).contacts, original.contacts)
        XCTAssertEqual(storage.records.count, 1)
    }
    func testChangedKeysCannotReplaceExistingPin() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        let original = LocalIdentity(), peer = UUID()
        let first = try observe(original, gate: &gate, peer: peer)
        let book = try confirm(first, gate: gate, storage: storage)
        let changed = LocalIdentity(nodeId: original.nodeId)
        let next = try observe(changed, gate: &gate, peer: peer)
        XCTAssertThrowsError(try confirm(next, gate: gate, storage: storage))
        XCTAssertEqual(storage.addCalls, 1)
        XCTAssertEqual(storage.records, book.contacts)
        XCTAssertEqual(book.match(nodeId: original.nodeId, fingerprint: changed.publicFingerprint), .changed)
    }
    func testStopDisconnectAndRestartInvalidatePendingConfirmation() throws {
        let storage = Memory(); var gate = ContactConfirmationGate(); let peer = UUID(), identity = LocalIdentity()
        let old = try observe(identity, gate: &gate, peer: peer)
        gate.invalidate(peer)
        XCTAssertThrowsError(try confirm(old, gate: gate, storage: storage))
        let second = try observe(identity, gate: &gate, peer: peer)
        gate.invalidateAll()
        _ = try observe(identity, gate: &gate, peer: peer)
        XCTAssertThrowsError(try confirm(second, gate: gate, storage: storage))
        XCTAssertEqual(storage.addCalls, 0)
    }
    func testEvenSameKeyNewHelloInvalidatesOldSnapshot() throws {
        let storage = Memory(); var gate = ContactConfirmationGate(); let peer = UUID(), identity = LocalIdentity()
        let old = try observe(identity, gate: &gate, peer: peer)
        let new = try observe(identity, gate: &gate, peer: peer)
        XCTAssertNotEqual(old.id, new.id)
        XCTAssertThrowsError(try confirm(old, gate: gate, storage: storage))
        XCTAssertEqual(try confirm(new, gate: gate, storage: storage).contacts.count, 1)
    }
    func testInvalidOrRelayedHelloCannotCreateCandidate() throws {
        var gate = ContactConfirmationGate(); let peer = UUID(), identity = LocalIdentity()
        let hello = try PacketCrypto.makeHello(identity: identity, createdAtMs: time)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(hello)) as? [String: Any])
        object["hops"] = 1
        let relayed = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try gate.observe(relayed, peerId: peer, localNodeId: local, nowMs: time))
        object["hops"] = 0; object["signature"] = "invalid"
        let altered = try JSONDecoder().decode(HelloPacket.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try gate.observe(altered, peerId: peer, localNodeId: local, nowMs: time))
        XCTAssertNil(gate.candidate(for: peer))
        XCTAssertThrowsError(try gate.observe(hello, peerId: peer, localNodeId: identity.nodeId, nowMs: time))
    }
    func testExpiredFutureAndOverflowTimesFailClosed() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        let candidate = try observe(LocalIdentity(), gate: &gate)
        XCTAssertThrowsError(try confirm(candidate, gate: gate, storage: storage, at: time + 900_001))
        XCTAssertThrowsError(try confirm(candidate, gate: gate, storage: storage, at: -1))
        XCTAssertThrowsError(try confirm(candidate, gate: gate, storage: storage, at: Int64.min))
        XCTAssertThrowsError(try observe(LocalIdentity(), gate: &gate, at: time + 120_001))
        XCTAssertEqual(storage.addCalls, 0)
    }
    func testLockedReadFailedWriteAndFailedReadbackNeverReturnTrustedBook() throws {
        var gate = ContactConfirmationGate(); let candidate = try observe(LocalIdentity(), gate: &gate)
        let locked = Memory(); locked.failRead = true
        XCTAssertThrowsError(try ContactPins.load(from: locked))
        XCTAssertThrowsError(try confirm(candidate, gate: gate, storage: locked))
        XCTAssertEqual(locked.addCalls, 0)
        let failed = Memory(); failed.failAdd = true
        XCTAssertThrowsError(try confirm(candidate, gate: gate, storage: failed))
        XCTAssertTrue(failed.records.isEmpty)
        let readback = Memory(); readback.failReadback = true
        XCTAssertThrowsError(try confirm(candidate, gate: gate, storage: readback))
        XCTAssertEqual(readback.records.count, 1) // A persisted write is retained, not erased on readback failure.
    }
    func testCorruptVersionDuplicateAndMalformedRecordStorageIsRejected() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        let candidate = try observe(LocalIdentity(), gate: &gate)
        let book = try confirm(candidate, gate: gate, storage: storage)
        XCTAssertThrowsError(try ContactBook(encoded: Data("not json".utf8)))
        XCTAssertThrowsError(try ContactBook(encoded: Data(repeating: 0, count: ContactBook.maximumEncodedBytes + 1)))
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: book.encoded()) as? [String: Any])
        envelope["version"] = 2
        XCTAssertThrowsError(try ContactBook(encoded: JSONSerialization.data(withJSONObject: envelope)))
        XCTAssertThrowsError(try ContactBook(contacts: storage.records + storage.records))
        let original = try XCTUnwrap(storage.records.first)
        let originalData = try JSONEncoder().encode(original)
        for (field, value) in ["nodeId": "a|b", "fingerprint": original.fingerprint + "\n", "encryptionPublicKey": "invalid"] {
            var object = try XCTUnwrap(JSONSerialization.jsonObject(with: originalData) as? [String: Any]); object[field] = value
            let bad = try JSONDecoder().decode(ConfirmedContact.self, from: JSONSerialization.data(withJSONObject: object))
            XCTAssertThrowsError(try ContactBook(contacts: [bad]))
        }
    }
    func testContactCapacityDoesNotOverwriteExistingRecords() throws {
        let storage = Memory(); var gate = ContactConfirmationGate()
        for _ in 0..<ContactBook.maximumContacts {
            let candidate = try observe(LocalIdentity(), gate: &gate)
            _ = try confirm(candidate, gate: gate, storage: storage)
            gate.invalidateAll()
        }
        let before = storage.records
        let next = try observe(LocalIdentity(), gate: &gate)
        XCTAssertThrowsError(try confirm(next, gate: gate, storage: storage))
        XCTAssertEqual(storage.records, before)
        XCTAssertEqual(storage.addCalls, ContactBook.maximumContacts)
    }
    func testObservationCapacityIsBounded() throws {
        var gate = ContactConfirmationGate(); let identity = LocalIdentity()
        for _ in 0..<128 { _ = try observe(identity, gate: &gate) }
        XCTAssertThrowsError(try observe(identity, gate: &gate))
        gate.invalidateAll()
        XCTAssertNoThrow(try observe(identity, gate: &gate))
    }
}
