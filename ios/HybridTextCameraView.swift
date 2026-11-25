import AVFoundation
import Foundation
import NitroModules
import UIKit

/// Back-camera preview with a box marking `textRegion`, showing its `session`, whose captures it takes: the region's
/// sharpest recent frame, read by Vision (`TextCaptureWork`). The camera runs while `active`, in a window, sized and
/// permitted. Props and callbacks live on main; the capture session is configured on its own queue, and frames are
/// kept and read on the video queue.
final class HybridTextCameraView: HybridTextCameraViewSpec, TextSessionCamera, @unchecked Sendable {
  private let captureSession = AVCaptureSession()
  private let output = AVCaptureVideoDataOutput()
  private let sessionQueue = DispatchQueue(label: "documentscanner.text.session")
  private let videoQueue = DispatchQueue(label: "documentscanner.text.video", qos: .userInitiated)
  private var frameDelegate: TextFrameDelegate?

  private lazy var preview: TextCameraPreviewView = MainActor.assumeIsolated {
    let preview = TextCameraPreviewView()
    preview.previewLayer.session = captureSession
    preview.onWindowChange = { [weak self] in self?.evaluate() }
    preview.onResize = { [weak self] in self?.layoutChanged() }
    preview.onTap = { [weak self] point in self?.tapped(point) }
    preview.overlay.onLayout = { [weak self] box, label in self?.guideLaidOut(box: box, label: label) }
    return preview
  }

  var view: UIView { preview }
  /// The session as last set, and the one this view shows, which another view may hold instead.
  private var requestedSession: (any HybridTextSessionSpec)?
  private var attachedSession: HybridTextSession?
  private var checkedSession: (any HybridTextSessionSpec)?
  var session: any HybridTextSessionSpec {
    get { requestedSession ?? HybridTextSession() }
    set { requestedSession = newValue }
  }
  var active: Bool? = nil
  var textRegion = TextRegion(x: 0.1, y: 0.44, width: 0.8, height: 0.12)
  var guide: TextGuide? = nil
  var tapToFocus: Bool? = nil
  /// Android only.
  var focusLock: Bool? = nil
  /// Android only.
  var zoom: Double? = nil
  var torch: TextTorchMode? = nil
  var analysisFrameWidth: Double? = nil
  var onGuideLayout: ((_ layout: TextGuideLayout) -> Void)? = nil { didSet { emittedGuideLayout = nil; emitGuideLayout() } }
  var onError: ((_ error: ScannerFailure) -> Void)? = nil

  // Main only.
  private var dropped = false
  private var configuredPreset: AVCaptureSession.Preset?
  private var device: AVCaptureDevice?
  private var running = false
  private var streaming = false
  private var generation = 0
  private var region = TextRegion(x: 0.1, y: 0.44, width: 0.8, height: 0.12)
  /// What the scan does, which the guide shows and the attached session publishes: iOS has no blur or lock to show.
  private var status: TextScanStatus = .off
  /// Captures not yet settled, which the guide shows as `capturing`.
  private var capturesWaiting = 0
  /// The preview has taken this run's turn, so the crop can be mapped through it.
  private var previewTurned = false
  private var removePermissionListener: (() -> Void)?
  private var rotation: AVCaptureDevice.RotationCoordinator?
  private var rotationObservation: NSKeyValueObservation?
  private var tapRevision = 0
  /// The guide as last laid out, in points, and as last reported.
  private var guideLayout: GuideRects?
  private var emittedGuideLayout: GuideRects?
  /// Notification handlers for the current run only; the session queue alone changes them.
  private let sessionObservers = TextSessionObservers()

  /// Hand-off to the video queue.
  private let shared = NSLock()
  private var sharedState = TextAnalysisState()
  /// Video queue only.
  private let work = TextCaptureWork()

  override init() {
    super.init()
    output.alwaysDiscardsLateVideoFrames = true
    output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
  }

