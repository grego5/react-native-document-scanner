import AVFoundation
import Foundation
import NitroModules
import UIKit
import Vision

/// Back-camera preview that reads one line inside `textRegion` with Vision text recognition. The
/// camera runs while `active`, in a window, sized and permitted. Video buffers arrive upright;
/// each one is recognized on the video queue inside the region (Vision's region of interest),
/// filtered and voted on there, and the agreed reading is published on main. Props, callbacks,
/// the reading and pending reads live on main; the capture session is configured on its own queue.
final class HybridTextCameraView: HybridTextCameraViewSpec, @unchecked Sendable {
  private let session = AVCaptureSession()
  private let output = AVCaptureVideoDataOutput()
  private let sessionQueue = DispatchQueue(label: "documentscanner.text.session")
  private let videoQueue = DispatchQueue(label: "documentscanner.text.video", qos: .userInitiated)
  private var frameDelegate: TextFrameDelegate?

  private lazy var preview: TextCameraPreviewView = MainActor.assumeIsolated {
    let preview = TextCameraPreviewView()
    preview.previewLayer.session = session
    preview.onWindowChange = { [weak self] in self?.evaluate() }
    preview.onResize = { [weak self] in self?.layoutChanged() }
    preview.onTap = { [weak self] point in self?.tapped(point) }
    return preview
  }

  var view: UIView { preview }
  var active: Bool? = nil
  var textRegion = TextRegion(x: 0.1, y: 0.44, width: 0.8, height: 0.12)
  var textGuide: TextGuide? = nil
  var textHighlight: Bool? = nil
  var guideColor: Double? = nil
  var allowedCharacters: String? = nil
  var textPattern: String? = nil
  var minLength: Double? = nil
  var tapToFocus: Bool? = nil
  /// Android only.
  var focusLock: Bool? = nil
  /// Android only.
  var zoom: Double? = nil
  var torch: TextTorchMode? = nil
  var recognitionRate: Double? = nil
  var analysisFrameWidth: Double? = nil
  var onCameraStateChange: ((_ state: CameraState) -> Void)? = nil { didSet { reportedState = nil; reportState() } }
  /// Android only: iOS has no lens hold and reports `inactive` once set.
  var onAutofocusChange: ((_ state: AutofocusState) -> Void)? = nil { didSet { onAutofocusChange?(.inactive) } }
  var onTextChange: ((_ reading: TextReading) -> Void)? = nil { didSet { reportedReading = nil; reportReading() } }
  var onError: ((_ error: ScannerFailure) -> Void)? = nil

  // Main only.
  private var dropped = false
  private var configuredPreset: AVCaptureSession.Preset?
  private var device: AVCaptureDevice?
  private var running = false
  private var streaming = false
  private var generation = 0
  private var region = TextRegion(x: 0.1, y: 0.44, width: 0.8, height: 0.12)
  private var filterKey: (String, String, Int)?
  private var reading: TextReading?
  private var reportedState: CameraState?
  private var reportedReading: TextReading?
  private var pendingReads: [UUID: Promise<Variant_ScannerFailure_TextReading>] = [:]
  private var removePermissionListener: (() -> Void)?
  private var rotation: AVCaptureDevice.RotationCoordinator?
  private var rotationObservations: [NSKeyValueObservation] = []
  private var tapRevision = 0
  /// Set while AVFoundation has interrupted the running session, such as in the background; the next frame clears it.
  private var interrupted = false
  /// Notification handlers for the current run only; the session queue alone changes them.
  private let sessionObservers = TextSessionObservers()

  /// Hand-off to the video queue.
  private let shared = NSLock()
  private var sharedState = TextAnalysisState()

  override init() {
    super.init()
    output.alwaysDiscardsLateVideoFrames = true
    output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
  }

  deinit {
    removePermissionListener?()
    nonisolated(unsafe) let session = session
    let observers = sessionObservers
    sessionQueue.async {
      observers.removeAll()
      if session.isRunning { session.stopRunning() }
    }
  }

