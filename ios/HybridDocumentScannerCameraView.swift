import AVFoundation
import Foundation
import NitroModules
import UIKit

/// Rear-camera preview over one AVFoundation owner. The camera runs only while the mode is not
/// `off`, the view is in a window with a size, the app is in the foreground and permission is
/// granted; losing any of them stops it deterministically, keeping the view mounted. Nitro sets
/// props, mounts and unmounts on main, where all of this state lives.
///
/// The bound scan session is borrowed: stopping or detaching the camera never closes it, and a
/// replaced or closed session advances `sessionGeneration`, which later capture publication
/// checks. The shutter is not implemented yet: `takePhoto` rejects, and capture state stays
/// `unavailable`.
final class HybridDocumentScannerCameraView: HybridDocumentScannerCameraViewSpec, @unchecked Sendable {
  private let camera: any CameraSessionControl
  private let permissions: CameraPermissions
  private let notifications: NotificationCenter
  private let inBackground: () -> Bool

  private lazy var preview: CameraPreviewView = MainActor.assumeIsolated {
    let preview = CameraPreviewView()
    camera.attachPreview(preview.previewLayer)
    preview.onWindowChange = { [weak self] in self?.windowChanged() }
    preview.onResize = { [weak self] in self?.evaluate() }
    return preview
  }

