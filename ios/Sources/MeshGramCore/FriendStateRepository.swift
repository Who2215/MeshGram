import CryptoKit
import Foundation

public enum FriendStoreError: Error { case notInitialized, incompleteState, alreadyExists, notLoaded, conflict, invalidKey, invalidStorage, readback }
public protocol FriendStateKeyStorage: AnyObject {
    func read() throws -> SymmetricKey?
    func createIfAbsent(_ key: SymmetricKey) throws
}
public protocol FriendStateBlobStorage: AnyObject {
    func read() throws -> Data?
    // Exact baseline check for a single cooperating writer, not cross-process CAS.
    func commit(new: Data, expected: Data?) throws
}

public final class FriendStateRepository {
    private let keys: FriendStateKeyStorage
    private let blobs: FriendStateBlobStorage
    private let node: String
    private let fingerprint: String
    private let lock = NSLock()
    private var loadedKey: SymmetricKey?
    private var baseline: Data?
    private var published: FriendState?
    public var state: FriendState? { lock.lock(); defer { lock.unlock() }; return published }

    public init(keys: FriendStateKeyStorage, blobs: FriendStateBlobStorage, localNodeId: String, ownFingerprint: String) {
        self.keys = keys; self.blobs = blobs; node = localNodeId; fingerprint = ownFingerprint
    }
    private func invalidate() { loadedKey = nil; baseline = nil; published = nil }

    @discardableResult
    public func load() throws -> FriendState {
        lock.lock(); defer { lock.unlock() }
        invalidate()
        let key = try keys.read(), blob = try blobs.read()
        guard key != nil || blob != nil else { throw FriendStoreError.notInitialized }
        guard let key, let blob else { throw FriendStoreError.incompleteState }
        guard key.bitCount == 256 else { throw FriendStoreError.invalidKey }
        let state = try EncryptedFriendState.open(blob, key: key, localNodeId: node, ownFingerprint: fingerprint)
        loadedKey = key; baseline = blob; published = state
        return state
    }

    @discardableResult
    public func initializeNew() throws -> FriendState {
        lock.lock(); defer { lock.unlock() }
        invalidate()
        do {
            guard try keys.read() == nil, try blobs.read() == nil else { throw FriendStoreError.alreadyExists }
            let key = SymmetricKey(size: .bits256), state = try FriendState()
            let blob = try EncryptedFriendState.seal(state, key: key, localNodeId: node, ownFingerprint: fingerprint)
            try keys.createIfAbsent(key)
            guard try keys.read() == key else { throw FriendStoreError.readback }
            try blobs.commit(new: blob, expected: nil)
            return try publishReadback(state, key: key, blob: blob)
        } catch { invalidate(); throw error }
    }

    @discardableResult
    public func save(_ state: FriendState, basedOn expectedState: FriendState) throws -> FriendState {
        lock.lock(); defer { lock.unlock() }
        return try saveLocked(state, basedOn: expectedState)
    }

    private func saveLocked(_ state: FriendState, basedOn expectedState: FriendState) throws -> FriendState {
        do {
            guard let key = loadedKey, let baseline, published != nil else { throw FriendStoreError.notLoaded }
            guard published == expectedState else { throw FriendStoreError.conflict }
            guard try keys.read() == key else { throw FriendStoreError.invalidKey }
            guard try blobs.read() == baseline else { throw FriendStoreError.conflict }
            let blob = try EncryptedFriendState.seal(state, key: key, localNodeId: node, ownFingerprint: fingerprint)
            try blobs.commit(new: blob, expected: baseline)
            return try publishReadback(state, key: key, blob: blob)
        } catch { invalidate(); throw error }
    }

    // Keep the pure transition, baseline check and verified publication under one lock.
    private func mutate(_ transition: (FriendState) throws -> FriendState.Transition) throws -> FriendState.Transition {
        lock.lock(); defer { lock.unlock() }
        guard let current = published else { throw FriendStoreError.notLoaded }
        return try commitTransition(transition(current), basedOn: current)
    }

    private func commitTransition(_ result: FriendState.Transition, basedOn current: FriendState) throws -> FriendState.Transition {
        guard result.outcome == .changed else { return result }
        let saved = try saveLocked(result.state, basedOn: current)
        return FriendState.Transition(state: saved, outcome: .changed)
    }

    private func updating(_ transform: (FriendState) throws -> FriendState) throws -> FriendState.Transition {
        try mutate { current in
            let next = try transform(current)
            return FriendState.Transition(state: next, outcome: next == current ? .ignored : .changed)
        }
    }

