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
  var focusEvents: ((FocusObservation) -> Void)?
  var focusSample: FocusObservation?
  var device: AVCaptureDevice? { nil }
  var starts: [CameraRequest] = []
  var stops = 0
  var running: Bool { starts.count > stops }
  func attachPreview(_ layer: AVCaptureVideoPreviewLayer) {}
  func start(_ request: CameraRequest) { starts.append(request) }
  func stop() { stops += 1 }
  func requestFocusObservation() {
    if let focusSample { focusEvents?(focusSample) }
  }
  func emit(_ event: CameraSessionEvent) { onEvent?(event) }
  var stills: [(settings: StillSettings, events: @Sendable (StillEvent) -> Void)] = []
  func capture(_ settings: StillSettings, events: @escaping @Sendable (StillEvent) -> Void) { stills.append((settings, events)) }
  /// Control commands and their completions, which run only when a test calls them.
  var commands: [CameraCommand] = []
  var completions: [(CommandOutcome) -> Void] = []
  func perform(_ command: CameraCommand, completion: @escaping (CommandOutcome) -> Void) {
    commands.append(command)
    completions.append(completion)
  }
}

/// Session actions that record each admitted page's live corners and fail its analysis at once.
private final class RecordedCornerSets: ScanSessionActions, @unchecked Sendable {
  private let lock = NSLock()
  private var cornerSets: [CornerCoordinates?] = []
  var liveCornerSets: [CornerCoordinates?] { lock.lock(); defer { lock.unlock() }; return cornerSets }

  func prepareAnalysis(_ source: SessionResources.Lease, liveCorners: CornerCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue {
    lock.lock(); cornerSets.append(liveCorners); lock.unlock()
    return { throw ScannerCodeError(code: .analysisFailed) }
  }

  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact {
    throw ScannerCodeError(code: .renderFailed)
  }

  func close() {}
}

private func sizes(_ pairs: [(Int, Int)]) -> [PixelSize] { pairs.map { PixelSize($0.0, $0.1) } }

final class CameraSessionTests: XCTestCase {
  // MARK: - Still dimensions

  func testStillSelectionRanksForTextLikeAndroidAndExcludes24And48MP() {
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
    // Both platforms prefer 4:3 even when a wider photo has a larger short side.
    XCTAssertEqual(CameraStillPolicy.select(sizes([(4032, 2268), (2048, 1536)])), PixelSize(2048, 1536))
    // At equal short sides, the aspect closest to 4:3 wins; square sizes follow wider ones.
    XCTAssertEqual(CameraStillPolicy.select(sizes([(1920, 1080), (1440, 1080)])), PixelSize(1440, 1080))
    XCTAssertEqual(CameraStillPolicy.select(sizes([(3000, 3000), (1600, 1200)])), PixelSize(1600, 1200))
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

  func testStandardStillMatchesTheSharedPlatformVectors() throws {
    let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "standard-resolution", withExtension: "tsv"))
    let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
      .filter { !$0.isEmpty && !$0.hasPrefix("#") }.dropFirst()
    func parse(_ list: String) -> [PixelSize] {
      list.split(separator: ",").map { item in
        let side = item.split(separator: "x").map { Int($0)! }
        return PixelSize(side[0], side[1])
      }
    }
    for line in rows {
      let v = line.split(separator: "\t").map(String.init)
      XCTAssertEqual(v.count, 3, line)
      XCTAssertEqual(CameraStillPolicy.selectStandard(parse(v[1])), parse(v[2]).first, v[0])
    }
    XCTAssertEqual(rows.count, 12)
  }

