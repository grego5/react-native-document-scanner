import AVFoundation
import Foundation
import os

/// What the view asks of its camera owner. Equal requests keep the configured session.
struct CameraRequest: Equatable, Sendable {
  let analysisWidth: Int
}

/// The supported configuration a started session runs with.
struct CameraConfiguration: Equatable, Sendable {
  let photo: PixelSize
  let video: PixelSize
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
  /// the buffer, which it borrows only for the call, and its presentation time in nanoseconds.
  var frameSink: ((CVPixelBuffer, Int64) -> Void)? { get set }
  /// The configured device, for rotation tracking; nil until `configured`.
  var device: AVCaptureDevice? { get }
  func attachPreview(_ layer: AVCaptureVideoPreviewLayer)
  func start(_ request: CameraRequest)
  func stop()
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
/// it can be slow, and it, its inputs/outputs and the device are configured only there; frames
/// arrive on `frameQueue`; `generation`, events and the preview layer belong to main.
final class AVCameraSession: NSObject, CameraSessionControl, AVCaptureVideoDataOutputSampleBufferDelegate,
  @unchecked Sendable {
  var onEvent: ((CameraSessionEvent) -> Void)?
  var frameSink: ((CVPixelBuffer, Int64) -> Void)? {
    get { lock.lock(); defer { lock.unlock() }; return sink }
    set { lock.lock(); sink = newValue; lock.unlock() }
  }
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
  // Shared by main, the queue and the frame queue.
  private let lock = NSLock()
  private var running = false
  private var configuredDevice: AVCaptureDevice?
  private var liveSession: AVCaptureSession?
  private var sink: ((CVPixelBuffer, Int64) -> Void)?
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

  /// Per-shot settings for the shutter: the configured still ceiling, never larger. Queue only.
  func photoSettings() -> AVCapturePhotoSettings? {
    guard let configured else { return nil }
    let settings = AVCapturePhotoSettings()
    settings.maxPhotoDimensions = configured.configuration.photo.dimensions
    return settings
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
    guard let choice = CameraFormatPolicy.choose(options, analysisWidth: request.analysisWidth) else {
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
    let configuration = CameraConfiguration(photo: choice.photo, video: choice.video)
    configured = (request, configuration)
    CameraLog.logger.info(
      "camera configured photo=\(choice.photo, privacy: .public) video=\(choice.video, privacy: .public)")
    return configuration
  }

  /// Returns once the run has stopped and its already queued frame callbacks have finished.
  private func stopSession() {
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
    guard frames.isArmed, let sink = frameSink else { return }
    let time = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
    guard time.isNumeric else { return }
    sink(buffer, CMTimeConvertScale(time, timescale: 1_000_000_000, method: .roundHalfAwayFromZero).value)
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
