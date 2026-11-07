import XCTest
@testable import ReactNativeDocumentScanner

final class CaptureFeedbackTimelineTests: XCTestCase {
  func testWaitingPulsesAndShootingStaysOpen() {
    XCTAssertEqual(CaptureFeedbackTimeline.waiting(0).outline, 1, accuracy: 1e-9)
    XCTAssertEqual(CaptureFeedbackTimeline.waiting(0.35).outline, 0.45, accuracy: 1e-9)
    XCTAssertEqual(CaptureFeedbackTimeline.waiting(0.7).outline, 1, accuracy: 1e-9)
    XCTAssertEqual(CaptureFeedbackTimeline.shooting(0).sweep, 0)
    XCTAssertLessThan(CaptureFeedbackTimeline.shooting(1).sweep, 0.9)
    XCTAssertFalse(CaptureFeedbackTimeline.shooting(10).finished)
  }

  func testCaptureContinuesTheSweepThenFadesAndFailureBlinks() {
    let sweep = CaptureFeedbackTimeline.shooting(0.2).sweep
    XCTAssertEqual(CaptureFeedbackTimeline.captured(0, from: sweep).sweep, sweep)
    let closed = CaptureFeedbackTimeline.captured(0.09, from: sweep)
    XCTAssertEqual(closed.sweep, 1)
    XCTAssertEqual(closed.fill, 0.3)
    XCTAssertTrue(CaptureFeedbackTimeline.captured(0.36, from: sweep).finished)
    XCTAssertEqual(CaptureFeedbackTimeline.failed(0.05).outline, 1)
    XCTAssertEqual(CaptureFeedbackTimeline.failed(0.15).outline, 0.25)
    XCTAssertEqual(CaptureFeedbackTimeline.failed(0.25).outline, 1)
    XCTAssertEqual(CaptureFeedbackTimeline.failed(0.35).outline, 0.25)
    XCTAssertTrue(CaptureFeedbackTimeline.failed(0.71).finished)
  }
}
