import Foundation

public struct DirectPayload: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case text; case deliveryAck = "message_delivery_ack"; case relayAck = "message_relay_ack" }
    public let type: String
    public let chatId: String
    public let chatType: String
    public let chatTitle: String?
    public let memberNodeIds: [String]
    public let text: String
    public let payloadKind: Kind
    public let messageId: String?
    public let ackMessageId: String?
    public let replyToMessageId: String?
    public let replyToPreview: String?
    public let forwardedFromAlias: String?
    public let forwardedFromMessageId: String?
    public let sentAtMs: Int64
    private enum CodingKeys: String, CodingKey {
        case type, chatId, chatType, chatTitle, memberNodeIds, text, payloadKind, messageId, ackMessageId
        case replyToMessageId, replyToPreview, forwardedFromAlias, forwardedFromMessageId, sentAtMs
    }
    public init(chatId: String, chatTitle: String? = nil, memberNodeIds: [String] = [], text: String = "",
                payloadKind: Kind = .text, messageId: String? = nil, ackMessageId: String? = nil,
                replyToMessageId: String? = nil, replyToPreview: String? = nil,
                forwardedFromAlias: String? = nil, forwardedFromMessageId: String? = nil, sentAtMs: Int64 = 0) throws {
        guard !chatId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              chatId.utf8.count <= 300, memberNodeIds.count <= 2, sentAtMs >= 0 else { throw ConsentError.invalidPayload }
        for id in [messageId, ackMessageId, replyToMessageId, forwardedFromMessageId].compactMap({ $0 }) {
            guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, id.utf8.count <= 128,
                  id.rangeOfCharacter(from: .controlCharacters) == nil else { throw ConsentError.invalidPayload }
        }
        switch payloadKind {
        case .text:
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, ackMessageId == nil else { throw ConsentError.invalidPayload }
        case .deliveryAck:
            guard ackMessageId != nil, text.isEmpty, messageId == nil else { throw ConsentError.invalidPayload }
        case .relayAck:
            guard let ackMessageId, chatId == "relay:\(ackMessageId)", text.isEmpty, messageId == nil else { throw ConsentError.invalidPayload }
        }
        type = "CHAT_PAYLOAD_V1"; chatType = "direct"; self.chatId = chatId; self.chatTitle = chatTitle
        self.memberNodeIds = memberNodeIds; self.text = text; self.payloadKind = payloadKind
        self.messageId = messageId; self.ackMessageId = ackMessageId; self.replyToMessageId = replyToMessageId
        self.replyToPreview = replyToPreview; self.forwardedFromAlias = forwardedFromAlias
        self.forwardedFromMessageId = forwardedFromMessageId; self.sentAtMs = sentAtMs
        guard try JSONEncoder().encode(self).count <= MeshWire.maximumFrameBytes else { throw ConsentError.invalidPayload }
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard try c.decode(String.self, forKey: .type) == "CHAT_PAYLOAD_V1",
              try c.decode(String.self, forKey: .chatType) == "direct" else { throw ConsentError.invalidPayload }
        try self.init(chatId: c.decode(String.self, forKey: .chatId), chatTitle: c.decodeIfPresent(String.self, forKey: .chatTitle),
            memberNodeIds: c.decodeIfPresent([String].self, forKey: .memberNodeIds) ?? [], text: c.decodeIfPresent(String.self, forKey: .text) ?? "",
            payloadKind: c.decodeIfPresent(Kind.self, forKey: .payloadKind) ?? .text,
            messageId: c.decodeIfPresent(String.self, forKey: .messageId), ackMessageId: c.decodeIfPresent(String.self, forKey: .ackMessageId),
            replyToMessageId: c.decodeIfPresent(String.self, forKey: .replyToMessageId), replyToPreview: c.decodeIfPresent(String.self, forKey: .replyToPreview),
            forwardedFromAlias: c.decodeIfPresent(String.self, forKey: .forwardedFromAlias), forwardedFromMessageId: c.decodeIfPresent(String.self, forKey: .forwardedFromMessageId),
            sentAtMs: c.decodeIfPresent(Int64.self, forKey: .sentAtMs) ?? 0)
    }
    public static func conversationId(_ first: String, _ second: String) throws -> String {
        let ids = [first, second].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard ids[0] != ids[1], ids.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 128 && !$0.contains(":") &&
            !$0.contains("|") && $0.rangeOfCharacter(from: .controlCharacters) == nil }) else { throw ConsentError.invalidPayload }
        let pair = ids.sorted { $0.utf16.lexicographicallyPrecedes($1.utf16) }
        return "dm:\(pair[0]):\(pair[1])"
    }
    // Only checks direct-message authority; relay receipts need their own outbox/route matcher.
    internal func requireFriend(localNodeId: String, verified: AuthenticatedPayload, state: FriendState) throws {
        guard payloadKind != .relayAck, state.isFriend(verified.senderNodeId, fingerprint: verified.fingerprint),
              chatId == (try Self.conversationId(localNodeId, verified.senderNodeId)) else { throw ConsentError.unauthorized }
        if !memberNodeIds.isEmpty {
            guard memberNodeIds.count == 2, Set(memberNodeIds) == Set([localNodeId, verified.senderNodeId]) else { throw ConsentError.unauthorized }
        }
    }
}

public enum KnownPayload {
    case friend(FriendPacket)
    case direct(DirectPayload)
    public static func decode(_ data: Data) throws -> Self {
        guard !data.isEmpty, data.count <= MeshWire.maximumFrameBytes else { throw ConsentError.invalidPayload }
        struct Header: Decodable { let type: String }
        let decoder = JSONDecoder()
        switch try decoder.decode(Header.self, from: data).type {
        case "MESH_FRIEND_V1": return .friend(try decoder.decode(FriendPacket.self, from: data))
        case "CHAT_PAYLOAD_V1": return .direct(try decoder.decode(DirectPayload.self, from: data))
        default: throw ConsentError.invalidPayload
        }
    }
    public static func shortGattData(_ packet: SecureMessagePacket) throws -> Data {
        let data = try JSONEncoder().encode(packet)
        guard data.count <= MeshWire.shortGattMaximumPacketBytes else { throw ConsentError.capacity }
        return data
    }
    public static func authorizedDirect(_ verified: AuthenticatedPayload, localNodeId: String, state: FriendState) throws -> DirectPayload {
        guard case .direct(let payload) = try decode(verified.data) else { throw ConsentError.invalidPayload }
        try payload.requireFriend(localNodeId: localNodeId, verified: verified, state: state)
        return payload
    }
}
