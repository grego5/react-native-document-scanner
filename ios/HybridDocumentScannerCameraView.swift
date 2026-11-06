import AVFoundation
import Foundation
import NitroModules
import UIKit

/// Rear-camera preview over one AVFoundation owner. The camera runs only while `active` is not
/// false, the view is in a window with a size, the app is in the foreground and permission is
/// granted; losing any of them stops it deterministically, keeping the view mounted. Nitro sets
/// props, mounts and unmounts on main, where all of this state lives.
///
/// The bound scan session is borrowed: stopping or detaching the camera never closes it, and a
/// replaced or closed session advances `sessionGeneration`, which later capture publication
/// checks. The camera starts only once `cornerDetectionModel` is prepared; a preparation failure keeps
/// it suspended until the source changes. Live guidance analyzes the camera's frames on its frame
/// queue and publishes here. Focus, torch and exposure run through `CameraControls`, which executes
/// their policies on the camera's session owner. `takePhoto` takes one still at a time and admits it
/// to the bound session as a page (`StillShot`); a restart or framing change waits for the shot.
final class HybridDocumentScannerCameraView: HybridDocumentScannerCameraViewSpec, @unchecked Sendable {
  private let camera: any CameraSessionControl
  private let permissions: CameraPermissions
  private let notifications: NotificationCenter
  private let inBackground: () -> Bool
  private let models: (String, @escaping (ScannerErrorCode?) -> Void) -> Void
  let detector: LiveDocumentDetector
  let controls: CameraControls

  private lazy var preview: CameraPreviewView = MainActor.assumeIsolated {
    let preview = CameraPreviewView()
    camera.attachPreview(preview.previewLayer)
    preview.onWindowChange = { [weak self] in self?.windowChanged() }
    preview.onResize = { [weak self] in self?.evaluate() }
    preview.onTap = { [weak self] point in self?.previewTapped(point) }
    return preview
  }

  var view: UIView { preview }
  var session: (any HybridScanSessionSpec) = HybridScanSession.placeholder()
  var imageCorrection = ImageCorrectionOptions(removeShadows: nil, filter: nil, simpleDewarp: nil)
  var outputOptions = ImageOutputOptions(jpegQuality: nil, format: nil, width: nil, height: nil, dpi: nil)
  var cornerDetectionModel: String? = nil
  var onError: ((_ error: ScannerFailure) -> Void)? = nil
  var active: Bool? = nil
  var guideLiveCorners: Bool? = nil
  var autoCrop: Bool? = nil
  var inferenceThrottling: Bool? = nil
  var minCornerScore: Double? = nil
  var lowQualityControl = LowQualityControl(enabled: true, captureBlurPercent: nil, requireFocus: nil)
  var tapToFocus: Bool? = nil
  var exposureCompensation: Double? = nil
  var minFrameRate: Double? = nil
  var torch: TorchMode? = nil
  /// Android only; AVFoundation's low-light boost is not applied.
  var lowLightBoost: Bool? = nil
  var captureMode: CaptureMode? = nil
  /// The largest bounded still, for text; false takes the standard 1440x1080-class one.
  var highResolutionPhoto: Bool? = nil
  var guideTransitionDuration: Double? = nil
  var analysisFrameWidth: Double? = nil
  var squareAspectRatio: Bool? = nil
  var showStats: Bool? = nil
  var guideColor: Double? = nil
  var captureFeedback: Bool? = nil
  var captureColor: Double? = nil
  // Each callback hears the current state when it is set, then changes only.
  var onCameraStateChange: ((_ state: CameraState) -> Void)? = nil { didSet { reportedState = nil } }
  var onCaptureStateChange: ((_ state: CameraCaptureState) -> Void)? = nil { didSet { reportedCapture = nil } }
  var onAutofocusChange: ((_ state: AutofocusState) -> Void)? = nil { didSet { reportedAutofocus = nil } }
  var onDocumentStateChange: ((_ state: DocumentState) -> Void)? = nil { didSet { reportedDocument = nil } }
  var onCaptureStage: ((_ stage: CaptureStage, _ waitReason: CaptureWaitReason?) -> Void)? = nil

