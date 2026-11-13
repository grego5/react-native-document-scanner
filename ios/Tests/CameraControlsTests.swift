import AVFoundation
import NitroModules
import UIKit
import XCTest
@testable import ReactNativeDocumentScanner

/// A camera owner whose device is scripted: commands are recorded and completed by the test (or
/// accepted at once), and focus samples are injected in the order main would receive them.
/// `AVCameraSession` orders samples and completions on its queue; `CameraSessionTests` covers that.
private final class ControlCamera: CameraSessionControl {
  var onEvent: ((CameraSessionEvent) -> Void)?
  var frameSink: ((CVPixelBuffer, Int64) -> Void)?
  var metadataSink: ((CameraFrameMetadata) -> Void)?
  var focusEvents: ((FocusObservation) -> Void)?
  var device: AVCaptureDevice? { nil }
  var commands: [CameraCommand] = []
  /// Completions the test answers; nil accepts every command at once.
  var held: [(CommandOutcome) -> Void]?
  var starts = 0
  func attachPreview(_ layer: AVCaptureVideoPreviewLayer) {}
  func start(_ request: CameraRequest) { starts += 1 }
  func stop() {}
  func perform(_ command: CameraCommand, completion: @escaping (CommandOutcome) -> Void) {
    commands.append(command)
    if held != nil { held?.append(completion) } else { completion(.accepted) }
  }
}

/// A manual clock and main-thread timer queue.
private final class Timers: @unchecked Sendable {
  var now: Int64 = 1_000_000_000
  private var due: [(at: Int64, block: @Sendable () -> Void)] = []
  func schedule(_ delay: Int64, _ block: @escaping @Sendable () -> Void) { due.append((now + delay, block)) }
  func advance(by ns: Int64) {
    now += ns
    while let index = due.firstIndex(where: { $0.at <= now }) { due.remove(at: index).block() }
  }
}

private let full = CameraCapabilities(focusPoint: true, scanFocus: true, continuousFocus: true, exposurePoint: true,
                                      torch: true, exposureBias: -2...2,
                                      frameRates: [FrameRateRange(min: 1, max: 60)])

final class CameraControlsTests: XCTestCase {
  private func rig(_ capabilities: CameraCapabilities? = full, settings: CameraControls.Settings = .init())
    -> (ControlCamera, Timers, CameraControls) {
    let camera = ControlCamera(), timers = Timers()
    let controls = CameraControls(camera: camera, clock: { timers.now }, schedule: timers.schedule)
    controls.update(settings)
    if let capabilities { controls.bind(capabilities) }
    return (camera, timers, controls)
  }

  private func observe(_ controls: CameraControls, adjusting: Bool, locked: Bool) {
    controls.focusObserved(FocusObservation(adjusting: adjusting, locked: locked))
  }

  // MARK: - Policies

