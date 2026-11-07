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
/// replaced or closed session rejects the capture's ticket, so a late shot cannot publish. The
/// camera starts only once `cornerDetectionModel` is prepared; a preparation failure keeps
/// it suspended until the source changes. Live guidance analyzes the camera's frames on its frame
/// queue and publishes here. Focus, torch and exposure run through `CameraControls`, which executes
/// their policies on the camera's session owner. `takePhoto` admits one capture at a time (`phase`);
/// its `CaptureGate` judges each analyzed frame's evidence until it shoots, and the shot is admitted
/// to the bound session as a page (`StillShot`). A restart or framing change waits for the capture.
final class HybridDocumentScannerCameraView: HybridDocumentScannerCameraViewSpec, @unchecked Sendable {
  private let camera: any CameraSessionControl
  private let permissions: CameraPermissions
  private let notifications: NotificationCenter
  private let inBackground: () -> Bool
  private let models: (String, @escaping (ScannerErrorCode?) -> Void) -> Void
  private let clock: () -> Int64
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
  var onLowLightBoostActiveChange: ((_ active: Bool) -> Void)? = nil { didSet { reportedBoost = nil } }
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
  private var retriedMediaReset = false
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
  private var boostActive = false
  private var reportedBoost: Bool?
  private var documentState = DocumentState.off
  private var reportedDocument: DocumentState?
  /// The source the camera waits for; nil before the first update.
  private var requestedModel: String?
  private var modelRequestID = UUID()
  private var modelReady = false
  private var guideOn = true
  private var resolvedScore = 0.95
  private var borrowed: ScanSessionState?
  private var removeSessionObserver: (() -> Void)?
  /// The one busy slot. A waiting capture frees it as soon as it is cancelled; a physical shot keeps
  /// it until its still is admitted or every file it wrote is retired, even after its promise settles.
  private var phase = CapturePhase.idle
  /// The square framing in effect; a change waits for the capture.
  private var appliedSquare = false
  /// The newest guide with an outline, which an ungated shot freezes as its live candidate.
  private var latestGuide: LiveGuideResult?
  private var statsScore: Double?
  private var evaluationScheduled = false

  init(camera: (any CameraSessionControl)? = nil, permissions: CameraPermissions = .shared,
       notifications: NotificationCenter = .default, inBackground: (() -> Bool)? = nil,
       models: ((String, @escaping (ScannerErrorCode?) -> Void) -> Void)? = nil,
       detector: LiveDocumentDetector? = nil, controls: CameraControls? = nil, clock: (() -> Int64)? = nil) {
    self.camera = camera ?? AVCameraSession()
    self.permissions = permissions
    self.notifications = notifications
    self.inBackground = inBackground ?? {
      MainActor.assumeIsolated { UIApplication.shared.applicationState == .background }
    }
    self.models = models ?? { CornerModelLoader.shared.load($0, completion: $1) }
    self.clock = clock ?? hostClockNs
    let detector = detector ?? LiveDocumentDetector()
    self.detector = detector
    let controls = controls ?? CameraControls(camera: self.camera)
    self.controls = controls
    super.init()
    self.camera.onEvent = { [weak self] in self?.handle($0) }
    self.camera.frameSink = { detector.analyze($0, sensorNs: $1) }
    // The controls own the camera reference; the camera's sinks must not own them back.
    self.camera.metadataSink = { [weak controls, weak detector] in
      controls?.frameObserved($0)
      detector?.frameMetadata($0)
    }
    self.camera.focusEvents = { [weak controls, weak detector] in
      controls?.focusObserved($0)
      detector?.focusObserved($0)
    }
    self.camera.lowLightBoostEvents = { [weak self] active in
      guard let self, !dropped else { return }
      boostActive = active
      report()
    }
    detector.onResult = { [weak self] in self?.guideResult($0) }
    controls.onChange = { [weak self] in
      self?.report()
      self?.scheduleCaptureEvaluation()
    }
    // Focus holds still while the photo exposes; torch changes wait for the whole capture.
    controls.shotInFlight = { [weak self] in
      guard let self, case .exposing = phase else { return false }
      return true
    }
    controls.capturePending = { [weak self] in self?.capturing ?? false }
  }

  func afterUpdate() {
    MainActor.assumeIsolated {
      guard !dropped else { return }
      updateSession()
      let nextActive = active ?? true
      if nextActive != activeApplied { activeApplied = nextActive; failed = false; retriedMediaReset = false }
      let next = CameraRequest(analysisFrameWidth: CameraFormatPolicy.analysisFrameWidth(analysisFrameWidth),
                               highResolutionPhoto: highResolutionPhoto ?? true)
      if next != request { request = next; failed = false; retriedMediaReset = false }
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
      evaluate()
    }
  }