  deinit {
    removePermissionListener?()
    nonisolated(unsafe) let session = captureSession
    let observers = sessionObservers
    sessionQueue.async {
      observers.removeAll()
      if session.isRunning { session.stopRunning() }
    }
  }

  func afterUpdate() {
    syncSession()
    let clamped = clampedTextRegion(textRegion)
    if !sameTextRegion(clamped, region) {
      region = clamped
      publishCrop()
      aimFocus()
    }
    MainActor.assumeIsolated { preview.overlay.update(region: region, guide: guide) }
    if running, configuredPreset != preset() { stop() }
    evaluate()
    applyTorch()
  }

  /// Shows `requestedSession` when it changed; one another view shows is refused once.
  private func syncSession() {
    let next = requestedSession
    if next === checkedSession { return }
    checkedSession = next
    if let previous = attachedSession {
      previous.detach(self)
      // Its waiting captures, all of them its own until the next session asks, settle now from what the camera kept
      // while it showed them.
      let work = work
      let current = target()
      videoQueue.async { work.settle(target: current) }
    }
    attachedSession = nil
    guard let session = next as? HybridTextSession else { return }
    guard session.attach(self) else {
      onError?(ScannerFailure(code: .inputInvalid))
      return
    }
    attachedSession = session
    session.show(self, status: status)
  }

  func onDropView() {
    dropped = true
    attachedSession?.detach(self)
    attachedSession = nil
    stop()
    removePermissionListener?()
    removePermissionListener = nil
  }

  // MARK: - Lifecycle

  private func evaluate() {
    let attached = MainActor.assumeIsolated { preview.window != nil && preview.bounds.width > 0 && preview.bounds.height > 0 }
    if attached, removePermissionListener == nil, !dropped {
      removePermissionListener = CameraPermissions.shared.addListener { [weak self] _ in self?.evaluate() }
    }
    let permitted = CameraPermissions.shared.status() == .granted
    if !dropped, active != false, attached, permitted { start() } else { stop() }
  }

  private func preset() -> AVCaptureSession.Preset {
    let width = analysisFrameWidth ?? 1_280
    if width > 1_920 { return .hd4K3840x2160 }
    if width > 1_280 { return .hd1920x1080 }
    return .hd1280x720
  }

