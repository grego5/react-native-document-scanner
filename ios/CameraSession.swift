import AVFoundation
import Foundation
import os

/// What the view asks of its camera owner. Equal requests keep the configured session.
struct CameraRequest: Equatable, Sendable {
  let analysisFrameWidth: Int
  /// The largest bounded still, as `scanner` mode takes; false takes `camera` mode's standard one.
  var highResolutionPhoto = true
}

/// The supported configuration a started session runs with.
struct CameraConfiguration: Equatable, Sendable {
  let photo: PixelSize
  let video: PixelSize
  var capabilities = CameraCapabilities()
  /// Whether the photo output keeps a zero-shutter-lag buffer in this configuration.
  var zeroShutterLag = false
}

/// One still's progress, on main: `exposed` once its exposure ends, then exactly one terminal
/// `delivered` (the encoded still) or `failed`.
enum StillEvent: @unchecked Sendable {
  case exposed
  /// The still finished successfully.
  case delivered(Data)
  case failed(ScannerErrorCode)
}

enum CameraSessionEvent: Equatable {
  /// The session is configured and starting; preview and framing may use the configuration.
  case configured(CameraConfiguration)
  /// The first frame after a start or an interruption arrived, with its delivered size.
  case streaming(PixelSize)
  /// A later frame of the same run arrived at a different size.
  case frameSize(PixelSize)
  /// The system interrupted the running session; `streaming` follows when it resumes.
  case interrupted
  /// No bounded configuration, no camera, or a runtime failure; the session has stopped.
  case failed(ScannerErrorCode)
}

/// The view's only camera dependency, confined to main. Events for a stopped or replaced
/// start are never delivered.
protocol CameraSessionControl: AnyObject {
  var onEvent: ((CameraSessionEvent) -> Void)? { get set }
  /// Live analysis of each frame of the armed run, called on the camera's serial frame queue with
  /// the buffer, which it borrows only for the call, and its presentation time in host-clock
  /// nanoseconds.
  var frameSink: ((CVPixelBuffer, Int64) -> Void)? { get set }
  /// Each armed frame's exposure and focus evidence, on the frame queue before `frameSink`.
  var metadataSink: ((CameraFrameMetadata) -> Void)? { get set }
  /// Focus states of the running configured device, on main; none after a stop. Each is sampled
  /// on the session owner, so main receives it in order with `perform` completions: after the
  /// completion of every command applied before the sample, before the completion of every later one.
  var focusEvents: ((FocusObservation) -> Void)? { get set }
  /// The configured device, for rotation tracking; nil until `configured`.
  var device: AVCaptureDevice? { get }
  func attachPreview(_ layer: AVCaptureVideoPreviewLayer)
  func start(_ request: CameraRequest)
  func stop()
  /// Applies `command` on the session owner after every earlier command and configuration, and
  /// calls `completion` on main with whether the device accepted it, ordered with `focusEvents`. An
  /// accepted exposure bias completes later instead, once the first frame exposed with it arrives,
  /// with that frame's host time. A command for a stopped run is dropped, and so is its completion.
  func perform(_ command: CameraCommand, completion: @escaping (CommandOutcome) -> Void)
  /// Takes one still with `settings` on the session owner after every earlier command. `events`
  /// runs on main and always ends with one terminal event, even when the camera stops meanwhile,
  /// so a caller's busy slot always settles. A camera that is not streaming fails at once.
  func capture(_ settings: StillSettings, events: @escaping @Sendable (StillEvent) -> Void)
}

/// Hardware controls are optional for a camera owner without a device.
extension CameraSessionControl {
  var metadataSink: ((CameraFrameMetadata) -> Void)? { get { nil } set {} }
  var focusEvents: ((FocusObservation) -> Void)? { get { nil } set {} }
  func perform(_ command: CameraCommand, completion: @escaping (CommandOutcome) -> Void) {}
  func capture(_ settings: StillSettings, events: @escaping @Sendable (StillEvent) -> Void) {
    DispatchQueue.main.async { events(.failed(.cameraUnavailable)) }
  }
}

