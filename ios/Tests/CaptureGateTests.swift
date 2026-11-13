import XCTest
@testable import ReactNativeDocumentScanner

/// Hardware-free capture readiness: the gate's decisions (the same sequences as Kotlin's
/// `CaptureGateTest` and the shared `capture-events.tsv`) and frame stability.
final class CaptureGateTests: XCTestCase {
  private let ready = CaptureEvidence(
    nowMs: 0, focusCheckEnabled: true, documentRequired: true, requireFocus: true, focusTimeoutMs: 2_000,
    documentFound: true, focused: true, bufferCheckEnabled: true, bufferReady: true, lightReady: true,
    motionCheckEnabled: true, steady: true)

  private func with(_ change: (inout CaptureEvidence) -> Void) -> CaptureEvidence {
    var next = ready
    change(&next)
    return next
  }

  private func gate(skip: Bool = false) -> CaptureGate { CaptureGate(steadyTimeoutMs: 1_500, skipLowQualityControl: skip) }

  // MARK: - Shared vectors

  func testCaptureEventsMatchTheSharedPlatformSequences() throws {
    let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "capture-events", withExtension: "tsv"))
    let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
      .filter { !$0.isEmpty && !$0.hasPrefix("#") }.dropFirst()
    var gates: [String: CaptureGate] = [:]
    func wait(_ name: String) -> GateWait {
      switch name {
      case "DOCUMENT": return .document
      case "FOCUS": return .focus
      case "LIGHT": return .light
      default: return .steady
      }
    }
    for line in rows {
      let v = line.split(separator: "\t").map(String.init)
      XCTAssertEqual(v.count, 10, line)
      var current = gates[v[0]] ?? gate()
      let evidence = with {
        $0.nowMs = Int64(v[1])!
        $0.documentFound = v[2] == "true"
        $0.focused = v[3] == "true"
        $0.bufferReady = v[4] == "true"
        $0.lightReady = v[5] == "true"
        $0.steady = v[6] == "true"
      }
      let expected: CaptureDecision
      switch v[7] {
      case "wait": expected = .wait(wait(v[8]), untilMs: Int64(v[9])!)
      case "shoot": expected = .shoot
      case "CAPTURE_FOCUS_TIMEOUT": expected = .reject(.captureFocusTimeout, wait(v[8]))
      case "CAPTURE_DOCUMENT_TIMEOUT": expected = .reject(.captureDocumentTimeout, wait(v[8]))
      case "CAPTURE_MOTION_TIMEOUT": expected = .reject(.captureMotionTimeout, wait(v[8]))
      default: expected = .reject(.captureFailed, wait(v[8]))
      }
      XCTAssertEqual(current.next(evidence), expected, line)
      gates[v[0]] = current
    }
    XCTAssertEqual(rows.count, 15)
  }

  // MARK: - Decisions

  func testPublicWaitReasonTracksTheChangingBlocker() {
    var g = gate()
    let reasons = [
      with { $0.documentFound = false; $0.focused = false },
      with { $0.nowMs = 100; $0.focused = false },
      with { $0.nowMs = 200; $0.lightReady = false },
      with { $0.nowMs = 300; $0.steady = false },
    ].map { evidence -> CaptureWaitReason? in
      if case .wait(let reason, _) = g.next(evidence) { return reason.publicReason }
      return nil
    }
    XCTAssertEqual(reasons, [.document, .focus, .light, .steady])
    XCTAssertEqual(g.next(with { $0.nowMs = 400 }), .shoot)
  }

  func testSkippedGateWaitsOnlyForAScanInProgress() {
    let blocked = with {
      $0.documentFound = false; $0.focused = false; $0.bufferReady = false; $0.lightReady = false; $0.steady = false
    }
    var skipped = gate(skip: true)
    XCTAssertEqual(skipped.next(with { $0 = blocked; $0.focusCheckEnabled = false }), .shoot)
    var normal = gate()
    XCTAssertEqual(normal.next(blocked), .wait(.document, untilMs: 2_000))

    var scanning = gate(skip: true)
    let pending = with { $0.focused = false; $0.focusingUntilMs = .max; $0.scanAvailable = true; $0.scanTrigger = .quality }
    XCTAssertEqual(scanning.next(pending), .wait(.focus, untilMs: .max), "a pending scan wakes the gate by settling")
    XCTAssertEqual(scanning.next(with { $0.nowMs = 2_000 }), .shoot)
    XCTAssertEqual(scanning.scans, 0, "a skipped gate never scans")
  }

  func testAutofocusStillMovingOutlastsTheFocusDeadlineUntilItsEvidenceGoesStale() {
    var g = gate()
    let moving = with { $0.focused = false; $0.focusingUntilMs = 2_300 }
    XCTAssertEqual(g.next(moving), .wait(.focus, untilMs: 2_000))
    XCTAssertEqual(g.next(with { $0 = moving; $0.nowMs = 2_000 }), .wait(.focus, untilMs: 2_300))
    XCTAssertEqual(g.next(with { $0 = moving; $0.nowMs = 2_300 }), .reject(.captureFocusTimeout, .focus))
  }

  func testReportsTheFirstBlockerInOrderUntilTheFocusDeadline() {
    var g = gate()
    XCTAssertEqual(g.next(with { $0.documentFound = false; $0.focused = false; $0.steady = false }),
                   .wait(.document, untilMs: 2_000))
    XCTAssertEqual(g.next(with { $0.nowMs = 500; $0.focused = false }), .wait(.focus, untilMs: 2_000))
    XCTAssertEqual(g.next(with { $0.nowMs = 600; $0.bufferReady = false }), .wait(.light, untilMs: 2_100))
    XCTAssertEqual(g.next(with { $0.nowMs = 700; $0.steady = false }), .wait(.steady, untilMs: 2_100))
    XCTAssertEqual(g.next(with { $0.nowMs = 800 }), .shoot)
  }

  func testOptionalFocusIsWaivedAtTheDeadlineButLightAndMotionAreNot() {
    var optional = gate()
    let unfocused = with { $0.requireFocus = false; $0.focused = false; $0.motionCheckEnabled = false; $0.lightReady = false }
    XCTAssertEqual(optional.next(unfocused), .wait(.focus, untilMs: 2_000))
    XCTAssertEqual(optional.next(with { $0 = unfocused; $0.nowMs = 2_000 }), .wait(.light, untilMs: 3_500))
    XCTAssertTrue(optional.focusSkipped)
    XCTAssertEqual(optional.next(with { $0 = unfocused; $0.nowMs = 3_500 }), .reject(.captureFailed, .light))

    var shaky = gate()
    _ = shaky.next(with { $0.steady = false })
    XCTAssertEqual(shaky.next(with { $0.nowMs = 1_500; $0.steady = false }), .reject(.captureMotionTimeout, .steady))
  }

  func testAFirstScanIsAdmittedOnlyBeforeTheFocusDeadline() {
    var late = gate()
    let unfocused = with { $0.focused = false; $0.scanAvailable = true }
    _ = late.next(unfocused)
    XCTAssertEqual(late.next(with { $0 = unfocused; $0.nowMs = 2_000; $0.scanTrigger = .dark }),
                   .reject(.captureFocusTimeout, .focus))
    var unavailable = gate()
    XCTAssertEqual(unavailable.next(with { $0 = unfocused; $0.scanAvailable = false; $0.scanTrigger = .dark }),
                   .wait(.focus, untilMs: 2_000))
    var early = gate()
    XCTAssertEqual(early.next(with { $0 = unfocused; $0.scanTrigger = .staleFocus }),
                   .startFocusScan(.staleFocus, attempt: 1))
  }

  func testAKnownFailureRetriesOnceAndMotionNeverStartsAScan() {
    var g = gate()
    let base = with { $0.focused = false; $0.scanAvailable = true }
    XCTAssertEqual(g.next(with { $0 = base; $0.scanTrigger = .staleFocus }), .startFocusScan(.staleFocus, attempt: 1))
    // Owning the lens ends the stale focus that triggered the scan; the retry does not need it.
    XCTAssertEqual(g.next(with { $0 = base; $0.nowMs = 100; $0.ownScan = .scanning }), .wait(.focus, untilMs: 2_100))
    XCTAssertEqual(g.next(with { $0 = base; $0.nowMs = 600; $0.ownScan = .failed }),
                   .startFocusScan(.staleFocus, attempt: 2))
    _ = g.next(with { $0 = base; $0.nowMs = 700; $0.ownScan = .scanning; $0.steady = false })
    XCTAssertEqual(g.next(with { $0 = base; $0.nowMs = 1_200; $0.ownScan = .failed; $0.steady = false }),
                   .wait(.focus, untilMs: 2_100), "the second failure is the last scan, however the camera moved")
    XCTAssertEqual(g.scans, 2)
  }

  func testAnUnknownScanResultIsNeitherScanningNorAFailureToRetry() {
    var g = gate()
    let base = with { $0.focused = false; $0.scanAvailable = true }
    XCTAssertEqual(g.next(with { $0 = base; $0.scanTrigger = .quality }), .startFocusScan(.quality, attempt: 1))
    XCTAssertEqual(g.next(with { $0 = base; $0.nowMs = 100; $0.ownScan = .scanning; $0.focusingUntilMs = .max }),
                   .wait(.focus, untilMs: 2_100))
    // Settled unknown, past the deadline: no retry, and no hold beyond the settled-frame allowance.
    XCTAssertEqual(g.next(with { $0 = base; $0.nowMs = 2_100; $0.ownScan = .settled; $0.frameSinceFocusChange = false }),
                   .wait(.focus, untilMs: 2_600))
    XCTAssertEqual(g.next(with { $0 = base; $0.nowMs = 2_600; $0.ownScan = .settled; $0.frameSinceFocusChange = false }),
                   .reject(.captureFocusTimeout, .focus))
    XCTAssertEqual(g.scans, 1)
    // The frames after it decide.
    var accepted = gate()
    _ = accepted.next(with { $0 = base; $0.scanTrigger = .quality })
    _ = accepted.next(with { $0 = base; $0.nowMs = 100; $0.ownScan = .scanning })
    XCTAssertEqual(accepted.next(with { $0.nowMs = 300; $0.ownScan = .settled; $0.scanAvailable = true }), .shoot)
  }

  func testASettledScanWaitsForInferenceWithoutADeadline() {
    let missing = with { $0.documentFound = false; $0.focused = false; $0.scanAvailable = true }
    var g = gate()
    XCTAssertEqual(g.next(with { $0 = missing; $0.scanTrigger = .dark }), .startFocusScan(.dark, attempt: 1))
    XCTAssertEqual(g.next(with { $0 = missing; $0.nowMs = 100; $0.ownScan = .scanning; $0.frameSinceFocusChange = false }),
                   .wait(.document, untilMs: 2_100))
    XCTAssertEqual(g.next(with { $0 = missing; $0.nowMs = 2_500; $0.ownScan = .scanning; $0.frameSinceFocusChange = false }),
                   .wait(.document, untilMs: .max))
    var settled = missing
    settled.nowMs = 3_000
    settled.ownScan = .settled
    settled.postFocusInferenceFinished = false
    XCTAssertEqual(g.next(settled), .wait(.document, untilMs: .max))
    XCTAssertEqual(g.next(with { $0 = settled; $0.nowMs = 8_100; $0.focused = true }),
                   .wait(.document, untilMs: .max))
    XCTAssertEqual(g.next(with { $0.nowMs = 8_800; $0.ownScan = .settled; $0.steady = false; $0.postFocusInferenceFinished = true }),
                   .wait(.steady, untilMs: 10_300))
    XCTAssertEqual(g.next(with { $0.nowMs = 8_900; $0.ownScan = .settled }), .shoot)

    // A coalesced settle still waits for an inference. Its negative result resolves the wait.
    var coalesced = gate()
    _ = coalesced.next(with { $0 = missing; $0.scanTrigger = .dark })
    _ = coalesced.next(with { $0 = missing; $0.nowMs = 100; $0.ownScan = .scanning })
    XCTAssertEqual(coalesced.next(settled), .wait(.document, untilMs: .max))
    XCTAssertEqual(coalesced.next(with { $0 = settled; $0.focused = true; $0.postFocusInferenceFinished = true }),
                   .reject(.captureDocumentTimeout, .document))
  }

  func testAZeroFocusTimeoutJudgesFocusAtThePressAlone() {
    var g = gate()
    XCTAssertEqual(g.next(with { $0.focusTimeoutMs = 0; $0.focused = false }), .reject(.captureFocusTimeout, .focus))
  }

  // MARK: - Stability

  private let framing = CameraFraming(buffer: PixelSize(640, 480), rotationAngle: 90, square: false)!
  private let page: LiveCorners = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8]

  private func frame(_ index: Int, status: FlowStatus = .measured, speed: Double? = 10, from: Int? = nil,
                     exposureNs: Int64? = 10_000_000, guide: LiveCorners?? = nil, lost: Int = 0) -> FrameRecord {
    let ns = Int64(index) * 33_000_000
    let fromNs = Int64(from ?? index - 1) * 33_000_000
    let blur = speed.flatMap { s in exposureNs.map { s * Double($0) * 1e-9 / 480 } }
    return FrameRecord(sequence: index, sensorNs: ns, framing: framing,
                       flow: FlowEvidence(status: status, speedPxPerSecond: speed, sourceWidth: 480, lostRegions: lost,
                                          fromNs: fromNs),
                       exposureNs: exposureNs, iso: 100, exposureEstimated: false, blur: blur, focusAdjusting: false,
                       focusReachedNs: nil, pageArrivedNs: nil, guide: guide ?? page)
  }

  func testFourConsecutiveQualifyingFramesAreSteady() {
    var s = CaptureStability()
    for i in 1...3 { XCTAssertFalse(s.observe(frame(i), requireDocument: true, blurLimit: 0.002)) }
    XCTAssertTrue(s.observe(frame(4), requireDocument: true, blurLimit: 0.002))
    XCTAssertEqual(s.latestSensorNs, 132_000_000)
  }

  func testUnknownMotionAndGapsNeverContinueASequence() {
    var s = CaptureStability()
    s.observe(frame(1), requireDocument: true, blurLimit: 0.002)
    s.observe(frame(2), requireDocument: true, blurLimit: 0.002)
    // Low texture has no gyroscope fallback on iOS: unknown motion restarts.
    s.observe(frame(3, status: .tooFewFeatures, speed: nil), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.count, 0)
    XCTAssertEqual(s.lastBreak, .trackingLost)
    s.observe(frame(4), requireDocument: true, blurLimit: 0.002)
    // A skipped frame: flow that does not start at the sequence's last frame begins a new one.
    s.observe(frame(6, from: 5), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.count, 1)
    XCTAssertEqual(s.lastBreak, .trackingLost)
  }

  func testUnknownExposureKeepsTheChainWithoutCountingAndSmearOrMovementBreaksIt() {
    var s = CaptureStability()
    s.observe(frame(1), requireDocument: true, blurLimit: 0.002)
    s.observe(frame(2, exposureNs: nil), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.count, 1, "unknown smear does not count")
    s.observe(frame(3), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.count, 2, "but keeps the chain")
    s.observe(frame(4, speed: 20, exposureNs: 60_000_000), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.lastBreak, .smeared)
    s.observe(frame(5, speed: 100), requireDocument: true, blurLimit: 0)
    XCTAssertEqual(s.lastBreak, .moving, "over 5% of the width per second")
    s.observe(frame(6), requireDocument: true, blurLimit: 0.002)
    s.observe(frame(7, guide: .some(nil)), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.lastBreak, .noDocument)
    s.observe(frame(8), requireDocument: true, blurLimit: 0.002)
    s.observe(frame(9, guide: [0.25, 0.2, 0.85, 0.2, 0.85, 0.8, 0.25, 0.8]), requireDocument: true, blurLimit: 0.002)
    XCTAssertEqual(s.lastBreak, .documentMoved)
    XCTAssertEqual(s.count, 1, "the moved page begins a new sequence")
  }
}
