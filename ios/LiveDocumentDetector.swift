import CoreVideo
import Foundation
import os

enum LiveGuideStatus: Equatable, Sendable {
  case searching, tracking, held, unavailable
}

/// One immutable guide publication. `corners` is normalized to the upright `framing.framed` region
/// of the frame it was accepted on; `acceptanceId` and `deadlineNs` (monotonic uptime) are set
/// for tracking and held results. `generation` is the detector generation the frame was analyzed
/// in; `modelGeneration`, `sequence` and `sensorNs` identify the model and frame behind it.
/// `score` is this frame's inference score, nil for a frame flow alone carried.
struct LiveGuideResult: Equatable, Sendable {
  var status: LiveGuideStatus
  var corners: LiveCorners? = nil
  var framing: CameraFraming? = nil
  var acceptanceId: Int64 = 0
  var deadlineNs: Int64 = 0
  var generation: Int = 0
  var modelGeneration: UInt64 = 0
  var sequence: Int = 0
  var sensorNs: Int64 = 0
  var score: Double? = nil
}

/// The next analysis frame's framed region, copied out of the camera buffer: packed BGRA rows
/// in raw buffer axes, with the EXIF orientation that turns them upright.
struct LiveFrame: Sendable {
  let pixels: Data
  let width: Int
  let height: Int
  let orientation: UInt8
  let sensorNs: Int64
}

/// Generation and pacing shared by main and the analysis owner.
final class LiveDetectionState: @unchecked Sendable {
  /// About 15 Hz while a document is tracked or held; acquisition takes every frame.
  static let trackingGapNs: Int64 = 60_000_000

  private let lock = NSLock()
  private var generation = 0
  private var active = false
  private var enabled = true
  private var failed = false
  private var lastScheduledNs: Int64?

  func begin(enabled: Bool) {
    lock.lock(); defer { lock.unlock() }
    generation += 1; active = true; self.enabled = enabled; failed = false; lastScheduledNs = nil
  }

  func setEnabled(_ value: Bool) {
    lock.lock(); defer { lock.unlock() }
    guard enabled != value else { return }
    enabled = value
    generation += 1; lastScheduledNs = nil
  }

  func invalidate() {
    lock.lock(); defer { lock.unlock() }
    generation += 1; lastScheduledNs = nil
  }

  func modelChanged() -> Int {
    lock.lock(); defer { lock.unlock() }
    failed = false; generation += 1; lastScheduledNs = nil
    return generation
  }

  func stop() {
    lock.lock(); defer { lock.unlock() }
    active = false; generation += 1; lastScheduledNs = nil
  }

  /// The current generation for a frame at least `minGapNs` after the previous admitted one, or nil.
  func acquire(nowNs: Int64, minGapNs: Int64) -> Int? {
    lock.lock(); defer { lock.unlock() }
    guard active, enabled, !failed else { return nil }
    if let lastScheduledNs, minGapNs == Int64.max || nowNs - lastScheduledNs < minGapNs { return nil }
    lastScheduledNs = nowNs
    return generation
  }

  var current: Int { lock.lock(); defer { lock.unlock() }; return generation }

  func isCurrent(_ token: Int) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return active && enabled && generation == token
  }

  func fail(_ token: Int) -> Bool {
    lock.lock(); defer { lock.unlock() }
    guard active, enabled, generation == token else { return false }
    failed = true
    return true
  }

  var hasFailed: Bool { lock.lock(); defer { lock.unlock() }; return failed }

  /// Whether guide work is useful at all, independent of pacing.
  var guidanceEnabled: Bool { lock.lock(); defer { lock.unlock() }; return active && enabled && !failed }
}

