import CryptoKit
import Foundation

public enum ContactPinError: Error { case invalidRecord, unsupportedVersion, capacity, changedKey, staleConfirmation }

public struct ConfirmedContact: Codable, Equatable, Identifiable, Sendable {
    public let version: Int
    public let nodeId: String
    public let encryptionPublicKey: String
    public let signingPublicKey: String
    public let fingerprint: String
    public let confirmedAtMs: Int64
    public var id: String { nodeId }

    public func hasSameKeys(as other: ConfirmedContact) -> Bool {
        nodeId == other.nodeId && fingerprint == other.fingerprint &&
            encryptionPublicKey == other.encryptionPublicKey && signingPublicKey == other.signingPublicKey
    }
    fileprivate static func validateNode(_ value: String) throws {
        guard !value.isEmpty, value == value.trimmingCharacters(in: .whitespacesAndNewlines),
              value.utf8.count <= 128, !value.contains("|"),
              value.rangeOfCharacter(from: .controlCharacters) == nil else { throw ContactPinError.invalidRecord }
    }
    fileprivate func validate() throws {
        guard version == 1 else { throw ContactPinError.unsupportedVersion }
        try Self.validateNode(nodeId)
        guard confirmedAtMs > 0, fingerprint.utf8.count == 64,
              fingerprint.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              encryptionPublicKey.utf8.count <= 512, signingPublicKey.utf8.count <= 512,
              let encryption = Data(base64Encoded: encryptionPublicKey),
              let signing = Data(base64Encoded: signingPublicKey),
              encryption.base64EncodedString() == encryptionPublicKey, signing.base64EncodedString() == signingPublicKey,
              PacketCrypto.fingerprint(encryptionKey: encryptionPublicKey, signingKey: signingPublicKey) == fingerprint
        else { throw ContactPinError.invalidRecord }
        _ = try P256.KeyAgreement.PublicKey(derRepresentation: encryption)
        _ = try P256.Signing.PublicKey(derRepresentation: signing)
    }
}

public struct ContactBook {
    public static let maximumContacts = 64
    public static let maximumEncodedBytes = 65_536
    public enum Match: Equatable { case unconfirmed, confirmed, changed }
    private struct Envelope: Codable { let version: Int; let contacts: [ConfirmedContact] }
    private var byNode: [String: ConfirmedContact] = [:]
    public var contacts: [ConfirmedContact] { byNode.values.sorted { $0.nodeId < $1.nodeId } }
    public init() {}
    public init(contacts: [ConfirmedContact]) throws {
        guard contacts.count <= Self.maximumContacts else { throw ContactPinError.capacity }
        for contact in contacts {
            try contact.validate()
            guard byNode[contact.nodeId] == nil else { throw ContactPinError.invalidRecord }
            byNode[contact.nodeId] = contact
        }
        _ = try encoded()
    }
    public init(encoded: Data) throws {
        guard encoded.count <= Self.maximumEncodedBytes else { throw ContactPinError.capacity }
        let envelope = try JSONDecoder().decode(Envelope.self, from: encoded)
        guard envelope.version == 1 else { throw ContactPinError.unsupportedVersion }
        try self.init(contacts: envelope.contacts)
    }
    public func encoded() throws -> Data {
        let data = try JSONEncoder().encode(Envelope(version: 1, contacts: contacts))
        guard data.count <= Self.maximumEncodedBytes else { throw ContactPinError.capacity }
        return data
    }
    public func match(nodeId: String, fingerprint: String) -> Match {
        guard let record = byNode[nodeId] else { return .unconfirmed }
        return record.fingerprint == fingerprint ? .confirmed : .changed
    }
    fileprivate mutating func confirm(_ hello: HelloPacket, at time: Int64) throws -> ConfirmedContact {
        try PacketCrypto.verifyHello(hello)
        let record = ConfirmedContact(version: 1, nodeId: hello.originNodeId, encryptionPublicKey: hello.encryptionPublicKey,
            signingPublicKey: hello.signingPublicKey, fingerprint: hello.fingerprint, confirmedAtMs: time)
        try record.validate()
        if let existing = byNode[record.nodeId] {
            guard existing.hasSameKeys(as: record) else { throw ContactPinError.changedKey }
            return existing
        }
        guard byNode.count < Self.maximumContacts else { throw ContactPinError.capacity }
        var next = self
        next.byNode[record.nodeId] = record
        _ = try next.encoded()
        self = next
        return record
    }
}