  // Main only.
  private var dropped = false
  private var attached = false
  private var foreground = true
  private var activeApplied = true
  private var request = CameraRequest(analysisFrameWidth: CameraFormatPolicy.defaultAnalysisWidth)
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
  private var reportedCapture: CameraCaptureState?
  private var reportedAutofocus: AutofocusState?
  private var documentState = DocumentState.off
  private var reportedDocument: DocumentState?
  /// The source the camera waits for; nil before the first update.
  private var requestedModel: String?
  private var modelReady = false
  private var guideOn = true
  private var resolvedScore = 0.95
  private var borrowed: ScanSessionState?
  private var removeSessionObserver: (() -> Void)?
  /// Advances when the bound session is replaced or closes; capture publication must match it.
  private(set) var sessionGeneration = 0
  /// The one accepted shot, from takePhoto until its still is admitted or every file it wrote is
  /// retired; it keeps the busy slot even after its promise settles.
  private var shot: StillShot?
  /// Whether the shot's exposure has ended, so it is saving.
  private var shotExposed = false
  /// The square framing in effect; a change waits for the shot in flight.
  private var appliedSquare = false
  /// The newest guide with an outline, which a shot freezes as its live candidate.
  private var latestGuide: LiveGuideResult?

  init(camera: (any CameraSessionControl)? = nil, permissions: CameraPermissions = .shared,
       notifications: NotificationCenter = .default, inBackground: (() -> Bool)? = nil,
       models: ((String, @escaping (ScannerErrorCode?) -> Void) -> Void)? = nil,
       detector: LiveDocumentDetector? = nil, controls: CameraControls? = nil) {
    self.camera = camera ?? AVCameraSession()
    self.permissions = permissions
    self.notifications = notifications
    self.inBackground = inBackground ?? {
      MainActor.assumeIsolated { UIApplication.shared.applicationState == .background }
    }
    self.models = models ?? { CornerModelLoader.shared.load($0, completion: $1) }
    let detector = detector ?? LiveDocumentDetector()
    self.detector = detector
    let controls = controls ?? CameraControls(camera: self.camera)
    self.controls = controls
    super.init()
    self.camera.onEvent = { [weak self] in self?.handle($0) }
    self.camera.frameSink = { detector.analyze($0, sensorNs: $1) }
    // The controls own the camera reference; the camera's sinks must not own them back.
    self.camera.metadataSink = { [weak controls] in controls?.frameObserved($0) }
    self.camera.focusEvents = { [weak controls] in controls?.focusObserved($0) }
    detector.onResult = { [weak self] in self?.guideResult($0) }
    controls.onChange = { [weak self] in self?.report() }
    // Focus holds still while the photo exposes; torch changes wait for the whole shot.
    controls.shotInFlight = { [weak self] in self.map { $0.shot != nil && !$0.shotExposed } ?? false }
    controls.capturePending = { [weak self] in self?.shot != nil }
  }

  func afterUpdate() {
    MainActor.assumeIsolated {
      guard !dropped else { return }
      updateSession()
      let nextActive = active ?? true
      if nextActive != activeApplied { activeApplied = nextActive; failed = false }
      let next = CameraRequest(analysisFrameWidth: CameraFormatPolicy.analysisFrameWidth(analysisFrameWidth),
                               highResolutionPhoto: highResolutionPhoto ?? true)
      if next != request { request = next; failed = false }
      applyFraming()
      controls.update(controlSettings)
      updateGuide()
      updateModel()
      evaluate()
      report()
    }
  }

