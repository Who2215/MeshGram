import Foundation
import XCTest
@testable import MeshGramCore

final class HelloWriteQueueTests: XCTestCase {
    private func queue(bytes: Int = 30, maximum: Int = 20) throws -> HelloWriteQueue {
        try HelloWriteQueue(payload: Data(repeating: 7, count: bytes), frameKey: 1,
                            maximumWriteLength: maximum)
    }

    func testInactiveQueueCannotWrite() throws {
        var q = try queue()
        XCTAssertEqual(q.state, .inactive)
        XCTAssertNil(try q.takeNext(now: 10))
        XCTAssertFalse(try q.acknowledge(sequence: 0, succeeded: true, now: 10))
        XCTAssertNil(q.deadline)
    }

    func testOnlyOneWriteCanBeInFlight() throws {
        var q = try queue()
        try q.activate(now: 10)
        let first = try XCTUnwrap(q.takeNext(now: 10))
        XCTAssertEqual(first.sequence, 0)
        XCTAssertEqual(first.data.count, 20)
        XCTAssertEqual(q.state, .waiting)
        XCTAssertNil(try q.takeNext(now: 11))
        XCTAssertEqual(q.inFlight, first)
        XCTAssertEqual(q.deadline, 15)
    }

    func testWrongAndDuplicateAcknowledgementsDoNotAdvance() throws {
        var q = try queue()
        try q.activate(now: 10)
        _ = try q.takeNext(now: 10)
        XCTAssertFalse(try q.acknowledge(sequence: 1, succeeded: true, now: 11))
        XCTAssertTrue(try q.acknowledge(sequence: 0, succeeded: true, now: 11))
        XCTAssertFalse(try q.acknowledge(sequence: 0, succeeded: true, now: 11))
        XCTAssertEqual(try q.takeNext(now: 11)?.sequence, 1)
        XCTAssertFalse(try q.acknowledge(sequence: 0, succeeded: true, now: 12))
        XCTAssertEqual(q.inFlight?.sequence, 1)
    }

    func testCompletionRequiresLastResponse() throws {
        var q = try queue(bytes: 13)
        try q.activate(now: 10)
        let first = try XCTUnwrap(q.takeNext(now: 10))
        try q.acknowledge(sequence: first.sequence, succeeded: true, now: 11)
        let last = try XCTUnwrap(q.takeNext(now: 11))
        XCTAssertEqual(q.state, .waiting)
        try q.acknowledge(sequence: last.sequence, succeeded: true, now: 12)
        XCTAssertEqual(q.state, .completed)
        XCTAssertNil(q.inFlight)
        XCTAssertNil(q.deadline)
        XCTAssertNil(try q.takeNext(now: 100))
        XCTAssertFalse(try q.acknowledge(sequence: last.sequence, succeeded: true, now: 100))
    }

    func testWriteFailureIsTerminal() throws {
        var q = try queue()
        try q.activate(now: 10)
        _ = try q.takeNext(now: 10)
        XCTAssertThrowsError(try q.acknowledge(sequence: 0, succeeded: false, now: 11))
        XCTAssertEqual(q.state, .failed)
        XCTAssertNil(try q.takeNext(now: 12))
        XCTAssertFalse(try q.acknowledge(sequence: 0, succeeded: true, now: 12))
    }

    func testResponseDeadlineIsExclusive() throws {
        var q = try queue()
        try q.activate(now: 10)
        _ = try q.takeNext(now: 10)
        XCTAssertFalse(q.expire(now: 14.999))
        XCTAssertTrue(q.expire(now: 15))
        XCTAssertEqual(q.state, .failed)
        XCTAssertNil(try q.takeNext(now: 16))
    }

    func testLateResponseCannotComplete() throws {
        var q = try queue(bytes: 1)
        try q.activate(now: 10)
        _ = try q.takeNext(now: 10)
        XCTAssertThrowsError(try q.acknowledge(sequence: 0, succeeded: true, now: 15))
        XCTAssertEqual(q.state, .failed)
    }

    func testOverallDeadlineCannotBeExtendedByResponses() throws {
        var q = try queue(bytes: 200)
        try q.activate(now: 10)
        for index in 0..<7 {
            let now = 10.0 + Double(index) * 4
            _ = try q.takeNext(now: now)
            try q.acknowledge(sequence: index, succeeded: true, now: now + 4)
        }
        _ = try q.takeNext(now: 38)
        XCTAssertEqual(q.deadline, 40)
        XCTAssertFalse(q.expire(now: 39.999))
        XCTAssertTrue(q.expire(now: 40))
    }

    func testCancellationOfEveryActiveStateIsTerminal() throws {
        for phase in 0..<3 {
            var q = try queue()
            if phase > 0 { try q.activate(now: 10) }
            if phase > 1 { _ = try q.takeNext(now: 10) }
            q.cancel()
            try q.activate(now: 20)
            XCTAssertEqual(q.state, .cancelled)
            XCTAssertNil(q.inFlight)
            XCTAssertNil(try q.takeNext(now: 20))
            XCTAssertFalse(try q.acknowledge(sequence: 0, succeeded: true, now: 20))
        }
    }