// Volatile proof of exactly the verified snapshot shown in a confirmation alert.
public struct ContactConfirmationGate {
    public struct Candidate: Equatable, Identifiable, Sendable {
        public let id: UUID
        public let peerId: UUID
        public let nodeId: String
        public let fingerprint: String
    }
    private struct Observed { let candidate: Candidate; let hello: HelloPacket }
    private var observed: [UUID: Observed] = [:]
    public init() {}
    public mutating func observe(_ hello: HelloPacket, peerId: UUID, localNodeId: String, nowMs: Int64) throws {
        observed.removeValue(forKey: peerId)
        try PacketCrypto.verifyHello(hello)
        try ConfirmedContact.validateNode(hello.originNodeId)
        try ConfirmedContact.validateNode(localNodeId)
        guard hello.originNodeId != localNodeId else { throw ContactPinError.invalidRecord }
        try checkFresh(hello, nowMs: nowMs)
        guard observed.count < 128 else { throw ContactPinError.capacity }
        let candidate = Candidate(id: UUID(), peerId: peerId, nodeId: hello.originNodeId, fingerprint: hello.fingerprint)
        observed[peerId] = Observed(candidate: candidate, hello: hello)
    }
    public func candidate(for peerId: UUID) -> Candidate? { observed[peerId]?.candidate }
    public mutating func invalidate(_ peerId: UUID) { observed.removeValue(forKey: peerId) }
    public mutating func invalidateAll() { observed.removeAll() }
    private func checkFresh(_ hello: HelloPacket, nowMs: Int64) throws {
        let difference = hello.createdAtMs.subtractingReportingOverflow(nowMs)
        guard nowMs > 0, hello.createdAtMs > 0, hello.hops == 0, !difference.overflow,
              (-Int64(900_000)...Int64(120_000)).contains(difference.partialValue) else { throw ContactPinError.staleConfirmation }
    }
    fileprivate func hello(for candidate: Candidate, nowMs: Int64) throws -> HelloPacket {
        guard let current = observed[candidate.peerId], current.candidate == candidate else {
            throw ContactPinError.staleConfirmation
        }
        try checkFresh(current.hello, nowMs: nowMs)
        return current.hello
    }
}

public protocol ContactPinStorage {
    func read() throws -> [ConfirmedContact]
    func add(_ contact: ConfirmedContact) throws -> ConfirmedContact
}

public enum ContactPins {
    public static func load(from storage: any ContactPinStorage) throws -> ContactBook {
        try ContactBook(contacts: storage.read())
    }
    // Called only from an explicit owner action. No trust is published before persistent readback.
    public static func confirm(_ candidate: ContactConfirmationGate.Candidate, gate: ContactConfirmationGate,
                               storage: any ContactPinStorage, confirmedAtMs: Int64) throws -> ContactBook {
        let hello = try gate.hello(for: candidate, nowMs: confirmedAtMs)
        var book = try load(from: storage)
        let record = try book.confirm(hello, at: confirmedAtMs)
        let written = try storage.add(record)
        guard written.hasSameKeys(as: record) else { throw ContactPinError.changedKey }
        let persisted = try load(from: storage)
        guard let saved = persisted.contacts.first(where: { $0.nodeId == record.nodeId }),
              saved.hasSameKeys(as: record) else { throw ContactPinError.invalidRecord }
        return persisted
    }
}