  func testStandardStillIsTheSmallest4By3WithA1080ShortSide() {
    XCTAssertEqual(CameraStillPolicy.selectStandard(sizes([(4032, 3024), (1920, 1440), (1440, 1080), (1920, 1080)])),
                   PixelSize(1440, 1080))
    XCTAssertEqual(CameraStillPolicy.selectStandard(sizes([(4032, 3024), (1920, 1440), (1920, 1080)])),
                   PixelSize(1920, 1440))
    // Nothing reaches 1080: the widest smaller 4:3; no 4:3 at all: the other aspects by the same rule.
    XCTAssertEqual(CameraStillPolicy.selectStandard(sizes([(1280, 960), (640, 480)])), PixelSize(1280, 960))
    XCTAssertEqual(CameraStillPolicy.selectStandard(sizes([(3840, 2160), (1920, 1080)])), PixelSize(1920, 1080))
    XCTAssertNil(CameraStillPolicy.selectStandard(sizes([(8064, 6048)])))
    let formats = [
      CameraFormatOption(video: PixelSize(1920, 1440), photos: sizes([(4032, 3024)])),
      CameraFormatOption(video: PixelSize(1440, 1080), photos: sizes([(1440, 1080), (4032, 3024)])),
    ]
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisFrameWidth: 640, highResolution: false),
                   CameraFormatChoice(index: 1, photo: PixelSize(1440, 1080), video: PixelSize(1440, 1080)))
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisFrameWidth: 640)?.photo, PixelSize(4032, 3024))
  }

  func testFormatChoicePutsTheStillFirstThenA4By3VideoNearestTheAnalysisWidth() {
    let formats = [
      CameraFormatOption(video: PixelSize(640, 480), photos: sizes([(640, 480)])),
      CameraFormatOption(video: PixelSize(1920, 1080), photos: sizes([(4032, 3024), (4032, 2268)])),
      CameraFormatOption(video: PixelSize(1920, 1440), photos: sizes([(4032, 3024), (8064, 6048)])),
      CameraFormatOption(video: PixelSize(1280, 960), photos: sizes([(4032, 3024)])),
      CameraFormatOption(video: PixelSize(4032, 3024), photos: sizes([(4032, 3024), (8064, 6048)])),
    ]
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisFrameWidth: 640),
                   CameraFormatChoice(index: 3, photo: PixelSize(4032, 3024), video: PixelSize(1280, 960)))
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisFrameWidth: 1600)?.index, 2)
    // Nothing reaches the width: the closest below it.
    XCTAssertEqual(CameraFormatPolicy.choose(formats, analysisFrameWidth: 5000)?.index, 4)
    // Only 24/48 MP stills: no bounded configuration.
    XCTAssertNil(CameraFormatPolicy.choose([
      CameraFormatOption(video: PixelSize(1920, 1440), photos: sizes([(5712, 4284), (8064, 6048)])),
    ], analysisFrameWidth: 640))
    XCTAssertEqual(CameraFormatPolicy.analysisFrameWidth(nil), 640)
    XCTAssertEqual(CameraFormatPolicy.analysisFrameWidth(10), 160)
    XCTAssertEqual(CameraFormatPolicy.analysisFrameWidth(5000), 1920)
    XCTAssertEqual(CameraFormatPolicy.analysisFrameWidth(.nan), 640)
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
    let detector = LiveDocumentDetector(inference: CornerInference())
    let controls: CameraControls
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    var states: [CameraState] = []
    var errors: [ScannerErrorCode] = []
    /// Added to the host clock the view judges captures by.
    var clockOffsetNs: Int64 = 0

    init(models: @escaping (String, @escaping (ScannerErrorCode?) -> Void) -> Void = { _, done in done(nil) }) {
      let permissions = CameraPermissions(authorization: authorization, notifications: notifications)
      weak var harness: Harness?
      controls = CameraControls(camera: fake)
      view = HybridDocumentScannerCameraView(camera: fake, permissions: permissions, notifications: notifications,
                                             inBackground: { harness?.background ?? false },
                                             models: models,
                                             detector: detector, controls: controls,
                                             clock: { hostClockNs() + (harness?.clockOffsetNs ?? 0) })
      harness = self
      view.cornerDetectionModel = "model.tflite"
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
    XCTAssertEqual(h.fake.starts, [CameraRequest(analysisFrameWidth: 640)])
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
    h.view.squareAspectRatio = true
    h.view.afterUpdate()
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 160, y: 0, width: 960, height: 960))
    h.fake.emit(.frameSize(PixelSize(640, 480)))
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 80, y: 0, width: 480, height: 480))
    h.view.squareAspectRatio = false
    h.view.afterUpdate()

    h.view.active = false
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.stops, 1)
    XCTAssertEqual(h.states.last, .suspended)
    XCTAssertTrue(h.view.view.window === h.window, "mode off keeps the view mounted")
    XCTAssertTrue(preview.previewLayer.isHidden)
    h.fake.emit(.streaming(PixelSize(1920, 1440)))
    XCTAssertEqual(h.states.last, .suspended, "a stopped camera's late events are ignored")

    h.view.active = true
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
    h.view.squareAspectRatio = true
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 1)
    XCTAssertEqual(h.view.analysisFraming?.crop, CGRect(x: 240, y: 0, width: 1440, height: 1440))
    XCTAssertEqual((h.view.view as! CameraPreviewView).previewLayer.frame,
                   CGRect(x: 0, y: 227, width: 390, height: 390))

    h.view.analysisFrameWidth = 1280
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.last, CameraRequest(analysisFrameWidth: 1280))
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
    h.view.active = false
    h.view.afterUpdate()
    h.view.active = true
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
    XCTAssertEqual(rejection(h.view), .cameraUnavailable, "detached")
    h.authorization.current = .denied
    h.attach()
    XCTAssertEqual(rejection(h.view), .cameraPermissionDenied)
    h.authorization.current = .authorized
    h.post(UIApplication.didBecomeActiveNotification)
    XCTAssertEqual(rejection(h.view), .captureNotReady, "starting")
    h.fake.emit(.streaming(PixelSize(1920, 1440)))
    let shot = photo(h.view)
    XCTAssertEqual(h.fake.stills.count, 1, "a ready camera fires")
    h.fake.stills[0].events(.failed(.captureFailed))
    XCTAssertEqual(outcome(shot), .second(.captureFailed))
    h.fake.stops += 1
    h.fake.emit(.failed(.cameraUnavailable))
    XCTAssertEqual(rejection(h.view), .cameraUnavailable, "no bounded configuration")

    // Stopping, detaching and unmounting the camera never close its session.
    h.view.active = false
    h.view.afterUpdate()
    XCTAssertEqual(rejection(h.view), .cameraUnavailable)
    h.detach()
    XCTAssertFalse(state.snapshot().closed)

    // Closing the borrowed session invalidates publication.
    let done = expectation(description: "closed")
    state.close().whenComplete { _ in done.fulfill() }
    wait(for: [done], timeout: 10)
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    XCTAssertEqual(rejection(h.view), .processorDisposed)

    h.view.session = HybridScanSession.placeholder()
    h.view.afterUpdate()
    XCTAssertEqual(rejection(h.view), .captureNotReady, "no native session")
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
    camera.start(CameraRequest(analysisFrameWidth: 640))
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
    camera.perform(.torch(true)) { outcome in
      XCTAssertFalse(outcome.applied, "a stopped camera applies nothing")
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
    // Interruption disarms. Only the resumed epoch can publish the next first frame.
    frames.disarm()
    XCTAssertNil(frames.frame(PixelSize(1920, 1440)))
    frames.arm(2)
    XCTAssertEqual(frames.frame(PixelSize(640, 480)), .first(2, PixelSize(640, 480)))
    XCTAssertNil(frames.frame(PixelSize(640, 480)))
    XCTAssertEqual(frames.takeCounts().delivered, 6)
    XCTAssertEqual(frames.takeCounts().delivered, 0)
  }

  /// A frame arriving while a start waits on the camera queue cannot mark that start ready:
  /// the start arms frame reporting only on the queue, after the earlier run has drained.
  @MainActor
  func testStartupConfigurationSurvivesAnInterruptionBeforeMainDelivery() {
    let gate = DispatchSemaphore(value: 0)
    defer { gate.signal() }
    let camera = AVCameraSession(discovery: { gate.wait(); return nil })
    let size = PixelSize(640, 480)
    let configuration = CameraConfiguration(photo: size, video: size)
    let delivered = expectation(description: "configuration delivered")
    var events: [CameraSessionEvent] = []
    camera.onEvent = {
      events.append($0)
      if $0 == .configured(configuration) { delivered.fulfill() }
    }
    camera.start(CameraRequest(analysisFrameWidth: 640))
    camera.publishConfiguration(configuration, run: 1)
    camera.interrupt(run: 1)
    wait(for: [delivered], timeout: 5)
    XCTAssertEqual(events, [.interrupted, .configured(configuration)])
    camera.stop()
  }

  @MainActor
  func testReplacementStartIgnoresQueuedConfigurationAndFramesFromTheOldStart() {
    let gate = DispatchSemaphore(value: 0)
    defer { gate.signal(); gate.signal() }
    let camera = AVCameraSession(discovery: { gate.wait(); return nil })
    let oldSize = PixelSize(640, 480)
    let newSize = PixelSize(1280, 960)
    let replacement = CameraConfiguration(photo: newSize, video: newSize)
    let delivered = expectation(description: "replacement configuration delivered")
    var events: [CameraSessionEvent] = []
    camera.onEvent = {
      events.append($0)
      if $0 == .configured(replacement) { delivered.fulfill() }
    }
    camera.start(CameraRequest(analysisFrameWidth: 640))
    camera.publishConfiguration(CameraConfiguration(photo: oldSize, video: oldSize), run: 1)
    camera.frames.arm(1)
    camera.frameArrived(oldSize)
    camera.stop()
    camera.start(CameraRequest(analysisFrameWidth: 1280))
    camera.publishConfiguration(replacement, run: 3)
    wait(for: [delivered], timeout: 5)
    XCTAssertEqual(events, [.configured(replacement)])
    camera.stop()
  }

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
    camera.start(CameraRequest(analysisFrameWidth: 640))
    camera.frameArrived(PixelSize(1920, 1440))
    RunLoop.main.run(until: Date().addingTimeInterval(0.1))
    XCTAssertEqual(events, [], "the late frame published nothing")
    gate.signal()
    wait(for: [failed], timeout: 10)
    XCTAssertEqual(events, [.failed(.cameraUnavailable)])
  }

  @MainActor
  func testShotKeepsItsSlotDefersRestartsAndAdmitsOnePage() throws {
    let h = Harness()
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    defer { _ = state.close() }
    h.view.session = session
    h.view.captureMode = .quality
    var capture: [CameraCaptureState] = []
    h.view.onCaptureStateChange = { capture.append($0) }
    h.view.afterUpdate()
    h.attach()
    h.fake.emit(.configured(CameraConfiguration(photo: PixelSize(640, 480), video: PixelSize(640, 480))))
    h.view.setRotation(preview: 90, capture: 90)
    h.fake.emit(.streaming(PixelSize(640, 480)))
    XCTAssertEqual(capture, [.unavailable, .ready])

    let first = photo(h.view)
    XCTAssertEqual(h.fake.stills.count, 1)
    XCTAssertEqual(h.fake.stills[0].settings, StillSettings(priority: .quality, quality: 1, rotationAngle: 90))
    XCTAssertEqual(capture.last, .shooting)
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy), "one shot at a time")
    // A new request and framing wait for the shot.
    h.view.analysisFrameWidth = 1280
    h.view.squareAspectRatio = true
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 1, "no restart during the shot")
    XCTAssertEqual(h.view.analysisFraming?.square, false)
    h.fake.stills[0].events(.exposed)
    XCTAssertEqual(capture.last, .saving)
    h.fake.stills[0].events(.delivered(try encodedStill(width: 640, height: 480, orientation: 6)))
    guard case .first(let id) = outcome(first) else { return XCTFail("the shot resolves with a page ID") }
    XCTAssertNotNil(state.snapshot().pages[id])
    let deadline = Date().addingTimeInterval(10)
    while h.fake.starts.count < 2 && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
    XCTAssertEqual(h.fake.starts.last, CameraRequest(analysisFrameWidth: 1280), "the deferred restart follows the shot")
    h.fake.emit(.streaming(PixelSize(1280, 960)))
    XCTAssertEqual(h.view.analysisFraming?.square, true)
  }

  @MainActor
  func testReplacingTheSessionRejectsTheShotButKeepsItsSlotUntilTheCameraSettles() throws {
    let h = Harness()
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    defer { _ = state.close() }
    h.view.session = session
    h.view.afterUpdate()
    h.attach()
    h.fake.emit(.streaming(PixelSize(640, 480)))
    var stages: [CaptureStage] = []
    h.view.onCaptureStage = { stage, _ in stages.append(stage) }
    let shot = photo(h.view)
    let replacement = HybridScanSession()
    defer { _ = replacement.state?.close() }
    h.view.session = replacement
    h.view.afterUpdate()
    XCTAssertEqual(outcome(shot), .second(.processorSuperseded), "rejected at once")
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy), "the physical shot still owns the slot")
    h.fake.stills[0].events(.exposed)
    h.fake.stills[0].events(.delivered(try encodedStill(width: 320, height: 240)))
    XCTAssertEqual(stages, [.waiting, .shooting], "obsolete stages do not reach the replacement")
    XCTAssertTrue(state.snapshot().pages.isEmpty, "nothing reaches the old session")
    XCTAssertTrue(replacement.state?.snapshot().pages.isEmpty ?? false, "nor the new one")
    let next = photo(h.view)
    XCTAssertEqual(h.fake.stills.count, 2, "the slot is free again")
    h.fake.stills[0].events(.exposed)
    XCTAssertEqual(stages, [.waiting, .shooting, .waiting, .shooting])
    h.view.onDropView()
    XCTAssertEqual(outcome(next), .second(.cameraUnavailable), "unmount rejects the shot in flight")
  }

  @MainActor
  func testInactivityWaitsForTheShotToPublishBeforeStopping() throws {
    let h = Harness()
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    defer { _ = state.close() }
    h.view.session = session
    h.view.afterUpdate()
    h.attach()
    h.fake.emit(.streaming(PixelSize(640, 480)))
    let shot = photo(h.view)
    h.view.active = false
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.stops, 0, "inactivity leaves the admitted shot running")
    h.fake.stills[0].events(.exposed)
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.stops, 0, "saving also keeps the camera alive")
    h.fake.stills[0].events(.delivered(try encodedStill(width: 320, height: 240)))
    guard case .first(let id) = outcome(shot) else { return XCTFail("the shot publishes despite inactivity") }
    XCTAssertNotNil(state.snapshot().pages[id])
    let deadline = Date().addingTimeInterval(10)
    while h.fake.stops == 0 && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
    XCTAssertEqual(h.fake.stops, 1, "the camera stops after publication")
    XCTAssertEqual(rejection(h.view), .cameraUnavailable)
    h.view.active = true
    h.view.afterUpdate()
    XCTAssertEqual(h.fake.starts.count, 2, "reactivation resumes the camera")
  }

  @MainActor
  func testStoppingTheCameraRejectsTheShotButKeepsItsSlotUntilTheCameraSettles() throws {
    let h = Harness()
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    defer { _ = state.close() }
    h.view.session = session
    h.view.afterUpdate()
    h.attach()
    h.fake.emit(.streaming(PixelSize(640, 480)))
    let shot = photo(h.view)
    h.view.active = false
    h.view.afterUpdate()
    h.detach()
    XCTAssertEqual(outcome(shot), .second(.cameraUnavailable), "detaching rejects at once")
    h.attach()
    XCTAssertEqual(h.fake.starts.count, 1, "the camera restarts only after the shot settles")
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy), "the physical shot still owns the slot")
    h.view.active = true
    h.view.afterUpdate()
    h.fake.stills[0].events(.delivered(try encodedStill(width: 320, height: 240)))
    XCTAssertTrue(state.snapshot().pages.isEmpty, "a delivered still publishes nothing")
    XCTAssertEqual(h.fake.starts.count, 2)
  }

  @MainActor
  func testFocusAndTorchHoldStillForTheShot() throws {
    let h = Harness()
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    defer { _ = state.close() }
    h.view.session = session
    h.view.afterUpdate()
    h.attach()
    var capabilities = CameraCapabilities()
    capabilities.focusPoint = true
    capabilities.scanFocus = true
    capabilities.torch = true
    h.fake.emit(.configured(CameraConfiguration(photo: PixelSize(640, 480), video: PixelSize(640, 480),
                                                capabilities: capabilities)))
    h.fake.emit(.streaming(PixelSize(640, 480)))
    func scans() -> Int { h.fake.commands.filter { if case .scanFocus = $0 { true } else { false } }.count }
    let shot = photo(h.view)
    h.controls.tap(at: CGPoint(x: 0.5, y: 0.5))
    XCTAssertEqual(scans(), 0, "no tap moves the lens while the photo exposes")
    h.view.torch = .on
    h.view.afterUpdate()
    XCTAssertFalse(h.fake.commands.contains(.torch(true)), "the torch waits for the shot")
    h.fake.stills[0].events(.exposed)
    h.controls.tap(at: CGPoint(x: 0.5, y: 0.5))
    XCTAssertEqual(scans(), 1, "a tap is accepted once the exposure ended")
    XCTAssertFalse(h.fake.commands.contains(.torch(true)))
    h.fake.stills[0].events(.delivered(try encodedStill(width: 320, height: 240)))
    guard case .first = outcome(shot) else { return XCTFail("the shot is admitted") }
    let deadline = Date().addingTimeInterval(10)
    while !h.fake.commands.contains(.torch(true)) && Date() < deadline {
      RunLoop.main.run(until: Date().addingTimeInterval(0.01))
    }
    XCTAssertTrue(h.fake.commands.contains(.torch(true)), "the deferred torch change applies after the shot")
  }

  @MainActor
  func testOnlyACurrentGuideSeedsTheShot() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let store = SourceStore(directory: root.appendingPathComponent("sources"))
    let registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                       queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.guide")),
                                       report: { _ in })
    let actions = RecordedCornerSets()
    let state = try registry.createState(actions: { _ in actions })
    defer { _ = state.close(); store.shutdown(); store.drain(); try? FileManager.default.removeItem(at: root) }
    let h = Harness()
    h.view.session = HybridScanSession(state: state)
    h.view.afterUpdate()
    h.attach()
    h.fake.emit(.configured(CameraConfiguration(photo: PixelSize(640, 480), video: PixelSize(640, 480))))
    h.view.setRotation(preview: 90, capture: 90)
    h.fake.emit(.streaming(PixelSize(640, 480)))
    let corners: LiveCorners = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8]
    func seeGuide() throws {
      let framing = try XCTUnwrap(h.view.analysisFraming)
      h.detector.onResult?(LiveGuideResult(status: .tracking, corners: corners, framing: framing))
    }
    func shoot() throws {
      let shot = photo(h.view)
      let still = try encodedStill(width: 640, height: 480, orientation: 6)
      h.fake.stills[h.fake.stills.count - 1].events(.delivered(still))
      guard case .first = outcome(shot) else { return XCTFail("the shot is admitted") }
    }
    try seeGuide()
    try shoot()
    XCTAssertEqual(actions.liveCornerSets.count, 1)
    XCTAssertNotNil(actions.liveCornerSets.last ?? nil, "a guide in the current framing seeds the page")
    try seeGuide()
    h.view.setRotation(preview: 0, capture: 0)
    try shoot()
    XCTAssertEqual(actions.liveCornerSets.count, 2)
    XCTAssertNil(actions.liveCornerSets.last ?? nil, "an outline from before the rotation is dropped")
    h.view.setRotation(preview: 90, capture: 90)
    try seeGuide()
    h.view.guideLiveCorners = false
    h.view.afterUpdate()
    try shoot()
    XCTAssertEqual(actions.liveCornerSets.count, 3)
    XCTAssertNil(actions.liveCornerSets.last ?? nil, "disabling guidance drops its outline")
  }

  // MARK: - Capture gate

  /// Analyzed-frame evidence as the analysis owner appends it, then the wake-up it sends to main.
  @MainActor
  private final class Frames {
    let h: Harness
    let framing: CameraFraming
    private var last: Int64 = 0
    init(_ h: Harness) { self.h = h; framing = h.view.analysisFraming! }

    init(_ h: Harness, framing: CameraFraming) { self.h = h; self.framing = framing }

    /// `blur` is the frame's smear as a fraction of its width.
    func feed(_ count: Int = 1, guide: LiveCorners? = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8],
              adjusting: Bool? = false, focusReachedBeforeNs: Int64 = 0, blur: Double = 10 * 0.01 / 480) {
      for _ in 0..<count {
        let from = last
        let ns = max(hostClockNs(), from + 33_000_000)
        last = ns
        let framing = framing
        h.detector.frames.append { sequence in
          FrameRecord(sequence: sequence, sensorNs: ns, framing: framing,
                      flow: FlowEvidence(status: .measured, speedPxPerSecond: 10, sourceWidth: 480, lostRegions: 0,
                                         fromNs: from),
                      exposureNs: 10_000_000, iso: 100, exposureEstimated: false, blur: blur,
                      focusAdjusting: adjusting, focusReachedNs: ns - focusReachedBeforeNs,
                      pageArrivedNs: guide == nil ? nil : ns, guide: guide)
        }
      }
      h.detector.onFrames?()
    }
  }

  /// A streaming camera bound to a fresh session, with `configure` applied before the first update.
  @MainActor
  private func gated(_ capabilities: CameraCapabilities = CameraCapabilities(),
                     configure: (HybridDocumentScannerCameraView) -> Void = { _ in }) throws -> (Harness, ScanSessionState) {
    let h = Harness()
    let session = HybridScanSession()
    let state = try XCTUnwrap(session.state)
    h.view.session = session
    configure(h.view)
    h.view.afterUpdate()
    h.attach()
    h.fake.emit(.configured(CameraConfiguration(photo: PixelSize(640, 480), video: PixelSize(640, 480),
                                                capabilities: capabilities)))
    h.view.setRotation(preview: 90, capture: 90)
    h.fake.emit(.streaming(PixelSize(640, 480)))
    return (h, state)
  }

  @MainActor
  private func scanPoints(_ h: Harness) -> [CGPoint] {
    h.fake.commands.compactMap { if case .scanFocus(let point, _) = $0 { point } else { nil } }
  }

  @MainActor
  private func settle() { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }

  @MainActor
  func testAGatedCaptureWaitsForAPageAndSteadyFramesThenShootsOnce() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    var stages: [CaptureWaitReason?] = []
    var capture: [CameraCaptureState] = []
    var events: [CaptureStage] = []
    h.view.onCaptureStage = { stage, reason in
      events.append(stage)
      if stage == .waiting { stages.append(reason) }
    }
    h.view.onCaptureStateChange = { capture.append($0) }
    let frames = Frames(h)
    let shot = photo(h.view, gated: true)
    XCTAssertTrue(h.fake.stills.isEmpty, "no page yet")
    XCTAssertEqual(stages, [nil, .document])
    XCTAssertEqual(capture.last, .waiting)
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy), "a waiting capture owns the slot")
    h.view.squareAspectRatio = true
    h.view.afterUpdate()
    XCTAssertEqual(h.view.analysisFraming?.square, false, "framing waits for the capture")
    frames.feed(3)
    XCTAssertTrue(h.fake.stills.isEmpty, "three steady frames are not enough")
    XCTAssertEqual(stages, [nil, .document, .steady])
    frames.feed()
    XCTAssertEqual(h.fake.stills.count, 1, "the fourth steady frame shoots")
    frames.feed(2)
    XCTAssertEqual(h.fake.stills.count, 1, "the gate shoots once")
    XCTAssertEqual(events, [.waiting, .waiting, .waiting, .shooting])
    h.fake.stills[0].events(.exposed)
    h.fake.stills[0].events(.exposed)
    XCTAssertEqual(events.last, .captured)
    XCTAssertEqual(events.count, 5)
    h.fake.stills[0].events(.delivered(try encodedStill(width: 640, height: 480, orientation: 6)))
    guard case .first(let id) = outcome(shot) else { return XCTFail("the gated shot is admitted") }
    XCTAssertNotNil(state.snapshot().pages[id])
  }

  @MainActor
  func testDisabledFeedbackStillReportsExposureAndWriteFailureWithoutReplay() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    h.view.captureFeedback = false
    h.view.afterUpdate()
    var stages: [CaptureStage] = []
    h.view.onCaptureStage = { stage, _ in stages.append(stage) }
    let shot = photo(h.view)
    XCTAssertEqual(stages, [.waiting, .shooting])
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy))
    XCTAssertEqual(stages, [.waiting, .shooting])
    h.view.onCaptureStage = { stage, _ in stages.append(stage) }
    h.view.afterUpdate()
    XCTAssertEqual(stages, [.waiting, .shooting])
    h.fake.stills[0].events(.exposed)
    h.fake.stills[0].events(.delivered(Data()))
    guard case .second = outcome(shot) else { return XCTFail("invalid data must fail to save") }
    settle()
    XCTAssertEqual(stages, [.waiting, .shooting, .captured, .failed])
  }

  @MainActor
  func testAGatedCaptureRejectsAtItsDeadlineAndFreesTheSlotAtOnce() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    let shot = photo(h.view, gated: true)
    h.clockOffsetNs = 4_100_000_000
    h.detector.onFrames?()
    XCTAssertEqual(outcome(shot), .second(.captureFocusTimeout), "no page within the focus timeout")
    XCTAssertTrue(h.fake.stills.isEmpty)
    _ = photo(h.view)
    XCTAssertEqual(h.fake.stills.count, 1, "a capture that never shot frees its slot at once")
  }

  @MainActor
  func testInvalidationWhileWaitingRejectsOnceAndLateFramesPublishNothing() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    let frames = Frames(h)
    let detached = photo(h.view, gated: true)
    h.detach()
    XCTAssertEqual(outcome(detached), .second(.cameraUnavailable))
    h.attach()
    XCTAssertEqual(h.fake.starts.count, 2, "without a physical shot the camera restarts at once")
    frames.feed(5)
    XCTAssertTrue(h.fake.stills.isEmpty, "frames after the rejection shoot nothing")

    h.fake.emit(.streaming(PixelSize(640, 480)))
    let replaced = photo(h.view, gated: true)
    let replacement = HybridScanSession()
    defer { _ = replacement.state?.close() }
    h.view.session = replacement
    h.view.afterUpdate()
    XCTAssertEqual(outcome(replaced), .second(.processorSuperseded))
    frames.feed(5)
    XCTAssertTrue(h.fake.stills.isEmpty)
    _ = photo(h.view)
    XCTAssertEqual(h.fake.stills.count, 1, "the slot is free again")
  }

  @MainActor
  func testAPendingTorchCommandHoldsLightAndTorchChangesWaitForTheCapture() throws {
    var capabilities = CameraCapabilities()
    capabilities.torch = true
    let (h, state) = try gated(capabilities) {
      $0.torch = .on
      $0.guideLiveCorners = false
      $0.lowQualityControl = LowQualityControl(enabled: true, captureBlurPercent: 0, requireFocus: nil)
    }
    defer { _ = state.close() }
    let frames = Frames(h)
    var stages: [CaptureWaitReason?] = []
    h.view.onCaptureStage = { stages.append($1) }
    let lit = try XCTUnwrap(h.fake.commands.lastIndex(of: .torch(true)), "the torch was sent at bind")
    let shot = photo(h.view, gated: true)
    XCTAssertEqual(stages, [nil, .light], "an unanswered torch command holds the shot")
    XCTAssertTrue(h.fake.stills.isEmpty)
    h.view.torch = .off
    h.view.afterUpdate()
    XCTAssertFalse(h.fake.commands.contains(.torch(false)), "torch changes wait for the capture")
    h.fake.completions[lit](.accepted)
    settle()
    XCTAssertTrue(h.fake.stills.isEmpty, "frames from before the torch applied show the earlier light")
    frames.feed()
    XCTAssertEqual(h.fake.stills.count, 1, "a frame exposed under the confirmed torch releases the shot")
    h.fake.stills[0].events(.failed(.captureFailed))
    XCTAssertEqual(outcome(shot), .second(.captureFailed))
    XCTAssertTrue(h.fake.commands.contains(.torch(false)), "the deferred change applies once the shot settles")
  }

  @MainActor
  func testQualityCapturesScanOnceForAnUnknownResultAndRetryAKnownFailureOnce() throws {
    var capabilities = CameraCapabilities()
    capabilities.focusPoint = true
    capabilities.scanFocus = true
    let (h, state) = try gated(capabilities) {
      $0.captureMode = .quality
      $0.guideLiveCorners = false
      $0.lowQualityControl = LowQualityControl(enabled: true, captureBlurPercent: 0, requireFocus: nil)
    }
    defer { _ = state.close() }
    let frames = Frames(h)
    func lastScan() -> Int { h.fake.commands.lastIndex { if case .scanFocus = $0 { true } else { false } }! }

    let first = photo(h.view, gated: true)
    XCTAssertEqual(scanPoints(h), [CameraControls.centre], "a quality capture scans at the centre without a page")
    h.fake.completions[lastScan()](.accepted)
    // AVFoundation's end of a scan: the lens locked and still, with no verdict.
    h.controls.focusObserved(FocusObservation(adjusting: false, locked: true))
    settle()
    XCTAssertEqual(scanPoints(h).count, 1, "an unknown result is not retried")
    XCTAssertTrue(h.fake.stills.isEmpty, "the frames after the scan decide")
    frames.feed(adjusting: true)
    XCTAssertTrue(h.fake.stills.isEmpty, "a moving lens is not focused")
    frames.feed()
    XCTAssertEqual(h.fake.stills.count, 1, "a still lens after the scan shoots")
    h.fake.stills[0].events(.failed(.captureFailed))
    XCTAssertEqual(outcome(first), .second(.captureFailed))

    let second = photo(h.view, gated: true)
    XCTAssertEqual(scanPoints(h).count, 2)
    h.fake.completions[lastScan()](.refused)
    settle()
    XCTAssertEqual(scanPoints(h).count, 3, "a refused scan is retried once")
    h.fake.completions[lastScan()](.refused)
    settle()
    XCTAssertEqual(scanPoints(h).count, 3, "a second failure without movement is the last")
    frames.feed()
    XCTAssertEqual(h.fake.stills.count, 1, "a known failure is not focus")
    h.clockOffsetNs = 4_100_000_000
    h.detector.onFrames?()
    XCTAssertEqual(outcome(second), .second(.captureFocusTimeout))
  }

  @MainActor
  func testStalePageFocusScansAtThePageButATapHoldsTheLens() throws {
    var capabilities = CameraCapabilities()
    capabilities.focusPoint = true
    capabilities.scanFocus = true
    capabilities.continuousFocus = true
    let (h, state) = try gated(capabilities) {
      $0.captureMode = .latency
      $0.lowQualityControl = LowQualityControl(enabled: true, captureBlurPercent: 0, requireFocus: nil)
    }
    defer { _ = state.close() }
    let frames = Frames(h)
    let page: LiveCorners = [0.125, 0.25, 0.625, 0.25, 0.625, 0.75, 0.125, 0.75]
    let shot = photo(h.view, gated: true)
    XCTAssertTrue(scanPoints(h).isEmpty, "latency keeps continuous autofocus")
    frames.feed(guide: page, focusReachedBeforeNs: 1_000_000_000)
    // The page centre (0.375, 0.5) upright, a quarter turn back into sensor axes.
    XCTAssertEqual(scanPoints(h), [CGPoint(x: 0.5, y: 0.625)], "focus from before the page scans at the page")
    XCTAssertTrue(h.fake.stills.isEmpty)
    h.view.onDropView()
    XCTAssertEqual(outcome(shot), .second(.cameraUnavailable))

    let (tapped, tappedState) = try gated(capabilities) {
      $0.captureMode = .latency
      $0.lowQualityControl = LowQualityControl(enabled: true, captureBlurPercent: 0, requireFocus: nil)
    }
    defer { _ = tappedState.close() }
    tapped.controls.tap(at: CGPoint(x: 0.9, y: 0.9))
    let held = photo(tapped.view, gated: true)
    Frames(tapped).feed(guide: page, focusReachedBeforeNs: 1_000_000_000)
    XCTAssertEqual(scanPoints(tapped), [CGPoint(x: 0.9, y: 0.9)], "the user's tap stays authoritative")
    tapped.view.onDropView()
    XCTAssertEqual(outcome(held), .second(.cameraUnavailable))
  }

  @MainActor
  func testExposureChangesWaitForTheCaptureAndTheirAcknowledgementHoldsLight() throws {
    var capabilities = CameraCapabilities()
    capabilities.exposureBias = -2...2
    let (h, state) = try gated(capabilities)
    defer { _ = state.close() }
    let bias = try XCTUnwrap(h.fake.commands.lastIndex(of: .exposureBias(0)), "the bias was sent at bind")
    var stages: [CaptureWaitReason?] = []
    h.view.onCaptureStage = { stages.append($1) }
    let frames = Frames(h)
    let shot = photo(h.view, gated: true)
    frames.feed(4)
    XCTAssertTrue(h.fake.stills.isEmpty, "an unanswered exposure command holds light")
    XCTAssertEqual(stages.last, .light)
    h.view.exposureCompensation = 1
    h.view.afterUpdate()
    XCTAssertFalse(h.fake.commands.contains(.exposureBias(1)), "exposure changes wait for the capture")
    h.fake.completions[bias](.accepted)
    settle()
    XCTAssertTrue(h.fake.stills.isEmpty, "steady frames from before the exposure applied do not count")
    frames.feed(3)
    XCTAssertTrue(h.fake.stills.isEmpty)
    frames.feed()
    XCTAssertEqual(h.fake.stills.count, 1, "four steady frames after the change shoot")
    h.fake.stills[0].events(.failed(.captureFailed))
    XCTAssertEqual(outcome(shot), .second(.captureFailed))
    XCTAssertEqual(h.fake.commands.last, .exposureBias(1), "the deferred change applies once the shot settles")
  }

  /// A bias acknowledges with the first frame exposed with it; frames before that frame were exposed
  /// with the old bias, even when they are read after the acknowledgement.
  @MainActor
  func testAnExposureBiasCountsOnlyFramesFromItsFirstExposedBuffer() throws {
    var capabilities = CameraCapabilities()
    capabilities.exposureBias = -2...2
    let (h, state) = try gated(capabilities)
    defer { _ = state.close() }
    let bias = try XCTUnwrap(h.fake.commands.lastIndex(of: .exposureBias(0)), "the bias was sent at bind")
    let frames = Frames(h)
    let shot = photo(h.view, gated: true)
    h.fake.completions[bias](CommandOutcome(applied: true, fromNs: hostClockNs() + 500_000_000))
    settle()
    frames.feed(4)
    XCTAssertTrue(h.fake.stills.isEmpty, "steady frames before the first biased buffer do not count")
    frames.feed(20)
    XCTAssertEqual(h.fake.stills.count, 1, "four steady frames from the first biased buffer shoot")
    h.fake.stills[0].events(.failed(.captureFailed))
    XCTAssertEqual(outcome(shot), .second(.captureFailed))
  }

  @MainActor
  func testAnInterruptionOrACameraFailureCancelsAWaitingCapture() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    let frames = Frames(h)
    let interrupted = photo(h.view, gated: true)
    frames.feed(3)
    h.fake.emit(.interrupted)
    XCTAssertEqual(outcome(interrupted), .second(.cameraUnavailable), "evidence from before it no longer holds")
    h.fake.emit(.streaming(PixelSize(640, 480)))
    frames.feed(5)
    XCTAssertTrue(h.fake.stills.isEmpty, "the cancelled capture shoots nothing")

    let failed = photo(h.view, gated: true)
    h.fake.emit(.failed(.cameraUnavailable))
    XCTAssertEqual(outcome(failed), .second(.cameraUnavailable), "rejected at once, not at a quality deadline")
    frames.feed(5)
    XCTAssertTrue(h.fake.stills.isEmpty)
  }

  @MainActor
  func testMediaResetWaitsForThePhysicalSlotAndRetriesOnlyOnceBeforeStreaming() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    let shot = photo(h.view)
    let deliver = try XCTUnwrap(h.fake.stills.first?.events)
    h.fake.stop()
    h.fake.emit(.mediaServicesReset)
    XCTAssertEqual(outcome(shot), .second(.cameraUnavailable))
    XCTAssertEqual(h.fake.starts.count, 1)
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy))
    deliver(.exposed)
    deliver(.delivered(try encodedStill(width: 320, height: 240)))
    XCTAssertTrue(state.snapshot().pages.isEmpty)
    XCTAssertEqual(h.fake.starts.count, 2)
    h.fake.stop()
    h.fake.emit(.mediaServicesReset)
    XCTAssertEqual(h.fake.starts.count, 2)
    XCTAssertEqual(h.errors, [.cameraUnavailable])
    h.post(UIApplication.didEnterBackgroundNotification)
    h.post(UIApplication.willEnterForegroundNotification)
    XCTAssertEqual(h.fake.starts.count, 3)
  }

  @MainActor
  func testReturningToTheSameModelIgnoresTheEarlierLoadAndDisposalIgnoresTheCurrentLoad() {
    var loads: [(ScannerErrorCode?) -> Void] = []
    let h = Harness { _, completion in loads.append(completion) }
    h.attach()
    h.view.afterUpdate()
    h.view.cornerDetectionModel = "other.tflite"
    h.view.afterUpdate()
    h.view.cornerDetectionModel = "model.tflite"
    h.view.afterUpdate()
    XCTAssertEqual(loads.count, 3)
    loads.first?(nil)
    XCTAssertTrue(h.fake.starts.isEmpty)
    h.view.onDropView()
    loads.last?(nil)
    XCTAssertTrue(h.fake.starts.isEmpty)
  }

  @MainActor
  func testConfigurationDeliveredWhileInterruptedBindsOnTheFirstFrame() {
    let h = Harness()
    h.view.afterUpdate()
    h.attach()
    let size = PixelSize(640, 480)
    var capabilities = CameraCapabilities()
    capabilities.torch = true
    let configuration = CameraConfiguration(photo: size, video: size, capabilities: capabilities)
    h.fake.emit(.interrupted)
    h.fake.emit(.configured(configuration))
    XCTAssertEqual(h.view.configuration, configuration)
    XCTAssertEqual(h.view.cameraState, .starting)
    XCTAssertFalse(h.controls.isBound)
    XCTAssertTrue(h.fake.commands.isEmpty)
    h.fake.emit(.streaming(size))
    XCTAssertEqual(h.view.cameraState, .ready)
    XCTAssertTrue(h.controls.isBound)
    XCTAssertEqual(h.fake.commands.filter { $0 == .torch(false) }.count, 1)
  }

  @MainActor
  func testResumingWhileACancelledStillSettlesStartsTheStreamAndBindsControlsOnce() throws {
    var capabilities = CameraCapabilities()
    capabilities.torch = true
    let (h, state) = try gated(capabilities)
    defer { _ = state.close() }
    let shot = photo(h.view)
    let deliver = try XCTUnwrap(h.fake.stills.first?.events)
    h.fake.emit(.interrupted)
    XCTAssertEqual(outcome(shot), .second(.cameraUnavailable))
    h.view.torch = .on
    h.view.afterUpdate()

    let size = PixelSize(640, 480)
    h.fake.emit(.streaming(size))
    let resumedGeneration = h.detector.state.current
    h.fake.emit(.streaming(size))
    XCTAssertEqual(h.detector.state.current, resumedGeneration)
    XCTAssertFalse(h.controls.isBound)
    XCTAssertFalse(h.fake.commands.contains(.torch(true)))
    XCTAssertEqual(outcome(photo(h.view)), .second(.captureBusy))

    deliver(.failed(.cameraUnavailable))
    XCTAssertTrue(h.controls.isBound)
    XCTAssertEqual(h.fake.commands.filter { $0 == .torch(true) }.count, 1)
    XCTAssertEqual(h.detector.state.current, resumedGeneration)
    XCTAssertTrue(state.snapshot().pageIds.isEmpty)
  }

  @MainActor
  func testResumedStreamingSamplesFocusAfterConsumersAreReady() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    var autofocus: [AutofocusState] = []
    h.view.onAutofocusChange = { autofocus.append($0) }
    h.fake.emit(.interrupted)
    let scanning = FocusObservation(adjusting: true, locked: false)
    h.fake.focusSample = scanning
    h.fake.focusEvents?(scanning)
    XCTAssertEqual(autofocus.last, .inactive)
    // The lens is still moving, so KVO has no change to report after the first frame.
    h.fake.emit(.streaming(PixelSize(640, 480)))
    XCTAssertEqual(autofocus.last, .scan)
  }

  @MainActor
  func testRepeatedCaptureReviewBackgroundAndSessionReplacementRetiresStorage() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let sources = root.appendingPathComponent("sources")
    let sessions = root.appendingPathComponent("sessions")
    let store = SourceStore(directory: sources)
    let registry = ScanSessionRegistry(root: sessions, store: store,
                                       queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.lifecycle")))
    defer {
      store.shutdown()
      store.drain()
      try? FileManager.default.removeItem(at: root)
    }
    let h = Harness()
    defer { h.view.onDropView(); h.window.isHidden = true }
    h.window.isHidden = false
    let size = PixelSize(320, 240)
    let still = try encodedStill(width: 320, height: 240)
    h.attach()

    for iteration in 0..<6 {
      let state = try registry.createState(actions: { _ in RecordedCornerSets() })
      let session = HybridScanSession(state: state)
      h.view.session = session
      h.view.afterUpdate()
      h.fake.emit(.configured(CameraConfiguration(photo: size, video: size)))
      h.fake.emit(.streaming(size))
      let capture = photo(h.view)
      guard let deliver = h.fake.stills.popLast()?.events else { return XCTFail("missing still") }
      deliver(.exposed)
      deliver(.delivered(still))
      guard case .first(let id) = outcome(capture) else { return XCTFail("capture did not publish") }
      XCTAssertEqual(state.snapshot().pageIds, [id])
      let pager = HybridImagePagerView()
      pager.session = session
      pager.selectedPageId = id
      let crop = HybridDocumentCropEditorView()
      crop.session = session
      crop.pageId = id
      let pagerLoaded = expectation(description: "pager loaded session \(iteration)")
      let cropLoaded = expectation(description: "crop loaded session \(iteration)")
      var pagerLoads: [String] = []
      var cropLoads = 0
      pager.onLoad = { page in
        pagerLoads.append(page)
        if pagerLoads.count == 1 { pagerLoaded.fulfill() }
      }
      crop.onLoad = { _, _ in
        cropLoads += 1
        if cropLoads == 1 { cropLoaded.fulfill() }
      }
      for view in [pager.view, crop.view] {
        view.frame = h.window.bounds
        h.window.addSubview(view)
      }
      pager.afterUpdate()
      crop.afterUpdate()
      h.window.layoutIfNeeded()
      wait(for: [pagerLoaded, cropLoaded], timeout: 10)
      XCTAssertEqual(pagerLoads.last, id)
      XCTAssertNotNil((pager.view as? ImagePagerView)?.shownCell?.image.image)
      XCTAssertNotNil(crop.photo)
      h.post(UIApplication.didEnterBackgroundNotification)
      XCTAssertEqual(h.view.cameraState, .suspended)
      let starts = h.fake.starts.count
      h.post(UIApplication.willEnterForegroundNotification)
      // A writer may still be handing the completed shot back to main.
      let deadline = Date().addingTimeInterval(5)
      while h.fake.starts.count == starts && Date() < deadline {
        RunLoop.main.run(until: Date().addingTimeInterval(0.01))
      }
      XCTAssertEqual(h.fake.starts.count, starts + 1)
      h.view.session = HybridScanSession.placeholder()
      h.view.afterUpdate()
      pager.onDropView()
      crop.onDropView()
      pager.view.removeFromSuperview()
      crop.view.removeFromSuperview()
      let closed = expectation(description: "closed session \(iteration)")
      state.close().whenComplete { @Sendable error in
        XCTAssertNil(error)
        closed.fulfill()
      }
      wait(for: [closed], timeout: 10)
      store.drain()
      XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: sources.path), [])
      XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: sessions.path), [])
    }
  }

  @MainActor
  func testMotionNeverStartsAScanAndSteadinessStartsOnceFocusIsAccepted() throws {
    var capabilities = CameraCapabilities()
    capabilities.focusPoint = true
    capabilities.scanFocus = true
    func quality() throws -> (Harness, ScanSessionState) {
      try gated(capabilities) {
        $0.captureMode = .quality
        $0.guideLiveCorners = false
      }
    }
    let (h, state) = try quality()
    defer { _ = state.close() }
    let frames = Frames(h)
    func lastScan(_ h: Harness) -> Int { h.fake.commands.lastIndex { if case .scanFocus = $0 { true } else { false } }! }
    let failing = photo(h.view, gated: true)
    XCTAssertEqual(scanPoints(h).count, 1)
    frames.feed(blur: 0.01)
    h.fake.completions[lastScan(h)](.refused)
    settle()
    XCTAssertEqual(scanPoints(h).count, 2, "a known failure is retried once")
    frames.feed(blur: 0.01)
    h.fake.completions[lastScan(h)](.refused)
    settle()
    frames.feed(blur: 0.01)
    XCTAssertEqual(scanPoints(h).count, 2, "motion during a failed scan starts no other")
    h.view.onDropView()
    XCTAssertEqual(outcome(failing), .second(.cameraUnavailable))

    let (settled, settledState) = try quality()
    defer { _ = settledState.close() }
    let settledFrames = Frames(settled)
    let shot = photo(settled.view, gated: true)
    settledFrames.feed(4)
    XCTAssertTrue(settled.fake.stills.isEmpty, "steady frames while the scan runs do not count")
    settled.fake.completions[lastScan(settled)](.accepted)
    settled.controls.focusObserved(FocusObservation(adjusting: false, locked: true))
    settle()
    settledFrames.feed(3)
    XCTAssertTrue(settled.fake.stills.isEmpty, "the sequence starts from scratch once focus is accepted")
    settledFrames.feed()
    XCTAssertEqual(settled.fake.stills.count, 1)
    XCTAssertEqual(scanPoints(settled).count, 1)
    settled.fake.stills[0].events(.failed(.captureFailed))
    XCTAssertEqual(outcome(shot), .second(.captureFailed))
  }

  @MainActor
  func testATurnWhileWaitingRestartsReadinessAndTheStillTakesTheShutterRotation() throws {
    let (h, state) = try gated()
    defer { _ = state.close() }
    let before = Frames(h)
    let shot = photo(h.view, gated: true)
    before.feed(3)
    h.view.setRotation(preview: 270, capture: 270)
    before.feed()
    XCTAssertTrue(h.fake.stills.isEmpty, "a page seen through the old framing does not shoot")
    let after = Frames(h, framing: try XCTUnwrap(h.view.analysisFraming))
    XCTAssertEqual(after.framing.rotation, 270)
    after.feed(3)
    XCTAssertTrue(h.fake.stills.isEmpty, "the new framing starts a new steady sequence")
    after.feed()
    XCTAssertEqual(h.fake.stills.count, 1)
    XCTAssertEqual(h.fake.stills[0].settings.rotationAngle, 270, "the still is shot in the framing it was judged in")
    h.fake.stills[0].events(.delivered(try encodedStill(width: 640, height: 480, orientation: 8)))
    guard case .first(let id) = outcome(shot) else { return XCTFail("the shot is admitted") }
    XCTAssertNotNil(state.snapshot().pages[id])
  }

  enum Outcome: Equatable {
    case first(String)
    case second(ScannerErrorCode)
  }

  /// A capture; ungated unless `gated`, so lifecycle tests fire at once.
  @MainActor
  private func photo(_ view: HybridDocumentScannerCameraView,
                     gated: Bool = false) -> Promise<Variant_ScannerFailure_CameraCaptureResult> {
    let promise = try! view.takePhotoResult(options: gated ? nil : TakePhotoOptions(skipLowQualityControl: true))
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    return promise
  }

  private final class Box<T>: @unchecked Sendable { var value: T?; init() {} }

  // Not main-actor isolated: a promise settled by the work queue runs its callback there, and a
  // main-actor closure would trap the runtime isolation check.
  private func outcome(_ promise: Promise<Variant_ScannerFailure_CameraCaptureResult>) -> Outcome? {
    let done = expectation(description: "settled")
    let result = Box<Outcome>()
    _ = promise.then {
      switch $0 {
      case .first(let failure): result.value = .second(failure.code)
      case .second(let capture): result.value = .first(capture.pageId)
      }
      done.fulfill()
    }
    wait(for: [done], timeout: 20)
    return result.value
  }

  @MainActor
  private func rejection(_ view: HybridDocumentScannerCameraView) -> ScannerErrorCode? {
    outcome(try! view.takePhotoResult(options: nil)).flatMap {
      if case .second(let code) = $0 { code } else { nil }
    }
  }
}