    public func setDiscoverable(_ value: Bool) throws -> FriendState.Transition {
        try updating { $0.settingDiscoverable(value) }
    }

    public func issueInvite(token: String = UUID().uuidString.lowercased(), now: Int64) throws -> FriendState.Transition {
        try updating { try $0.issuingInvite(token: token, now: now) }
    }

    public func revokeInvite() throws -> FriendState.Transition {
        try updating { $0.revokingInvite() }
    }

    public func receive(_ verified: AuthenticatedPayload, nearby: Bool, now: Int64) throws -> FriendState.Transition {
        try mutate { try $0.receive(verified, nearby: nearby, now: now) }
    }

    public func request(peer: ConfirmedContact, peerAlias: String, peerDiscoverable: Bool, nearby: Bool,
                        inviteToken: String = "", requestId: String = UUID().uuidString.lowercased(), now: Int64) throws -> FriendState.Transition {
        try mutate { try $0.ownerRequest(peer: peer, localNodeId: node, peerAlias: peerAlias,
            peerDiscoverable: peerDiscoverable, nearby: nearby, inviteToken: inviteToken, requestId: requestId, now: now) }
    }

    public func accept(peer: ConfirmedContact, now: Int64) throws -> FriendState.Transition {
        try mutate { try $0.ownerAccept(peer: peer, now: now) }
    }

    public func block(_ nodeId: String) throws -> FriendState.Transition {
        try mutate { $0.ownerBlock(nodeId) }
    }

    public func prepareFriendship(_ action: FriendPacket.Action, identity: LocalIdentity, recipient: HelloPacket,
                                  pins: ContactBook, ownAlias: String, ownAvatarData: String = "",
                                  now: Int64) throws -> PreparedFriendshipPacket {
        lock.lock(); defer { lock.unlock() }
        let current = try checkedCurrent(identity: identity)
        return try PreparedFriendshipPacket.make(action, state: current, identity: identity, recipient: recipient,
            pins: pins, ownAlias: ownAlias, ownAvatarData: ownAvatarData, now: now)
    }

    private func checkedCurrent(identity: LocalIdentity) throws -> FriendState {
        guard let current = published, let key = loadedKey, let baseline else { throw FriendStoreError.notLoaded }
        guard identity.nodeId == node, identity.publicFingerprint == fingerprint else { throw ConsentError.unauthorized }
        // Caller holds lock; revalidation never creates or rewrites storage.
        do {
            guard try keys.read() == key else { throw FriendStoreError.invalidKey }
            guard try blobs.read() == baseline else { throw FriendStoreError.conflict }
        } catch { invalidate(); throw error }
        return current
    }

    // nearby is a trusted link observation, never a field decoded from the packet.
    public func receiveFriendship(_ packet: SecureMessagePacket, identity: LocalIdentity, pins: ContactBook,
                                  nearby: Bool, now: Int64) throws -> FriendState.Transition {
        lock.lock(); defer { lock.unlock() }
        let current = try checkedCurrent(identity: identity)
        var stringBytes = 0
        for value in [packet.type, packet.id, packet.originNodeId, packet.senderAlias, packet.senderEncryptionPublicKey,
                      packet.senderSigningPublicKey, packet.senderFingerprint, packet.targetNodeId, packet.relayNodeId,
                      packet.ephemeralPublicKey, packet.nonce, packet.ciphertext, packet.signature] {
            guard value.utf8.count <= MeshWire.maximumFrameBytes - stringBytes else { throw MeshProtocolError.invalidFrame }
            stringBytes += value.utf8.count
        }
        guard try JSONEncoder().encode(packet).count <= MeshWire.maximumFrameBytes else { throw MeshProtocolError.invalidFrame }
        let verified = try AuthenticatedPayload.open(packet, identity: identity, pins: pins)
        return try commitTransition(current.receive(verified, nearby: nearby, now: now), basedOn: current)
    }

    private func publishReadback(_ expected: FriendState, key: SymmetricKey, blob: Data) throws -> FriendState {
        guard try keys.read() == key, let saved = try blobs.read(), saved == blob else { throw FriendStoreError.readback }
        let state = try EncryptedFriendState.open(saved, key: key, localNodeId: node, ownFingerprint: fingerprint)
        guard state == expected else { throw FriendStoreError.readback }
        loadedKey = key; baseline = saved; published = state
        return state
    }
}