  private func start() {
    guard !running else { return }
    guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
      onError?(ScannerFailure(code: .cameraUnavailable))
      return
    }
    running = true
    generation += 1
    let generation = generation
    let preset = preset()
    configuredPreset = preset
    self.device = camera
    let delegate = TextFrameDelegate(owner: self, generation: generation)
    frameDelegate = delegate
    updateShared {
      $0.generation = generation
      $0.streamed = false
    }
    reportState()
    // AVFoundation objects are not Sendable; the session queue alone configures them.
    nonisolated(unsafe) let session = captureSession
    nonisolated(unsafe) let output = output
    nonisolated(unsafe) let device = camera
    let videoQueue = videoQueue
    sessionQueue.async { [weak self] in
      do {
        session.beginConfiguration()
        session.inputs.forEach(session.removeInput)
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw TextCameraBindError() }
        session.addInput(input)
        session.sessionPreset = session.canSetSessionPreset(preset) ? preset : .high
        if !session.outputs.contains(output) {
          guard session.canAddOutput(output) else { throw TextCameraBindError() }
          session.addOutput(output)
        }
        output.setSampleBufferDelegate(delegate, queue: videoQueue)
        // Buffers keep the sensor's own orientation, whatever the preview's turn, so the crop maps through the preview
        // alone and no frame in flight comes turned otherwise than the ones before it.
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(0) {
          connection.videoRotationAngle = 0
        }
        session.commitConfiguration()
        // Registered here, after any earlier run's stop on this queue, so only this run's errors reach it.
        self?.observeSession(run: generation)
        session.startRunning()
        DispatchQueue.main.async { [weak self] in
          guard let self, running, self.generation == generation else { return }
          trackRotation(run: generation)
          applyTorch()
        }
      } catch {
        session.commitConfiguration()
        DispatchQueue.main.async { [weak self] in self?.fail(run: generation) }
      }
    }
  }

  /// Session queue: watches the session for `run` only, replacing any earlier run's handlers. Each handler hops to
  /// main, where a newer generation ignores it.
  private func observeSession(run: Int) {
    // An interrupted session keeps running; frames count again only once AVFoundation ends the interruption.
    let center = NotificationCenter.default
    sessionObservers.replace(with: [
      center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: captureSession, queue: nil) {
        [weak self] _ in
        DispatchQueue.main.async { [weak self] in self?.interrupt(run: run) }
      },
      center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: captureSession, queue: nil) {
        [weak self] _ in
        DispatchQueue.main.async { [weak self] in self?.endInterruption(run: run) }
      },
      // startRunning() reports a failed start here, as does a session that fails while running.
      center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: captureSession, queue: nil) {
        [weak self] _ in
        DispatchQueue.main.async { [weak self] in self?.fail(run: run) }
      },
    ])
  }

  /// Main: the session of `run` failed; stops through the shared path and reports it.
  private func fail(run: Int) {
    guard running, generation == run else { return }
    stop()
    onError?(ScannerFailure(code: .cameraUnavailable))
  }

  /// Main: pauses frames until AVFoundation ends the interruption. Only a repeat while still paused is a duplicate;
  /// one arriving after the end but before a fresh frame pauses again.
  private func interrupt(run: Int) {
    guard running, generation == run, !isPaused() else { return }
    updateShared { $0.paused = true }
    makeUnavailable()
  }

  /// Main: frames count again. The first fresh frame restores readiness.
  private func endInterruption(run: Int) {
    guard running, generation == run, isPaused() else { return }
    updateShared { $0.paused = false; $0.streamed = false }
  }

  private func isPaused() -> Bool {
    shared.lock()
    defer { shared.unlock() }
    return sharedState.paused
  }

  /// Main: the one transition that makes the camera unavailable, for a stop, a failed start or an interruption.
  /// Frames already queued no longer count. While still running, readiness returns with the next fresh frame.
  private func makeUnavailable() {
    streaming = false
    updateShared { $0.availability += 1; $0.streamed = false }
    // Waiting captures read the sharpest kept frame before the kept frames go.
    let work = work
    let current = target()
    videoQueue.async {
      work.settle(target: current)
      work.clear()
    }
    reportState()
  }

  private func stop() {
    guard running else { return }
    running = false
    generation += 1
    frameDelegate = nil
    rotationObservation = nil
    rotation = nil
    device = nil
    previewTurned = false
    updateShared { $0.generation = nil; $0.paused = false; $0.crop = nil }
    nonisolated(unsafe) let session = captureSession
    let observers = sessionObservers
    sessionQueue.async {
      observers.removeAll()
      if session.isRunning { session.stopRunning() }
    }
    makeUnavailable()
  }

  private func layoutChanged() {
    publishCrop()
    aimFocus()
    evaluate()
  }

  private func trackRotation(run: Int) {
    MainActor.assumeIsolated {
      guard let device = self.device else { return }
      let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: preview.previewLayer)
      rotation = coordinator
      rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) {
        [weak self] coordinator, _ in
        let angle = coordinator.videoRotationAngleForHorizonLevelPreview
        // AVFoundation delivers preview-angle changes on main.
        MainActor.assumeIsolated {
          guard let self, running, generation == run else { return }
          if let connection = preview.previewLayer.connection, connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
          }
          previewTurned = true
          publishCrop()
          aimFocus()
        }
      }
    }
  }

  // MARK: - Focus and torch

  /// Continuous autofocus and exposure aimed at the region's centre, preferring near focus.
  private func aimFocus() {
    let centre = CGPoint(x: (region.x + region.width / 2), y: (region.y + region.height / 2))
    guard let point = devicePoint(viewFraction: centre) else { return }
    focus(at: point)
  }

  private func tapped(_ point: CGPoint) {
    guard tapToFocus != false, running else { return }
    focus(at: point)
    tapRevision += 1
    let revision = tapRevision
    DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
      guard let self, tapRevision == revision else { return }
      aimFocus()
    }
  }

  private func devicePoint(viewFraction: CGPoint) -> CGPoint? {
    MainActor.assumeIsolated {
      let bounds = preview.bounds
      guard bounds.width > 0, bounds.height > 0 else { return nil }
      return preview.devicePoint(CGPoint(x: viewFraction.x * bounds.width, y: viewFraction.y * bounds.height))
    }
  }

  private func focus(at point: CGPoint) {
    guard let current = device else { return }
    nonisolated(unsafe) let device = current
    sessionQueue.async {
      do {
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        if device.isAutoFocusRangeRestrictionSupported { device.autoFocusRangeRestriction = .near }
        if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = point }
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        if device.isExposurePointOfInterestSupported { device.exposurePointOfInterest = point }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
      } catch {}
    }
  }

  private func applyTorch() {
    guard let current = device, current.hasTorch else { return }
    let mode: AVCaptureDevice.TorchMode = torch == .on ? .on : .off
    nonisolated(unsafe) let device = current
    sessionQueue.async {
      guard device.torchMode != mode, device.isTorchModeSupported(mode) else { return }
      do {
        try device.lockForConfiguration()
        device.torchMode = mode
        device.unlockForConfiguration()
      } catch {}
    }
  }

  // MARK: - Frames

  private func updateShared(_ change: (inout TextAnalysisState) -> Void) {
    shared.lock()
    change(&sharedState)
    shared.unlock()
  }

  /// Main: where the region lies in the sensor-oriented buffers, as the preview shows them now, and the turn that shows
  /// it upright; none until the preview has turned for this run. Frames exposed before it keep to the crop they were
  /// exposed under, so they are not kept for this one.
  private func publishCrop() {
    let crop: CGRect? = MainActor.assumeIsolated {
      let bounds = preview.bounds
      guard running, previewTurned, bounds.width > 0, bounds.height > 0 else { return nil }
      let rect = CGRect(x: region.x * bounds.width, y: region.y * bounds.height,
                        width: region.width * bounds.width, height: region.height * bounds.height)
      let unit = preview.previewLayer.metadataOutputRectConverted(fromLayerRect: rect)
        .intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
      return unit.isNull || unit.isEmpty || !unit.width.isFinite || !unit.height.isFinite ? nil : unit
    }
    let turn = MainActor.assumeIsolated { preview.previewLayer.connection?.videoRotationAngle ?? 0 }
    // The clock frame times are given in.
    let since = CMClockGetTime(captureSession.synchronizationClock ?? CMClockGetHostTimeClock()).seconds
    updateShared {
      $0.region = region
      $0.crop = crop
      $0.turn = Int(turn.rounded())
      $0.since = since
      $0.revision += 1
      // A new crop supersedes any first-frame callback that has yet to make the view ready.
      if !streaming { $0.streamed = false }
    }
  }

  /// Video queue: keeps the frame's region and serves waiting captures; the first frame since the last unavailable
  /// transition that matches the preview's crop reports readiness.
  fileprivate func frameArrived(generation: Int, buffer: CVPixelBuffer, time: Double) {
    shared.lock()
    guard sharedState.generation == generation, !sharedState.paused, sharedState.crop != nil,
          time >= sharedState.since else {
      shared.unlock()
      return
    }
    let first = !sharedState.streamed
    sharedState.streamed = true
    let availability = sharedState.availability
    let target = targetLocked()
    shared.unlock()
    if first {
      let revision = target.revision
      DispatchQueue.main.async { [weak self] in
        self?.firstFrame(generation: generation, availability: availability, revision: revision)
      }
    }
    work.observe(buffer, time: time, target: target, now: ProcessInfo.processInfo.systemUptime)
  }

  private func targetLocked() -> TextFrameTarget {
    TextFrameTarget(region: sharedState.region, crop: sharedState.crop ?? .zero, turn: sharedState.turn,
                    revision: sharedState.revision)
  }

  private func target() -> TextFrameTarget {
    shared.lock()
    defer { shared.unlock() }
    return targetLocked()
  }

  // MARK: - Captures

  func capture(for session: HybridTextSession, timeout: Int, done: @escaping @Sendable (TextCapture?, ScannerErrorCode?) -> Void) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return done(nil, .cameraUnavailable) }
      startCapture(for: session, timeout: timeout, done: done)
    }
  }

  /// Main: `session`'s capture, by the workflow of `TextCaptureWork`; `timeout` in milliseconds. A session this view no
  /// longer shows, as after a swap the press crossed on its way here, is refused.
  private func startCapture(for session: HybridTextSession, timeout: Int,
                            done: @escaping @Sendable (TextCapture?, ScannerErrorCode?) -> Void) {
    guard running, streaming, !dropped, attachedSession === session else { return done(nil, .cameraUnavailable) }
    let seconds = Double(timeout) / 1_000
    let pending = PendingTextCapture(deadline: ProcessInfo.processInfo.systemUptime + seconds, done: done) {
      DispatchQueue.main.async { [weak self] in
        guard let self else { return }
        capturesWaiting -= 1
        reportState()
      }
    }
    // Its settlement's decrement is posted behind this block, so it always follows.
    capturesWaiting += 1
    reportState()
    let work = work
    // The crop is read on the video queue, where frames read it, so a frame queued ahead of the press with a newer crop
    // is not undone by the press bringing an older one.
    videoQueue.async { [weak self] in
      guard let self else { return pending.fail(.cameraUnavailable) }
      work.enqueue(pending, target: target(), now: ProcessInfo.processInfo.systemUptime)
    }
    // Past its deadline a capture takes the best frame so far, even when no frame arrives to serve it.
    videoQueue.asyncAfter(deadline: .now() + seconds) { [weak self, weak pending] in
      guard let self, let pending else { return }
      work.deadlineReached(for: pending, target: target(), now: ProcessInfo.processInfo.systemUptime)
    }
  }

  /// Main: the first frame since the last unavailable transition arrived for this crop.
  private func firstFrame(generation: Int, availability: Int, revision: Int) {
    shared.lock()
    let current = sharedState.availability == availability && sharedState.revision == revision && !sharedState.paused
    shared.unlock()
    guard current, running, self.generation == generation, !streaming else { return }
    streaming = true
    reportState()
  }

  private func reportState() {
    guard !dropped else { return }
    let next: TextScanStatus = !running ? .off : !streaming ? .starting : capturesWaiting > 0 ? .capturing : .ready
    guard next != status else { return }
    status = next
    preview.overlay.show(next)
    attachedSession?.show(self, status: next)
  }

  /// Main: the overlay laid the guide out at `box` and `label`, in points.
  private func guideLaidOut(box: CGRect, label: CGRect?) {
    guideLayout = GuideRects(box: box, label: label)
    emitGuideLayout()
  }

  private func emitGuideLayout() {
    guard !dropped, let layout = guideLayout, layout != emittedGuideLayout else { return }
    emittedGuideLayout = layout
    func frame(_ rect: CGRect) -> TextGuideFrame {
      TextGuideFrame(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height)
    }
    onGuideLayout?(TextGuideLayout(box: frame(layout.box), label: layout.label.map(frame)))
  }
}