  // Unmount stops the camera and releases every observer; the borrowed session stays open.
  func onDropView() {
    MainActor.assumeIsolated {
      guard !dropped else { return }
      dropped = true
      camera.frameSink = nil
      camera.metadataSink = nil
      camera.focusEvents = nil
      stopObserving()
      removeSessionObserver?(); removeSessionObserver = nil
      borrowed = nil
      sessionGeneration += 1
      shot?.ticket.reject(.cameraUnavailable)
      evaluate()
    }
  }

  /// The quality gate is not implemented yet, so `options` changes nothing: an accepted shot fires
  /// at once.
  func takePhotoResult(options: TakePhotoOptions?) throws -> Promise<Variant_ScannerFailure_CameraCaptureResult> {
    let promise = Promise<Variant_ScannerFailure_CameraCaptureResult>()
    let ticket = CaptureTicket { result in
      switch result {
      case .success(let result): promise.resolve(withResult: .second(result))
      case .failure(let error): promise.resolve(withResult: .first(ScannerFailure(code: error.code)))
      }
    }
    DispatchQueue.main.async { [weak self] in
      guard let self else { ticket.reject(.cameraUnavailable); return }
      MainActor.assumeIsolated { shoot(ticket) }
    }
    return promise
  }

  /// The framing live analysis will apply to raw video buffers, from the size actually
  /// delivered rather than the configured format; nil until the first frame.
  var analysisFraming: CameraFraming? {
    frameSize.flatMap { CameraFraming(buffer: $0, rotationAngle: captureAngle, square: appliedSquare) }
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
    let wanted = !dropped && activeApplied && attached && foreground && sized && modelReady
      && permissions.status() == .granted
    if !wanted {
      stopCamera()
    } else if started != request && !failed && shot == nil {
      // A restart for a new request waits for the shot in flight; losing a condition does not.
      stopCamera()
      started = request
      detector.begin(enabled: guideOn)
      camera.start(request)
      setDocumentState(boundDocumentState)
    }
    report()
  }

  /// Any stop (detach, background, inactive, permission or unmount) invalidates the shot in
  /// flight at once; its slot stays busy until the camera settles it.
  private func stopCamera() {
    shot?.ticket.reject(.cameraUnavailable)
    guard started != nil else { return }
    camera.stop()
    started = nil
    reset()
  }

