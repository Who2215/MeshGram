import Foundation

public struct PreparedFriendshipPacket: Equatable {
    public let action: FriendPacket.Action
    public let peerNodeId: String
    public let peerFingerprint: String
    public let requestId: String
    public let frameId: String
    public let createdAtMs: Int64
    public let wireData: Data

    internal static func make(_ action: FriendPacket.Action, state: FriendState, identity: LocalIdentity,
                              recipient: HelloPacket, pins: ContactBook, ownAlias: String,
                              ownAvatarData: String, now: Int64) throws -> Self {
        guard recipient.originNodeId != identity.nodeId,
              let pin = pins.contacts.first(where: { $0.nodeId == recipient.originNodeId }),
              pin.fingerprint == recipient.fingerprint, pin.encryptionPublicKey == recipient.encryptionPublicKey,
              pin.signingPublicKey == recipient.signingPublicKey,
              let record = state.records.first(where: { $0.nodeId == pin.nodeId && $0.fingerprint == pin.fingerprint }),
              !record.blocked else { throw ConsentError.unauthorized }
        let age = now.subtractingReportingOverflow(record.updatedAtMs)
        guard now > 0, record.updatedAtMs > 0, !age.overflow,
              age.partialValue >= -300_000, age.partialValue <= FriendState.requestLifetimeMs else { throw ConsentError.expired }
        let requestId: String, token: String
        switch action {
        case .request:
            guard !record.accepted, let id = record.outgoingId else { throw ConsentError.unauthorized }
            requestId = id; token = record.outgoingInviteToken
        case .accept:
            guard record.accepted, let id = record.incomingId else { throw ConsentError.unauthorized }
            requestId = id; token = ""
        case .profile:
            throw ConsentError.unauthorized
        }
        let payload = try FriendPacket(action: action, requestId: requestId, alias: ownAlias,
                                       avatarData: ownAvatarData, inviteToken: token)
        let packet = try PacketCrypto.encrypt(JSONEncoder().encode(payload), identity: identity, recipient: recipient,
            trustedNodeId: pin.nodeId, trustedFingerprint: pin.fingerprint, createdAtMs: now)
        return Self(action: action, peerNodeId: pin.nodeId, peerFingerprint: pin.fingerprint,
                    requestId: requestId, frameId: packet.id, createdAtMs: now,
                    wireData: try KnownPayload.shortGattData(packet))
    }
}
