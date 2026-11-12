import Foundation

public enum ConsentError: Error { case invalidPayload, invalidState, unauthorized, expired, capacity }

// Constructed only after opening an encrypted packet against a previously confirmed pin.
public struct AuthenticatedPayload {
    public let packetId: String
    public let senderNodeId: String
    public let fingerprint: String
    public let createdAtMs: Int64
    public let hops: Int
    public let data: Data
    internal init(senderNodeId: String, fingerprint: String, createdAtMs: Int64, hops: Int, data: Data,
                  packetId: String = UUID().uuidString.lowercased()) {
        self.packetId = packetId
        self.senderNodeId = senderNodeId; self.fingerprint = fingerprint
        self.createdAtMs = createdAtMs; self.hops = hops; self.data = data
    }
    public static func open(_ packet: SecureMessagePacket, identity: LocalIdentity, pins: ContactBook) throws -> Self {
        guard packet.originNodeId != identity.nodeId, !packet.id.isEmpty, packet.id.utf8.count <= 128,
              packet.id.rangeOfCharacter(from: .controlCharacters) == nil,
              let pin = pins.contacts.first(where: { $0.nodeId == packet.originNodeId }),
              pin.encryptionPublicKey == packet.senderEncryptionPublicKey,
              pin.signingPublicKey == packet.senderSigningPublicKey,
              (1...MeshWire.messageMaxHops).contains(packet.maxHops) else { throw ConsentError.unauthorized }
        let clear = try PacketCrypto.decrypt(packet, identity: identity,
            trustedNodeId: pin.nodeId, trustedFingerprint: pin.fingerprint)
        guard !clear.isEmpty, clear.count <= MeshWire.maximumFrameBytes else { throw ConsentError.invalidPayload }
        return Self(senderNodeId: pin.nodeId, fingerprint: pin.fingerprint,
                    createdAtMs: packet.createdAtMs, hops: packet.hops, data: clear, packetId: packet.id)
    }
}

public struct FriendPacket: Codable, Equatable, Sendable {
    public enum Action: String, Codable, Sendable { case request, accept, profile }
    public let type: String
    public let action: Action
    public let requestId: String
    public let alias: String
    public let avatarData: String
    public let inviteToken: String
    private enum CodingKeys: String, CodingKey { case type, action, requestId, alias, avatarData, inviteToken }
    public init(action: Action, requestId: String, alias: String, avatarData: String = "", inviteToken: String = "") throws {
        guard (16...64).contains(requestId.utf16.count), !alias.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              alias.utf16.count <= 32, avatarData.utf16.count <= 24_000, inviteToken.utf16.count <= 64 else {
            throw ConsentError.invalidPayload
        }
        type = "MESH_FRIEND_V1"; self.action = action; self.requestId = requestId
        self.alias = alias; self.avatarData = avatarData; self.inviteToken = inviteToken
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard try c.decode(String.self, forKey: .type) == "MESH_FRIEND_V1" else { throw ConsentError.invalidPayload }
        try self.init(action: c.decode(Action.self, forKey: .action), requestId: c.decode(String.self, forKey: .requestId),
            alias: c.decode(String.self, forKey: .alias), avatarData: c.decodeIfPresent(String.self, forKey: .avatarData) ?? "",
            inviteToken: c.decodeIfPresent(String.self, forKey: .inviteToken) ?? "")
    }
}