/// The guide's box and label in points, compared before reporting since generated structs have no equality in Swift.
private struct GuideRects: Equatable {
  let box: CGRect
  let label: CGRect?
}

/// An ARGB number as a colour, or `fallback` when unset.
private func argbColor(_ value: Double?, _ fallback: UInt32) -> UIColor {
  let argb = value.flatMap { $0.isFinite ? UInt32(truncatingIfNeeded: Int64($0)) : nil } ?? fallback
  return UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                 blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
}

private struct TextCameraBindError: Error {}

/// One run's session notification handlers. Only the session queue touches them, so a run's handlers change between
/// that queue's stop of the previous run and its own start.
private final class TextSessionObservers: @unchecked Sendable {
  private var tokens: [NSObjectProtocol] = []

  func replace(with next: [NSObjectProtocol]) {
    removeAll()
    tokens = next
  }

  func removeAll() {
    tokens.forEach(NotificationCenter.default.removeObserver)
    tokens = []
  }
}

private struct TextAnalysisState {
  var generation: Int?
  /// The region; where it lies in the sensor-oriented buffers, normalized, nil until the preview has turned; the
  /// clockwise turn in degrees that shows it upright; when that crop began, on the capture session clock; and the
  /// revision that changes with them.
  var region = TextRegion(x: 0.1, y: 0.44, width: 0.8, height: 0.12)
  var crop: CGRect?
  var turn = 0
  var since = 0.0
  var revision = 0
  var streamed = false
  /// Bumped by each unavailable transition, so a first-frame callback queued before one cannot restore readiness.
  var availability = 0
  /// Set from an interruption until AVFoundation ends it; frames are dropped meanwhile.
  var paused = false
}