    func testInvalidClockAndBackwardsTimeFailClosed() throws {
        for time in [Double.nan, Double.infinity, -1, Double.greatestFiniteMagnitude] {
            var q = try queue()
            XCTAssertThrowsError(try q.activate(now: time))
            XCTAssertEqual(q.state, .failed)
        }
        var q = try queue()
        try q.activate(now: 10)
        _ = try q.takeNext(now: 11)
        XCTAssertTrue(q.expire(now: 10))
        XCTAssertEqual(q.state, .failed)
    }

    func testRepeatedActivationDoesNotResetDeadline() throws {
        var q = try queue()
        try q.activate(now: 10)
        _ = try q.takeNext(now: 10)
        try q.activate(now: 100)
        XCTAssertEqual(q.deadline, 15)
        XCTAssertTrue(q.expire(now: 15))
    }

    func testNonfiniteResponseTimeCannotAdvance() throws {
        for time in [Double.nan, Double.infinity] {
            var q = try queue()
            try q.activate(now: 10)
            _ = try q.takeNext(now: 10)
            XCTAssertThrowsError(try q.acknowledge(sequence: 0, succeeded: true, now: time))
            XCTAssertEqual(q.state, .failed)
            XCTAssertNil(try q.takeNext(now: 11))
        }
    }

    func testCompletedQueueCannotReactivateOrBecomeCancelled() throws {
        var q = try queue(bytes: 1)
        try q.activate(now: 10)
        _ = try q.takeNext(now: 10)
        try q.acknowledge(sequence: 0, succeeded: true, now: 11)
        q.cancel()
        try q.activate(now: 20)
        XCTAssertEqual(q.state, .completed)
        XCTAssertFalse(q.expire(now: 100))
        XCTAssertNil(try q.takeNext(now: 100))
    }

    func testWriteLengthAndFrameCountAreBounded() throws {
        for maximum in [-1, 0, 19] { XCTAssertThrowsError(try queue(maximum: maximum)) }
        XCTAssertThrowsError(try queue(bytes: 0))
        XCTAssertThrowsError(try queue(bytes: 3061))
        _ = try queue(bytes: 3060)
        XCTAssertThrowsError(try queue(bytes: MeshWire.maximumFrameBytes, maximum: 512))
        XCTAssertThrowsError(try queue(bytes: MeshWire.maximumFrameBytes + 1, maximum: 512))
        var q = try queue(bytes: 200, maximum: 512)
        try q.activate(now: 10)
        XCTAssertEqual(try q.takeNext(now: 10)?.data.count, 20)
    }

    func testReportedLongWriteLimitStillUsesShortFrames() throws {
        for maximum in [20, 188, 512, Int.max] {
            var q = try queue(bytes: 30, maximum: maximum)
            try q.activate(now: 10)
            var count = 0
            while let write = try q.takeNext(now: 10) {
                XCTAssertLessThanOrEqual(write.data.count, 20)
                try q.acknowledge(sequence: write.sequence, succeeded: true, now: 10)
                count += 1
            }
            XCTAssertEqual(count, 3)
            XCTAssertEqual(q.state, .completed)
        }
    }

    func testConnectionsHaveIndependentQueues() throws {
        var first = try queue()
        var second = try queue()
        try first.activate(now: 10)
        try second.activate(now: 10)
        _ = try first.takeNext(now: 10)
        _ = try second.takeNext(now: 10)
        first.cancel()
        try second.acknowledge(sequence: 0, succeeded: true, now: 11)
        XCTAssertEqual(first.state, .cancelled)
        XCTAssertEqual(try second.takeNext(now: 11)?.sequence, 1)
    }

    func testSignedHelloSurvivesAcknowledgedFragmentRoundTrip() throws {
        let packet = try PacketCrypto.makeHello(identity: LocalIdentity(), createdAtMs: 1_700_000_000_000)
        let payload = try JSONEncoder().encode(packet)
        var q = try HelloWriteQueue(payload: payload, frameKey: 0x01020304, maximumWriteLength: 20)
        var assembler = FrameAssembler()
        var result: Data?
        try q.activate(now: 10)
        var sequence = 0
        while let write = try q.takeNext(now: 10) {
            XCTAssertEqual(write.sequence, sequence)
            XCTAssertLessThanOrEqual(write.data.count, 20)
            result = try assembler.receive(write.data, now: 10) ?? result
            try q.acknowledge(sequence: sequence, succeeded: true, now: 10)
            sequence += 1
        }
        XCTAssertEqual(q.state, .completed)
        XCTAssertEqual(result, payload)
        let restored = try JSONDecoder().decode(HelloPacket.self, from: XCTUnwrap(result))
        try PacketCrypto.verifyHello(restored)
        XCTAssertEqual(restored.discoverable, false)
    }
}