  /// `options.skipLowQualityControl` skips the gate's checks for this capture alone.
  func takePhotoResult(options: TakePhotoOptions?) throws -> Promise<Variant_ScannerFailure_CameraCaptureResult> {
    let promise = Promise<Variant_ScannerFailure_CameraCaptureResult>()
    let ticket = CaptureTicket { result in
      switch result {
      case .success(let result): promise.resolve(withResult: .second(result))
      case .failure(let error): promise.resolve(withResult: .first(ScannerFailure(code: error.code)))
      }
    }
    let skip = options?.skipLowQualityControl == true
    DispatchQueue.main.async { [weak self] in
      guard let self else { ticket.reject(.cameraUnavailable); return }
      MainActor.assumeIsolated { admit(ticket, skipLowQualityControl: skip) }
    }
    return promise
  }

  func getCameraFeatures() -> CameraFeatureSupport? {
    CameraPermissions.onMain {
      configuration.map {
        CameraFeatureSupport(lowLightBoostSupported: $0.capabilities.lowLightBoost,
                             zeroShutterLagSupported: $0.zeroShutterLagSupported)
      }
    }
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
    retriedMediaReset = false
    if inWindow { startObserving() } else { stopObserving() }
    evaluate()
  }

  private func startObserving() {
    foreground = !inBackground()
    removePermissionListener = permissions.addListener { [weak self] _ in
      guard let self else { return }
      failed = false
      retriedMediaReset = false
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
    retriedMediaReset = false
    evaluate()
  }

  /// Starts, restarts or stops the camera to match the current conditions.
  private func evaluate() {
    let sized = MainActor.assumeIsolated { preview.bounds.width > 0 && preview.bounds.height > 0 }
    let available = !dropped && attached && foreground && sized && modelReady
      && permissions.status() == .granted
    if !available {
      stopCamera()
    } else if !activeApplied && !capturing {
      stopCamera(keepFrozen: true)
    } else if activeApplied && started != request && !failed && !capturing {
      // Inactivity and a new request wait for the shot; losing availability cancels it.
      stopCamera()
      started = request
      camera.start(request)
      setDocumentState(boundDocumentState)
    }
    report()
  }

  /// A stop invalidates the capture at once; a physical shot's slot stays busy until the camera
  /// settles it. Inactivity alone defers this stop until the capture finishes.
  private func stopCamera(keepFrozen: Bool = false) {
    if started != nil {
      camera.stop()
      started = nil
      reset()
    }
    cancelCapture(.cameraUnavailable, keepFrozen: keepFrozen)
  }

  private func reset() {
    statsScore = nil
    boostActive = false
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
      MainActor.assumeIsolated { preview.video = configuration.video }
    case .streaming(let size):
      frameSize = size
      if !streaming {
        streaming = true
        retriedMediaReset = false
        detector.begin(enabled: guideOn)
        trackRotation()
        setDocumentState(boundDocumentState)
        bindControlsIfReady()
        camera.requestFocusObservation()
      }
    case .frameSize(let size):
      frameSize = size
    case .analysisSlow:
      if streaming { onError?(ScannerFailure(code: .cameraAnalysisSlow)) }
    case .interrupted:
      statsScore = nil
      boostActive = false
      streaming = false
      frameSize = nil
      latestGuide = nil
      detector.stop()
      controls.unbind(preservingTorchDecision: true)
      rotationObservations = []
      rotation = nil
      MainActor.assumeIsolated { preview.guide.clear() }
      setDocumentState(.off)
      cancelCapture(.cameraUnavailable, reportFailure: true)
    case .mediaServicesReset:
      started = nil
      reset()
      cancelCapture(.cameraUnavailable, reportFailure: true)
      failed = retriedMediaReset
      if failed {
        onError?(ScannerFailure(code: .cameraUnavailable))
      } else {
        retriedMediaReset = true
        evaluate()
      }
    case .failed(let code):
      started = nil
      failed = true
      reset()
      cancelCapture(code, reportFailure: true)
      onError?(ScannerFailure(code: code))
    }
    report()
  }