  var view: UIView { preview }
  var session: (any HybridScanSessionSpec) = HybridScanSession.placeholder()
  var renderOptions: DocumentRenderOptions = DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil, format: nil, paperSize: nil, dpi: nil, followPageEdges: nil)
  var quadTensorModel: String? = nil
  var onError: ((_ error: ScannerFailure) -> Void)? = nil
  var mode: CameraMode? = nil
  var guide: Bool? = nil
  var adaptiveDetection: Bool? = nil
  var minCornerScore: Double? = nil
  var qualityGate: Bool? = nil
  var qualityGateConfig: QualityGateConfig? = nil
  var tapToFocus: Bool? = nil
  var exposureCompensation: Double? = nil
  var minFrameRate: Double? = nil
  var torch: TorchMode? = nil
  var captureMode: CaptureMode? = nil
  var jpegQuality: Double? = nil
  var guideTransitionDuration: Double? = nil
  var analysisWidth: Double? = nil
  var squareFrame: Bool? = nil
  var showStats: Bool? = nil
  var guideColor: Double? = nil
  var captureFeedback: Bool? = nil
  var captureColor: Double? = nil
  // Each callback hears the current state when it is set, then changes only.
  var onCameraStateChange: ((_ state: CameraState) -> Void)? = nil { didSet { reportedState = nil } }
  var onCaptureStateChange: ((_ state: CameraCaptureState) -> Void)? = nil { didSet { captureReported = false } }
  var onAutofocusChange: ((_ state: AutofocusState) -> Void)? = nil { didSet { autofocusReported = false } }
  var onDocumentStateChange: ((_ state: DocumentState) -> Void)? = nil { didSet { documentReported = false } }
  var onCaptureStage: ((_ stage: CaptureStage, _ waitReason: CaptureWaitReason?) -> Void)? = nil

  // Main only.
  private var dropped = false
  private var attached = false
  private var foreground = true
  private var active = true
  private var request = CameraRequest(analysisWidth: CameraFormatPolicy.defaultAnalysisWidth)
  /// The request the camera was started with; nil while stopped.
  private var started: CameraRequest?
  /// A failed start stays suspended until a lifecycle change or a new request retries it.
  private var failed = false
  private var streaming = false
  private(set) var configuration: CameraConfiguration?
  /// The size of the latest delivered video buffer; nil until the first frame.
  private(set) var frameSize: PixelSize?
  /// AVFoundation's clockwise capture rotation angle, which turns analysis buffers upright.
  private(set) var captureAngle: Double = 0
  private var rotation: AVCaptureDevice.RotationCoordinator?
  private var rotationObservations: [NSKeyValueObservation] = []
  private var removePermissionListener: (() -> Void)?
  private var lifecycleObservers: [NSObjectProtocol] = []
  private var reportedState: CameraState?
  private var captureReported = false
  private var autofocusReported = false
  private var documentReported = false
  private var borrowed: ScanSessionState?
  private var removeSessionObserver: (() -> Void)?
  /// Advances when the bound session is replaced or closes; capture publication must match it.
  private(set) var sessionGeneration = 0

  init(camera: (any CameraSessionControl)? = nil, permissions: CameraPermissions = .shared,
       notifications: NotificationCenter = .default, inBackground: (() -> Bool)? = nil) {
    self.camera = camera ?? AVCameraSession()
    self.permissions = permissions
    self.notifications = notifications
    self.inBackground = inBackground ?? {
      MainActor.assumeIsolated { UIApplication.shared.applicationState == .background }
    }
    super.init()
    self.camera.onEvent = { [weak self] in self?.handle($0) }
  }

  func afterUpdate() {
    MainActor.assumeIsolated {
      guard !dropped else { return }
      updateSession()
      let nextActive = mode != .off
      if nextActive != active { active = nextActive; failed = false }
      let next = CameraRequest(analysisWidth: CameraFormatPolicy.analysisWidth(analysisWidth))
      if next != request { request = next; failed = false }
      preview.square = squareFrame ?? false
      evaluate()
      report()
    }
  }

  // Unmount stops the camera and releases every observer; the borrowed session stays open.
  func onDropView() {
    MainActor.assumeIsolated {
      guard !dropped else { return }
      dropped = true
      stopObserving()
      removeSessionObserver?(); removeSessionObserver = nil
      borrowed = nil
      sessionGeneration += 1
      evaluate()
    }
  }

  func takePhotoResult(options: TakePhotoOptions?) throws -> Promise<Variant_String_ScannerFailure> {
    let promise = Promise<Variant_String_ScannerFailure>()
    DispatchQueue.main.async { [weak self] in
      let code = MainActor.assumeIsolated { self?.captureRejection() } ?? .cameraUnavailable
      promise.resolve(withResult: .second(ScannerFailure(code: code)))
    }
    return promise
  }

  /// The framing live analysis will apply to raw video buffers, from the size actually
  /// delivered rather than the configured format; nil until the first frame.
  var analysisFraming: CameraFraming? {
    frameSize.flatMap { CameraFraming(buffer: $0, rotationAngle: captureAngle, square: squareFrame ?? false) }
  }

  var cameraState: CameraState {
    guard started != nil else { return .suspended }
    return streaming ? .ready : .starting
  }

  // MARK: - Lifecycle

  private func windowChanged() {
    guard !dropped else { return }
    let inWindow = MainActor.assumeIsolated { preview.window != nil }
    if inWindow == attached { return }
    attached = inWindow
    failed = false
    if inWindow { startObserving() } else { stopObserving() }
    evaluate()
  }

  private func startObserving() {
    foreground = !inBackground()
    removePermissionListener = permissions.addListener { [weak self] _ in
      guard let self else { return }
      failed = false
      evaluate()
    }
    lifecycleObservers = [
      notifications.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) {
        [weak self] _ in MainActor.assumeIsolated { self?.setForeground(false) }
      },
      notifications.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) {
        [weak self] _ in MainActor.assumeIsolated { self?.setForeground(true) }
      },
    ]
  }

  private func stopObserving() {
    removePermissionListener?(); removePermissionListener = nil
    lifecycleObservers.forEach(notifications.removeObserver)
    lifecycleObservers = []
  }

  private func setForeground(_ value: Bool) {
    guard foreground != value else { return }
    foreground = value
    failed = false
    evaluate()
  }

  /// Starts, restarts or stops the camera to match the current conditions.
  private func evaluate() {
    let sized = MainActor.assumeIsolated { preview.bounds.width > 0 && preview.bounds.height > 0 }
    let wanted = !dropped && active && attached && foreground && sized && permissions.status() == .granted
    if !wanted {
      stopCamera()
    } else if started != request && !failed {
      stopCamera()
      started = request
      camera.start(request)
    }
    report()
  }

  private func stopCamera() {
    guard started != nil else { return }
    camera.stop()
    started = nil
    reset()
  }

  private func reset() {
    streaming = false
    configuration = nil
    frameSize = nil
    rotationObservations = []
    rotation = nil
    MainActor.assumeIsolated { preview.video = nil }
  }

  private func handle(_ event: CameraSessionEvent) {
    guard !dropped, started != nil else { return }
    switch event {
    case .configured(let configuration):
      self.configuration = configuration
      trackRotation()
      MainActor.assumeIsolated { preview.video = configuration.video }
    case .streaming(let size):
      streaming = true
      frameSize = size
    case .frameSize(let size):
      frameSize = size
    case .interrupted:
      streaming = false
    case .failed(let code):
      started = nil
      failed = true
      reset()
      onError?(ScannerFailure(code: code))
    }
    report()
  }

  private func trackRotation() {
    guard let device = camera.device else { return }
    MainActor.assumeIsolated {
      let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: preview.previewLayer)
      rotation = coordinator
      rotationObservations = [
        coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) {
          [weak self] coordinator, _ in
          let angle = Double(coordinator.videoRotationAngleForHorizonLevelPreview)
          DispatchQueue.main.async { MainActor.assumeIsolated { self?.preview.rotationAngle = angle } }
        },
        coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.initial, .new]) {
          [weak self] coordinator, _ in
          let angle = Double(coordinator.videoRotationAngleForHorizonLevelCapture)
          DispatchQueue.main.async { self?.captureAngle = angle }
        },
      ]
      preview.rotate()
    }
  }

  /// Test seam for rotation without a camera device.
  func setRotation(preview previewAngle: Double, capture: Double) {
    MainActor.assumeIsolated { preview.rotationAngle = previewAngle }
    captureAngle = capture
  }

  // MARK: - Session borrowing

  private func updateSession() {
    let next = (session as? HybridScanSession)?.state
    guard next !== borrowed else { return }
    removeSessionObserver?(); removeSessionObserver = nil
    borrowed = next
    sessionGeneration += 1
    guard let next else { return }
    do {
      removeSessionObserver = try next.observe { [weak self, weak next] change in
        guard let self, let next, change.closed, borrowed === next else { return }
        sessionGeneration += 1
      }.unsubscribe
    } catch {
      // Only a closed session refuses observation; it stays unavailable for capture.
    }
  }

  // MARK: - Capture

  private func captureRejection() -> ScannerErrorCode {
    guard !dropped else { return .cameraUnavailable }
    guard let borrowed else { return .captureNotReady }
    if borrowed.snapshot().closed { return .processorDisposed }
    guard active, attached else { return .cameraUnavailable }
    guard permissions.status() == .granted else { return .cameraPermissionDenied }
    if failed { return .cameraUnavailable }
    guard cameraState == .ready else { return .captureNotReady }
    return .featureUnsupported
  }

  // MARK: - Callbacks

  private func report() {
    let state = cameraState
    if state != reportedState, let onCameraStateChange {
      reportedState = state
      onCameraStateChange(state)
    }
    if !captureReported, let onCaptureStateChange { captureReported = true; onCaptureStateChange(.unavailable) }
    if !autofocusReported, let onAutofocusChange { autofocusReported = true; onAutofocusChange(.inactive) }
    if !documentReported, let onDocumentStateChange { documentReported = true; onDocumentStateChange(.off) }
  }
}