  func afterUpdate() {
    let clamped = clampedTextRegion(textRegion)
    let key = (allowedCharacters ?? "", textPattern ?? "", Int(minLength ?? 1))
    let regionChanged = !sameTextRegion(clamped, region)
    var newFilter: TextLineFilter?
    if filterKey == nil || filterKey! != key {
      filterKey = key
      newFilter = TextLineFilter(allowedCharacters: key.0, pattern: key.1, minLength: key.2)
    }
    if regionChanged || newFilter != nil {
      region = clamped
      updateShared {
        $0.region = clamped
        if let newFilter { $0.filter = newFilter }
        $0.revision += 1
      }
      clearReading()
      if regionChanged { aimFocus() }
      if newFilter?.patternInvalid == true { onError?(ScannerFailure(code: .inputInvalid)) }
    }
    let interval = Int64(1_000 / min(max(recognitionRate ?? 5, 1), 15))
    updateShared { $0.frameIntervalMs = interval }
    MainActor.assumeIsolated {
      preview.overlay.update(region: region, guide: textGuide ?? TextGuide.none, highlight: textHighlight == true,
                             color: Self.color(guideColor))
    }
    if running, configuredPreset != preset() { stop() }
    evaluate()
    applyTorch()
  }

  func readTextResult(timeoutMs: Double?) throws -> Promise<Variant_ScannerFailure_TextReading> {
    let promise = Promise<Variant_ScannerFailure_TextReading>()
    let timeout = min(max(timeoutMs ?? 1_500, 0), 30_000)
    DispatchQueue.main.async { [weak self] in
      guard let self, !dropped, running, !interrupted else {
        promise.resolve(withResult: .first(ScannerFailure(code: .cameraUnavailable)))
        return
      }
      if let reading {
        promise.resolve(withResult: .second(reading))
        return
      }
      let id = UUID()
      pendingReads[id] = promise
      DispatchQueue.main.asyncAfter(deadline: .now() + timeout / 1_000) { [weak self] in
        guard let self, let promise = pendingReads.removeValue(forKey: id) else { return }
        promise.resolve(withResult: .second(emptyReading()))
      }
    }
    return promise
  }

