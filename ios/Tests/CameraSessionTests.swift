import AVFoundation
import NitroModules
import UIKit
import XCTest
@testable import ReactNativeDocumentScanner

private final class GrantedAuthorization: CameraAuthorization {
  var current: AVAuthorizationStatus = .authorized
  func authorizationStatus() -> AVAuthorizationStatus { current }
  func requestFailure() -> ScannerErrorCode? { nil }
  func requestAccess(completion: @escaping @Sendable () -> Void) { completion() }
}

/// Records the view's camera calls; tests deliver the events a real session would.
private final class FakeCamera: CameraSessionControl {
  var onEvent: ((CameraSessionEvent) -> Void)?
  var frameSink: ((CVPixelBuffer, Int64) -> Void)?
  var device: AVCaptureDevice? { nil }
  var starts: [CameraRequest] = []
  var stops = 0
  var running: Bool { starts.count > stops }
  func attachPreview(_ layer: AVCaptureVideoPreviewLayer) {}
  func start(_ request: CameraRequest) { starts.append(request) }
  func stop() { stops += 1 }
  func emit(_ event: CameraSessionEvent) { onEvent?(event) }
}

private func sizes(_ pairs: [(Int, Int)]) -> [PixelSize] { pairs.map { PixelSize($0.0, $0.1) } }

final class CameraSessionTests: XCTestCase {
  // MARK: - Still dimensions

  func testStillSelectionPrefersLargestBounded4By3AndExcludes24And48MP() {
    // 12, 24 and 48 MP 4:3 modes as a 48 MP sensor advertises them.
    let advertised = sizes([(4032, 3024), (5712, 4284), (8064, 6048)])
    XCTAssertEqual(CameraStillPolicy.select(advertised), PixelSize(4032, 3024))
    // The ceiling itself is valid; one more column is not.
    XCTAssertEqual(CameraStillPolicy.select(sizes([(4096, 3072), (4032, 3024)])), PixelSize(4096, 3072))
    XCTAssertFalse(CameraStillPolicy.isBounded(PixelSize(4097, 3072)))
    // Both bounds apply: a long side over 4096 or more than 12,582,912 pixels.
    XCTAssertFalse(CameraStillPolicy.isBounded(PixelSize(4200, 1000)))
    XCTAssertFalse(CameraStillPolicy.isBounded(PixelSize(4096, 3100)))
    XCTAssertFalse(CameraStillPolicy.isBounded(PixelSize(0, 3024)))
    // A smaller 4:3 size beats a larger size of another aspect.
    XCTAssertEqual(CameraStillPolicy.select(sizes([(4032, 2268), (2048, 1536)])), PixelSize(2048, 1536))
  }

  func testStillSelectionFallsBackToLargestOtherAspectThenNothing() {
    XCTAssertEqual(CameraStillPolicy.select(sizes([(3840, 2160), (4032, 2268), (5712, 3213), (8064, 4536)])),
                   PixelSize(4032, 2268))
    XCTAssertNil(CameraStillPolicy.select(sizes([(5712, 4284), (8064, 6048)])))
    XCTAssertNil(CameraStillPolicy.select([]))
  }

  func testStillSelectionIgnoresPortraitAxes() {
    let portrait = sizes([(3024, 4032), (4284, 5712), (6048, 8064)])
    XCTAssertEqual(CameraStillPolicy.select(portrait), PixelSize(3024, 4032))
    XCTAssertTrue(PixelSize(3024, 4032).isFourByThree)
  }

  func testDeliveredPhotoMayBeSmallerButNeverLarger() {
    let requested = PixelSize(4032, 3024)
    XCTAssertTrue(CameraStillPolicy.accepts(requested, requested: requested))
    XCTAssertTrue(CameraStillPolicy.accepts(PixelSize(3024, 4032), requested: requested))
    XCTAssertTrue(CameraStillPolicy.accepts(PixelSize(1920, 1440), requested: requested))
    XCTAssertTrue(CameraStillPolicy.accepts(PixelSize(1440, 1920), requested: requested))
    XCTAssertFalse(CameraStillPolicy.accepts(PixelSize(4284, 5712), requested: requested))
    XCTAssertFalse(CameraStillPolicy.accepts(PixelSize(4096, 3072), requested: requested))
    XCTAssertFalse(CameraStillPolicy.accepts(PixelSize(0, 0), requested: requested))
  }