private final class TextFrameDelegate: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
  weak var owner: HybridTextCameraView?
  let generation: Int

  init(owner: HybridTextCameraView, generation: Int) {
    self.owner = owner
    self.generation = generation
  }

  func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
    guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    owner?.frameArrived(generation: generation, buffer: buffer,
                        time: CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds)
  }
}

/// The preview, aspect-filled over the whole view, with the region overlay above it. Main only.
final class TextCameraPreviewView: UIView {
  let previewLayer = AVCaptureVideoPreviewLayer()
  let overlay = TextRegionOverlay()
  var onWindowChange: (() -> Void)?
  var onResize: (() -> Void)?
  /// A tap, as an AVFoundation device point.
  var onTap: ((CGPoint) -> Void)?
  private var laidOutSize = CGSize.zero

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .black
    clipsToBounds = true
    previewLayer.videoGravity = .resizeAspectFill
    layer.addSublayer(previewLayer)
    overlay.isUserInteractionEnabled = false
    addSubview(overlay)
    addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    onWindowChange?()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    previewLayer.frame = bounds
    CATransaction.commit()
    overlay.frame = bounds
    if bounds.size != laidOutSize {
      laidOutSize = bounds.size
      onResize?()
    }
  }

  func devicePoint(_ viewPoint: CGPoint) -> CGPoint? {
    let point = previewLayer.captureDevicePointConverted(fromLayerPoint: viewPoint)
    guard point.x.isFinite, point.y.isFinite, (0...1).contains(point.x), (0...1).contains(point.y) else { return nil }
    return point
  }

  @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
    guard recognizer.state == .ended, let point = devicePoint(recognizer.location(in: self)) else { return }
    onTap?(point)
  }
}