  private func bindControlsIfReady() {
    guard streaming, !capturing, !controls.isBound, let configuration else { return }
    controls.bind(configuration.capabilities)
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
          DispatchQueue.main.async { [weak coordinator] in
            guard let self, let coordinator, rotation === coordinator else { return }
            MainActor.assumeIsolated { preview.rotationAngle = angle }
          }
        },
        coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.initial, .new]) {
          [weak self] coordinator, _ in
          let angle = Double(coordinator.videoRotationAngleForHorizonLevelCapture)
          DispatchQueue.main.async { [weak coordinator] in
            guard let self, let coordinator, rotation === coordinator else { return }
            captureAngle = angle
            updateDetectorSettings()
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
      preview.feedback.color = captureColor.flatMap { UInt32(exactly: $0) } ?? 0xFFFFFFFF
      if captureFeedback == false { preview.feedback.clear() }
      overlay.color = guideColor.flatMap { $0.isFinite ? UInt32(truncatingIfNeeded: Int64($0)) : nil }
        ?? LiveGuideOverlay.defaultColor
      overlay.transitionNs = guideTransitionDuration.flatMap { $0.isFinite ? Int64(max(0, $0) * 1_000_000) : nil }
        ?? GuideTransition.defaultDurationNs
    }
    updateDetectorSettings()
    let nextGuide = guideLiveCorners ?? true
    guard nextGuide != guideOn else { return }
    guideOn = nextGuide
    statsScore = nil
    latestGuide = nil
    guard started != nil, configuration == nil || streaming || controls.isBound else { return }
    detector.setEnabled(nextGuide)
    MainActor.assumeIsolated { preview.guide.clear() }
    setDocumentState(boundDocumentState)
  }

  private func updateDetectorSettings() {
    detector.update(LiveDocumentDetector.Settings(adaptive: inferenceThrottling ?? false, minCornerScore: resolvedScore,
                                                  rotationAngle: captureAngle, square: appliedSquare,
                                                  motionEvidence: waiting?.config.motionCheck ?? false))
  }

  /// Applies the square prop unless a capture is in flight; its end applies it.
  private func applyFraming() {
    guard !capturing else { return }
    let square = squareAspectRatio ?? false
    MainActor.assumeIsolated { preview.square = square }
    guard square != appliedSquare else { return }
    appliedSquare = square
    updateDetectorSettings()
  }

  private func guideResult(_ result: LiveGuideResult) {
    guard !dropped, started != nil, guideOn else { return }
    latestGuide = result.corners != nil && result.framing != nil ? result : nil
    if let score = result.score { statsScore = score }
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
    let id = UUID()
    modelRequestID = id
    modelReady = false
    failed = false
    retriedMediaReset = false
    detector.invalidate()
    // Completions arrive on main.
    models(source) { [weak self] code in
      guard let self, !dropped, modelRequestID == id, code != .modelSuperseded else { return }
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
      torch: torch ?? .off,
      lowLightBoost: lowLightBoost ?? true)
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
    cancelCapture(.processorSuperseded)
    guard let next else { return }
    do {
      removeSessionObserver = try next.observe { [weak self, weak next] change in
        guard let self, let next, change.closed, borrowed === next else { return }
        cancelCapture(.processorDisposed)
        // A restart that waited for the capture applies now.
        evaluate()
      }.unsubscribe
    } catch {
      // Only a closed session refuses observation; it stays unavailable for capture.
    }
  }

  // MARK: - Capture

  /// The busy slot: free, a capture its gate is judging, or a physical shot exposing, then saving.
  /// Admission, tap refusal, torch and exposure deferral and the reported capture state all derive
  /// from it.
  private enum CapturePhase {
    case idle
    case waiting(GatedCapture)
    case exposing(StillShot)
    case saving(StillShot)
  }

  private var waiting: GatedCapture? {
    if case .waiting(let capture) = phase { return capture }
    return nil
  }

  private var physical: StillShot? {
    switch phase {
    case .exposing(let shot), .saving(let shot): return shot
    case .idle, .waiting: return nil
    }
  }

  /// A capture waits or shoots; it holds the busy slot.
  private var capturing: Bool {
    if case .idle = phase { return false }
    return true
  }

  /// Why a new capture cannot start now; nil when it can.
  private func captureRejection() -> ScannerErrorCode? {
    guard !dropped else { return .cameraUnavailable }
    if capturing { return .captureBusy }
    guard let borrowed else { return .captureNotReady }
    if borrowed.snapshot().closed { return .processorDisposed }
    guard activeApplied, attached else { return .cameraUnavailable }
    guard permissions.status() == .granted else { return .cameraPermissionDenied }
    if failed { return .cameraUnavailable }
    guard cameraState == .ready else { return .captureNotReady }
    return nil
  }

  /// `lowQualityControl` as one admitted capture keeps it. `blurLimit` is a fraction of the frame
  /// width; zero turns the motion check off.
  struct GateConfig: Equatable {
    var lowQualityControl: Bool
    var blurLimit: Double
    var requireFocus: Bool
    /// `guideLiveCorners`: the capture needs a page, and focus must postdate its arrival.
    var guide: Bool

    var motionCheck: Bool { lowQualityControl && blurLimit > 0 }
    var documentRequired: Bool { lowQualityControl && guide }
  }

  /// Unmeasured starting point shared with Android: about two analysis pixels at 320 wide.
  static let defaultCaptureBlurPercent = 0.2
  /// How recent a frame must be for its moving lens to extend a focus wait.
  static let freshFocusFrameNs: Int64 = 500_000_000

  /// One admitted takePhoto while its gate waits. Admission freezes the bound session, render
  /// options, model selection, still settings and the gate's props; the shot takes its rotation,
  /// framing and guide from one moment when it fires.
  /// Main only.
  private final class GatedCapture: @unchecked Sendable {
    let ticket: CaptureTicket
    let session: ScanSessionState
    let options: DocumentRenderOptions
    let selection: CornerInference.Selection?
    let settings: StillSettings
    let autoCrop: Bool
    let config: GateConfig
    let skip: Bool
    var gate: CaptureGate
    var stability = CaptureStability()
    /// The next frame record to judge, and the newest judged one exposed after the latest focus change.
    var cursor: Int
    var latest: FrameRecord?
    var focusGeneration: Int64
    var focusChangedNs: Int64
    /// The latest light change the device applied, and the first judged frame exposed after it
    /// (nil until one is); frames before it show the earlier light.
    var lightChangedNs: Int64
    var lightFrameNs: Int64?
    let admittedLightNs: Int64
    var reason: GateWait?
    var deadline: DispatchWorkItem?

    init(ticket: CaptureTicket, session: ScanSessionState, options: DocumentRenderOptions,
         selection: CornerInference.Selection?, settings: StillSettings, autoCrop: Bool, config: GateConfig,
         skip: Bool, cursor: Int, focus: FocusSnapshot, lightChangedNs: Int64) {
      self.ticket = ticket; self.session = session; self.options = options; self.selection = selection
      self.settings = settings; self.autoCrop = autoCrop; self.config = config; self.skip = skip
      self.gate = CaptureGate(skipLowQualityControl: skip)
      self.cursor = cursor
      self.focusGeneration = focus.generation
      self.focusChangedNs = focus.changedNs
      self.lightChangedNs = lightChangedNs
      self.admittedLightNs = lightChangedNs
    }

    /// A light change the device applied while this capture waited.
    var lightChanged: Bool { lightChangedNs != admittedLightNs }
  }

  /// The gate's props as a capture admitted now keeps them.
  private var gateConfig: GateConfig {
    let control = lowQualityControl
    let enabled = control.enabled ?? true
    let percent = control.captureBlurPercent.flatMap { $0.isFinite ? $0 : nil } ?? Self.defaultCaptureBlurPercent
    return GateConfig(lowQualityControl: enabled, blurLimit: enabled ? max(0, percent) / 100 : 0,
                      requireFocus: enabled && (control.requireFocus ?? true), guide: guideOn)
  }

  /// Accepts one capture: freezes the bound session, render options, model selection, still
  /// settings and the gate's props, then lets its gate judge the frames that follow.
  private func admit(_ ticket: CaptureTicket, skipLowQualityControl: Bool) {
    if let code = captureRejection() { ticket.reject(code); return }
    guard let borrowed else { return }
    let settings = StillSettings.resolve(mode: captureMode, zeroShutterLag: configuration?.zeroShutterLag ?? false)
    let paper: PaperSize? = outputOptions.width == nil && outputOptions.height == nil ? nil :
      PaperSize(width: outputOptions.width ?? .nan, height: outputOptions.height ?? .nan)
    let renderOptions = DocumentRenderOptions(removeShadows: imageCorrection.removeShadows,
      jpegQuality: outputOptions.jpegQuality, filter: imageCorrection.filter, format: outputOptions.format,
      paperSize: paper, dpi: outputOptions.dpi, simpleDewarp: imageCorrection.simpleDewarp)
    let capture = GatedCapture(ticket: ticket, session: borrowed, options: renderOptions,
                               selection: try? CornerInference.shared.selection(), settings: settings,
                               autoCrop: autoCrop ?? true, config: gateConfig, skip: skipLowQualityControl,
                               cursor: detector.frames.cursor(), focus: controls.snapshot,
                               lightChangedNs: controls.lightChangedNs)
    phase = .waiting(capture)
    MainActor.assumeIsolated { preview.clearFrozen() }
    updateDetectorSettings()
    detector.onFrames = { [weak self] in self?.evaluateCapture() }
    CameraLog.logger.info(
      "capture requested skip=\(skipLowQualityControl, privacy: .public) config=\(String(describing: capture.config), privacy: .public)")
    emitStage(.waiting)
    report()
    evaluateCapture()
  }

  /// Coalesces events that arrive in one main turn, such as a focus change and a torch completion.
  private func scheduleCaptureEvaluation() {
    guard let capture = waiting, !evaluationScheduled else { return }
    evaluationScheduled = true
    DispatchQueue.main.async { [weak self, weak capture] in
      guard let self, let capture, waiting === capture else { return }
      evaluationScheduled = false
      evaluateCapture()
    }
  }

  /// One judgment of the waiting capture, on an event rather than a poll: new frame records, a
  /// focus, torch or exposure change, or a deadline. Records are judged in order, each once; those
  /// exposed before the latest focus change are skipped. Document detection continues while
  /// autofocus runs, but the four-frame motion and blur check starts from scratch once focus is
  /// accepted: until then, a focus change, a light change the device applied and a framing change
  /// each restart it. Motion only delays the shot or rejects it at the steady deadline; it never
  /// starts another scan. The evidence then goes to the capture's `CaptureGate`, whose decision
  /// alone scans, waits, shoots or rejects.
  ///
  /// iOS focus acceptance: AVFoundation reports neither a lock nor a failure, so a frame counts as
  /// focused when the device was not adjusting focus as it was delivered, and, for continuous
  /// autofocus with a page required, when the lens last settled after the page arrived. A scan that
  /// ended (unknown result) holds nothing by itself: the frames after it decide. A known failure (a
  /// refused or timed-out scan) is not focused. A frame without focus evidence is never focused. A
  /// fixed-focus device has no focus check.
  private func evaluateCapture() {
    guard let capture = waiting else { return }
    if capture.ticket.isSettled {
      release(capture)
      evaluate()
      return
    }
    let config = capture.config
    let capabilities = configuration?.capabilities ?? CameraCapabilities()
    let hasAutofocus = capabilities.continuousFocus || capabilities.focusScans
    let snapshot = controls.snapshot
    let nowNs = clock()
    let nowMs = nowNs / 1_000_000
    let blurLimit = config.blurLimit
    let motionCheck = config.motionCheck
    let documentRequired = config.documentRequired
    let focusCheck = config.lowQualityControl && (hasAutofocus || documentRequired)
    let waitsForFocus = focusCheck && hasAutofocus && config.requireFocus
    let skip = capture.skip
    let (frames, missed) = detector.frames.since(capture.cursor)
    if snapshot.generation != capture.focusGeneration {
      capture.focusGeneration = snapshot.generation
      capture.focusChangedNs = snapshot.changedNs
      capture.stability.reset()
      capture.latest = nil
    }
    if controls.lightChangedNs != capture.lightChangedNs {
      capture.lightChangedNs = controls.lightChangedNs
      capture.lightFrameNs = nil
      capture.stability.reset()
    }
    // Continuous autofocus may still hold a focus reached before the page arrived; a tap's or a
    // capture's scan is exempt, since it was aimed after the press or by the user.
    func predatesPage(_ frame: FrameRecord) -> Bool {
      guard documentRequired, hasAutofocus, snapshot.owner == nil, let arrived = frame.pageArrivedNs else { return false }
      return (frame.focusReachedNs ?? .min) < arrived
    }
    if missed && motionCheck { capture.stability.interrupt() }
    for frame in frames {
      capture.cursor = frame.sequence + 1
      guard frame.sensorNs >= capture.focusChangedNs else { continue }
      // A turn or a square change moves the page on the still; readiness starts over.
      if let previous = capture.latest, previous.framing != frame.framing { capture.stability.interrupt() }
      capture.latest = frame
      guard frame.sensorNs >= capture.lightChangedNs else { continue }
      if capture.lightFrameNs == nil { capture.lightFrameNs = frame.sensorNs }
      guard motionCheck else { continue }
      // A frame exposed while the lens moved does not continue the sequence; one without focus
      // evidence cannot tell.
      if waitsForFocus, !capture.gate.focusSkipped, let adjusting = frame.focusAdjusting, adjusting || predatesPage(frame) {
        capture.stability.interrupt(.unfocused)
        continue
      }
      capture.stability.observe(frame, requireDocument: documentRequired && !capture.gate.focusSkipped,
                                blurLimit: blurLimit)
    }
    // Evidence seen through another framing than the one the still would be shot in no longer holds.
    let framing = analysisFraming
    let latest = capture.latest.flatMap { $0.framing == framing ? $0 : nil }
    let scanningFrame = latest?.focusAdjusting == true
    let staleFocus = latest.map(predatesPage) ?? false
    // `quality` scans for each photo, at the page once one is found or the centre; `zsl` and
    // `latency` keep continuous autofocus and scan only for a page whose focus is stale, or in a
    // dark scene the torch does not light.
    let qualityScan = capture.settings.priority == .quality && (!documentRequired || latest != nil)
    let staleScan = staleFocus && !scanningFrame && (!documentRequired || latest?.guide != nil)
    let dark = (latest?.exposureIsoMs ?? 0) >= TorchPolicy.darkIsoMs && controls.torchApplied != true
    let darkScan = dark && !scanningFrame && (!documentRequired || latest != nil)
    let trigger: ScanTrigger? = qualityScan ? .quality : staleScan ? .staleFocus : darkScan ? .dark : nil
    let ownScan: OwnScan
    if snapshot.owner != .capture {
      ownScan = .none
    } else if snapshot.pending {
      ownScan = .scanning
    } else {
      switch snapshot.locked {
      case true?: ownScan = .locked
      case false?: ownScan = .failed
      case nil: ownScan = .settled
      }
    }
    let knownFailure = snapshot.owner != nil && !snapshot.pending && snapshot.locked == false
    let focused: Bool
    if !hasAutofocus || !config.requireFocus {
      focused = true
    } else if skip {
      focused = !snapshot.scanning(nowNs: nowNs)
    } else if snapshot.scanning(nowNs: nowNs) || staleFocus || knownFailure {
      focused = false
    } else {
      focused = latest?.focusAdjusting == false
    }
    // Frames judged before focus was accepted do not count toward steadiness: the sequence starts
    // with the first frame of accepted focus.
    if waitsForFocus && !skip && !capture.gate.focusSkipped && !focused { capture.stability.reset() }
    // A moving lens outlasts a focus deadline only while its frame is recent, so a stalled
    // analysis cannot hold the shot on an old record.
    let frameScanningUntil = latest.flatMap { frame -> Int64? in
      scanningFrame ? nowMs + (Self.freshFocusFrameNs - (nowNs - frame.sensorNs)) / 1_000_000 : nil
    }
    let focusingUntil: Int64? = !hasAutofocus ? nil
      : snapshot.scanning(nowNs: nowNs) ? .max : frameScanningUntil.flatMap { $0 > nowMs ? $0 : nil }
    // A light change the device applied counts once a frame exposed after it was judged.
    let lightReady = !controls.lightPending && (!capture.lightChanged || capture.lightFrameNs != nil)
    let evidence = CaptureEvidence(
      nowMs: nowMs, focusCheckEnabled: skip ? hasAutofocus && config.requireFocus : focusCheck,
      documentRequired: documentRequired, requireFocus: config.requireFocus, focusTimeoutMs: CaptureGate.focusTimeoutMs,
      documentFound: latest?.guide != nil, focused: focused, lightReady: lightReady,
      motionCheckEnabled: motionCheck,
      // The sequence must reach the newest frame, so one that stopped short of it is not steady.
      steady: capture.stability.stable && latest != nil && latest?.sensorNs == capture.stability.latestSensorNs,
      focusingUntilMs: focusingUntil,
      scanAvailable: !skip && waitsForFocus && capabilities.focusScans && controls.isBound
        && !(snapshot.owner == .tap && snapshot.scanHolds(nowNs: nowNs)),
      scanTrigger: trigger, ownScan: ownScan,
      frameSinceFocusChange: latest != nil)
    let decision = capture.gate.next(evidence)
    switch decision {
    case .startFocusScan(let trigger, let attempt):
      let point = latest.flatMap { frame in
        frame.guide.map { g in
          frame.framing.toBuffer(CGPoint(x: (g[0] + g[2] + g[4] + g[6]) / 4, y: (g[1] + g[3] + g[5] + g[7]) / 4))
        }
      } ?? CameraControls.centre
      CameraLog.logger.info(
        "capture focus scan \(attempt, privacy: .public) trigger=\(String(describing: trigger), privacy: .public)")
      // The new focus generation re-evaluates this capture.
      controls.capture(at: point)
    case .wait(let reason, let until):
      if capture.reason != reason {
        capture.reason = reason
        CameraLog.logger.info("capture waiting for \(String(describing: reason), privacy: .public)")
        onCaptureStage?(.waiting, reason.publicReason)
      }
      capture.deadline?.cancel()
      capture.deadline = nil
      if until != .max {
        let item = DispatchWorkItem { [weak self, weak capture] in
          guard let self, let capture, waiting === capture else { return }
          evaluateCapture()
        }
        capture.deadline = item
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(max(0, until - nowMs))), execute: item)
      }
    case .reject(let code, let reason):
      CameraLog.logger.info(
        "capture rejected \(String(describing: code), privacy: .public) waiting for \(String(describing: reason), privacy: .public) stableFrames=\(capture.stability.count, privacy: .public) break=\(String(describing: capture.stability.lastBreak), privacy: .public)")
      capture.ticket.reject(code)
      emitStage(.failed)
      release(capture)
      evaluate()
    case .shoot:
      if capture.gate.focusSkipped { CameraLog.logger.info("capture focus not settled; shooting") }
      shoot(capture, approved: latest)
    }
    report()
  }

  /// The capture stops waiting: its deadline and frame wake-ups end. The caller sets the next phase.
  private func endWaiting(_ capture: GatedCapture) {
    evaluationScheduled = false
    capture.deadline?.cancel()
    capture.deadline = nil
    detector.onFrames = nil
  }

  /// A waiting capture that never shot leaves the slot: it releases its focus scan and applies the
  /// torch, exposure and framing changes that waited for it. The caller re-evaluates the camera.
  private func release(_ capture: GatedCapture) {
    guard waiting === capture else { return }
    endWaiting(capture)
    phase = .idle
    updateDetectorSettings()
    guard !dropped else { return }
    controls.afterCapture()
    applyFraming()
  }

  /// The one cancellation path, for stop, interruption, failure, session replacement or close and
  /// unmount. A waiting capture rejects and frees its slot at once; a physical shot rejects and
  /// keeps its slot until the camera or the writer settles it. With `reportFailure` (interruption or
  /// runtime failure) the rejected request ends its stages with `failed`, once; otherwise its
  /// feedback is simply invalidated. Either way the invalidated shot's later callbacks report nothing.
  private func cancelCapture(_ code: ScannerErrorCode, keepFrozen: Bool = false, reportFailure: Bool = false) {
    MainActor.assumeIsolated {
      preview.feedback.clear()
      if !keepFrozen { preview.clearFrozen() }
    }
    let rejected: Bool
    switch phase {
    case .idle: return
    case .exposing(let shot), .saving(let shot):
      detector.cancelFrameRequest()
      rejected = shot.ticket.reject(code, invalidating: true)
    case .waiting(let capture):
      rejected = capture.ticket.reject(code)
      release(capture)
    }
    if rejected, reportFailure { emitStage(.failed) }
  }

  /// The gate's first shoot. The still's rotation, framing and live guide come from one moment, now:
  /// a gate that judged a page passes the frame it approved when that frame was seen through the
  /// current framing; otherwise the newest guide seen through it. Live analysis is asked for the
  /// shutter frame, and the still is taken with the settings frozen at admission.
  private func shoot(_ capture: GatedCapture, approved: FrameRecord?) {
    endWaiting(capture)
    let framing = analysisFraming
    var settings = capture.settings
    settings.rotationAngle = captureAngle
    let guide: (corners: LiveCorners, framing: CameraFraming)?
    if capture.config.documentRequired && !capture.skip {
      guide = approved.flatMap { frame in frame.guide.flatMap { frame.framing == framing ? ($0, frame.framing) : nil } }
    } else {
      guide = latestGuide.flatMap { result in
        result.corners.flatMap { corners in result.framing.flatMap { $0 == framing ? (corners: corners, framing: $0) : nil } }
      }
    }
    let still = StillShot(ticket: capture.ticket, session: capture.session, options: capture.options,
                          selection: capture.selection, settings: settings,
                          square: appliedSquare, guide: guide, autoCrop: capture.autoCrop)
    phase = .exposing(still)
    updateDetectorSettings()
    let previewFraming = MainActor.assumeIsolated { preview.framing }
    detector.requestFrame { [weak self, weak still] frame in
      guard let still, let sample = still.frame.fill(frame) else { return }
      DispatchQueue.main.async { [weak self, weak still] in
        guard let self, let still, physical === still, !still.ticket.isInvalidated,
              let previewFraming, preview.framing == previewFraming, let framing,
              sample.orientation == framing.orientation,
              PixelSize(sample.image.width, sample.image.height) == framing.framed else { return }
        let rotation = (previewFraming.rotation - framing.rotation + 360) % 360
        preview.freeze(sample.image, rotation: rotation, id: still.id)
      }
    }
    CameraLog.logger.info(
      "still requested quality=\(settings.quality, privacy: .public) priority=\(String(describing: settings.priority), privacy: .public)")
    emitStage(.shooting)
    camera.capture(settings) { [weak self] event in
      guard let self else { still.ticket.reject(.cameraUnavailable); return }
      shotEvent(still, event)
    }
    report()
  }

  private func shotEvent(_ shot: StillShot, _ event: StillEvent) {
    guard physical === shot else { return }
    switch event {
    case .exposed:
      guard case .exposing = phase else { return }
      markSaving(shot)
      if !shot.ticket.isInvalidated { emitStage(.captured) }
      report()
    case .failed(let code):
      if shot.ticket.reject(code), !shot.ticket.isInvalidated { failShotFeedback(shot) }
      finishShot(shot)
    case .delivered(let data):
      markSaving(shot)
      // An invalidated shot writes nothing; the slot frees at once.
      guard !shot.ticket.isSettled else { return finishShot(shot) }
      let id = shot.id
      shot.save(data, previewReleased: { [weak self] in
        self?.preview.clearFrozen(id: id)
      }) { [weak self] outcome in
        DispatchQueue.main.async { [weak self] in
          guard let self, physical === shot else { return }
          if outcome == .failed, !shot.ticket.isInvalidated { failShotFeedback(shot) }
          finishShot(shot)
        }
      }
      report()
    }
  }

  private func failShotFeedback(_ shot: StillShot) {
    MainActor.assumeIsolated { preview.clearFrozen(id: shot.id) }
    emitStage(.failed)
  }

  /// The shot's exposure ended: focus may move again, and the capture state is saving.
  private func markSaving(_ shot: StillShot) {
    guard case .exposing(let current) = phase, current === shot else { return }
    phase = .saving(shot)
  }

  /// Frees the busy slot, then applies the changes and the restart that waited for the shot. A
  /// camera that stays active shows live video again; the session keeps its retained frame for
  /// review. An inactive one keeps the frozen frame until its usual release.
  private func finishShot(_ shot: StillShot) {
    guard physical === shot else { return }
    detector.cancelFrameRequest()
    phase = .idle
    guard !dropped else { return }
    // Releases focus held for the shot and applies torch and exposure changes that waited for it.
    if controls.isBound {
      controls.afterCapture()
    } else {
      bindControlsIfReady()
      if controls.isBound {
        camera.requestFocusObservation()
      }
    }
    applyFraming()
    evaluate()
    if activeApplied { MainActor.assumeIsolated { preview.clearFrozen(id: shot.id) } }
  }

  private var captureState: CameraCaptureState {
    switch phase {
    case .exposing: return .shooting
    case .saving: return .saving
    case .waiting: return .waiting
    case .idle: return captureRejection() == nil ? .ready : .unavailable
    }
  }

  // MARK: - Callbacks

  private func emitStage(_ stage: CaptureStage) {
    if captureFeedback != false {
      MainActor.assumeIsolated { preview.feedback.show(stage) }
    }
    onCaptureStage?(stage, nil)
  }

  private func report() {
    MainActor.assumeIsolated { preview.showStats(showStats == true ? statsRows : nil) }
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
    if boostActive != reportedBoost, let onLowLightBoostActiveChange {
      reportedBoost = boostActive
      onLowLightBoostActiveChange(boostActive)
    }
    if documentState != reportedDocument, let onDocumentStateChange {
      reportedDocument = documentState
      onDocumentStateChange(documentState)
    }
  }

  private var statsRows: [String] {
    let missing = "-"
    let frame = analysisFraming.map { "\($0.framed.width)x\($0.framed.height)" } ?? missing
    let score = statsScore.map { String(format: "%.4f", $0) } ?? missing
    let capture = configuration.map { config -> String in
      switch StillSettings.resolve(mode: captureMode, zeroShutterLag: config.zeroShutterLag).priority {
      case .balanced: return "ZSL"
      case .quality: return "quality"
      case .speed: return "latency"
      }
    } ?? missing
    let torchState = controls.torchApplied.map { $0 ? "on" : "off" } ?? missing
    let boost = configuration.map {
      if !$0.capabilities.lowLightBoost { return "unavailable" }
      if boostActive { return "active" }
      return lowLightBoost == false ? "off" : "auto (idle)"
    } ?? missing
    return [
      "Camera: \(cameraState.stringValue)",
      "AF: \(controls.autofocus.stringValue)",
      "Page: \(documentState.stringValue)",
      "Score: \(score)",
      "Frame: \(frame)",
      "Mode: \(capture)",
      "Torch: \(torchState)",
      "Capture: \(captureState.stringValue)",
      "Boost: \(boost)",
    ]
  }
}