  func testFormatChoicePutsTheStillFirstThenA4By3VideoNearestTheAnalysisWidth() {
    let formats = [
      CameraFormatOption(video: PixelSize(640, 480), photos: sizes([(640, 480)])),
      CameraFormatOption(video: PixelSize(1920, 1080), photos: sizes([(4032, 3024), (4032, 2268)])),
      CameraFormatOption(video: PixelSize(1920, 1440), photos: sizes([(4032, 3024), (8064, 6048)])),
      CameraFormatOption(video: PixelSize(1280, 960), photos: sizes([(4032, 3024)])),
      CameraFormatOption(video: PixelSize(4032, 3024), photos: sizes([(4032, 3024), (8064, 6048)])),
    ]
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisWidth: 640),
                   CameraFormatChoice(index: 3, photo: PixelSize(4032, 3024), video: PixelSize(1280, 960)))
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisWidth: 1600)?.index, 2)
    // Nothing reaches the width: the closest below it.
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisWidth: 5000)?.index, 4)
    // Only 24/48 MP stills: no bounded configuration.
    XCTAssertNil(CameraFormatPolicy.choose([
      CameraFormatOption(video: PixelSize(1920, 1440), photos: sizes([(5712, 4284), (8064, 6048)])),
    ], analysisWidth: 640))
    XCTAssertEqual(CameraFormatPolicy.analysisWidth(nil), 640)
    XCTAssertEqual(CameraFormatPolicy.analysisWidth(10), 160)
    XCTAssertEqual(CameraFormatPolicy.analysisWidth(5000), 1920)
    XCTAssertEqual(CameraFormatPolicy.analysisWidth(.nan), 640)
  }

  // MARK: - Framing

  func testFramingTurnsBuffersUprightAndRoundTrips() throws {
    let buffer = PixelSize(1920, 1440)
    for (angle, orientation, upright) in [(0.0, 1, (1920, 1440)), (90, 6, (1440, 1920)), (180, 3, (1920, 1440)),
                                          (270, 8, (1440, 1920)), (-90, 8, (1440, 1920)), (450, 6, (1440, 1920))] {
      let framing = try XCTUnwrap(CameraFraming(buffer: buffer, rotationAngle: angle, square: false))
      XCTAssertEqual(framing.orientation, UInt8(orientation), "\(angle)")
      XCTAssertEqual(framing.upright, PixelSize(upright.0, upright.1), "\(angle)")
      XCTAssertEqual(framing.crop, CGRect(x: 0, y: 0, width: 1920, height: 1440))
      for point in [CGPoint(x: 0, y: 0), CGPoint(x: 0.25, y: 0.75), CGPoint(x: 1, y: 0.5)] {
        let back = framing.toBuffer(framing.toFramed(point))
        XCTAssertEqual(back.x, point.x, accuracy: 1e-9); XCTAssertEqual(back.y, point.y, accuracy: 1e-9)
      }
    }
    // Portrait: the buffer's top-left corner becomes the upright top-right.
    let portrait = try XCTUnwrap(CameraFraming(buffer: buffer, rotationAngle: 90, square: false))
    XCTAssertEqual(portrait.toFramed(CGPoint(x: 0, y: 0)), CGPoint(x: 1, y: 0))
    XCTAssertEqual(portrait.toFramed(CGPoint(x: 1, y: 0)), CGPoint(x: 1, y: 1))
    let upsideDown = try XCTUnwrap(CameraFraming(buffer: buffer, rotationAngle: 180, square: false))
    XCTAssertEqual(upsideDown.toFramed(CGPoint(x: 0, y: 0)), CGPoint(x: 1, y: 1))
    XCTAssertNil(CameraFraming(buffer: PixelSize(0, 1440), rotationAngle: 0, square: false))
    XCTAssertNil(CameraFraming(buffer: buffer, rotationAngle: .infinity, square: false))
  }

  func testVirtualSquareCropsTheCenteredSquareIndependentlyOfRotation() throws {
    let buffer = PixelSize(1920, 1440)
    for angle in [0.0, 90, 180, 270] {
      let framing = try XCTUnwrap(CameraFraming(buffer: buffer, rotationAngle: angle, square: true))
      XCTAssertEqual(framing.crop, CGRect(x: 240, y: 0, width: 1440, height: 1440), "\(angle)")
      XCTAssertEqual(framing.framed, PixelSize(1440, 1440))
      // The crop's buffer corners are the framed image's corners.
      let corners = [CGPoint(x: 240.0 / 1920, y: 0), CGPoint(x: 1680.0 / 1920, y: 1)].map(framing.toFramed)
      for corner in corners {
        for value in [corner.x, corner.y] {
          XCTAssertTrue(abs(value) < 1e-9 || abs(value - 1) < 1e-9, "\(angle) \(corner)")
        }
      }
      let center = framing.toFramed(CGPoint(x: 0.5, y: 0.5))
      XCTAssertEqual(center.x, 0.5, accuracy: 1e-9); XCTAssertEqual(center.y, 0.5, accuracy: 1e-9)
    }
    // An odd margin rounds down so the crop stays integral.
    let odd = try XCTUnwrap(CameraFraming(buffer: PixelSize(641, 480), rotationAngle: 0, square: true))
    XCTAssertEqual(odd.crop, CGRect(x: 80, y: 0, width: 480, height: 480))
  }

  func testPresentationFitsTheFramedImage() throws {
    let bounds = CGRect(x: 0, y: 0, width: 390, height: 844)
    let portrait = try XCTUnwrap(CameraFraming(buffer: PixelSize(1920, 1440), rotationAngle: 90, square: false))
    XCTAssertEqual(portrait.presentation(in: bounds), CGRect(x: 0, y: 162, width: 390, height: 520))
    let square = try XCTUnwrap(CameraFraming(buffer: PixelSize(1920, 1440), rotationAngle: 90, square: true))
    XCTAssertEqual(square.presentation(in: bounds), CGRect(x: 0, y: 227, width: 390, height: 390))
    let landscape = try XCTUnwrap(CameraFraming(buffer: PixelSize(1920, 1440), rotationAngle: 0, square: false))
    XCTAssertEqual(landscape.presentation(in: CGRect(x: 0, y: 0, width: 844, height: 390)),
                   CGRect(x: 162, y: 0, width: 520, height: 390))
    XCTAssertEqual(portrait.presentation(in: .zero), .zero)
  }

  // MARK: - Lifecycle

  @MainActor
  private final class Harness {
    let fake = FakeCamera()
    let authorization = GrantedAuthorization()
    let notifications = NotificationCenter()
    var background = false
    let view: HybridDocumentScannerCameraView
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    var states: [CameraState] = []
    var errors: [ScannerErrorCode] = []

    init() {
      let permissions = CameraPermissions(authorization: authorization, notifications: notifications)
      weak var harness: Harness?
      view = HybridDocumentScannerCameraView(camera: fake, permissions: permissions, notifications: notifications,
                                             inBackground: { harness?.background ?? false },
                                             models: { _, done in done(nil) },
                                             detector: LiveDocumentDetector(inference: CornerInference()))
      harness = self
      view.quadTensorModel = "model.tflite"
      view.view.frame = window.bounds
      view.onCameraStateChange = { [unowned self] in states.append($0) }
      view.onError = { [unowned self] in errors.append($0.code) }
    }

    func attach() {
      window.addSubview(view.view)
      window.layoutIfNeeded()
    }

    func detach() { view.view.removeFromSuperview() }

    func post(_ name: Notification.Name) { notifications.post(name: name, object: nil) }
  }

  @MainActor
  func testCameraFollowsModeAttachmentAndForegroundWithoutUnmounting() {
    let h = Harness()
    h.view.afterUpdate()
    XCTAssertEqual(h.states, [.suspended])
    XCTAssertTrue(h.fake.starts.isEmpty, "a detached view never starts")
    h.attach()
    XCTAssertEqual(h.fake.starts, [CameraRequest(analysisWidth: 640)])
    XCTAssertEqual(h.states, [.suspended, .starting])
    h.fake.emit(.configured(CameraConfiguration(photo: PixelSize(4032, 3024), video: PixelSize(1920, 1440))))
    h.view.setRotation(preview: 90, capture: 90)
    XCTAssertEqual(h.states.last, .starting, "ready waits for the first frame")
    XCTAssertNil(h.view.analysisFraming, "framing waits for a delivered buffer")
    // AVFoundation may deliver buffers smaller than the active format.
    h.fake.emit(.streaming(PixelSize(1280, 960)))
    XCTAssertEqual(h.states.last, .ready)
    let preview = h.view.view as! CameraPreviewView
    XCTAssertFalse(preview.previewLayer.isHidden)
    XCTAssertEqual(preview.previewLayer.frame, CGRect(x: 0, y: 162, width: 390, height: 520))
    XCTAssertEqual(h.view.analysisFraming?.orientation, 6)
    XCTAssertEqual(h.view.analysisFraming?.buffer, PixelSize(1280, 960))
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 0, y: 0, width: 1280, height: 960))
    h.view.squareFrame = true
    h.view.afterUpdate()
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 160, y: 0, width: 960, height: 960))
    h.fake.emit(.frameSize(PixelSize(640, 480)))
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 80, y: 0, width: 480, height: 480))
    h.view.squareFrame = false
    h.view.afterUpdate()

    h.view.mode = .off
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.stops, 1)
    XCTAssertEqual(h.states.last, .suspended)
    XCTAssertTrue(h.view.view.window === h.window, "mode off keeps the view mounted")
    XCTAssertTrue(preview.previewLayer.isHidden)
    h.fake.emit(.streaming(PixelSize(1920, 1440)))
    XCTAssertEqual(h.states.last, .suspended, "a stopped camera's late events are ignored")

    h.view.mode = .scanner
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 2)
    h.post(UIApplication.didEnterBackgroundNotification)
    XCTAssertEqual(h.fake.stops, 2)
    XCTAssertEqual(h.states.last, .suspended)
    h.post(UIApplication.willEnterForegroundNotification)
    XCTAssertEqual(h.fake.starts.count, 3)
    h.fake.emit(.streaming(PixelSize(1920, 1440)))
    h.fake.emit(.interrupted)
    XCTAssertEqual(h.states.suffix(3), [.starting, .ready, .starting])
    h.detach()
    XCTAssertEqual(h.fake.stops, 3)
    XCTAssertFalse(h.fake.running)
    XCTAssertEqual(h.states.last, .suspended)
    h.post(UIApplication.willEnterForegroundNotification)
    XCTAssertEqual(h.fake.starts.count, 3, "a detached view stops observing the app")
    h.attach()
    XCTAssertEqual(h.fake.starts.count, 4)
    h.view.onDropView()
    XCTAssertFalse(h.fake.running)
    h.view.afterUpdate()
    h.window.layoutIfNeeded()
    XCTAssertEqual(h.fake.starts.count, 4, "an unmounted view never restarts")
    XCTAssertTrue(h.errors.isEmpty)
  }

  @MainActor
  func testPermissionSizeAndRequestChanges() {
    let h = Harness()
    h.authorization.current = .denied
    h.view.view.frame = .zero
    h.attach()
    h.view.afterUpdate()
    XCTAssertTrue(h.fake.starts.isEmpty, "no permission")
    h.authorization.current = .authorized
    h.post(UIApplication.didBecomeActiveNotification)
    XCTAssertTrue(h.fake.starts.isEmpty, "an unsized view waits for layout")
    h.view.view.frame = h.window.bounds
    h.view.view.layoutIfNeeded()
    XCTAssertEqual(h.fake.starts.count, 1)

    // The square is virtual: it changes framing, not the camera.
    h.fake.emit(.configured(CameraConfiguration(photo: PixelSize(4032, 3024), video: PixelSize(1920, 1440))))
    h.view.setRotation(preview: 90, capture: 90)
    h.fake.emit(.streaming(PixelSize(1920, 1440)))
    h.view.squareFrame = true
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 1)
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 240, y: 0, width: 1440, height: 1440))
    XCTAssertEqual((h.view.view as! CameraPreviewView).previewLayer.frame,
                   CGRect(x: 0, y: 227, width: 390, height: 390))

    h.view.analysisWidth = 1280
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.last, CameraRequest(analysisWidth: 1280))
    XCTAssertEqual(h.fake.stops, 1)
    XCTAssertTrue(h.fake.running)

    h.authorization.current = .denied
    h.post(UIApplication.didBecomeActiveNotification)
    XCTAssertFalse(h.fake.running, "revoked permission stops the camera")
    XCTAssertEqual(h.states.last, .suspended)
  }

  @MainActor
  func testFailureStaysSuspendedUntilALifecycleChange() {
    let h = Harness()
    h.attach()
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 1)
    // The session stops itself before it reports a failure.
    h.fake.stops += 1
    h.fake.emit(.failed(.cameraUnavailable))
    XCTAssertEqual(h.errors, [.cameraUnavailable])
    XCTAssertEqual(h.states.last, .suspended)
    h.view.afterUpdate()
    h.view.view.frame = CGRect(x: 0, y: 0, width: 300, height: 400)
    h.view.view.layoutIfNeeded()
    XCTAssertEqual(h.fake.starts.count, 1, "no retry loop")
    h.view.mode = .off
    h.view.afterUpdate()
    h.view.mode = .camera
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 2, "a mode change retries")
  }

  @MainActor
  func testCaptureRejectsAndTheBorrowedSessionOutlivesTheCamera() throws {
    let h = Harness()
    XCTAssertEqual(rejection(h.view), .captureNotReady, "no native session")
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    h.view.session = session
    h.view.afterUpdate()
    let bound = h.view.sessionGeneration
    XCTAssertEqual(rejection(h.view), .cameraUnavailable, "detached")
    h.authorization.current = .denied
    h.attach()
    XCTAssertEqual(rejection(h.view), .cameraPermissionDenied)
    h.authorization.current = .authorized
    h.post(UIApplication.didBecomeActiveNotification)
    XCTAssertEqual(rejection(h.view), .captureNotReady, "starting")
    h.fake.emit(.streaming(PixelSize(1920, 1440)))
    XCTAssertEqual(rejection(h.view), .featureUnsupported, "the shutter is not implemented")
    h.fake.stops += 1
    h.fake.emit(.failed(.cameraUnavailable))
    XCTAssertEqual(rejection(h.view), .cameraUnavailable, "no bounded configuration")

    // Stopping, detaching and unmounting the camera never close its session.
    h.view.mode = .off
    h.view.afterUpdate()
    XCTAssertEqual(rejection(h.view), .cameraUnavailable)
    h.detach()
    XCTAssertFalse(state.snapshot().closed)

    // Closing the borrowed session invalidates publication.
    let done = expectation(description: "closed")
    state.close().whenComplete { _ in done.fulfill() }
    wait(for: [done], timeout: 10)
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertGreaterThan(h.view.sessionGeneration, bound)
    XCTAssertEqual(rejection(h.view), .processorDisposed)

    let replaced = h.view.sessionGeneration
    h.view.session = HybridScanSession.placeholder()
    h.view.afterUpdate()
    XCTAssertGreaterThan(h.view.sessionGeneration, replaced)
    h.view.onDropView()
    XCTAssertEqual(rejection(h.view), .cameraUnavailable)
  }

  @MainActor
  func testCallbacksHearTheCurrentStateWhenSet() {
    let h = Harness()
    h.attach()
    h.view.afterUpdate()
    var late: [CameraState] = []
    var capture: [CameraCaptureState] = []
    var document: [DocumentState] = []
    var focus: [AutofocusState] = []
    h.view.onCameraStateChange = { late.append($0) }
    h.view.onCaptureStateChange = { capture.append($0) }
    h.view.onDocumentStateChange = { document.append($0) }
    h.view.onAutofocusChange = { focus.append($0) }
    h.view.afterUpdate()
    h.view.afterUpdate()
    XCTAssertEqual(late, [.starting])
    XCTAssertEqual(capture, [.unavailable])
    XCTAssertEqual(document, [.searching], "a started camera searches for a page")
    XCTAssertEqual(focus, [.inactive])
  }

  /// The simulator has no camera: a real session reports it unavailable and stays stopped.
  @MainActor
  func testSessionWithoutACameraFailsAsUnavailable() throws {
    let camera = AVCameraSession(discovery: { nil })
    var events: [CameraSessionEvent] = []
    let failed = expectation(description: "failed")
    camera.onEvent = {
      events.append($0)
      if case .failed = $0 { failed.fulfill() }
    }
    camera.start(CameraRequest(analysisWidth: 640))
    wait(for: [failed], timeout: 10)
    XCTAssertEqual(events, [.failed(.cameraUnavailable)])
    XCTAssertNil(camera.device)
  }

  /// Focus is sampled on the session queue, so main receives each sample in order with command
  /// completions, whatever thread AVFoundation notified on: a sample requested after a command
  /// reaches main after that command's completion.
  @MainActor
  func testFocusSamplesReachMainInQueueOrderWithCommandCompletions() {
    let camera = AVCameraSession(discovery: { nil })
    var order: [String] = []
    camera.focusEvents = { order.append($0.locked ? "locked" : "moving") }
    let done = expectation(description: "delivered")
    let notified = DispatchGroup()
    notified.enter()
    DispatchQueue.global().async {
      camera.reportFocus(run: 0) { FocusObservation(adjusting: true, locked: false) }
      notified.leave()
    }
    notified.wait()
    camera.perform(.torch(true)) { applied in
      XCTAssertFalse(applied, "a stopped camera applies nothing")
      order.append("completion")
    }
    camera.reportFocus(run: 0) {
      XCTAssertFalse(Thread.isMainThread, "sampled on the session queue")
      return FocusObservation(adjusting: false, locked: true)
    }
    camera.perform(.torch(false)) { _ in
      order.append("completion")
      done.fulfill()
    }
    wait(for: [done], timeout: 5)
    XCTAssertEqual(order, ["moving", "completion", "locked", "completion"])
  }

  func testFrameMonitorReportsOnlyTheArmedRunAndItsDeliveredSizes() {
    let frames = CameraFrameMonitor()
    XCTAssertNil(frames.frame(PixelSize(1920, 1440)), "nothing is armed before a run starts")
    frames.arm(1)
    XCTAssertEqual(frames.frame(PixelSize(1920, 1440)), .first(1, PixelSize(1920, 1440)))
    XCTAssertNil(frames.frame(PixelSize(1920, 1440)))
    XCTAssertEqual(frames.frame(PixelSize(1280, 960)), .resized(1, PixelSize(1280, 960)))
    frames.rearm(1)
    XCTAssertEqual(frames.frame(PixelSize(1280, 960)), .first(1, PixelSize(1280, 960)), "after an interruption")
    frames.rearm(2)
    XCTAssertNil(frames.frame(PixelSize(1280, 960)), "a stale interruption arms nothing")
    // Restart: the stop disarms, so a late frame of run 1 reports nothing until run 2 is armed.
    frames.disarm()
    XCTAssertNil(frames.frame(PixelSize(1920, 1440)))
    frames.arm(2)
    XCTAssertEqual(frames.frame(PixelSize(640, 480)), .first(2, PixelSize(640, 480)))
    XCTAssertEqual(frames.takeCounts().delivered, 8)
    XCTAssertEqual(frames.takeCounts().delivered, 0)
  }

  /// A frame arriving while a start waits on the camera queue cannot mark that start ready:
  /// the start arms frame reporting only on the queue, after the earlier run has drained.
  @MainActor
  func testAFrameBeforeTheQueueArmsTheStartIsIgnored() {
    let gate = DispatchSemaphore(value: 0)
    let camera = AVCameraSession(discovery: { gate.wait(); return nil })
    var events: [CameraSessionEvent] = []
    let failed = expectation(description: "failed")
    camera.onEvent = {
      events.append($0)
      if case .failed = $0 { failed.fulfill() }
    }
    camera.start(CameraRequest(analysisWidth: 640))
    camera.frameArrived(PixelSize(1920, 1440))
    RunLoop.main.run(until: Date().addingTimeInterval(0.1))
    XCTAssertEqual(events, [], "the late frame published nothing")
    gate.signal()
    wait(for: [failed], timeout: 10)
    XCTAssertEqual(events, [.failed(.cameraUnavailable)])
  }

  @MainActor
  private func rejection(_ view: HybridDocumentScannerCameraView) -> ScannerErrorCode? {
    let done = expectation(description: "takePhoto")
    var code: ScannerErrorCode?
    _ = try? view.takePhotoResult(options: nil).then {
      code = $0.asType(ScannerFailure.self)?.code
      done.fulfill()
    }
    wait(for: [done], timeout: 10)
    return code
  }
}