/// The guide, `TextGuide`: the box marking the region, and the status's label centred below or above it, as wide as the
/// box at most and up to two lines. The label takes its direction from its text and is announced when it changes. A
/// hidden guide draws nothing, but its box is still laid out and reported to `onLayout`.
final class TextRegionOverlay: UIView {
  /// The box, to its stroke's outer edge, and the label while one shows, in points, each time they are laid out.
  var onLayout: ((_ box: CGRect, _ label: CGRect?) -> Void)?
  private let box = CAShapeLayer()
  private let label = GuideLabel()
  private var region: TextRegion?
  private var hidden = false
  private var radius: CGFloat = 8
  private var labelOffset: CGFloat = 12
  private var labelAbove = false
  private var guide: TextGuide?
  private var status: TextScanStatus = .off

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    box.fillColor = nil
    layer.addSublayer(box)
    label.numberOfLines = 2
    label.lineBreakMode = .byTruncatingTail
    label.textAlignment = .center
    label.adjustsFontForContentSizeCategory = true
    label.layer.masksToBounds = true
    label.isHidden = true
    addSubview(label)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  func update(region: TextRegion, guide: TextGuide?) {
    self.region = region
    self.guide = guide
    hidden = guide?.hidden == true
    radius = CGFloat(guide?.radius ?? 8)
    labelOffset = CGFloat(guide?.labelOffset ?? 12)
    labelAbove = guide?.labelPlacement == .top
    box.isHidden = hidden
    box.lineWidth = CGFloat(guide?.strokeWidth ?? 2)
    applyColor()
    let style = guide?.labelStyle
    let font = UIFont.systemFont(ofSize: CGFloat(style?.fontSize ?? 14), weight: style?.fontWeight == .bold ? .bold : .regular)
    label.font = UIFontMetrics.default.scaledFont(for: font)
    label.textColor = argbColor(style?.color, 0xFFFF_FFFF)
    label.backgroundColor = argbColor(style?.backgroundColor, 0x9900_0000)
    label.layer.cornerRadius = CGFloat(style?.radius ?? 6)
    label.inset = CGFloat(style?.padding ?? 6)
    applyLabel()
  }