  private func reset() {
    detector.stop()
    controls.unbind()
    MainActor.assumeIsolated { preview.guide.clear() }
    setDocumentState(.off)
    latestGuide = nil
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
      controls.bind(configuration.capabilities)
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
          DispatchQueue.main.async {
            self?.captureAngle = angle
            self?.updateDetectorSettings()
          }
        },
      ]
      preview.rotate()
    }
  }

  /// Test seam for rotation without a camera device.
  func setRotation(preview previewAngle: Double, capture: Double) {
    MainActor.assumeIsolated { preview.rotationAngle = previewAngle }
    captureAngle = capture
    updateDetectorSettings()
  }

  // MARK: - Live guidance

  private var boundDocumentState: DocumentState {
    guard started != nil, guideOn else { return .off }
    return detector.state.hasFailed ? .unavailable : .searching
  }

  private func setDocumentState(_ state: DocumentState) {
    documentState = state
    MainActor.assumeIsolated { preview.guide.viewfinder = state == .searching }
  }

  /// Guide props apply without restarting the camera.
  private func updateGuide() {
    var score = minCornerScore ?? 0.95
    if !score.isFinite || score < 0 || score > 1 {
      onError?(ScannerFailure(code: .inputInvalid))
      score = 0.95
    }
    resolvedScore = score
    MainActor.assumeIsolated {
      let overlay = preview.guide
      overlay.color = guideColor.flatMap { $0.isFinite ? UInt32(truncatingIfNeeded: Int64($0)) : nil }
        ?? LiveGuideOverlay.defaultColor
      overlay.transitionNs = guideTransitionDuration.flatMap { $0.isFinite ? Int64(max(0, $0) * 1_000_000) : nil }
        ?? GuideTransition.defaultDurationNs
    }
    updateDetectorSettings()
    let nextGuide = guideLiveCorners ?? true
    guard nextGuide != guideOn else { return }
    guideOn = nextGuide
    latestGuide = nil
    guard started != nil else { return }
    detector.setEnabled(nextGuide)
    MainActor.assumeIsolated { preview.guide.clear() }
    setDocumentState(boundDocumentState)
  }

  private func updateDetectorSettings() {
    detector.update(LiveDocumentDetector.Settings(adaptive: inferenceThrottling ?? false, minCornerScore: resolvedScore,
                                                  rotationAngle: captureAngle, square: appliedSquare))
  }

  /// Applies the square prop unless a shot is in flight; the shot's end applies it.
  private func applyFraming() {
    guard shot == nil else { return }
    let square = squareAspectRatio ?? false
    MainActor.assumeIsolated { preview.square = square }
    guard square != appliedSquare else { return }
    appliedSquare = square
    updateDetectorSettings()
  }

  private func guideResult(_ result: LiveGuideResult) {
    guard !dropped, started != nil, guideOn else { return }
    latestGuide = result.corners != nil && result.framing != nil ? result : nil
    switch result.status {
    case .searching: setDocumentState(.searching)
    case .tracking: setDocumentState(.tracking)
    case .held: setDocumentState(.held)
    case .unavailable: setDocumentState(.unavailable)
    }
    MainActor.assumeIsolated { preview.guide.show(result) }
    report()
  }

  /// A new source stops the camera until it is prepared; a failure keeps it suspended until the
  /// source changes.
  private func updateModel() {
    let source = cornerDetectionModel ?? ""
    guard source != requestedModel else { return }
    requestedModel = source
    modelReady = false
    failed = false
    detector.invalidate()
    // Completions arrive on main.
    models(source) { [weak self] code in
      guard let self, !dropped, requestedModel == source, code != .modelSuperseded else { return }
      if let code {
        stopCamera()
        setDocumentState(.unavailable)
        onError?(ScannerFailure(code: code))
      } else {
        modelReady = true
      }
      evaluate()
    }
  }

  // MARK: - Controls

  /// Props as the controls take them: non-finite numbers count as omitted.
  private var controlSettings: CameraControls.Settings {
    CameraControls.Settings(
      tapToFocus: tapToFocus ?? true,
      exposureBias: exposureCompensation.flatMap { $0.isFinite ? $0 : nil } ?? 0,
      minFrameRate: minFrameRate.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? 0,
      torch: torch ?? .off)
  }

  /// A tap on the shown preview, as a device point.
  private func previewTapped(_ point: CGPoint) {
    guard !dropped, started != nil, streaming else { return }
    controls.tap(at: point)
  }

  // MARK: - Session borrowing

  private func updateSession() {
    let next = (session as? HybridScanSession)?.state
    guard next !== borrowed else { return }
    removeSessionObserver?(); removeSessionObserver = nil
    borrowed = next
    sessionGeneration += 1
    shot?.ticket.reject(.processorSuperseded)
    guard let next else { return }
    do {
      removeSessionObserver = try next.observe { [weak self, weak next] change in
        guard let self, let next, change.closed, borrowed === next else { return }
        sessionGeneration += 1
        shot?.ticket.reject(.processorDisposed)
        report()
      }.unsubscribe
    } catch {
      // Only a closed session refuses observation; it stays unavailable for capture.
    }
  }

  // MARK: - Capture

  /// Why a new shot cannot start now; nil when it can.
  private func captureRejection() -> ScannerErrorCode? {
    guard !dropped else { return .cameraUnavailable }
    if shot != nil { return .captureBusy }
    guard let borrowed else { return .captureNotReady }
    if borrowed.snapshot().closed { return .processorDisposed }
    guard activeApplied, attached else { return .cameraUnavailable }
    guard permissions.status() == .granted else { return .cameraPermissionDenied }
    if failed { return .cameraUnavailable }
    guard cameraState == .ready else { return .captureNotReady }
    return nil
  }

  /// Accepts one shot: freezes the bound session, render options, model selection, still
  /// settings, framing and live guide, asks live analysis for the shutter frame, and fires.
  private func shoot(_ ticket: CaptureTicket) {
    if let code = captureRejection() { ticket.reject(code); return }
    guard let borrowed else { return }
    var settings = StillSettings.resolve(mode: captureMode,
                                         zeroShutterLag: configuration?.zeroShutterLag ?? false)
    settings.rotationAngle = captureAngle
    // Only an outline seen through the framing the still is shot in; one from before a rotation
    // or square change would land elsewhere on the still.
    let framing = analysisFraming
    let guide = latestGuide.flatMap { result in
      result.corners.flatMap { corners in result.framing.flatMap { $0 == framing ? (corners: corners, framing: $0) : nil } }
    }
    let paper: PaperSize? = outputOptions.width == nil && outputOptions.height == nil ? nil :
      PaperSize(width: outputOptions.width ?? .nan, height: outputOptions.height ?? .nan)
    let renderOptions = DocumentRenderOptions(removeShadows: imageCorrection.removeShadows,
      jpegQuality: outputOptions.jpegQuality, filter: imageCorrection.filter, format: outputOptions.format,
      paperSize: paper, dpi: outputOptions.dpi, simpleDewarp: imageCorrection.simpleDewarp)
    let shot = StillShot(ticket: ticket, session: borrowed, options: renderOptions,
                         selection: try? CornerInference.shared.selection(), settings: settings,
                         square: appliedSquare, guide: guide, autoCrop: autoCrop ?? true)
    self.shot = shot
    shotExposed = false
    detector.requestFrame { [slot = shot.frame] in slot.fill($0) }
    CameraLog.logger.info(
      "still requested quality=\(settings.quality, privacy: .public) priority=\(String(describing: settings.priority), privacy: .public)")
    camera.capture(settings) { [weak self] event in
      guard let self else { shot.ticket.reject(.cameraUnavailable); return }
      shotEvent(shot, event)
    }
    report()
  }

  private func shotEvent(_ shot: StillShot, _ event: StillEvent) {
    switch event {
    case .exposed:
      guard self.shot === shot else { return }
      shotExposed = true
      report()
    case .failed(let code):
      shot.ticket.reject(code)
      finishShot(shot)
    case .delivered(let data):
      shotExposed = true
      // An invalidated shot writes nothing; the slot frees at once.
      guard !shot.ticket.isSettled else { return finishShot(shot) }
      shot.save(data) {
        DispatchQueue.main.async { [weak self] in self?.finishShot(shot) }
      }
      report()
    }
  }

  /// Frees the busy slot, then applies a restart or framing change that waited for the shot.
  private func finishShot(_ shot: StillShot) {
    guard self.shot === shot else { return }
    self.shot = nil
    shotExposed = false
    guard !dropped else { return }
    // Releases focus held for the shot and applies a torch change that waited for it.
    controls.afterCapture()
    applyFraming()
    evaluate()
  }

  private var captureState: CameraCaptureState {
    if shot != nil { return shotExposed ? .saving : .shooting }
    return captureRejection() == nil ? .ready : .unavailable
  }

  // MARK: - Callbacks

  private func report() {
    let state = cameraState
    if state != reportedState, let onCameraStateChange {
      reportedState = state
      onCameraStateChange(state)
    }
    let capture = captureState
    if capture != reportedCapture, let onCaptureStateChange {
      reportedCapture = capture
      onCaptureStateChange(capture)
    }
    let autofocus = controls.autofocus
    if autofocus != reportedAutofocus, let onAutofocusChange {
      reportedAutofocus = autofocus
      onAutofocusChange(autofocus)
    }
    if documentState != reportedDocument, let onDocumentStateChange {
      reportedDocument = documentState
      onDocumentStateChange(documentState)
    }
  }
}