  func testTorchPolicyMatchesTheSharedPlatformVectors() throws {
    let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "torch", withExtension: "tsv"))
    let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
      .filter { !$0.isEmpty && !$0.hasPrefix("#") }.dropFirst()
    var policy = TorchPolicy()
    for line in rows {
      let v = line.split(separator: "\t").map(String.init)
      XCTAssertEqual(v.count, 6, line)
      if v[0] == "1" { policy.reset() }
      let edge = policy.observe(observationNs: Int64(v[1])!, exposureNs: Int64(v[2])!,
                                iso: v[3] == "-" ? nil : Double(v[3])!)
      XCTAssertEqual(edge, v[4] == "true", line)
      XCTAssertEqual(policy.lit, v[5] == "true", line)
    }
    XCTAssertEqual(rows.count, 13)
  }

  func testExposureBiasClampsAndFrameRateFollowsAndroidChoice() {
    XCTAssertEqual(CameraExposurePolicy.bias(5, range: -2...2), 2)
    XCTAssertEqual(CameraExposurePolicy.bias(-5, range: -2...2), -2)
    XCTAssertEqual(CameraExposurePolicy.bias(.nan, range: -2...2), 0)
    XCTAssertNil(CameraExposurePolicy.bias(1, range: nil), "no adjustable bias ignores the prop")
    XCTAssertNil(CameraExposurePolicy.bias(1, range: 0...0))
    let ranges = [FrameRateRange(min: 1, max: 30), FrameRateRange(min: 24, max: 60)]
    XCTAssertNil(CameraExposurePolicy.frameRate(minFps: 0, ranges: ranges), "0 keeps the format's own range")
    XCTAssertEqual(CameraExposurePolicy.frameRate(minFps: 15, ranges: ranges), FrameRateRange(min: 15, max: 30))
    XCTAssertEqual(CameraExposurePolicy.frameRate(minFps: 40, ranges: ranges), FrameRateRange(min: 40, max: 40))
    // Nothing reaches 90: the highest reachable minimum, at its own maximum.
    XCTAssertEqual(CameraExposurePolicy.frameRate(minFps: 90, ranges: ranges), FrameRateRange(min: 60, max: 60))
    XCTAssertNil(CameraExposurePolicy.frameRate(minFps: 15, ranges: []))
  }

  // MARK: - Focus

  func testTapScansAndSettlesOnlyOnItsOwnLockWithoutClaimingFocus() {
    let (camera, timers, controls) = rig()
    XCTAssertEqual(camera.commands.first, .continuousFocus(CameraControls.centre), "a binding aims at the centre")
    camera.held = []
    let point = CGPoint(x: 0.2, y: 0.3)
    timers.advance(by: 10)
    controls.tap(at: point)
    XCTAssertEqual(camera.commands.last, .scanFocus(point, meter: point))
    XCTAssertEqual(controls.snapshot.owner, .tap)
    XCTAssertTrue(controls.snapshot.pending)
    let started = controls.snapshot.generation
    // A sample delivered before the command's completion was taken before it applied.
    observe(controls, adjusting: false, locked: true)
    XCTAssertTrue(controls.snapshot.pending)
    timers.advance(by: 10)
    camera.held!.last!(.accepted)
    observe(controls, adjusting: true, locked: false)
    XCTAssertEqual(controls.autofocus, .scan)
    XCTAssertTrue(controls.snapshot.pending)
    observe(controls, adjusting: false, locked: true)
    XCTAssertFalse(controls.snapshot.pending)
    XCTAssertNil(controls.snapshot.locked, "AVFoundation reports that the scan ended, not that it focused")
    XCTAssertEqual(controls.snapshot.owner, .tap)
    XCTAssertGreaterThan(controls.snapshot.generation, started)
    XCTAssertEqual(controls.autofocus, .inactive)
    // The hold expires and continuous autofocus resumes at the centre.
    timers.advance(by: FocusPolicy.tapHoldNs)
    XCTAssertNil(controls.snapshot.owner)
    XCTAssertEqual(camera.commands.last, .continuousFocus(CameraControls.centre))
  }

  func testTapsAreRefusedWhenOffDuringAShotOrOnFixedFocus() {
    let (camera, _, controls) = rig(settings: .init(tapToFocus: false))
    let sent = camera.commands.count
    controls.tap(at: CGPoint(x: 0.5, y: 0.5))
    XCTAssertEqual(camera.commands.count, sent)
    controls.update(.init(tapToFocus: true))
    controls.shotInFlight = { true }
    controls.tap(at: CGPoint(x: 0.5, y: 0.5))
    XCTAssertEqual(camera.commands.count, sent, "a shot's focus must not change")
    XCTAssertNil(controls.snapshot.owner)

    let fixed = rig(CameraCapabilities())
    XCTAssertTrue(fixed.0.commands.isEmpty, "fixed focus without a torch, bias or rates sends nothing")
    fixed.2.tap(at: CGPoint(x: 0.5, y: 0.5))
    XCTAssertTrue(fixed.0.commands.isEmpty)
    // A capture on fixed focus has nothing to wait for: its scan settles at once, unknown.
    fixed.2.capture(at: CGPoint(x: 0.5, y: 0.5))
    XCTAssertEqual(fixed.2.snapshot.owner, .capture)
    XCTAssertFalse(fixed.2.snapshot.pending)
    XCTAssertNil(fixed.2.snapshot.locked)
  }

  func testCaptureScanTimesOutAndFocusCanReacquireWhileTheCaptureWaits() {
    let (camera, timers, controls) = rig()
    let page = CGPoint(x: 0.6, y: 0.4)
    controls.capture(at: page)
    XCTAssertEqual(camera.commands.last, .scanFocus(page, meter: nil))
    timers.advance(by: FocusPolicy.scanTimeoutNs)
    XCTAssertFalse(controls.snapshot.pending)
    XCTAssertEqual(controls.snapshot.locked, false, "a scan that never ended timed out")
    XCTAssertEqual(controls.snapshot.owner, .capture, "the capture keeps the lens until it finishes")
    // While the capture waits without a shot, a new scan may start; the old scan's evidence is stale.
    let timedOut = controls.snapshot.generation
    controls.capture(at: page)
    XCTAssertTrue(controls.snapshot.pending)
    XCTAssertGreaterThan(controls.snapshot.generation, timedOut)
    observe(controls, adjusting: true, locked: false)
    observe(controls, adjusting: false, locked: true)
    XCTAssertFalse(controls.snapshot.pending)
    XCTAssertNil(controls.snapshot.locked)
    // The shot exposes: no new scan; afterwards the lens returns to continuous autofocus.
    controls.shotInFlight = { true }
    let settled = controls.snapshot.generation
    controls.capture(at: page)
    XCTAssertEqual(controls.snapshot.generation, settled)
    controls.shotInFlight = { false }
    controls.afterCapture()
    XCTAssertNil(controls.snapshot.owner)
    XCTAssertEqual(camera.commands.last, .continuousFocus(CameraControls.centre))
  }

  func testAnEarlierScanFinishingWhileTheNewCommandWaitsDoesNotSettleIt() {
    let (camera, timers, controls) = rig()
    let page = CGPoint(x: 0.5, y: 0.6)
    controls.capture(at: page)
    observe(controls, adjusting: true, locked: false)
    timers.advance(by: FocusPolicy.scanTimeoutNs)
    XCTAssertEqual(controls.snapshot.locked, false, "the first scan timed out while the lens still moved")
    // A new scan's command waits on the session queue while the first scan finishes on the device.
    camera.held = []
    controls.capture(at: page)
    timers.advance(by: 5)
    observe(controls, adjusting: true, locked: false)
    observe(controls, adjusting: false, locked: true)
    XCTAssertTrue(controls.snapshot.pending, "the earlier scan's lock is not the new scan's end")
    timers.advance(by: 5)
    camera.held!.last!(.accepted)
    XCTAssertTrue(controls.snapshot.pending)
    // A scan that ends while its command applies is sampled after the completion.
    observe(controls, adjusting: false, locked: true)
    XCTAssertFalse(controls.snapshot.pending)
    XCTAssertNil(controls.snapshot.locked)
  }

  func testATapPastItsHoldWaitsForTheShotInFlight() {
    let (_, timers, controls) = rig()
    controls.tap(at: CGPoint(x: 0.3, y: 0.3))
    controls.shotInFlight = { true }
    timers.advance(by: FocusPolicy.tapHoldNs)
    XCTAssertEqual(controls.snapshot.owner, .tap, "an overdue tap waits for the shot")
    controls.shotInFlight = { false }
    controls.afterCapture()
    XCTAssertNil(controls.snapshot.owner)
  }

  func testUnbindRetiresCompletionsAndObservationsOfTheEarlierBinding() {
    let (camera, timers, controls) = rig()
    camera.held = []
    controls.tap(at: CGPoint(x: 0.4, y: 0.4))
    let refuse = camera.held!.last!
    controls.unbind()
    XCTAssertNil(controls.snapshot.owner)
    let unbound = controls.snapshot
    refuse(.refused)
    observe(controls, adjusting: true, locked: false)
    XCTAssertEqual(controls.snapshot, unbound, "late evidence of a stopped run changes nothing")
    XCTAssertEqual(controls.autofocus, .inactive)
    timers.advance(by: FocusPolicy.tapHoldNs)
    XCTAssertEqual(controls.snapshot, unbound)
    // A new binding: a refused scan command settles as a failure.
    controls.bind(full)
    controls.tap(at: CGPoint(x: 0.4, y: 0.4))
    camera.held!.last!(.refused)
    XCTAssertFalse(controls.snapshot.pending)
    XCTAssertEqual(controls.snapshot.locked, false)
  }

  // MARK: - Torch, exposure and frame rate

  func testLowLightBoostChecksSupportAndDefersChangesUntilCaptureSettles() {
    let (camera, _, controls) = rig()
    XCTAssertFalse(camera.commands.contains(.lowLightBoost(true)))
    var supported = full
    supported.lowLightBoost = true
    controls.unbind()
    controls.bind(supported)
    XCTAssertTrue(camera.commands.contains(.lowLightBoost(true)))
    controls.capturePending = { true }
    controls.update(.init(lowLightBoost: false))
    XCTAssertFalse(camera.commands.contains(.lowLightBoost(false)))
    controls.capturePending = { false }
    controls.afterCapture()
    controls.afterCapture()
    XCTAssertEqual(camera.commands.filter { $0 == .lowLightBoost(false) }.count, 1)
  }

  func testTorchChangesDoNotResendAutomaticBoost() {
    var supported = full
    supported.lowLightBoost = true
    let (camera, _, controls) = rig(supported, settings: .init(torch: .auto))
    XCTAssertTrue(camera.commands.contains(.lowLightBoost(true)))

    camera.commands.removeAll()
    controls.update(.init(torch: .off))
    XCTAssertTrue(camera.commands.isEmpty)

    camera.commands.removeAll()
    controls.capturePending = { true }
    controls.update(.init(torch: .on))
    XCTAssertTrue(camera.commands.isEmpty)
    controls.capturePending = { false }
    controls.afterCapture()
    XCTAssertEqual(camera.commands, [.torch(true)])

    camera.commands.removeAll()
    controls.update(.init(torch: .off))
    XCTAssertEqual(camera.commands, [.torch(false)])
  }

  func testTorchModesDeferDuringACaptureAndCamerasWithoutATorchIgnoreThem() {
    let noTorch = rig(CameraCapabilities(focusPoint: true, scanFocus: true, continuousFocus: true), settings: .init(torch: .on))
    XCTAssertFalse(noTorch.0.commands.contains { if case .torch = $0 { return true } else { return false } })

    let (camera, _, controls) = rig(settings: .init(torch: .on))
    XCTAssertEqual(camera.commands.last, .torch(true))
    controls.capturePending = { true }
    controls.update(.init(torch: .off))
    XCTAssertEqual(camera.commands.last, .torch(true), "the light does not change during a capture")
    controls.capturePending = { false }
    controls.afterCapture()
    XCTAssertEqual(camera.commands.last, .torch(false))
    XCTAssertEqual(controls.torchApplied, false)
  }

  func testAutoTorchLatchesAfterSustainedDarkFramesUntilTheModeChanges() {
    let (camera, _, controls) = rig(settings: .init(torch: .auto))
    XCTAssertEqual(camera.commands.last, .torch(false))
    let torches = { camera.commands.filter { if case .torch = $0 { return true } else { return false } } }
    // An unreported exposure is not evidence; estimated device readings count and say so.
    controls.frameObserved(CameraFrameMetadata(hostNs: 0, exposureNs: nil, iso: nil, estimated: false))
    controls.frameObserved(CameraFrameMetadata(hostNs: 0, exposureNs: 66_000_000, iso: 1600, estimated: true))
    controls.frameObserved(CameraFrameMetadata(hostNs: 999_999_999, exposureNs: 66_000_000, iso: 1600, estimated: false))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(torches(), [.torch(false)])
    controls.frameObserved(CameraFrameMetadata(hostNs: 1_000_000_000, exposureNs: 66_000_000, iso: 1600, estimated: false))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(torches(), [.torch(false), .torch(true)])
    // Lit, the frames are bright, but the torch stays: they show the torch, not the room.
    controls.frameObserved(CameraFrameMetadata(hostNs: 1_100_000_000, exposureNs: 1_000_000, iso: 100, estimated: false))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(torches().last, .torch(true))
    controls.update(.init(torch: .off))
    controls.update(.init(torch: .auto))
    XCTAssertEqual(torches(), [.torch(false), .torch(true), .torch(false)], "a mode change resets the latch")
  }

  func testAnUnknownExposureRestartsTheDarknessWait() {
    let (camera, _, controls) = rig(settings: .init(torch: .auto))
    let dark = { (ns: Int64) in CameraFrameMetadata(hostNs: ns, exposureNs: 66_000_000, iso: 1600, estimated: false) }
    controls.frameObserved(dark(0))
    controls.frameObserved(CameraFrameMetadata(hostNs: 500_000_000, exposureNs: nil, iso: nil, estimated: false))
    controls.frameObserved(dark(600_000_000))
    controls.frameObserved(dark(1_000_000_000))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(camera.commands.last, .torch(false), "darkness restarted at 0.6 s, not 0")
    controls.frameObserved(dark(1_600_000_000))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(camera.commands.last, .torch(true))
  }

  func testInterruptionRestartsPartialTorchEvidenceAndReappliesAnEstablishedDecision() {
    let (camera, _, controls) = rig(settings: .init(torch: .auto))
    let dark = { (ns: Int64) in CameraFrameMetadata(hostNs: ns, exposureNs: 66_000_000, iso: 1600, estimated: false) }
    let torches = { camera.commands.filter { if case .torch = $0 { return true } else { return false } } }
    controls.frameObserved(dark(0))
    controls.frameObserved(dark(500_000_000))
    controls.unbind(preservingTorchDecision: true)
    controls.bind(full)
    controls.frameObserved(dark(60_000_000_000))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(torches(), [.torch(false), .torch(false)])
    controls.frameObserved(dark(61_000_000_000))
    // Interrupt before the queued light decision reaches main.
    controls.unbind(preservingTorchDecision: true)
    controls.bind(full)
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(torches(), [.torch(false), .torch(false), .torch(true)])
  }

  func testInterruptedAcknowledgementsCannotSettleAReplacementBinding() {
    let (camera, timers, controls) = rig(nil, settings: .init(torch: .on))
    camera.held = []
    controls.bind(full)
    controls.tap(at: CGPoint(x: 0.2, y: 0.3))
    let old = camera.held ?? []
    controls.capturePending = { true }
    controls.update(.init(exposureBias: 1, torch: .off))
    controls.unbind(preservingTorchDecision: true)
    controls.afterCapture()
    controls.capturePending = { false }
    camera.commands = []
    camera.held = []
    controls.bind(full)
    old.forEach { $0(.accepted) }
    timers.advance(by: 60_000_000_000)
    XCTAssertTrue(controls.lightPending)
    XCTAssertNil(controls.torchApplied)
    XCTAssertNil(controls.snapshot.owner)
    XCTAssertEqual(camera.commands.filter { $0 == .torch(false) }.count, 1)
    XCTAssertEqual(camera.commands.filter { $0 == .exposureBias(1) }.count, 1)
    camera.held?.forEach { $0(.accepted) }
    XCTAssertFalse(controls.lightPending)
    XCTAssertEqual(controls.torchApplied, false)
  }

  func testAFrameOfAStoppedRunNeverReachesTheNextBinding() {
    let (camera, _, controls) = rig(settings: .init(torch: .auto))
    let dark = { (ns: Int64) in CameraFrameMetadata(hostNs: ns, exposureNs: 66_000_000, iso: 1600, estimated: false) }
    controls.frameObserved(dark(0))
    controls.unbind()
    // A frame that passed the camera's armed check before the stop arrives afterwards.
    controls.frameObserved(dark(500_000_000))
    controls.bind(full)
    controls.frameObserved(dark(60_000_000_000))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(camera.commands.last, .torch(false), "the new run starts its own second of darkness")
    controls.frameObserved(dark(61_000_000_000))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(camera.commands.last, .torch(true))
  }

  func testExposureBiasAndFrameRateApplyWithinTheDeviceRange() {
    let (camera, _, controls) = rig(settings: .init(exposureBias: 5, minFrameRate: 15))
    XCTAssertTrue(camera.commands.contains(.exposureBias(2)), "bias clamps to the device's range")
    XCTAssertTrue(camera.commands.contains(.frameRate(FrameRateRange(min: 15, max: 30))))
    controls.update(.init(exposureBias: -1, minFrameRate: 0))
    XCTAssertEqual(Array(camera.commands.suffix(2)), [.exposureBias(-1), .frameRate(nil)])
    let plain = rig(CameraCapabilities(continuousFocus: true), settings: .init(exposureBias: 1, minFrameRate: 15))
    XCTAssertEqual(plain.0.commands, [.continuousFocus(CameraControls.centre)])
  }

  // MARK: - Camera view

  @MainActor
  func testViewSendsPreviewTapsAndReportsTheDeviceFocusState() {
    let camera = ControlCamera()
    let notifications = NotificationCenter()
    final class Granted: CameraAuthorization {
      func authorizationStatus() -> AVAuthorizationStatus { .authorized }
      func requestFailure() -> ScannerErrorCode? { nil }
      func requestAccess(completion: @escaping @Sendable () -> Void) { completion() }
    }
    let view = HybridDocumentScannerCameraView(
      camera: camera, permissions: CameraPermissions(authorization: Granted(), notifications: notifications),
      notifications: notifications, inBackground: { false }, models: { _, done in done(nil) },
      detector: LiveDocumentDetector(inference: CornerInference()))
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    view.view.frame = window.bounds
    view.cornerDetectionModel = "model.tflite"
    view.torch = .on
    var focus: [AutofocusState] = []
    view.onAutofocusChange = { focus.append($0) }
    window.addSubview(view.view)
    window.layoutIfNeeded()
    view.afterUpdate()
    XCTAssertEqual(camera.starts, 1)
    camera.onEvent?(.configured(CameraConfiguration(photo: PixelSize(4032, 3024), video: PixelSize(640, 480),
                                                    capabilities: full)))
    XCTAssertFalse(camera.commands.contains(.torch(true)), "controls wait for the first frame")
    let preview = view.view as! CameraPreviewView
    preview.onTap?(CGPoint(x: 0.25, y: 0.75))
    XCTAssertFalse(camera.commands.contains { if case .scanFocus = $0 { return true } else { return false } },
                   "taps wait for the first frame")
    camera.onEvent?(.streaming(PixelSize(640, 480)))
    XCTAssertTrue(camera.commands.contains(.torch(true)), "the torch prop applies without a restart")
    preview.onTap?(CGPoint(x: 0.25, y: 0.75))
    XCTAssertEqual(camera.commands.last, .scanFocus(CGPoint(x: 0.25, y: 0.75), meter: CGPoint(x: 0.25, y: 0.75)))
    camera.focusEvents?(FocusObservation(adjusting: true, locked: false))
    view.active = false
    view.afterUpdate()
    XCTAssertEqual(focus, [.inactive, .scan, .inactive], "iOS reports only what AVFoundation observes")
  }
}
