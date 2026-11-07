import AVFoundation
import CoreGraphics
import CoreVideo
import NitroModules
import UIKit
import XCTest
@testable import ReactNativeDocumentScanner

/// Scripted model output, shared by every runtime a `CornerInference` opens.
private final class Script: @unchecked Sendable {
  private let lock = NSLock()
  private var corners: [Double]? = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8]
  private var score = 0.99
  private var fails = false
  private(set) var calls: [(orientation: UInt8, crop: CGRect, rowBytes: Int)] = []

  func set(corners: [Double]?, score: Double = 0.99, fails: Bool = false) {
    lock.lock(); self.corners = corners; self.score = score; self.fails = fails; lock.unlock()
  }

  var count: Int { lock.lock(); defer { lock.unlock() }; return calls.count }

  func predict(_ buffer: CVPixelBuffer, _ orientation: UInt8, _ crop: CGRect) throws -> CornerPrediction {
    lock.lock(); defer { lock.unlock() }
    calls.append((orientation, crop, CVPixelBufferGetBytesPerRow(buffer)))
    if fails { throw CornerInferenceError.inferenceFailed }
    return CornerPrediction(corners: corners, score: score, preprocessingMs: 0, inferenceMs: 0)
  }
}

private final class ScriptedRuntime: CornerRuntime {
  let script: Script
  init(_ script: Script) { self.script = script }
  var inputWidth: Int { 224 }
  var inputHeight: Int { 224 }
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
    throw CornerInferenceError.inferenceFailed
  }
  func analyzeLive(_ buffer: CVPixelBuffer, orientation: UInt8, crop: CGRect) throws -> CornerPrediction {
    try script.predict(buffer, orientation, crop)
  }
}

private final class ManualClock: @unchecked Sendable {
  private let lock = NSLock()
  private var value: Int64 = 1_000_000_000
  var now: Int64 { lock.lock(); defer { lock.unlock() }; return value }
  func advance(_ ns: Int64) { lock.lock(); value += ns; lock.unlock() }
}

/// A BGRA buffer with padded rows: light paper with dark word-like marks, or blank.
private func frame(width: Int = 640, height: Int = 480, textured: Bool = true) throws -> CVPixelBuffer {
  var buffer: CVPixelBuffer?
  // Rows end in 64 bytes of padding.
  let rowBytes = width * 4 + 64
  let storage = UnsafeMutableRawPointer.allocate(byteCount: rowBytes * height, alignment: 64)
  XCTAssertEqual(CVPixelBufferCreateWithBytes(nil, width, height, kCVPixelFormatType_32BGRA, storage, rowBytes,
                                              { _, base in base?.deallocate() }, nil, nil, &buffer),
                 kCVReturnSuccess)
  let pixels = try XCTUnwrap(buffer)
  CVPixelBufferLockBaseAddress(pixels, [])
  defer { CVPixelBufferUnlockBaseAddress(pixels, []) }
  let context = try XCTUnwrap(CGContext(
    data: CVPixelBufferGetBaseAddress(pixels), width: width, height: height, bitsPerComponent: 8,
    bytesPerRow: CVPixelBufferGetBytesPerRow(pixels), space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue))
  context.setFillColor(gray: 0.88, alpha: 1)
  context.fill(CGRect(x: 0, y: 0, width: width, height: height))
  guard textured else { return pixels }
  var random: UInt32 = 7
  func next(_ n: Int) -> Int {
    random = random &* 1_664_525 &+ 1_013_904_223
    return Int((random >> 8) % UInt32(n))
  }
  var y = 12
  while y < height - 20 {
    var x = 10
    while x < width - 20 {
      let word = 12 + next(48), h = 6 + next(8)
      context.setFillColor(gray: CGFloat(30 + next(60)) / 255, alpha: 1)
      context.fill(CGRect(x: x, y: y, width: word, height: h))
      x += word + 6 + next(10)
    }
    y += 22 + next(6)
  }
  return pixels
}

final class LiveGuidanceTests: XCTestCase {
  // MARK: - Adaptive pacing parity

