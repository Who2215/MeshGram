import Foundation

// A restarted search owns a new manager; old manager events cannot affect it.
public struct HelloSessionGate {
    private var manager: ObjectIdentifier?
    public init() {}
    public mutating func activate(_ manager: AnyObject) { self.manager = ObjectIdentifier(manager) }
    public mutating func invalidate() { manager = nil }
    public func accepts(_ manager: AnyObject) -> Bool { self.manager == ObjectIdentifier(manager) }
}

// ATT responses confirm writes, not that the remote app accepted a HELLO.
public struct HelloWriteQueue {
    public enum State: Equatable { case inactive, ready, waiting, completed, failed, cancelled }
    public enum WriteError: Error { case invalidClock, timedOut, rejected }
    public struct Write: Equatable {
        public let sequence: Int
        public let data: Data
    }
    public private(set) var state: State = .inactive
    public private(set) var inFlight: Write?
    private var frames: [Data]
    private var cursor = 0
    private var lastTime: TimeInterval = 0
    private var overallDeadline: TimeInterval = 0
    private var responseDeadline: TimeInterval?

    public init(payload: Data, frameKey: UInt32, maximumWriteLength: Int) throws {
        guard maximumWriteLength >= 20 else { throw MeshProtocolError.invalidFrame }
        // withResponse may allow long writes; Android's handler expects complete short frames.
        frames = try FrameCodec.packetize(payload, frameKey: frameKey, chunkSize: 12)
    }

    public var deadline: TimeInterval? {
        guard state == .ready || state == .waiting else { return nil }
        return min(overallDeadline, responseDeadline ?? overallDeadline)
    }

    public mutating func activate(now: TimeInterval) throws {
        guard state == .inactive else { return }
        guard now.isFinite, now >= 0, (now + 30).isFinite, now + 30 > now else {
            finish(.failed)
            throw WriteError.invalidClock
        }
        lastTime = now
        overallDeadline = now + 30
        state = .ready
    }

    public mutating func takeNext(now: TimeInterval) throws -> Write? {
        guard state == .ready || state == .waiting else { return nil }
        if expire(now: now) { throw WriteError.timedOut }
        guard state == .ready else { return nil }
        let write = Write(sequence: cursor, data: frames[cursor])
        inFlight = write
        responseDeadline = now + 5
        state = .waiting
        return write
    }

    @discardableResult
    public mutating func acknowledge(sequence: Int, succeeded: Bool, now: TimeInterval) throws -> Bool {
        guard state == .waiting, inFlight?.sequence == sequence else { return false }
        if expire(now: now) { throw WriteError.timedOut }
        guard succeeded else { finish(.failed); throw WriteError.rejected }
        cursor += 1
        inFlight = nil
        responseDeadline = nil
        if cursor == frames.count { finish(.completed) }
        else { state = .ready }
        return true
    }

    @discardableResult
    public mutating func expire(now: TimeInterval) -> Bool {
        guard let deadline else { return false }
        guard now.isFinite, now >= lastTime, now < deadline else {
            finish(.failed)
            return true
        }
        lastTime = now
        return false
    }

    public mutating func cancel() {
        guard state == .inactive || state == .ready || state == .waiting else { return }
        finish(.cancelled)
    }

    private mutating func finish(_ terminal: State) {
        state = terminal
        frames.removeAll()
        inFlight = nil
        responseDeadline = nil
    }
}