  func onDropView() {
    dropped = true
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
      $0.revision += 1
      $0.streamed = false
    }
    reportState()
    // AVFoundation objects are not Sendable; the session queue alone configures them.
    nonisolated(unsafe) let session = session
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
        session.commitConfiguration()
        // Registered here, after any earlier run's stop on this queue, so only this run's errors reach it.
        self?.observeSession(run: generation)
        session.startRunning()
        DispatchQueue.main.async { [weak self] in
          guard let self, running, self.generation == generation else { return }
          trackRotation()
          aimFocus()
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
      center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) {
        [weak self] _ in
        DispatchQueue.main.async { [weak self] in self?.interrupt(run: run) }
      },
      center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) {
        [weak self] _ in
        DispatchQueue.main.async { [weak self] in self?.endInterruption(run: run) }
      },
      // startRunning() reports a failed start here, as does a session that fails while running.
      center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) {
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
    interrupted = true
    updateShared { $0.paused = true }
    makeUnavailable()
  }

  /// Main: frames count again. Reads stay unavailable until the first fresh frame restores readiness and clears
  /// `interrupted`.
  private func endInterruption(run: Int) {
    guard running, generation == run, isPaused() else { return }
    updateShared { $0.paused = false; $0.streamed = false }
  }

  private func isPaused() -> Bool {
    shared.lock()
    defer { shared.unlock() }
    return sharedState.paused
  }

  /// Main: the one transition that makes reads unavailable, for a stop, a failed start or an interruption. Frames
  /// already queued no longer count, the reading clears, and pending reads fail with `camera/unavailable`. While still
  /// running, readiness returns with the next fresh frame.
  private func makeUnavailable() {
    streaming = false
    updateShared { $0.revision += 1; $0.availability += 1; $0.streamed = false }
    clearReading()
    for promise in pendingReads.values {
      promise.resolve(withResult: .first(ScannerFailure(code: .cameraUnavailable)))
    }
    pendingReads.removeAll()
    reportState()
  }

  private func stop() {
    guard running else { return }
    running = false
    interrupted = false
    generation += 1
    frameDelegate = nil
    rotationObservations = []
    rotation = nil
    device = nil
    updateShared { $0.generation = nil; $0.paused = false }
    nonisolated(unsafe) let session = session
    let observers = sessionObservers
    sessionQueue.async {
      observers.removeAll()
      if session.isRunning { session.stopRunning() }
    }
    makeUnavailable()
  }

  private func layoutChanged() {
    let size = MainActor.assumeIsolated { preview.bounds.size }
    updateShared { $0.viewSize = size; $0.revision += 1 }
    clearReading()
    aimFocus()
    evaluate()
  }

  private func trackRotation() {
    MainActor.assumeIsolated {
      guard let device = self.device else { return }
      let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: preview.previewLayer)
      rotation = coordinator
      rotationObservations = [
        coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) { [weak self] c, _ in
          let angle = c.videoRotationAngleForHorizonLevelPreview
          DispatchQueue.main.async {
            guard let self else { return }
            MainActor.assumeIsolated {
              if let connection = self.preview.previewLayer.connection, connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
              }
            }
          }
        },
        coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.initial, .new]) { [weak self] c, _ in
          let angle = c.videoRotationAngleForHorizonLevelCapture
          guard let self else { return }
          // Upright buffers keep the region mapping and Vision's orientation trivial.
          sessionQueue.async { [self] in
            if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
              connection.videoRotationAngle = angle
            }
          }
          updateShared { $0.revision += 1 }
        },
      ]
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

  // MARK: - Recognition

  private func updateShared(_ change: (inout TextAnalysisState) -> Void) {
    shared.lock()
    change(&sharedState)
    shared.unlock()
  }

  /// Video queue.
  fileprivate func recognize(_ buffer: CMSampleBuffer, generation: Int) {
    guard let pixels = CMSampleBufferGetImageBuffer(buffer) else { return }
    let nowMs = Int64(CACurrentMediaTime() * 1_000)
    shared.lock()
    guard sharedState.generation == generation else {
      shared.unlock()
      return
    }
    // Frames still queued when the session was interrupted do not count until the interruption ends.
    guard !sharedState.paused else {
      shared.unlock()
      return
    }
    let state = sharedState
    let firstFrame = !sharedState.streamed
    sharedState.streamed = true
    let throttled = nowMs - sharedState.lastFrameMs < sharedState.frameIntervalMs
    if !throttled { sharedState.lastFrameMs = nowMs }
    shared.unlock()
    if firstFrame {
      let availability = state.availability
      DispatchQueue.main.async { [weak self] in self?.firstFrame(generation: generation, availability: availability) }
    }
    guard !throttled else { return }
    var analyzer = videoAnalyzer
    if analyzer.revision != state.revision {
      analyzer.votes.clear()
      analyzer.outline = nil
      analyzer.revision = state.revision
    }
    let bufferSize = CGSize(width: CVPixelBufferGetWidth(pixels), height: CVPixelBufferGetHeight(pixels))
    let crop = textRegionInBuffer(state.region, view: state.viewSize, buffer: bufferSize)
    guard crop.width * bufferSize.width >= 16, crop.height * bufferSize.height >= 16 else {
      videoAnalyzer = analyzer
      return
    }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false
    // Vision's origin is bottom left.
    request.regionOfInterest = CGRect(x: crop.minX, y: 1 - crop.maxY, width: crop.width, height: crop.height)
    var accepted: TextLineFilter.Candidate?
    do {
      try VNImageRequestHandler(cvPixelBuffer: pixels, orientation: .up).perform([request])
      // Boxes are normalized to the region of interest, bottom-left origin; flip to top-left.
      let lines = (request.results ?? []).compactMap { observation -> RecognizedLine? in
        guard let candidate = observation.topCandidates(1).first else { return nil }
        let box = observation.boundingBox
        return RecognizedLine(words: Self.words(candidate, line: box), top: 1 - box.maxY, bottom: 1 - box.minY,
                              confidence: Double(candidate.confidence))
      }
      accepted = state.filter.select(lines, cropWidth: 1, cropHeight: 1)
    } catch {
      videoAnalyzer = analyzer
      return
    }
    let region = state.region
    let winner = analyzer.votes.add(accepted?.text, confidence: accepted?.confidence ?? 0, nowMs: nowMs)
    // The outline restarts at the newest frame that read the agreed text and otherwise keeps that text's last box; a
    // new winner this frame did not box has none yet. Vision's boxes are already fractions of the region of interest.
    if let winner, let accepted, accepted.text == winner.text {
      analyzer.outline = (winner.text, textBoundsInView(accepted, region: region, cropWidth: 1, cropHeight: 1))
    } else if analyzer.outline?.text != winner?.text {
      analyzer.outline = nil
    }
    let outline = analyzer.outline?.bounds
    videoAnalyzer = analyzer
    let revision = state.revision
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      shared.lock()
      let current = sharedState.revision == revision
      shared.unlock()
      if current, running { publish(winner, region: region, outline: outline) }
    }
  }

  /// Video queue only.
  private var videoAnalyzer = TextVideoAnalyzer()

  /// Each whitespace-separated word of `candidate` with its horizontal extent, so a reading can be the
  /// piece of the line under the region's centre. A word Vision cannot box takes its share of the line.
  private static func words(_ candidate: VNRecognizedText, line: CGRect) -> [RecognizedWord] {
    let string = candidate.string
    let count = Double(max(string.count, 1))
    var words: [RecognizedWord] = []
    var index = string.startIndex
    while index < string.endIndex {
      guard let start = string[index...].firstIndex(where: { !$0.isWhitespace }) else { break }
      let end = string[start...].firstIndex(where: \.isWhitespace) ?? string.endIndex
      let range = start..<end
      if let box = (try? candidate.boundingBox(for: range))?.boundingBox, box.width > 0 {
        words.append(RecognizedWord(text: String(string[range]), left: box.minX, right: box.maxX))
      } else {
        let from = Double(string.distance(from: string.startIndex, to: start)) / count
        let to = Double(string.distance(from: string.startIndex, to: end)) / count
        words.append(RecognizedWord(text: String(string[range]), left: line.minX + from * line.width,
                                    right: line.minX + to * line.width))
      }
      index = end
    }
    return words
  }

  /// Main: the first frame of this run since the last unavailable transition has arrived.
  private func firstFrame(generation: Int, availability: Int) {
    shared.lock()
    let current = sharedState.availability == availability && !sharedState.paused
    shared.unlock()
    guard current, running, self.generation == generation, !streaming else { return }
    streaming = true
    interrupted = false
    reportState()
  }

  private func publish(_ winner: TextVotes.Winner?, region: TextRegion, outline: TextRegion?) {
    reading = winner.map {
      TextReading(text: $0.text, confidence: $0.confidence, votes: Double($0.votes), region: region, bounds: outline)
    }
    if let reading {
      for promise in pendingReads.values { promise.resolve(withResult: .second(reading)) }
      pendingReads.removeAll()
    }
    reportReading()
  }

  private func clearReading() {
    reading = nil
    reportReading()
  }

  private func emptyReading() -> TextReading { TextReading(text: "", confidence: 0, votes: 0, region: region, bounds: nil) }

  private func reportReading() {
    guard !dropped else { return }
    let next = reading ?? emptyReading()
    MainActor.assumeIsolated { preview.overlay.showText(next.bounds) }
    if let last = reportedReading, last.text == next.text, last.votes == next.votes,
       sameTextBounds(last.bounds, next.bounds) { return }
    reportedReading = next
    onTextChange?(next)
  }

  private func reportState() {
    guard !dropped else { return }
    let state: CameraState = !running ? .suspended : streaming ? .ready : .starting
    guard state != reportedState else { return }
    reportedState = state
    onCameraStateChange?(state)
  }

  private static func color(_ value: Double?) -> UIColor {
    let argb = UInt32(truncatingIfNeeded: Int64(value ?? Double(0xFF61_E1CF)))
    return UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                   blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
  }
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
  var region = TextRegion(x: 0.1, y: 0.44, width: 0.8, height: 0.12)
  var viewSize = CGSize.zero
  var filter = TextLineFilter(allowedCharacters: "", pattern: "", minLength: 1)
  /// Bumped whenever votes must restart: a new region, filter, layout, rotation or binding.
  var revision = 0
  var lastFrameMs: Int64 = 0
  var frameIntervalMs: Int64 = 200
  var streamed = false
  /// Bumped by each unavailable transition, so a first-frame callback queued before one cannot restore readiness.
  var availability = 0
  /// Set from an interruption until AVFoundation ends it; frames are dropped meanwhile.
  var paused = false
}