  func testAdaptivePolicyMatchesTheSharedPlatformSequences() throws {
    let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "adaptive", withExtension: "tsv"))
    let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
      .map(String.init).filter { !$0.isEmpty && !$0.hasPrefix("#") }.dropFirst()
    XCTAssertFalse(rows.isEmpty)
    func corners(_ text: String) -> LiveCorners? { text == "-" ? nil : text.split(separator: ",").map { Double($0)! } }
    var name: String?
    var policy = AdaptiveDetectionPolicy()
    var checked = 0
    for line in rows {
      let v = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
      XCTAssertEqual(v.count, 12, line)
      if v[0] != name { name = v[0]; policy = AdaptiveDetectionPolicy() }
      switch v[1] {
      case "reference": policy.reference(corners(v[5]))
      case "confirmed": policy.confirmed()
      case "reset": policy.reset()
      case "next":
        let gap = policy.next(tracking: v[2] == "true", reliable: v[3] == "true", degraded: v[4] == "true",
                              current: corners(v[5]), width: Int(v[6])!, height: Int(v[7])!)
        XCTAssertEqual(gap, v[8] == "max" ? Int64.max : Int64(v[8])!, line)
        XCTAssertEqual(policy.status, v[9], line)
        XCTAssertEqual(policy.lastReset, v[10], line)
        XCTAssertEqual(policy.doubt, v[11] == "-" ? nil : v[11], line)
        XCTAssertEqual(policy.isPaused, v[9] == "paused", line)
        checked += 1
      default: XCTFail("Unknown step: \(line)")
      }
    }
    XCTAssertGreaterThan(checked, 20)
  }

  // MARK: - Detector

  func testAdaptiveCadenceRearmsAfterMovementAndLimitsUnknownMotion() {
    var cadence = AdaptiveInferenceCadence()
    func gap(_ ms: Int64, moving: Bool = false, measured: Bool = true, searching: Bool = true,
             trackingGap: Int64 = 0) -> Int64? {
      cadence.gap(now: ms * 1_000_000, searching: searching, moving: moving,
                  measured: measured, trackingGap: trackingGap)
    }
    XCTAssertNil(gap(0))
    XCTAssertNil(gap(30))
    XCTAssertNil(gap(60))
    XCTAssertEqual(gap(90), 0)
    cadence.didAttempt(at: 90_000_000)
    XCTAssertNil(gap(200, measured: false))
    XCTAssertNil(gap(250))
    XCTAssertNil(gap(280))
    XCTAssertNil(gap(310))
    XCTAssertNil(gap(400), "brief tracking loss does not trigger an immediate retry")
    XCTAssertNil(gap(500, moving: true))
    XCTAssertNil(gap(600, moving: true))
    XCTAssertNil(gap(650))
    XCTAssertNil(gap(680))
    XCTAssertNil(gap(710))
    XCTAssertEqual(gap(740), 0)
    cadence.didAttempt(at: 740_000_000)
    XCTAssertNil(gap(900, measured: false))
    XCTAssertNil(gap(1_739, measured: false))
    XCTAssertEqual(gap(1_740, measured: false), 0)
    cadence.didAttempt(at: 1_740_000_000)
    XCTAssertNil(gap(2_739, measured: false))
    XCTAssertNil(gap(3_000, searching: false, trackingGap: AdaptiveDetectionPolicy.paused))
    XCTAssertNil(gap(3_030, searching: false, trackingGap: AdaptiveDetectionPolicy.paused))
    XCTAssertNil(gap(3_060, searching: false, trackingGap: AdaptiveDetectionPolicy.paused))
    XCTAssertNil(gap(3_090, searching: false, trackingGap: AdaptiveDetectionPolicy.paused))
    XCTAssertEqual(gap(3_120, searching: false, trackingGap: AdaptiveDetectionPolicy.revalidateGapNs),
                   AdaptiveDetectionPolicy.revalidateGapNs)
  }

  func testAdaptiveCadenceRetriesAnUnconfirmedSteadyScene() {
    var cadence = AdaptiveInferenceCadence()
    func gap(_ ms: Int64, searching: Bool = true) -> Int64? {
      cadence.gap(now: ms * 1_000_000, searching: searching, moving: false, measured: true,
                  trackingGap: AdaptiveDetectionPolicy.paused)
    }

    for ms: Int64 in [0, 30, 60] { XCTAssertNil(gap(ms)) }
    XCTAssertEqual(gap(90), 0)
    cadence.didAttempt(at: 90_000_000)
    XCTAssertNil(gap(1_089))
    XCTAssertEqual(gap(1_090), 0)
    cadence.didAttempt(at: 1_090_000_000)
    XCTAssertNil(gap(2_089))
    XCTAssertEqual(gap(2_090), 0)
    XCTAssertNil(gap(2_100, searching: false))
    XCTAssertNil(gap(5_000, searching: false))
  }

  func testAdaptiveCadenceMakesProgressWhenMotionNeverSettles() {
    var cadence = AdaptiveInferenceCadence()
    func gap(_ ms: Int64, moving: Bool = false, measured: Bool = true, searching: Bool = true,
             trackingGap: Int64 = 0) -> Int64? {
      cadence.gap(now: ms * 1_000_000, searching: searching, moving: moving,
                  measured: measured, trackingGap: trackingGap)
    }

    for ms in stride(from: Int64(0), to: 1_000, by: 30) {
      XCTAssertNil(gap(ms, moving: ms % 90 == 0, measured: ms % 90 != 60))
    }
    XCTAssertEqual(gap(1_000, moving: true), 0)
    cadence.didAttempt(at: 1_000_000_000)
    XCTAssertNil(gap(1_999, moving: true))
    XCTAssertEqual(gap(2_000, moving: true), 0)
    cadence.didAttempt(at: 2_000_000_000)
    XCTAssertNil(gap(2_010, searching: false, trackingGap: AdaptiveDetectionPolicy.paused))
    XCTAssertNil(gap(3_500, searching: false, trackingGap: AdaptiveDetectionPolicy.paused))
    let revalidation = AdaptiveDetectionPolicy.revalidateGapNs
    XCTAssertNil(gap(3_600, moving: true, searching: false, trackingGap: revalidation))
    XCTAssertNil(gap(4_599, moving: true, searching: false, trackingGap: revalidation))
    XCTAssertEqual(gap(4_600, moving: true, searching: false, trackingGap: revalidation), 0)
  }

  func testAdaptiveCadenceUsesLowBlurOnlyToStartEarly() {
    var cadence = AdaptiveInferenceCadence()
    func gap(_ ms: Int64, blur: Double?, measured: Bool = true, moving: Bool = true) -> Int64? {
      cadence.gap(now: ms * 1_000_000, searching: true, moving: moving,
                  measured: measured, trackingGap: 0, blurFraction: blur)
    }

    XCTAssertNil(gap(0, blur: nil))
    XCTAssertNil(gap(30, blur: 0.0019, measured: false))
    XCTAssertNil(gap(60, blur: 0.0021))
    XCTAssertEqual(gap(90, blur: 0.0019), 0)
    cadence.didAttempt(at: 90_000_000)
    for ms in stride(from: Int64(120), through: 840, by: 30) {
      XCTAssertNil(gap(ms, blur: 0.0019, moving: false))
    }
    XCTAssertNil(gap(1_089, blur: 0.0019, moving: false))
    XCTAssertEqual(gap(1_090, blur: 0.0019, moving: false), 0)
    cadence.didAttempt(at: 1_090_000_000)

    XCTAssertNil(cadence.gap(now: 1_120_000_000, searching: false, moving: false, measured: true,
                             trackingGap: AdaptiveDetectionPolicy.paused, blurFraction: 0.0019))
    XCTAssertEqual(gap(1_150, blur: 0.0019), 0)
    cadence.didAttempt(at: 1_150_000_000)
    XCTAssertNil(gap(1_180, blur: 0.0019))
  }

  private final class Rig {
    let script = Script()
    let clock = ManualClock()
    let inference: CornerInference
    let detector: LiveDocumentDetector
    var results: [LiveGuideResult] = []
    var sensorNs: Int64 = 5_000_000_000

    init() {
      let script = self.script
      inference = CornerInference(makeRuntime: { _ in ScriptedRuntime(script) })
      _ = try? inference.replace(data: Data([1]))
      let clock = self.clock
      detector = LiveDocumentDetector(inference: inference, clock: { clock.now })
      detector.onResult = { [unowned self] in results.append($0) }
    }

    /// One frame 33 ms after the previous; results are delivered before returning.
    func analyze(_ buffer: CVPixelBuffer) {
      sensorNs += 33_333_333
      clock.advance(33_333_333)
      detector.analyze(buffer, sensorNs: sensorNs)
      RunLoop.main.run(until: Date().addingTimeInterval(0.02))
    }
  }

  func testGuideUsesPaddedBGRAWithTheFramingsOrientationAndSquareCrop() throws {
    let rig = Rig()
    let buffer = try frame()
    XCTAssertGreaterThan(CVPixelBufferGetBytesPerRow(buffer), 640 * 4, "rows are padded")
    rig.detector.update(.init(adaptive: false, minCornerScore: 0.95, rotationAngle: 90, square: true))
    rig.detector.begin(enabled: true)
    rig.analyze(buffer)
    let call = try XCTUnwrap(rig.script.calls.last)
    XCTAssertEqual(call.orientation, 6)
    XCTAssertEqual(call.crop, CGRect(x: 80, y: 0, width: 480, height: 480))
    let result = try XCTUnwrap(rig.results.last)
    XCTAssertEqual(result.status, .tracking)
    XCTAssertEqual(result.corners, [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8])
    XCTAssertEqual(result.framing?.orientation, 6)
    XCTAssertEqual(result.framing?.framed, PixelSize(480, 480))
    XCTAssertEqual(result.acceptanceId, 1)
    XCTAssertEqual(result.sequence, 1)
    XCTAssertEqual(result.sensorNs, rig.sensorNs)
    XCTAssertEqual(result.modelGeneration, 1)
    XCTAssertEqual(result.deadlineNs, rig.clock.now + LiveDocumentDetector.holdNs)
    // The full 4:3 frame, upright.
    rig.detector.update(.init(adaptive: false, minCornerScore: 0.95, rotationAngle: 0, square: false))
    rig.clock.advance(100_000_000)
    rig.analyze(buffer)
    XCTAssertEqual(rig.script.calls.last?.orientation, 1)
    XCTAssertEqual(rig.script.calls.last?.crop, CGRect(x: 0, y: 0, width: 640, height: 480))
    XCTAssertEqual(rig.results.last?.framing?.framed, PixelSize(640, 480))
  }

  func testGuideToggleStopsInferenceAndPublicationWithoutAStop() throws {
    let rig = Rig()
    let buffer = try frame()
    rig.detector.begin(enabled: true)
    rig.analyze(buffer)
    XCTAssertEqual(rig.script.count, 1)
    rig.detector.setEnabled(false)
    rig.results = []
    for _ in 0..<3 { rig.analyze(buffer) }
    XCTAssertEqual(rig.script.count, 1, "disabled guidance runs no inference")
    XCTAssertTrue(rig.results.isEmpty)
    rig.detector.setEnabled(true)
    rig.analyze(buffer)
    XCTAssertEqual(rig.script.count, 2)
    XCTAssertEqual(rig.results.last?.status, .tracking)
    XCTAssertEqual(rig.results.last?.acceptanceId, 2, "acceptance IDs stay monotonic across resets")
  }

  func testAResultFromAnEarlierGenerationIsNeverDelivered() throws {
    let rig = Rig()
    let buffer = try frame()
    rig.detector.begin(enabled: true)
    rig.detector.analyze(buffer, sensorNs: 1)
    // A model change on main invalidates the result before main delivers it.
    rig.detector.invalidate()
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertTrue(rig.results.isEmpty)
    rig.detector.stop()
    rig.detector.analyze(buffer, sensorNs: 2)
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertTrue(rig.results.isEmpty, "a stopped detector analyzes nothing")
  }

  func testAReplacedModelRestartsTheGuideWithItsGeneration() throws {
    let rig = Rig()
    let buffer = try frame(textured: false)
    rig.detector.begin(enabled: true)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.modelGeneration, 1)
    _ = try rig.inference.replace(data: Data([2]))
    rig.clock.advance(100_000_000)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.status, .searching, "the old model's guide is dropped")
    XCTAssertEqual(rig.results.last?.modelGeneration, 2)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.status, .tracking)
    XCTAssertEqual(rig.results.last?.modelGeneration, 2)
  }

  func testLowScoreClearsTheGuideAndWaitsForMovement() throws {
    let rig = Rig()
    let buffer = try frame()
    rig.detector.begin(enabled: true)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.status, .tracking)
    rig.script.set(corners: [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8], score: 0.5)
    rig.clock.advance(100_000_000)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.status, .searching)
    XCTAssertEqual(rig.results.last?.score, 0.5)
    let calls = rig.script.count
    for _ in 0..<4 { rig.clock.advance(100_000_000); rig.analyze(buffer) }
    XCTAssertEqual(rig.script.count, calls, "a still low-score scene is not inferred again")
    // A score threshold change restarts inference.
    rig.detector.update(.init(adaptive: false, minCornerScore: 0.4, rotationAngle: 0, square: false))
    rig.analyze(buffer)
    rig.analyze(buffer)
    XCTAssertGreaterThan(rig.script.count, calls)
    XCTAssertEqual(rig.results.last?.status, .tracking)
  }

  func testTrackingLossAndHoldExpiryReturnToSearching() throws {
    let rig = Rig()
    let blank = try frame(textured: false)
    rig.detector.begin(enabled: true)
    rig.analyze(blank)
    XCTAssertEqual(rig.results.last?.status, .tracking)
    // Without flow evidence the model alone decides: a missing page clears the guide.
    rig.script.set(corners: nil)
    rig.clock.advance(100_000_000)
    rig.analyze(blank)
    XCTAssertEqual(rig.results.last?.status, .searching)
    XCTAssertNil(rig.results.last?.corners)
    // An accepted guide that is not refreshed expires on main at its deadline.
    rig.script.set(corners: [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8])
    rig.clock.advance(100_000_000)
    rig.analyze(blank)
    XCTAssertEqual(rig.results.last?.status, .tracking)
    rig.clock.advance(LiveDocumentDetector.holdNs)
    let expired = expectation(description: "expired")
    rig.detector.onResult = {
      XCTAssertEqual($0.status, .searching)
      expired.fulfill()
    }
    wait(for: [expired], timeout: 2)
  }

  func testRuntimeFailureReportsUnavailableUntilTheNextStart() throws {
    let rig = Rig()
    let buffer = try frame()
    rig.script.set(corners: nil, fails: true)
    rig.detector.begin(enabled: true)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.status, .unavailable)
    XCTAssertTrue(rig.detector.state.hasFailed)
    rig.analyze(buffer)
    XCTAssertEqual(rig.script.count, 1, "a failed detector stops inferring")
    rig.script.set(corners: [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8])
    rig.detector.begin(enabled: true)
    rig.analyze(buffer)
    XCTAssertEqual(rig.results.last?.status, .tracking)
  }

  func testAdaptiveDetectionPausesWhileFlowCarriesTheGuide() throws {
    let rig = Rig()
    let page = try frame(), blank = try frame(textured: false)
    rig.detector.update(.init(adaptive: true, minCornerScore: 0.95, rotationAngle: 0, square: false))
    rig.detector.begin(enabled: true)
    for _ in 0..<5 { rig.analyze(page) }
    XCTAssertEqual(rig.script.count, 1)
    let first = try XCTUnwrap(rig.results.last)
    for _ in 0..<5 { rig.analyze(page) }
    XCTAssertEqual(rig.script.count, 1, "reliable flow keeps inference paused")
    let carried = try XCTUnwrap(rig.results.last)
    XCTAssertEqual(carried.status, .tracking)
    XCTAssertGreaterThan(carried.acceptanceId, first.acceptanceId, "flow retention refreshes the hold")
    XCTAssertNil(carried.score, "a carried frame has no inference score")
    XCTAssertEqual(carried.corners?.count, 8)
    // Losing tracks briefly keeps the existing outline and defers inference.
    rig.analyze(blank)
    rig.analyze(blank)
    XCTAssertEqual(rig.script.count, 1)
    rig.clock.advance(1_000_000_000)
    rig.analyze(blank)
    XCTAssertEqual(rig.script.count, 2)
  }

  func testAFrameRequestCopiesOnlyTheNextFramedRegion() throws {
    let rig = Rig()
    let buffer = try frame()
    rig.detector.update(.init(adaptive: false, minCornerScore: 0.95, rotationAngle: 270, square: true))
    var frames: [LiveFrame] = []
    rig.detector.requestFrame { _ in XCTFail("a replaced request is never called") }
    rig.detector.requestFrame { frames.append($0) }
    rig.detector.begin(enabled: false)
    rig.analyze(buffer)
    rig.analyze(buffer)
    XCTAssertEqual(frames.count, 1, "one request, one frame, even with guidance off")
    let copy = try XCTUnwrap(frames.first)
    XCTAssertEqual(copy.width, 480)
    XCTAssertEqual(copy.height, 480)
    XCTAssertEqual(copy.orientation, 8)
    XCTAssertEqual(copy.pixels.count, 480 * 480 * 4, "packed rows, without the buffer's padding")
    CVPixelBufferLockBaseAddress(buffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
    let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(buffer)
    for (x, y) in [(0, 0), (479, 0), (17, 300), (479, 479)] {
      for channel in 0..<4 {
        XCTAssertEqual(copy.pixels[(y * 480 + x) * 4 + channel], base[y * stride + (80 + x) * 4 + channel])
      }
    }
  }

  // MARK: - Overlay

  func testTransitionSmoothsSmallGapsFollowsLargeOnesAndSnapsJumps() {
    let a: [CGFloat] = [0, 0, 100, 0, 100, 100, 0, 100]
    let frame: Int64 = 16_000_000
    func firstFrameShare(_ shift: CGFloat) -> CGFloat {
      var transition = GuideTransition()
      transition.retarget(a, nowNs: 0, snapDistance: 200)
      transition.retarget(a.map { $0 + shift }, nowNs: 0, snapDistance: 200)
      transition.advance(nowNs: frame)
      return transition.points[0] / shift
    }
    // A 4 pt jitter keeps the 80 ms time constant; a 90 pt move of a 200 pt snap distance nears the 20 ms one.
    XCTAssertEqual(firstFrameShare(4), 1 - exp(-16.0 / 80), accuracy: 1e-6)
    XCTAssertGreaterThan(firstFrameShare(90), firstFrameShare(4) * 2)

    var transition = GuideTransition()
    transition.retarget(a, nowNs: 0, snapDistance: 200)
    XCTAssertEqual(transition.points, a, "the first guide is drawn in place")
    XCTAssertFalse(transition.isAnimating)
    let b = a.map { $0 + 20 }
    transition.retarget(b, nowNs: 0, snapDistance: 200)
    XCTAssertTrue(transition.isAnimating)
    var now: Int64 = 0
    while transition.isAnimating && now < 2_000_000_000 { now += frame; transition.advance(nowNs: now) }
    XCTAssertEqual(transition.points, b, "it lands exactly on its target")
    transition.retarget(b.map { $0 + 300 }, nowNs: now, snapDistance: 200)
    XCTAssertFalse(transition.isAnimating, "a likely new page snaps")
    transition.durationNs = 0
    transition.retarget(b, nowNs: now + 1, snapDistance: 500)
    XCTAssertEqual(transition.points, b)
  }

  @MainActor
  func testOverlayMapsTheFramedCornersAndDimsHeldGuides() throws {
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    let overlay = LiveGuideOverlay(clock: { 0 })
    overlay.frame = window.bounds
    window.addSubview(overlay)
    overlay.layoutIfNeeded()
    overlay.viewfinder = true
    XCTAssertTrue(overlay.showsViewfinder)
    let framing = try XCTUnwrap(CameraFraming(buffer: PixelSize(640, 480), rotationAngle: 90, square: false))
    var result = LiveGuideResult(status: .tracking, corners: [0, 0, 1, 0, 1, 1, 0, 1], framing: framing,
                                 acceptanceId: 3, deadlineNs: 1)
    overlay.show(result)
    // A 480x640 upright frame fits 390x520, centered vertically.
    XCTAssertEqual(overlay.drawnPoints, [0, 162, 390, 162, 390, 682, 0, 682])
    XCTAssertEqual(overlay.strokeAlpha, 1)
    XCTAssertFalse(overlay.showsViewfinder, "brackets hide behind a guide")
    result.status = .held
    overlay.show(result)
    XCTAssertEqual(overlay.strokeAlpha, 0.45, accuracy: 1e-9)
    XCTAssertEqual(overlay.drawnPoints, [0, 162, 390, 162, 390, 682, 0, 682], "held dims in place")
    overlay.show(LiveGuideResult(status: .searching))
    XCTAssertFalse(overlay.hasOutline)
    XCTAssertTrue(overlay.showsViewfinder)
  }

  @MainActor
  func testOverlayMapsThroughTheBufferWhenPreviewAndCaptureAnglesDiffer() throws {
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    let overlay = LiveGuideOverlay(clock: { 0 })
    overlay.frame = window.bounds
    window.addSubview(overlay)
    overlay.layoutIfNeeded()
    // A portrait-locked app held sideways: analysis turns buffers upright by 90 degrees while the
    // preview layer keeps showing them unturned.
    let capture = try XCTUnwrap(CameraFraming(buffer: PixelSize(640, 480), rotationAngle: 90, square: false))
    overlay.previewFraming = try XCTUnwrap(CameraFraming(buffer: PixelSize(640, 480), rotationAngle: 0, square: false))
    overlay.show(LiveGuideResult(status: .tracking, corners: [0, 0, 1, 0, 1, 1, 0, 1], framing: capture,
                                 acceptanceId: 1, deadlineNs: 1))
    // The 640x480 preview fits 390x292.5, centered vertically. Upright TL is the buffer's bottom-left.
    XCTAssertEqual(overlay.drawnPoints, [0, 568.25, 0, 275.75, 390, 275.75, 390, 568.25])
    overlay.previewFraming = try XCTUnwrap(CameraFraming(buffer: PixelSize(640, 480), rotationAngle: 90, square: false))
    XCTAssertFalse(overlay.hasOutline, "a guide mapped through the old preview framing is cleared")
  }

  // MARK: - Ownership races

  /// A runtime whose live analysis waits at a gate, holding the shared inference queue; counts live instances.
  private final class GatedRuntime: CornerRuntime {
    final class Gate: @unchecked Sendable {
      let entered = DispatchSemaphore(value: 0)
      let release = DispatchSemaphore(value: 0)
      private let lock = NSLock()
      private var count = 0
      var alive: Int { lock.lock(); defer { lock.unlock() }; return count }
      func change(_ delta: Int) { lock.lock(); count += delta; lock.unlock() }
    }
    let gate: Gate
    init(_ gate: Gate) { self.gate = gate; gate.change(1) }
    deinit { gate.change(-1) }
    var inputWidth: Int { 224 }
    var inputHeight: Int { 224 }
    func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
      throw CornerInferenceError.inferenceFailed
    }
    func analyzeLive(_ buffer: CVPixelBuffer, orientation: UInt8, crop: CGRect) throws -> CornerPrediction {
      gate.entered.signal()
      gate.release.wait()
      return CornerPrediction(corners: nil, score: 0, preprocessingMs: 0, inferenceMs: 0)
    }
  }

  func testLeaseAcquisitionNeverBlocksMainAndAStopDiscardsALateLease() throws {
    let gate = GatedRuntime.Gate()
    defer { gate.release.signal() }
    let inference = CornerInference(makeRuntime: { _ in GatedRuntime(gate) })
    try inference.replace(data: Data([1]))
    // Another owner holds the shared inference queue.
    let blocker = inference.acquire()
    let buffer = try frame()
    let done = expectation(description: "blocker")
    DispatchQueue.global().async {
      _ = try? blocker.analyzeLive(buffer)
      blocker.close()
      done.fulfill()
    }
    XCTAssertEqual(gate.entered.wait(timeout: .now() + 2), .success)
    let preparation = DispatchQueue(label: "test.live.prepare")
    let acquisitionStarted = DispatchSemaphore(value: 0)
    let detector = LiveDocumentDetector(inference: inference, prepareQueue: preparation, acquireLease: {
      acquisitionStarted.signal()
      return inference.acquire()
    })
    detector.begin(enabled: true)
    // The preload has committed to acquiring a lease while the inference queue is blocked.
    XCTAssertEqual(acquisitionStarted.wait(timeout: .now() + 2), .success)
    // A regression that holds the detector lock while acquiring must fail the timing assertion
    // instead of hanging this test. The normal path releases the gate immediately after stop.
    let unblock = DispatchWorkItem { gate.release.signal() }
    DispatchQueue.global().asyncAfter(deadline: .now() + 2, execute: unblock)
    defer { unblock.cancel() }
    let started = Date()
    detector.update(.init(adaptive: true, minCornerScore: 0.9, rotationAngle: 90, square: false))
    detector.stop()
    XCTAssertLessThan(Date().timeIntervalSince(started), 0.5, "settings and stop wait for no inference")
    gate.release.signal()
    wait(for: [done], timeout: 3)
    // Join the late acquisition and its disposal before checking the last owner released the model.
    preparation.sync {}
    inference.drain()
    XCTAssertEqual(gate.alive, 0, "no lease outlives the stop")
  }

  func testASupersededModelNeverBecomesTheSelection() throws {
    let gate = GatedRuntime.Gate()
    let inference = CornerInference(makeRuntime: { data in
      guard data.first == 0xA else { throw CornerInferenceError.modelLoadFailed }
      // The older model is validating when the newer request arrives.
      gate.entered.signal()
      _ = gate.release.wait(timeout: .now() + 2)
      return GatedRuntime(gate)
    })
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: folder) }
    let older = folder.appendingPathComponent("older.tflite"), newer = folder.appendingPathComponent("newer.tflite")
    try Data([0xA]).write(to: older)
    try Data([0xB]).write(to: newer)
    let loader = CornerModelLoader(inference: inference)
    var olderOutcomes: [ScannerErrorCode?] = [], newerOutcomes: [ScannerErrorCode?] = []
    let finished = expectation(description: "loads")
    finished.expectedFulfillmentCount = 2
    loader.load(older.path) { olderOutcomes.append($0); finished.fulfill() }
    XCTAssertEqual(gate.entered.wait(timeout: .now() + 2), .success)
    loader.load(newer.path) { newerOutcomes.append($0); finished.fulfill() }
    gate.release.signal()
    wait(for: [finished], timeout: 3)
    XCTAssertEqual(olderOutcomes, [.modelSuperseded])
    XCTAssertEqual(newerOutcomes, [.modelLoadFailed], "the newest request reports its own failure")
    XCTAssertThrowsError(try inference.selection(), "the superseded model was never activated") {
      XCTAssertEqual($0 as? CornerInferenceError, .modelUnavailable)
    }
  }

  // MARK: - Camera view

  @MainActor
  private final class ViewRig {
    final class Camera: CameraSessionControl {
      var onEvent: ((CameraSessionEvent) -> Void)?
      var frameSink: ((CVPixelBuffer, Int64) -> Void)?
      var device: AVCaptureDevice? { nil }
      var starts = 0
      var stops = 0
      func attachPreview(_ layer: AVCaptureVideoPreviewLayer) {}
      func start(_ request: CameraRequest) { starts += 1 }
      func stop() { stops += 1 }
    }
    final class Granted: CameraAuthorization {
      func authorizationStatus() -> AVAuthorizationStatus { .authorized }
      func requestFailure() -> ScannerErrorCode? { nil }
      func requestAccess(completion: @escaping @Sendable () -> Void) { completion() }
    }

    let camera = Camera()
    let script = Script()
    let view: HybridDocumentScannerCameraView
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    var models: [String] = []
    var modelResult: ScannerErrorCode?
    var documents: [DocumentState] = []
    var errors: [ScannerErrorCode] = []

    init() {
      let notifications = NotificationCenter()
      let script = self.script
      let inference = CornerInference(makeRuntime: { _ in ScriptedRuntime(script) })
      _ = try? inference.replace(data: Data([1]))
      weak var rig: ViewRig?
      view = HybridDocumentScannerCameraView(
        camera: camera, permissions: CameraPermissions(authorization: Granted(), notifications: notifications),
        notifications: notifications, inBackground: { false },
        models: { source, done in
          rig?.models.append(source)
          done(rig?.modelResult)
        },
        detector: LiveDocumentDetector(inference: inference))
      rig = self
      view.view.frame = window.bounds
      view.onDocumentStateChange = { [unowned self] in documents.append($0) }
      view.onError = { [unowned self] in errors.append($0.code) }
      view.cornerDetectionModel = "model.tflite"
      window.addSubview(view.view)
      window.layoutIfNeeded()
    }

    func stream() {
      camera.onEvent?(.configured(CameraConfiguration(photo: PixelSize(4032, 3024), video: PixelSize(640, 480))))
      camera.onEvent?(.streaming(PixelSize(640, 480)))
    }
  }

  @MainActor
  func testCameraWaitsForTheModelAndAFailureKeepsItSuspended() {
    let rig = ViewRig()
    rig.modelResult = .modelLoadFailed
    rig.view.afterUpdate()
    XCTAssertEqual(rig.models, ["model.tflite"])
    XCTAssertEqual(rig.camera.starts, 0, "a failed model keeps the camera suspended")
    XCTAssertEqual(rig.errors, [.modelLoadFailed])
    XCTAssertEqual(rig.documents.last, .unavailable)
    XCTAssertEqual(rig.view.cameraState, .suspended)
    rig.view.afterUpdate()
    XCTAssertEqual(rig.models.count, 1, "the same source is not prepared again")
    rig.modelResult = nil
    rig.view.cornerDetectionModel = "other.tflite"
    rig.view.afterUpdate()
    XCTAssertEqual(rig.camera.starts, 1)
    XCTAssertEqual(rig.documents.last, .searching)
  }

  @MainActor
  func testGuideResultsDriveTheDocumentStateAndTheToggleKeepsTheCamera() throws {
    let rig = ViewRig()
    rig.view.afterUpdate()
    rig.stream()
    XCTAssertEqual(rig.documents, [.off, .searching])
    let preview = rig.view.view as! CameraPreviewView
    XCTAssertTrue(preview.guide.showsViewfinder)
    let buffer = try frame()
    let sink = try XCTUnwrap(rig.camera.frameSink)
    sink(buffer, 1_000_000)
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(rig.documents.last, .tracking)
    XCTAssertTrue(preview.guide.hasOutline)
    rig.view.guideLiveCorners = false
    rig.view.afterUpdate()
    XCTAssertEqual(rig.documents.last, .off)
    XCTAssertFalse(preview.guide.hasOutline)
    XCTAssertEqual(rig.camera.starts, 1)
    XCTAssertEqual(rig.camera.stops, 0, "the guide toggles without restarting the camera")
    let calls = rig.script.count
    sink(buffer, 2_000_000)
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(rig.script.count, calls)
    rig.view.guideLiveCorners = true
    rig.view.afterUpdate()
    XCTAssertEqual(rig.documents.last, .searching)
    rig.view.active = false
    rig.view.afterUpdate()
    XCTAssertEqual(rig.documents.last, .off)
  }

  @MainActor
  func testInvalidMinCornerScoreIsReportedAndTheDefaultApplies() {
    let rig = ViewRig()
    rig.view.minCornerScore = 2
    rig.view.afterUpdate()
    XCTAssertEqual(rig.errors, [.inputInvalid])
    XCTAssertEqual(rig.camera.starts, 1)
  }
}