/// First-frame and buffer-size reporting for one camera owner. Only an armed run reports,
/// and a run is armed on the camera queue after the previous run stopped and its frame
/// callbacks drained, so a late frame of an earlier run can never mark a restart ready.
final class CameraFrameMonitor: @unchecked Sendable {
  enum Report: Equatable {
    case first(Int, PixelSize)
    case resized(Int, PixelSize)
  }

  private let lock = NSLock()
  /// The run whose frames report; nil while stopped or not yet armed.
  private var run: Int?
  private var awaitingFirst = false
  private var size: PixelSize?
  private var delivered = 0
  private var dropped = 0

  /// Camera queue only, once the previous run's callbacks have drained.
  func arm(_ generation: Int) {
    lock.lock(); defer { lock.unlock() }
    run = generation; awaitingFirst = true; size = nil
  }

  /// An interruption of the armed run waits for its next frame again.
  func rearm(_ generation: Int) {
    lock.lock(); defer { lock.unlock() }
    if run == generation { awaitingFirst = true }
  }

  func disarm() {
    lock.lock(); defer { lock.unlock() }
    run = nil; awaitingFirst = false; size = nil
  }

  /// Frame queue: the actual delivered buffer size.
  func frame(_ delivered: PixelSize) -> Report? {
    lock.lock(); defer { lock.unlock() }
    self.delivered += 1
    guard let run else { return nil }
    if awaitingFirst {
      awaitingFirst = false
      size = delivered
      return .first(run, delivered)
    }
    guard delivered != size else { return nil }
    size = delivered
    return .resized(run, delivered)
  }

  func drop() { lock.lock(); dropped += 1; lock.unlock() }

  /// Whether a run's frames currently report; frames of no run are not analyzed.
  var isArmed: Bool { lock.lock(); defer { lock.unlock() }; return run != nil }

  /// Frames delivered and dropped since the last call.
  func takeCounts() -> (delivered: Int, dropped: Int) {
    lock.lock(); defer { lock.unlock() }
    defer { delivered = 0; dropped = 0 }
    return (delivered, dropped)
  }
}

enum CameraLog {
  static let logger = Logger(subsystem: "ReactNativeDocumentScanner", category: "camera")
}