public struct FriendRecord: Codable, Equatable, Sendable {
    public let nodeId: String
    public let fingerprint: String
    public fileprivate(set) var alias: String
    public fileprivate(set) var avatarData: String
    public fileprivate(set) var outgoingId: String?
    public fileprivate(set) var outgoingInviteToken: String
    public fileprivate(set) var incomingId: String?
    public fileprivate(set) var accepted: Bool
    public fileprivate(set) var blocked: Bool
    public fileprivate(set) var updatedAtMs: Int64
    public init(nodeId: String, fingerprint: String, alias: String, avatarData: String = "",
                outgoingId: String? = nil, outgoingInviteToken: String = "", incomingId: String? = nil,
                accepted: Bool = false, blocked: Bool = false, updatedAtMs: Int64 = 0) throws {
        guard !nodeId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, nodeId.utf8.count <= 128,
              fingerprint.utf8.count == 64, fingerprint.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              !alias.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, alias.utf16.count <= 32,
              avatarData.utf16.count <= 24_000, outgoingInviteToken.utf16.count <= 64, updatedAtMs >= 0 else {
            throw ConsentError.invalidState
        }
        for id in [outgoingId, incomingId].compactMap({ $0 }) {
            guard (16...64).contains(id.utf16.count) else { throw ConsentError.invalidState }
        }
        self.nodeId = nodeId; self.fingerprint = fingerprint; self.alias = alias; self.avatarData = avatarData
        self.outgoingId = outgoingId; self.outgoingInviteToken = outgoingInviteToken; self.incomingId = incomingId
        self.accepted = accepted; self.blocked = blocked; self.updatedAtMs = updatedAtMs
    }
    private enum CodingKeys: String, CodingKey {
        case nodeId, fingerprint, alias, avatarData, outgoingId, outgoingInviteToken, incomingId, accepted, blocked, updatedAtMs
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(nodeId: c.decode(String.self, forKey: .nodeId), fingerprint: c.decode(String.self, forKey: .fingerprint),
            alias: c.decode(String.self, forKey: .alias), avatarData: c.decodeIfPresent(String.self, forKey: .avatarData) ?? "",
            outgoingId: c.decodeIfPresent(String.self, forKey: .outgoingId),
            outgoingInviteToken: c.decodeIfPresent(String.self, forKey: .outgoingInviteToken) ?? "",
            incomingId: c.decodeIfPresent(String.self, forKey: .incomingId), accepted: c.decodeIfPresent(Bool.self, forKey: .accepted) ?? false,
            blocked: c.decodeIfPresent(Bool.self, forKey: .blocked) ?? false, updatedAtMs: c.decodeIfPresent(Int64.self, forKey: .updatedAtMs) ?? 0)
    }
}