/// Live guidance for one camera view. `analyze` runs on the caller's serial analysis owner (the
/// camera's frame queue), borrows each buffer only for that call and retains no buffer: flow keeps
/// its own small gray copy, and a frame request copies one framed region. Settings and lifecycle
/// calls come from main, which alone receives results. Guide acceptance, flow evidence and score
/// rules come from the shared core; pacing is `AdaptiveDetectionPolicy`.
final class LiveDocumentDetector: @unchecked Sendable {
  /// What the analysis owner reads per frame; set from main.
  struct Settings: Equatable, Sendable {
    var adaptive = false
    var minCornerScore = 0.95
    /// AVFoundation's clockwise capture rotation angle.
    var rotationAngle: Double = 0
    var square = false
    /// A capture's motion check needs flow even while guidance is off.
    var motionEvidence = false
  }

  /// A page unseen this long arrives anew, so a focus reached before it no longer counts.
  static let pageLostNs: Int64 = 1_000_000_000

  static let holdNs: Int64 = 400_000_000
  static let minArea = 0.03
  /// Inference pauses above this framing speed, in buffer widths per second.
  static let maxInferenceSpeedFraction = 0.01

  private let acquireLease: @Sendable () -> CornerInference.Lease
  private let clock: () -> Int64
  private let logger = Logger(subsystem: "ReactNativeDocumentScanner", category: "live")
  let state = LiveDetectionState()

  // Shared under `lock`.
  private let lock = NSLock()
  private var settings = Settings()
  private var lease: CornerInference.Lease?
  /// Between `begin` and `stop`: only then may an acquired lease be kept.
  private var running = false
  private var pending: LiveGuideResult?
  private var frameRequest: ((LiveFrame) -> Void)?
  /// Advances with each camera start; frame evidence of an earlier run never continues.
  private var run = 0
  private var framesListener: (() -> Void)?
  private var framesScheduled = false
  private var framesListenerRevision = 0
  /// When the latest focus sample after movement found the lens still; between frames, so a
  /// movement too short for any frame to show still counts.
  private var sampledAdjusting = false
  private var sampledSettledNs: Int64?
  private let prepareQueue: DispatchQueue

  /// Every analyzed frame's capture evidence, in order.
  let frames = FrameHistory()
  /// Called on main after frames were appended, coalesced; nil while nothing waits for them.
  var onFrames: (() -> Void)? {
    get { lock.lock(); defer { lock.unlock() }; return framesListener }
    set {
      lock.lock()
      framesListener = newValue
      framesListenerRevision += 1
      framesScheduled = false
      lock.unlock()
    }
  }

  // Main only.
  var onResult: ((LiveGuideResult) -> Void)?
  private var deliveredAcceptanceId: Int64 = 0
  private var expiredAcceptanceId: Int64 = 0
  private var expiry: DispatchWorkItem?

  // Analysis owner only.
  private let guide = ScannerLiveGuide(minArea: LiveDocumentDetector.minArea, holdNs: LiveDocumentDetector.holdNs)
  private var flow: ScannerFlowTracker?
  private var flowFailed = false
  private var flowGeneration = -1
  private var policy = AdaptiveDetectionPolicy()
  private var adaptiveWasEnabled = false
  private var cadence = AdaptiveInferenceCadence()
  private var cadenceGeneration = -1
  private var cadenceFraming: CameraFraming?
  private var appliedMinCornerScore = 0.95
  private var modelGeneration: UInt64?
  private var scorePausedGeneration: Int?
  private var tracking = false
  private var lastToken = -1
  private var lastFraming: CameraFraming?
  private var acceptedId: Int64 = 0
  private var acceptedFraming: CameraFraming?
  /// The latest accepted outline, carried by flow, and the framing and sensor time it belongs to.
  private var flowRegion: LiveCorners?
  private var flowRegionFraming: CameraFraming?
  private var trackedRegionSensorNs: Int64 = 0
  private var sequence = 0
  /// This frame's metadata, delivered just before it on the same owner.
  private var metadata: CameraFrameMetadata?
  /// The newest published outline and until when it holds; nil while none.
  private var lastGuide: (corners: LiveCorners, framing: CameraFraming?, deadlineNs: Int64, generation: Int)?
  private var focusRun = -1
  private var lastAdjusting: Bool?
  private var focusReachedNs: Int64?
  private var pageArrivedNs: Int64?
  private var pageSeenNs: Int64 = 0

