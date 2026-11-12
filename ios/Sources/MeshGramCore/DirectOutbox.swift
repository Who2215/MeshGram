import Foundation

public struct PreparedDirectMessage: Equatable {
    public let localNodeId: String
    public let peerNodeId: String
    public let peerFingerprint: String
    public let chatId: String
    public let logicalMessageId: String
    public let frameId: String
    public let createdAtMs: Int64
    public let wireData: Data

    public static func make(text: String, messageId: String, identity: LocalIdentity, recipient: HelloPacket,
                            pins: ContactBook, friendship: FriendState, nowMs: Int64) throws -> Self {
        guard nowMs > 0, validOutboxNode(identity.nodeId), validOutboxNode(recipient.originNodeId),
              friendship.isFriend(recipient.originNodeId, fingerprint: recipient.fingerprint),
              let pin = pins.contacts.first(where: { $0.nodeId == recipient.originNodeId }),
              pin.fingerprint == recipient.fingerprint, pin.encryptionPublicKey == recipient.encryptionPublicKey,
              pin.signingPublicKey == recipient.signingPublicKey else { throw ConsentError.unauthorized }
        let chatId = try DirectPayload.conversationId(identity.nodeId, pin.nodeId)
        let payload = try DirectPayload(chatId: chatId, memberNodeIds: [identity.nodeId, pin.nodeId],
                                        text: text, messageId: messageId, sentAtMs: nowMs)
        let packet = try PacketCrypto.encrypt(JSONEncoder().encode(payload), identity: identity, recipient: recipient,
            trustedNodeId: pin.nodeId, trustedFingerprint: pin.fingerprint, createdAtMs: nowMs)
        return Self(localNodeId: identity.nodeId, peerNodeId: pin.nodeId, peerFingerprint: pin.fingerprint,
                    chatId: chatId, logicalMessageId: messageId, frameId: packet.id, createdAtMs: nowMs,
                    wireData: try KnownPayload.shortGattData(packet))
    }
}

private func validOutboxNode(_ id: String) -> Bool {
    !id.isEmpty && id == id.trimmingCharacters(in: .whitespacesAndNewlines) && id.utf8.count <= 128 &&
        !id.contains(":") && !id.contains("|") && id.rangeOfCharacter(from: .controlCharacters) == nil
}

// No persistence/restore API: a future controller must commit encrypted state before sending.
public struct DirectOutbox {
    public static let maximumEntries = 100
    public enum Phase: Equatable { case prepared, written, delivered }
    public enum Outcome: Equatable { case changed, ignored }
    public struct Entry: Equatable {
        public let message: PreparedDirectMessage
        public fileprivate(set) var phase: Phase
    }
    public let localNodeId: String
    public private(set) var entries: [Entry] = []

    public init(localNodeId: String) throws {
        guard validOutboxNode(localNodeId) else { throw ConsentError.invalidState }
        self.localNodeId = localNodeId
    }

    @discardableResult
    public mutating func enqueue(_ message: PreparedDirectMessage, friendship: FriendState, nowMs: Int64) throws -> Outcome {
        guard message.localNodeId == localNodeId,
              friendship.isFriend(message.peerNodeId, fingerprint: message.peerFingerprint),
              message.chatId == (try DirectPayload.conversationId(localNodeId, message.peerNodeId)) else {
            throw ConsentError.unauthorized
        }
        try Self.requireRecent(message.createdAtMs, now: nowMs)
        if let existing = entries.first(where: { $0.message.logicalMessageId == message.logicalMessageId || $0.message.frameId == message.frameId }) {
            guard existing.message == message else { throw ConsentError.invalidState }
            return .ignored
        }
        guard entries.count < Self.maximumEntries else { throw ConsentError.capacity }
        entries.append(Entry(message: message, phase: .prepared))
        return .changed
    }

    // The adapter must bind this callback to its active write session; ATT is not app delivery.
    @discardableResult
    public mutating func markATTWritten(frameId: String, logicalMessageId: String, peerNodeId: String,
                                        succeeded: Bool) -> Outcome {
        guard succeeded, let index = entries.firstIndex(where: {
            $0.message.frameId == frameId && $0.message.logicalMessageId == logicalMessageId && $0.message.peerNodeId == peerNodeId
        }), entries[index].phase == .prepared else { return .ignored }
        entries[index].phase = .written
        return .changed
    }

    @discardableResult
    public mutating func acknowledgeDelivery(_ verified: AuthenticatedPayload, friendship: FriendState,
                                             nowMs: Int64) throws -> Outcome {
        try Self.requireRecent(verified.createdAtMs, now: nowMs)
        guard case .direct(let decoded) = try KnownPayload.decode(verified.data), decoded.payloadKind == .deliveryAck else { return .ignored }
        let payload = try KnownPayload.authorizedDirect(verified, localNodeId: localNodeId, state: friendship)
        guard let index = entries.firstIndex(where: {
            $0.message.logicalMessageId == payload.ackMessageId && $0.message.peerNodeId == verified.senderNodeId &&
                $0.message.peerFingerprint == verified.fingerprint && $0.message.chatId == payload.chatId
        }) else { return .ignored }
        let message = entries[index].message
        try Self.requireRecent(message.createdAtMs, now: nowMs)
        let sinceCreation = verified.createdAtMs.subtractingReportingOverflow(message.createdAtMs)
        guard !sinceCreation.overflow, sinceCreation.partialValue >= -300_000 else { throw ConsentError.expired }
        guard entries[index].phase != .delivered else { return .ignored }
        entries[index].phase = .delivered
        return .changed
    }

    private static func requireRecent(_ time: Int64, now: Int64) throws {
        let age = now.subtractingReportingOverflow(time)
        guard now > 0, time > 0, !age.overflow, age.partialValue >= -300_000,
              age.partialValue <= FriendState.requestLifetimeMs else { throw ConsentError.expired }
    }
}