  /// The camera's scan does `status`. iOS reports `off`, `starting`, `ready` and `capturing`: the box takes the
  /// `capturing` colour while a capture waits, and the label shows while the camera runs.
  func show(_ status: TextScanStatus) {
    guard status != self.status else { return }
    self.status = status
    applyColor()
    applyLabel()
  }

  private func applyColor() {
    let colors = guide?.colors
    let color = status == .capturing ? colors?.capturing ?? colors?.ready : colors?.ready
    box.strokeColor = argbColor(color, 0xFF61_E1CF).cgColor
  }

  private func applyLabel() {
    let labels = guide?.labels
    let own: String? = switch status {
    case .ready: labels?.ready
    case .capturing: labels?.capturing ?? labels?.ready
    default: nil
    }
    let text = hidden ? nil : own.flatMap { $0.isEmpty ? nil : $0 }
    if label.text != text {
      label.text = text
      if let text, window != nil { UIAccessibility.post(notification: .announcement, argument: text) }
    }
    label.isHidden = text == nil
    setNeedsLayout()
  }

  override func traitCollectionDidChange(_ previous: UITraitCollection?) {
    super.traitCollectionDidChange(previous)
    if previous?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory { setNeedsLayout() }
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    defer { CATransaction.commit() }
    guard let region, bounds.width > 0, bounds.height > 0 else {
      box.path = nil
      return
    }
    box.frame = bounds
    let rect = CGRect(x: region.x * bounds.width, y: region.y * bounds.height,
                      width: region.width * bounds.width, height: region.height * bounds.height)
    box.path = UIBezierPath(roundedRect: rect, cornerRadius: radius).cgPath
    let half = hidden ? 0 : box.lineWidth / 2
    let outer = rect.insetBy(dx: -half, dy: -half)
    var labelFrame: CGRect?
    if !label.isHidden {
      let size = label.sizeThatFits(CGSize(width: rect.width, height: bounds.height))
      let y = labelAbove ? outer.minY - labelOffset - size.height : outer.maxY + labelOffset
      let x = outer.midX - size.width / 2
      let frame = CGRect(x: min(max(x, 0), max(bounds.width - size.width, 0)),
                         y: min(max(y, 0), max(bounds.height - size.height, 0)), width: size.width, height: size.height)
      label.frame = frame
      labelFrame = frame
    }
    onLayout?(outer, labelFrame)
  }
}

/// A label padded by `inset` inside its background.
private final class GuideLabel: UILabel {
  var inset: CGFloat = 6 { didSet { setNeedsDisplay() } }

  override func drawText(in rect: CGRect) {
    super.drawText(in: rect.insetBy(dx: inset, dy: inset))
  }

  override func sizeThatFits(_ size: CGSize) -> CGSize {
    let inner = super.sizeThatFits(CGSize(width: max(size.width - 2 * inset, 0), height: .greatestFiniteMagnitude))
    return CGSize(width: min(inner.width + 2 * inset, size.width), height: inner.height + 2 * inset)
  }
}