public struct FriendState: Codable, Equatable, Sendable {
    public static let maximumRecords = 1000
    public static let maximumPending = 100
    public static let requestLifetimeMs: Int64 = 7 * 24 * 60 * 60 * 1000
    public static let inviteLifetimeMs: Int64 = 24 * 60 * 60 * 1000
    public private(set) var discoverable: Bool
    public private(set) var inviteToken: String
    public private(set) var inviteExpiresAtMs: Int64
    public private(set) var records: [FriendRecord]
    public enum Outcome: Equatable { case changed, ignored, rejected }
    public struct Transition { public let state: FriendState; public let outcome: Outcome }
    public init(discoverable: Bool = false, inviteToken: String = "", inviteExpiresAtMs: Int64 = 0,
                records: [FriendRecord] = []) throws {
        guard records.count <= Self.maximumRecords, Set(records.map(\.nodeId)).count == records.count,
              inviteToken.utf16.count <= 64, inviteExpiresAtMs >= 0 else { throw ConsentError.invalidState }
        self.discoverable = discoverable; self.inviteToken = inviteToken
        self.inviteExpiresAtMs = inviteExpiresAtMs; self.records = records
    }
    private enum CodingKeys: String, CodingKey { case discoverable, inviteToken, inviteExpiresAtMs, records }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(discoverable: c.decodeIfPresent(Bool.self, forKey: .discoverable) ?? false,
            inviteToken: c.decodeIfPresent(String.self, forKey: .inviteToken) ?? "",
            inviteExpiresAtMs: c.decodeIfPresent(Int64.self, forKey: .inviteExpiresAtMs) ?? 0,
            records: c.decodeIfPresent([FriendRecord].self, forKey: .records) ?? [])
    }
    public func isFriend(_ nodeId: String, fingerprint: String) -> Bool {
        records.contains { $0.nodeId == nodeId && $0.fingerprint == fingerprint && $0.accepted && !$0.blocked }
    }
    public func settingDiscoverable(_ value: Bool) -> Self { var next = self; next.discoverable = value; return next }
    public func issuingInvite(token: String = UUID().uuidString.lowercased(), now: Int64) throws -> Self {
        let expiry = now.addingReportingOverflow(Self.inviteLifetimeMs)
        guard now > 0, !token.isEmpty, token.utf16.count <= 64, !expiry.overflow else { throw ConsentError.invalidState }
        var next = self; next.inviteToken = token; next.inviteExpiresAtMs = expiry.partialValue; return next
    }
    public func revokingInvite() -> Self { var next = self; next.inviteToken = ""; next.inviteExpiresAtMs = 0; return next }
    private func result(_ outcome: Outcome) -> Transition { Transition(state: self, outcome: outcome) }
    private static func validAge(_ date: Int64, now: Int64, maximum: Int64) -> Bool {
        let age = now.subtractingReportingOverflow(date)
        return now > 0 && date > 0 && !age.overflow && age.partialValue >= -300_000 && age.partialValue <= maximum
    }
    // nearby must originate from the real BLE link + signed discoverable HELLO, never from JSON fields.
    public func receive(_ verified: AuthenticatedPayload, nearby: Bool, now: Int64) throws -> Transition {
        guard verified.data.count <= MeshWire.maximumFrameBytes,
              Self.validAge(verified.createdAtMs, now: now, maximum: Self.requestLifetimeMs) else { return result(.rejected) }
        let packet = try JSONDecoder().decode(FriendPacket.self, from: verified.data)
        let previous = records.first { $0.nodeId == verified.senderNodeId }
        if previous?.blocked == true || (previous != nil && previous?.fingerprint != verified.fingerprint) { return result(.rejected) }
        var record = try previous ?? FriendRecord(nodeId: verified.senderNodeId, fingerprint: verified.fingerprint, alias: packet.alias)
        switch packet.action {
        case .request:
            if previous?.incomingId == packet.requestId || previous?.accepted == true || previous?.incomingId != nil { return result(.ignored) }
            let invited = !inviteToken.isEmpty && now < inviteExpiresAtMs && packet.inviteToken == inviteToken
            guard (discoverable && nearby && verified.hops == 0) || invited || previous?.outgoingId != nil,
                  records.filter({ !$0.accepted && !$0.blocked }).count < Self.maximumPending else { return result(.rejected) }
            record.incomingId = packet.requestId
        case .accept:
            guard let previous, previous.outgoingId == packet.requestId,
                  Self.validAge(previous.updatedAtMs, now: now, maximum: Self.requestLifetimeMs) else { return result(.rejected) }
            record.accepted = true
        case .profile:
            guard previous?.accepted == true else { return result(.rejected) }
            if record.incomingId == packet.requestId { record.incomingId = nil }
        }
        guard previous != nil || records.count < Self.maximumRecords else { return result(.rejected) }
        record.alias = packet.alias; record.avatarData = packet.avatarData; record.updatedAtMs = now
        var next = self; next.records = records.filter { $0.nodeId != record.nodeId } + [record]
        if packet.action == .request && !packet.inviteToken.isEmpty && packet.inviteToken == inviteToken {
            next.inviteToken = ""; next.inviteExpiresAtMs = 0
        }
        return Transition(state: next, outcome: next == self ? .ignored : .changed)
    }
    public func ownerRequest(peer: ConfirmedContact, localNodeId: String, peerAlias: String, peerDiscoverable: Bool, nearby: Bool,
                             inviteToken: String = "", requestId: String = UUID().uuidString.lowercased(), now: Int64) throws -> Transition {
        _ = try ContactBook(contacts: [peer])
        guard !localNodeId.isEmpty, peer.nodeId != localNodeId, now > 0,
              (discoverable && peerDiscoverable && nearby) || !inviteToken.isEmpty else { return result(.rejected) }
        _ = try FriendPacket(action: .request, requestId: requestId, alias: peerAlias, inviteToken: inviteToken)
        let previous = records.first { $0.nodeId == peer.nodeId }
        guard previous == nil || previous?.fingerprint == peer.fingerprint else { return result(.rejected) }
        if isFriend(peer.nodeId, fingerprint: peer.fingerprint) || (previous?.outgoingId != nil && previous?.blocked == false) { return result(.ignored) }
        guard previous != nil || records.count < Self.maximumRecords else { return result(.rejected) }
        let record = try FriendRecord(nodeId: peer.nodeId, fingerprint: peer.fingerprint, alias: peerAlias,
            outgoingId: requestId, outgoingInviteToken: inviteToken,
            incomingId: previous?.blocked == true ? nil : previous?.incomingId, updatedAtMs: now)
        var next = self; next.records = records.filter { $0.nodeId != peer.nodeId } + [record]
        return Transition(state: next, outcome: .changed)
    }
    public func ownerAccept(peer: ConfirmedContact, now: Int64) throws -> Transition {
        _ = try ContactBook(contacts: [peer])
        guard let index = records.firstIndex(where: { $0.nodeId == peer.nodeId && $0.fingerprint == peer.fingerprint }),
              !records[index].blocked, records[index].incomingId != nil,
              Self.validAge(records[index].updatedAtMs, now: now, maximum: Self.requestLifetimeMs) else { return result(.rejected) }
        if records[index].accepted { return result(.ignored) }
        var next = self; next.records[index].accepted = true
        return Transition(state: next, outcome: .changed)
    }
    public func ownerBlock(_ nodeId: String) -> Transition {
        guard let index = records.firstIndex(where: { $0.nodeId == nodeId }) else { return result(.ignored) }
        var next = self; next.records[index].blocked = true; next.records[index].accepted = false
        next.records[index].incomingId = nil; next.records[index].outgoingId = nil
        next.records[index].outgoingInviteToken = ""; next.records[index].avatarData = ""
        return Transition(state: next, outcome: next == self ? .ignored : .changed)
    }
}