/// The one AVFoundation owner of a camera view: the rear physical wide-angle camera, a photo
/// output with a bounded maximum still size, and a video-data output that keeps no backlog.
/// The session is created on `queue` at the first start that finds a camera, since building
/// it can be slow, and it, its inputs/outputs and the device are configured only there, as are
/// control commands, in submission order; frames arrive on `frameQueue`; `generation`, events and
/// the preview layer belong to main. Frame times are converted from the session's clock to the
/// host clock, the domain of `DispatchTime` uptime and focus observations.
final class AVCameraSession: NSObject, CameraSessionControl, AVCaptureVideoDataOutputSampleBufferDelegate,
  @unchecked Sendable {
  var onEvent: ((CameraSessionEvent) -> Void)?
  var frameSink: ((CVPixelBuffer, Int64) -> Void)? {
    get { lock.lock(); defer { lock.unlock() }; return sink }
    set { lock.lock(); sink = newValue; lock.unlock() }
  }
  var metadataSink: ((CameraFrameMetadata) -> Void)? {
    get { lock.lock(); defer { lock.unlock() }; return metadata }
    set { lock.lock(); metadata = newValue; lock.unlock() }
  }
  var focusEvents: ((FocusObservation) -> Void)?
  var device: AVCaptureDevice? {
    lock.lock(); defer { lock.unlock() }
    return running ? configuredDevice : nil
  }

  private let queue = DispatchQueue(label: "ReactNativeDocumentScanner.camera")
  private let frameQueue = DispatchQueue(label: "ReactNativeDocumentScanner.camera.frames")
  private let discovery: () -> AVCaptureDevice?
  // Main only.
  private var generation = 0
  private weak var previewLayer: AVCaptureVideoPreviewLayer?
  // Queue only.
  private var pipeline: Pipeline?
  private var input: AVCaptureDeviceInput?
  private var configured: (request: CameraRequest, configuration: CameraConfiguration)?
  /// The running device's focus observations; invalidated when the run stops.
  private var deviceObservations: [NSKeyValueObservation] = []
  // Shared by main, the queue and the frame queue.
  private let lock = NSLock()
  private var running = false
  private var configuredDevice: AVCaptureDevice?
  private var liveSession: AVCaptureSession?
  private var sink: ((CVPixelBuffer, Int64) -> Void)?
  private var metadata: ((CameraFrameMetadata) -> Void)?
  private var observers: [NSObjectProtocol] = []
  let frames = CameraFrameMonitor()

  init(discovery: @escaping () -> AVCaptureDevice? = {
    AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
  }) {
    self.discovery = discovery
    super.init()
  }

  /// The session and its outputs, built once on the queue.
  private final class Pipeline {
    let session = AVCaptureSession()
    let photoOutput = AVCapturePhotoOutput()
    let videoOutput = AVCaptureVideoDataOutput()
  }

  private func makePipeline() -> Pipeline {
    if let pipeline { return pipeline }
    let created = Pipeline()
    let session = created.session
    let center = NotificationCenter.default
    let added = [
      center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main) {
        [weak self] note in
        let error = note.userInfo?[AVCaptureSessionErrorKey] as? NSError
        CameraLog.logger.error("camera runtime error \(error?.localizedDescription ?? "unknown", privacy: .public)")
        self?.fail(.cameraUnavailable)
      },
      center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: .main) {
        [weak self] note in
        guard let self, isRunning else { return }
        CameraLog.logger.info("camera interrupted \(String(describing: note.userInfo), privacy: .public)")
        frames.rearm(generation)
        onEvent?(.interrupted)
      },
    ]
    lock.lock(); liveSession = session; observers = added; lock.unlock()
    pipeline = created
    return created
  }

  deinit {
    observers.forEach(NotificationCenter.default.removeObserver)
  }

  /// The layer shows the session once one exists.
  func attachPreview(_ layer: AVCaptureVideoPreviewLayer) {
    dispatchPrecondition(condition: .onQueue(.main))
    previewLayer = layer
    connectPreview()
  }

  private func connectPreview() {
    lock.lock(); let session = liveSession; lock.unlock()
    if let session, let previewLayer, previewLayer.session !== session { previewLayer.session = session }
  }

  func start(_ request: CameraRequest) {
    dispatchPrecondition(condition: .onQueue(.main))
    generation += 1
    let current = generation
    lock.lock(); running = true; lock.unlock()
    queue.async { [self] in
      let configuration: CameraConfiguration
      do {
        configuration = try configure(request)
      } catch let error as ScannerCodeError {
        stopSession()
        let code = error.code
        return publish(current) { $0.fail(code) }
      } catch {
        stopSession()
        return publish(current) { $0.fail(.cameraUnavailable) }
      }
      lock.lock(); configuredDevice = input?.device; lock.unlock()
      if let device = input?.device { observeFocus(device, run: current) }
      publish(current) { owner in
        owner.connectPreview()
        owner.onEvent?(.configured(configuration))
      }
      guard let session = pipeline?.session else { return }
      // A start without a stop still ends the earlier run before this one is armed.
      if session.isRunning { stopSession() }
      frames.arm(current)
      session.startRunning()
      if !session.isRunning {
        CameraLog.logger.error("camera session did not start")
        publish(current) { $0.fail(.cameraUnavailable) }
      }
    }
  }

  func stop() {
    dispatchPrecondition(condition: .onQueue(.main))
    generation += 1
    lock.lock(); running = false; lock.unlock()
    frames.disarm()
    queue.async { [self] in stopSession() }
  }

  func perform(_ command: CameraCommand, completion: @escaping (CommandOutcome) -> Void) {
    dispatchPrecondition(condition: .onQueue(.main))
    let current = generation
    // Created and called on main only.
    nonisolated(unsafe) let completion = completion
    queue.async { [self] in
      let finish: @Sendable (CommandOutcome) -> Void = { outcome in
        DispatchQueue.main.async { [self] in
          guard generation == current else { return }
          completion(outcome)
        }
      }
      guard isRunning, configured != nil, let input else { return finish(.refused) }
      var firstBuffer: (@Sendable (CMTime) -> Void)?
      if case .exposureBias = command {
        // The bias callback uses the device clock. Video output timestamps use the session clock.
        let deviceClock = input.ports.first { $0.mediaType == .video }?.clock
        firstBuffer = { time in
          let fromNs = deviceClock.flatMap { hostNs(time, clock: $0) }
          finish(CommandOutcome(applied: true, fromNs: fromNs))
        }
      }
      let applied = CameraDeviceAdapter.apply(command, to: input.device, firstBuffer: firstBuffer)
      if !applied { CameraLog.logger.info("camera command refused \(String(describing: command), privacy: .public)") }
      if !applied || firstBuffer == nil { finish(applied ? .accepted : .refused) }
    }
  }

  func capture(_ settings: StillSettings, events: @escaping @Sendable (StillEvent) -> Void) {
    dispatchPrecondition(condition: .onQueue(.main))
    queue.async { [self] in
      guard isRunning, let configured, let pipeline, pipeline.session.isRunning,
            let connection = pipeline.photoOutput.connection(with: .video), connection.isEnabled, connection.isActive,
            let format = StillFormat.choose(codecs: pipeline.photoOutput.availablePhotoCodecTypes,
                                            pixelFormats: pipeline.photoOutput.availablePhotoPixelFormatTypes)
      else {
        CameraLog.logger.info("still refused: the camera is not streaming")
        return DispatchQueue.main.async { events(.failed(.cameraNotReady)) }
      }
      // The still is tagged with the rotation live analysis applies, so their upright frames match.
      if connection.isVideoRotationAngleSupported(settings.rotationAngle) {
        connection.videoRotationAngle = settings.rotationAngle
      }
      let photo = Self.photoSettings(settings, format: format, dimensions: configured.configuration.photo,
                                     maxPriority: pipeline.photoOutput.maxPhotoQualityPrioritization)
      pipeline.photoOutput.capturePhoto(with: photo, delegate: PhotoDelegate(events: events))
    }
  }

  /// Per-shot settings: the configured still ceiling (a delivered still may be smaller, never
  /// larger), the frozen JPEG quality, or uncompressed BGRA where JPEG is unavailable.
  static func photoSettings(_ settings: StillSettings, format: StillFormat, dimensions: PixelSize,
                            maxPriority: AVCapturePhotoOutput.QualityPrioritization) -> AVCapturePhotoSettings {
    let photo: AVCapturePhotoSettings
    switch format {
    case .jpeg:
      photo = AVCapturePhotoSettings(format: [
        AVVideoCodecKey: AVVideoCodecType.jpeg,
        AVVideoCompressionPropertiesKey: [AVVideoQualityKey: settings.quality],
      ])
    case .bgra:
      photo = AVCapturePhotoSettings(format: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
    }
    photo.maxPhotoDimensions = dimensions.dimensions
    let priority: AVCapturePhotoOutput.QualityPrioritization
    switch settings.priority {
    case .quality: priority = .quality
    case .balanced: priority = .balanced
    case .speed: priority = .speed
    }
    photo.photoQualityPrioritization = priority.rawValue <= maxPriority.rawValue ? priority : maxPriority
    return photo
  }

  /// Owns itself from the request until AVFoundation's final callback, which the photo output
  /// makes for every request, and turns the callbacks into exactly one terminal event.
  private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    private let events: @Sendable (StillEvent) -> Void
    private let lock = NSLock()
    private var keepAlive: PhotoDelegate?
    private var result: StillEvent?

    init(events: @escaping @Sendable (StillEvent) -> Void) {
      self.events = events
      super.init()
      keepAlive = self
    }

    private func send(_ event: StillEvent) {
      let events = events
      DispatchQueue.main.async { events(event) }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
      send(.exposed)
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
      let event: StillEvent
      if let error {
        CameraLog.logger.error("still failed \(error.localizedDescription, privacy: .public)")
        event = .failed(.captureFailed)
      } else if let data = photo.fileDataRepresentation() {
        event = .delivered(data)
      } else {
        event = .failed(.captureFailed)
      }
      lock.lock(); result = event; lock.unlock()
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
                     error: Error?) {
      lock.lock()
      let event = error == nil ? result ?? .failed(.captureFailed) : .failed(.captureFailed)
      keepAlive = nil
      lock.unlock()
      if let error { CameraLog.logger.error("still capture ended \(error.localizedDescription, privacy: .public)") }
      send(event)
    }
  }

  // MARK: - Queue

  private func configure(_ request: CameraRequest) throws -> CameraConfiguration {
    if let configured, configured.request == request { return configured.configuration }
    guard let device = input?.device ?? discovery() else {
      CameraLog.logger.error("camera unavailable: no rear wide-angle camera")
      throw ScannerCodeError(code: .cameraUnavailable)
    }
    let formats = device.formats
    let options = formats.map { format in
      CameraFormatOption(video: PixelSize(CMVideoFormatDescriptionGetDimensions(format.formatDescription)),
                         photos: format.supportedMaxPhotoDimensions.map(PixelSize.init))
    }
    guard let choice = CameraFormatPolicy.choose(options, analysisFrameWidth: request.analysisFrameWidth,
                                                     highResolution: request.highResolutionPhoto) else {
      CameraLog.logger.error("camera unavailable: no bounded still size among \(formats.count) formats")
      throw ScannerCodeError(code: .cameraUnavailable)
    }
    configured = nil
    let pipeline = makePipeline()
    let session = pipeline.session, photoOutput = pipeline.photoOutput, videoOutput = pipeline.videoOutput
    session.beginConfiguration()
    defer { session.commitConfiguration() }
    if input == nil {
      let next = try AVCaptureDeviceInput(device: device)
      guard session.canAddInput(next) else { throw ScannerCodeError(code: .cameraUnavailable) }
      session.addInput(next)
      input = next
    }
    if !session.outputs.contains(photoOutput) {
      guard session.canAddOutput(photoOutput) else { throw ScannerCodeError(code: .cameraUnavailable) }
      session.addOutput(photoOutput)
    }
    if !session.outputs.contains(videoOutput) {
      // Full active-format buffers: automatic sizing may deliver smaller preview-sized ones.
      videoOutput.automaticallyConfiguresOutputBufferDimensions = false
      videoOutput.deliversPreviewSizedOutputBuffers = false
      videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
      // Late frames are dropped, so analysis never queues a backlog.
      videoOutput.alwaysDiscardsLateVideoFrames = true
      videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
      guard session.canAddOutput(videoOutput) else { throw ScannerCodeError(code: .cameraUnavailable) }
      session.addOutput(videoOutput)
    }
    try device.lockForConfiguration()
    device.activeFormat = formats[choice.index]
    device.unlockForConfiguration()
    // The output accepts only a size the active format supports; anything else would throw.
    guard device.activeFormat.supportedMaxPhotoDimensions.map(PixelSize.init).contains(choice.photo) else {
      throw ScannerCodeError(code: .cameraUnavailable)
    }
    photoOutput.maxPhotoDimensions = choice.photo.dimensions
    // Per-shot prioritization may then go up to quality; zero shutter lag stays on where supported.
    // Its buffered photo may predate the gate's approved frames, so the view checks each delivered
    // photo's timestamp against them.
    photoOutput.maxPhotoQualityPrioritization = .quality
    if photoOutput.isZeroShutterLagSupported { photoOutput.isZeroShutterLagEnabled = true }
    let configuration = CameraConfiguration(photo: choice.photo, video: choice.video,
                                            capabilities: CameraDeviceAdapter.capabilities(device),
                                            zeroShutterLag: photoOutput.isZeroShutterLagEnabled)
    configured = (request, configuration)
    CameraLog.logger.info(
      "camera configured photo=\(choice.photo, privacy: .public) video=\(choice.video, privacy: .public) zsl=\(configuration.zeroShutterLag, privacy: .public)")
    return configuration
  }

  /// Reports the device's focus state to main for run `run` whenever it changes. A change
  /// notification only wakes the report: the state is sampled on the queue. Queue only.
  private func observeFocus(_ device: AVCaptureDevice, run: Int) {
    deviceObservations.forEach { $0.invalidate() }
    let report: @Sendable (AVCaptureDevice) -> Void = { [weak self] device in
      self?.reportFocus(run: run) { CameraDeviceAdapter.focusObservation(device) }
    }
    deviceObservations = [
      device.observe(\.isAdjustingFocus, options: [.initial, .new]) { device, _ in report(device) },
      device.observe(\.focusMode, options: [.new]) { device, _ in report(device) },
    ]
  }

  /// Samples focus on the queue and delivers it to main for run `run`. Commands apply on the same
  /// queue and complete to main from it, so a sample taken after a command applied reaches main
  /// after that command's completion, and one taken before it reaches main first. AVFoundation
  /// notifies on its own threads, so a notification's thread never decides the order. Any thread.
  func reportFocus(run: Int, _ sample: @escaping () -> FocusObservation) {
    queue.async { [weak self] in
      guard self != nil else { return }
      let observation = sample()
      DispatchQueue.main.async { [weak self] in
        guard let self, self.generation == run else { return }
        self.focusEvents?(observation)
      }
    }
  }

  /// Returns once the run has stopped and its already queued frame callbacks have finished.
  private func stopSession() {
    deviceObservations.forEach { $0.invalidate() }
    deviceObservations = []
    frames.disarm()
    if let session = pipeline?.session, session.isRunning { session.stopRunning() }
    frameQueue.sync {}
    let counts = frames.takeCounts()
    if counts != (0, 0) {
      CameraLog.logger.info("camera stopped frames=\(counts.0) dropped=\(counts.1)")
    }
  }

  // MARK: - Main

  private func publish(_ current: Int, _ deliver: @escaping @Sendable (AVCameraSession) -> Void) {
    DispatchQueue.main.async { [self] in
      guard generation == current else { return }
      deliver(self)
    }
  }

  private var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return running }

  private func fail(_ code: ScannerErrorCode) {
    dispatchPrecondition(condition: .onQueue(.main))
    guard isRunning else { return }
    stop()
    onEvent?(.failed(code))
  }

  // MARK: - Frames

  func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                     from connection: AVCaptureConnection) {
    // Nothing is retained past this call; live analysis borrows the buffer synchronously.
    guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    frameArrived(PixelSize(CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer)))
    guard frames.isArmed else { return }
    lock.lock()
    let sink = sink, metadata = metadata, device = configuredDevice, clock = liveSession?.synchronizationClock
    lock.unlock()
    guard sink != nil || metadata != nil else { return }
    guard let frameNs = hostNs(CMSampleBufferGetPresentationTimeStamp(sampleBuffer), clock: clock) else { return }
    metadata?(CameraDeviceAdapter.metadata(sampleBuffer, hostNs: frameNs, device: device))
    sink?(buffer, frameNs)
  }

  /// A frame of the actual delivered size; the frame callback's only effect.
  func frameArrived(_ size: PixelSize) {
    switch frames.frame(size) {
    case .first(let run, let size)?: publish(run) { $0.onEvent?(.streaming(size)) }
    case .resized(let run, let size)?: publish(run) { $0.onEvent?(.frameSize(size)) }
    case nil: break
    }
  }

  func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer,
                     from connection: AVCaptureConnection) {
    frames.drop()
  }
}