  init(inference: CornerInference = .shared,
       clock: @escaping () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds) },
       prepareQueue: DispatchQueue = DispatchQueue(label: "ReactNativeDocumentScanner.live.prepare"),
       acquireLease: (@Sendable () -> CornerInference.Lease)? = nil) {
    self.acquireLease = acquireLease ?? { inference.acquire() }
    self.clock = clock
    self.prepareQueue = prepareQueue
  }

  // MARK: - Main

  func update(_ next: Settings) {
    lock.lock(); settings = next; lock.unlock()
  }

  /// A camera start. Enabled guidance opens and warms the interpreter before the first frame.
  func begin(enabled: Bool) {
    lock.lock(); running = true; run += 1; sampledAdjusting = false; sampledSettledNs = nil; lock.unlock()
    frames.clear()
    state.begin(enabled: enabled)
    cancelDelivery()
    if enabled { preload() }
  }

  func setEnabled(_ enabled: Bool) {
    state.setEnabled(enabled)
    cancelDelivery()
    if enabled { preload() }
  }

  /// The model or the source changed: in-flight results become stale.
  func invalidate() {
    state.invalidate()
    cancelDelivery()
  }

  /// A camera stop releases the interpreter lease; running inference finishes first.
  func stop() {
    state.stop()
    cancelDelivery()
    lock.lock(); running = false; let released = lease; lease = nil; frameRequest = nil; lock.unlock()
    frames.clear()
    released?.close()
  }

  /// Hands the next analyzed frame's framed region to `receiver` on the analysis owner. A newer
  /// request replaces one still waiting, whose receiver is never called.
  func requestFrame(_ receiver: @escaping (LiveFrame) -> Void) {
    lock.lock(); frameRequest = receiver; lock.unlock()
  }

  func cancelFrameRequest() {
    lock.lock(); frameRequest = nil; lock.unlock()
  }

  private func preload() {
    let generation = state.current
    prepareQueue.async { [self] in
      guard state.current == generation, state.guidanceEnabled else { return }
      do { try currentLease()?.prepare() } catch {
        // A missing or failing model is reported by the first frame that needs it.
      }
    }
  }

  /// The interpreter lease for the running camera; nil once stopped. Acquiring waits on the shared
  /// inference queue, so it happens outside `lock`, which main takes for settings and `stop`. The
  /// acquisition is kept only if the camera still runs and no other caller kept one first;
  /// otherwise it is closed.
  private func currentLease() -> CornerInference.Lease? {
    lock.lock()
    if let lease { lock.unlock(); return lease }
    let wanted = running
    let owner = run
    lock.unlock()
    guard wanted else { return nil }
    let acquired = acquireLease()
    lock.lock()
    let kept = running && run == owner ? (lease ?? acquired) : nil
    if kept === acquired { lease = acquired }
    lock.unlock()
    if kept !== acquired { acquired.close() }
    return kept
  }

  private func cancelDelivery() {
    lock.lock(); pending = nil; lock.unlock()
    expiry?.cancel(); expiry = nil
  }

  // MARK: - Analysis owner

  /// A focus sample of the running device, on main; only its time and stillness are used.
  func focusObserved(_ observation: FocusObservation) {
    lock.lock(); defer { lock.unlock() }
    if observation.adjusting {
      sampledAdjusting = true
    } else if sampledAdjusting, observation.hostNs > 0 {
      sampledAdjusting = false
      sampledSettledNs = observation.hostNs
    }
  }

  /// The next frame's exposure and focus evidence, delivered just before it on the analysis owner.
  func frameMetadata(_ metadata: CameraFrameMetadata) { self.metadata = metadata }

  /// Analyzes one camera buffer in sensor axes; `sensorNs` is its presentation time.
  func analyze(_ buffer: CVPixelBuffer, sensorNs: Int64) {
    lock.lock()
    let metadata = self.metadata.flatMap { $0.hostNs == sensorNs ? $0 : nil }
    self.metadata = nil
    guard running else { lock.unlock(); return }
    let settings = settings
    let request = frameRequest
    frameRequest = nil
    let run = run
    lock.unlock()
    guard let framing = CameraFraming(buffer: PixelSize(CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer)),
                                      rotationAngle: settings.rotationAngle, square: settings.square) else { return }
    sequence += 1
    if let request, let frame = Self.copy(buffer, framing: framing, sensorNs: sensorNs) { request(frame) }
    if settings.minCornerScore != appliedMinCornerScore {
      appliedMinCornerScore = settings.minCornerScore
      state.invalidate()
      resetTracking()
      releaseFlow()
    }
    let startedAt = state.guidanceEnabled ? clock() : nil
    if startedAt == nil { lastGuide = nil }
    let estimate: ScannerFlowEstimate?
    if startedAt != nil || settings.motionEvidence {
      estimate = updateFlow(buffer, framing: framing, sensorNs: sensorNs)
    } else {
      releaseFlow()
      estimate = nil
    }
    let blur: Double?
    if let estimate, estimate.status == FlowStatus.measured.rawValue, let speed = estimate.speed?.doubleValue,
       estimate.sourceWidth > 0, let exposure = metadata?.exposureNs {
      blur = speed * Double(exposure) * 1e-9 / Double(estimate.sourceWidth)
    } else {
      blur = nil
    }
    if let startedAt {
      guideFrame(buffer, framing: framing, sensorNs: sensorNs, settings: settings, estimate: estimate,
                 blur: blur, startedAt: startedAt)
    }
    record(framing: framing, sensorNs: sensorNs, metadata: metadata, estimate: estimate, blur: blur, run: run)
  }

  /// Appends this frame's capture evidence and wakes main if something waits for it.
  private func record(framing: CameraFraming, sensorNs: Int64, metadata: CameraFrameMetadata?,
                      estimate: ScannerFlowEstimate?, blur: Double?, run: Int) {
    if run != focusRun {
      focusRun = run
      lastAdjusting = nil
      focusReachedNs = nil
      pageArrivedNs = nil
      pageSeenNs = 0
    }
    let adjusting = metadata?.focusAdjusting
    if adjusting == false && lastAdjusting == true { focusReachedNs = sensorNs }
    if let adjusting { lastAdjusting = adjusting }
    lock.lock(); let sampled = sampledSettledNs; lock.unlock()
    if adjusting == false, let sampled, sampled <= sensorNs, sampled > (focusReachedNs ?? .min) { focusReachedNs = sampled }
    let now = clock()
    let guide = lastGuide.flatMap { held in
      held.framing == framing && now < held.deadlineNs && state.isCurrent(held.generation) ? held.corners : nil
    }
    if guide != nil {
      if pageArrivedNs == nil || sensorNs &- pageSeenNs > Self.pageLostNs { pageArrivedNs = sensorNs }
      pageSeenNs = sensorNs
    } else if pageArrivedNs != nil && sensorNs &- pageSeenNs > Self.pageLostNs {
      pageArrivedNs = nil
    }
    let flow = estimate.flatMap { estimate in
      FlowStatus(rawValue: estimate.status).map {
        FlowEvidence(status: $0, speedPxPerSecond: estimate.speed?.doubleValue, sourceWidth: estimate.sourceWidth,
                     lostRegions: estimate.lostRegions, fromNs: estimate.fromNs)
      }
    }
    let exposure = metadata?.exposureNs
    let reached = focusReachedNs, arrived = pageArrivedNs
    lock.lock()
    guard running, self.run == run else { lock.unlock(); return }
    frames.append { sequence in
      FrameRecord(sequence: sequence, sensorNs: sensorNs, framing: framing, flow: flow, exposureNs: exposure,
                  iso: metadata?.iso, exposureEstimated: metadata?.estimated ?? false, blur: blur,
                  focusAdjusting: adjusting, focusReachedNs: reached, pageArrivedNs: arrived, guide: guide)
    }
    let wake = framesListener != nil && !framesScheduled
    let revision = framesListenerRevision
    if wake { framesScheduled = true }
    lock.unlock()
    guard wake else { return }
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      lock.lock()
      guard running, self.run == run, framesListenerRevision == revision else { lock.unlock(); return }
      framesScheduled = false
      let listener = framesListener
      lock.unlock()
      listener?()
    }
  }

  /// Guide work for one frame with guidance on: pacing, inference and acceptance.
  private func guideFrame(_ buffer: CVPixelBuffer, framing: CameraFraming, sensorNs: Int64, settings: Settings,
                          estimate: ScannerFlowEstimate?, blur: Double?, startedAt: Int64) {
    let adaptive = settings.adaptive
    if adaptive != adaptiveWasEnabled {
      policy.reset()
      cadence = AdaptiveInferenceCadence()
      scorePausedGeneration = nil
      adaptiveWasEnabled = adaptive
    }
    let generation = state.current
    if adaptive, cadenceGeneration != generation || cadenceFraming != framing {
      resetTracking()
      cadenceGeneration = generation
      cadenceFraming = framing
    }
    let measured = estimate?.status == FlowStatus.measured.rawValue
    if let paused = scorePausedGeneration,
       !ScannerLiveGuide.keepScorePause(state.isCurrent(paused), speed: measured ? estimate?.speed : nil,
                                        sourceWidth: estimate?.sourceWidth ?? 0) {
      scorePausedGeneration = nil
    }
    let scorePaused = scorePausedGeneration != nil
    let lost = estimate?.lostRegions ?? 0
    let searching = adaptive && (!tracking || !policy.hasReference)
    let carried = framing == flowRegionFraming ? flowRegion.flatMap { warp($0, to: sensorNs) } : nil
    // Flow carried the page outline to this frame. Lost regions only degrade it; the policy bounds how long.
    let reliable = carried != nil && measured && estimate?.hasTransform == true
    let upright = framing.framed
    let gap = adaptive
      ? policy.next(tracking: tracking, reliable: reliable, degraded: lost > 0, current: flowRegion,
                    width: upright.width, height: upright.height)
      : tracking ? LiveDetectionState.trackingGapNs : 0
    let speedLimit = searching ? Self.maxInferenceSpeedFraction / 2 : Self.maxInferenceSpeedFraction
    let moving = measured && ((!adaptive && lost > 0) || (estimate?.speed?.doubleValue).map {
      $0 / Double(framing.buffer.width) > speedLimit
    } == true)
    let eligibleGap = adaptive
      ? cadence.gap(now: startedAt, searching: searching, moving: moving, measured: measured && lost == 0,
                    trackingGap: gap, blurFraction: blur)
      : moving || scorePaused ? nil : gap
    let token = eligibleGap.flatMap { state.acquire(nowNs: startedAt, minGapNs: $0) }
    guard let token else {
      // Pacing and movement skip inference; flow's own consensus fit decides whether tracking holds.
      guard reliable, lost == 0, state.isCurrent(lastToken) else { return }
      if let held = guide.retainTracked(Self.numbers(carried), width: upright.width, height: upright.height, sensorNs: sensorNs,
                                     arrivalNs: startedAt), let accepted = held.accepted?.map(\.doubleValue) {
        acceptedId = held.acceptanceId
        acceptedFraming = framing
        flowRegion = accepted
        trackedRegionSensorNs = sensorNs
        publish(LiveGuideResult(status: .tracking, corners: accepted, framing: framing, acceptanceId: held.acceptanceId,
                                deadlineNs: held.deadlineNs, generation: lastToken,
                                modelGeneration: modelGeneration ?? 0, sequence: sequence, sensorNs: sensorNs))
      } else {
        // An expired or invalid tracked guide must wake inference instead of staying paused.
        policy.reset()
      }
      return
    }
    guard state.isCurrent(token) else { return }
    // A stop raced this frame; its token is stale.
    guard let lease = currentLease() else { return }
    if adaptive { cadence.didAttempt(at: startedAt) }
    let analysis: CornerAnalysis
    do {
      analysis = try lease.analyzeLive(buffer, orientation: framing.orientation, crop: framing.crop)
    } catch {
      if state.fail(token) {
        logger.error("live detection unavailable: \(String(describing: error), privacy: .public)")
        lock.lock()
        let ownsLease = self.lease === lease
        if ownsLease { self.lease = nil }
        lock.unlock()
        if ownsLease { lease.close() }
        resetTracking()
        publish(LiveGuideResult(status: .unavailable, generation: token))
      }
      return
    }
    if let previous = modelGeneration, previous != analysis.generation {
      // A replaced model starts over; this frame's token is stale from here on.
      modelGeneration = analysis.generation
      resetTracking()
      releaseFlow()
      publish(LiveGuideResult(status: .searching, generation: state.modelChanged(),
                              modelGeneration: analysis.generation))
      return
    }
    modelGeneration = analysis.generation
    let prediction = analysis.prediction
    let rejected = ScannerLiveGuide.scoreRejected(NSNumber(value: prediction.score), minimum: settings.minCornerScore)
    let candidate = rejected ? nil : prediction.corners
    if rejected {
      clearTrackedOutline()
      if !adaptive { scorePausedGeneration = token }
    }
    if token != lastToken || framing != lastFraming {
      guide.reset()
      tracking = false
      lastToken = token
      lastFraming = framing
    }
    let flowHeld = !rejected && reliable && lost == 0
      ? guide.retainTracked(Self.numbers(carried), width: upright.width, height: upright.height, sensorNs: sensorNs,
                         arrivalNs: startedAt)
      : nil
    let validated = guide.update(Self.numbers(candidate), width: upright.width,
                                     height: upright.height, sensorNs: sensorNs, arrivalNs: startedAt)
    let flowRetained = flowHeld != nil && validated.status != 1
    let status = flowRetained ? 1 : validated.status
    let accepted = validated.accepted?.map(\.doubleValue)
    tracking = status != 0
    if !flowRetained {
      if status == 1 {
        policy.reference(accepted)
        if adaptive { policy.confirmed() }
      } else if !adaptive || status == 0 {
        policy.reference(nil)
      }
    }
    flowRegion = accepted
    trackedRegionSensorNs = sensorNs
    flowRegionFraming = framing
    if validated.acceptanceId != acceptedId {
      acceptedId = validated.acceptanceId
      acceptedFraming = framing
    }
    var result = LiveGuideResult(status: .searching, framing: framing, generation: token,
                                 modelGeneration: analysis.generation, sequence: sequence, sensorNs: sensorNs,
                                 score: prediction.score)
    if status != 0, let accepted {
      result.status = status == 1 ? .tracking : .held
      result.corners = accepted
      result.framing = acceptedFraming
      result.acceptanceId = validated.acceptanceId
      result.deadlineNs = validated.deadlineNs
    }
    publish(result)
  }

  private func warp(_ corners: LiveCorners, to sensorNs: Int64) -> LiveCorners? {
    flow?.warp(Self.numbers(corners)!, fromNs: trackedRegionSensorNs, toNs: sensorNs)?
      .map(\.doubleValue)
  }

  private func updateFlow(_ buffer: CVPixelBuffer, framing: CameraFraming, sensorNs: Int64) -> ScannerFlowEstimate? {
    guard !flowFailed else { return nil }
    let tracker = flow ?? ScannerFlowTracker()
    flow = tracker
    let generation = state.current
    if generation != flowGeneration {
      tracker.reset()
      flowRegion = nil
      flowGeneration = generation
    }
    let region = framing == flowRegionFraming ? flowRegion : nil
    do {
      let estimate = try tracker.update(buffer, orientation: framing.orientation, crop: framing.crop,
                                        sensorNs: sensorNs, region: Self.numbers(region),
                                        fitTransform: true)
      if let region, let advanced = warp(region, to: sensorNs) {
        flowRegion = advanced
        trackedRegionSensorNs = sensorNs
      }
      return estimate
    } catch {
      logger.error("image motion estimation unavailable: \(String(describing: error), privacy: .public)")
      releaseFlow()
      flowFailed = true
      return nil
    }
  }

  /// Clears acceptance and pause state when the source, score policy or model changes.
  private func resetTracking() {
    cadence = AdaptiveInferenceCadence()
    scorePausedGeneration = nil
    clearTrackedOutline()
  }

  private func clearTrackedOutline() {
    guide.reset()
    tracking = false
    policy.reset()
    flowRegion = nil
  }

  private func releaseFlow() {
    flow = nil
    flowFailed = false
    flowRegion = nil
  }

  private static func numbers(_ corners: LiveCorners?) -> [NSNumber]? { corners?.map { NSNumber(value: $0) } }

  private static func copy(_ buffer: CVPixelBuffer, framing: CameraFraming, sensorNs: Int64) -> LiveFrame? {
    guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
          CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { return nil }
    defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
    guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
    let crop = framing.crop, stride = CVPixelBufferGetBytesPerRow(buffer)
    let width = Int(crop.width), height = Int(crop.height), row = width * 4
    var pixels = Data(count: row * height)
    pixels.withUnsafeMutableBytes { target in
      for y in 0..<height {
        let source = base + (Int(crop.minY) + y) * stride + Int(crop.minX) * 4
        (target.baseAddress! + y * row).copyMemory(from: source, byteCount: row)
      }
    }
    return LiveFrame(pixels: pixels, width: width, height: height, orientation: framing.orientation,
                     sensorNs: sensorNs)
  }

  // MARK: - Publication

  /// Coalesces to the newest result; main receives it on its next turn.
  private func publish(_ result: LiveGuideResult) {
    lastGuide = result.corners.map { ($0, result.framing, result.deadlineNs, result.generation) }
    lock.lock()
    let scheduled = pending != nil
    pending = result
    lock.unlock()
    if scheduled { return }
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      lock.lock(); let next = pending; pending = nil; lock.unlock()
      if let next { deliver(next) }
    }
  }

  private func deliver(_ result: LiveGuideResult) {
    dispatchPrecondition(condition: .onQueue(.main))
    guard state.isCurrent(result.generation) else { return }
    if result.corners != nil {
      // A frame analyzed before the deadline must not resurrect guidance that already expired.
      if result.acceptanceId <= expiredAcceptanceId { return }
      // An analysis or main-thread stall can deliver a result after its deadline; never draw it.
      if clock() >= result.deadlineNs {
        expire(result.acceptanceId, generation: result.generation)
        return
      }
      deliveredAcceptanceId = result.acceptanceId
      scheduleExpiry(result)
    } else {
      expiry?.cancel(); expiry = nil
    }
    onResult?(result)
  }

  private func scheduleExpiry(_ result: LiveGuideResult) {
    expiry?.cancel()
    let id = result.acceptanceId, generation = result.generation
    let item = DispatchWorkItem { [weak self] in
      guard let self, state.isCurrent(generation), deliveredAcceptanceId == id else { return }
      expire(id, generation: generation)
    }
    expiry = item
    let delay = max(0, result.deadlineNs - clock())
    DispatchQueue.main.asyncAfter(deadline: .now() + .nanoseconds(Int(delay)), execute: item)
  }

  private func expire(_ acceptanceId: Int64, generation: Int) {
    expiry?.cancel(); expiry = nil
    expiredAcceptanceId = max(expiredAcceptanceId, acceptanceId)
    onResult?(LiveGuideResult(status: .searching, generation: generation))
  }
}