private struct TextVideoAnalyzer {
  var votes = TextVotes()
  /// The outline of the text being read, as view fractions.
  var outline: (text: String, bounds: TextRegion)?
  var revision = -1
}

private final class TextFrameDelegate: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
  weak var owner: HybridTextCameraView?
  let generation: Int

  init(owner: HybridTextCameraView, generation: Int) {
    self.owner = owner
    self.generation = generation
  }

  func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
    owner?.recognize(sampleBuffer, generation: generation)
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

/// Draws the region's guide and highlights the text being read, each when asked; nothing otherwise.
final class TextRegionOverlay: UIView {
  private let textOutline = CAShapeLayer()
  private let crosshair = CAShapeLayer()
  private var region: TextRegion?
  private var text: TextRegion?
  private var guide = TextGuide.none
  private var highlight = false

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    crosshair.lineWidth = 1.5
    crosshair.lineCap = .round
    crosshair.lineJoin = .round
    crosshair.fillColor = nil
    layer.addSublayer(crosshair)
    layer.addSublayer(textOutline)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  func update(region: TextRegion, guide: TextGuide, highlight: Bool, color: UIColor) {
    self.region = region
    self.guide = guide
    self.highlight = highlight
    crosshair.strokeColor = color.cgColor
    crosshair.fillColor = color.cgColor
    textOutline.fillColor = color.withAlphaComponent(0.35).cgColor
    setNeedsLayout()
  }

  /// The text being read, as fractions of the view, or nil for none.
  func showText(_ bounds: TextRegion?) {
    guard !sameTextBounds(bounds, text) else { return }
    text = bounds
    setNeedsLayout()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    defer { CATransaction.commit() }
    guard bounds.width > 0, bounds.height > 0 else {
      crosshair.path = nil
      textOutline.path = nil
      return
    }
    textOutline.frame = bounds
    // Text whose centre has left the region is no longer what is read; the reading outlives it by its votes, but the
    // highlight goes with it.
    if highlight, let text, let region,
       (region.x...(region.x + region.width)).contains(text.x + text.width / 2),
       (region.y...(region.y + region.height)).contains(text.y + text.height / 2) {
      let box = CGRect(x: text.x * bounds.width, y: text.y * bounds.height,
                       width: text.width * bounds.width, height: text.height * bounds.height).insetBy(dx: -3, dy: -3)
      textOutline.path = UIBezierPath(roundedRect: box, cornerRadius: 4).cgPath
    } else {
      textOutline.path = nil
    }
    guard let region, guide != .none else {
      crosshair.path = nil
      return
    }
    let rect = CGRect(x: region.x * bounds.width, y: region.y * bounds.height,
                      width: region.width * bounds.width, height: region.height * bounds.height)
    if guide == .line {
      let inset = rect.width * 0.08
      let y = rect.midY + min(14, rect.height / 3)
      let line = UIBezierPath()
      line.move(to: CGPoint(x: rect.minX + inset, y: y))
      line.addLine(to: CGPoint(x: rect.maxX - inset, y: y))
      crosshair.frame = bounds
      crosshair.path = line.cgPath
      return
    }
    if guide == .dot {
      // The layer fills what it strokes; the other guides are open lines with nothing to fill.
      let radius = 3.5
      crosshair.frame = bounds
      crosshair.path = UIBezierPath(ovalIn: CGRect(x: rect.midX - radius, y: rect.midY - radius,
                                                   width: radius * 2, height: radius * 2)).cgPath
      return
    }
    // The point readings are chosen nearest to.
    let arm = min(8, rect.height / 3, rect.width / 3)
    let cross = UIBezierPath()
    cross.move(to: CGPoint(x: rect.midX - arm, y: rect.midY))
    cross.addLine(to: CGPoint(x: rect.midX + arm, y: rect.midY))
    cross.move(to: CGPoint(x: rect.midX, y: rect.midY - arm))
    cross.addLine(to: CGPoint(x: rect.midX, y: rect.midY + arm))
    crosshair.frame = bounds
    crosshair.path = cross.cgPath
  }
}
