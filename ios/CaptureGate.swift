import Foundation

// MARK: - Frame evidence

/// The shared flow estimator's status, in the portable `motion::Status` order.
enum FlowStatus: Int, Equatable, Sendable {
  case measured, noReference, tooFewFeatures, trackingFailed, poorCoverage
}

/// One analyzed frame's motion from the previous analyzed frame, as the shared estimator measured it.
struct FlowEvidence: Equatable, Sendable {
  let status: FlowStatus
  /// Pixels per second of the analyzed image, nil when not measured.
  let speedPxPerSecond: Double?
  let sourceWidth: Int
  let lostRegions: Int
  /// Host time of the frame the estimate starts at.
  let fromNs: Int64
}

/// The capture evidence of one analyzed frame, built on the analysis owner in frame order. Times are
/// host-clock nanoseconds (the frame's presentation time). `exposureNs` and `iso` are nil when
/// unreported; `exposureEstimated` says they are the device's settings at delivery rather than the
/// frame's own EXIF. `blur` is the flow-measured smear over the exposure as a fraction of the frame
/// width, nil when flow or exposure is unknown. iOS has no gyroscope fallback, so a low-texture
/// frame has unknown motion. `focusAdjusting` is the device's `isAdjustingFocus` sampled with the
/// frame, nil without a device. `focusReachedNs` is the time of the first still frame after the
/// lens last moved, nil before any movement was seen this run; `pageArrivedNs` is the first frame
/// of the current page sighting. `guide` is the confirmed page outline in effect after this frame,
/// normalized to `framing.framed`.
struct FrameRecord: Equatable, Sendable {
  let sequence: Int
  let sensorNs: Int64
  let framing: CameraFraming
  let flow: FlowEvidence?
  let exposureNs: Int64?
  let iso: Double?
  let exposureEstimated: Bool
  let blur: Double?
  let focusAdjusting: Bool?
  let focusReachedNs: Int64?
  let pageArrivedNs: Int64?
  let guide: LiveCorners?
  var inferenceFinished = false

  /// Exposure time in ms times ISO, the auto torch's darkness measure; nil when either is unknown.
  var exposureIsoMs: Double? {
    guard let exposureNs, exposureNs > 0, let iso, iso > 0 else { return nil }
    return Double(exposureNs) / 1e6 * iso
  }
}

/// Recent frame records in order, so a reader that fell behind still sees every frame. Bounded: a
/// reader more than `capacity` frames behind is told it missed some. Written on the analysis owner,
/// read on main.
final class FrameHistory: @unchecked Sendable {
  private let lock = NSLock()
  private let capacity: Int
  private var entries: [FrameRecord] = []
  private var nextSequence = 1

  init(capacity: Int = 32) { self.capacity = capacity }

  /// The sequence the next appended frame will get; a reader starting now passes it to `since`.
  func cursor() -> Int { lock.lock(); defer { lock.unlock() }; return nextSequence }

  func append(_ make: (_ sequence: Int) -> FrameRecord) {
    lock.lock(); defer { lock.unlock() }
    entries.append(make(nextSequence))
    nextSequence += 1
    if entries.count > capacity { entries.removeFirst(entries.count - capacity) }
  }

  /// Frames from `sequence` on, oldest first, and whether earlier ones from `sequence` were dropped.
  func since(_ sequence: Int) -> (frames: [FrameRecord], missed: Bool) {
    lock.lock(); defer { lock.unlock() }
    let newer = entries.filter { $0.sequence >= sequence }
    return (newer, (newer.first?.sequence ?? nextSequence) > sequence)
  }

  /// Drops every frame; sequences keep rising, so an old cursor never matches a new run's frames.
  func clear() { lock.lock(); entries.removeAll(); lock.unlock() }
}

// MARK: - Stability

enum StabilityBreak: Equatable, Sendable { case moving, smeared, trackingLost, noDocument, documentMoved, unfocused }

/// Framing stability over consecutive analyzed frames, the same rules as Kotlin's
/// `CaptureStability`: flow from the previous analyzed frame under `maxSpeed` frame widths per
/// second, the frame's own smear within the blur limit, and, with a page required, the page
/// holding its position, scale and shape. Missing or broken flow, a frame whose flow does not start
/// at the sequence's last frame, and unknown smear never continue a sequence. A frame without
/// exposure has unknown smear: it checks speed and geometry and keeps the sequence unbroken, but
/// does not count. Main only.
struct CaptureStability {
  static let requiredFrames = 4
  static let maxSpeed = 0.05
  static let maxShift = 0.02
  static let maxScale = 0.03
  static let maxShape = 0.02

  private let required: Int
  private var reference: LiveCorners?
  private(set) var count = 0
  private(set) var latestSensorNs: Int64 = 0
  private var chainSensorNs: Int64 = 0
  private(set) var lastBreak: StabilityBreak?

  init(required: Int = CaptureStability.requiredFrames) { self.required = required }

  var stable: Bool { count >= required }

  mutating func reset() {
    count = 0
    reference = nil
    latestSensorNs = 0
    chainSensorNs = 0
  }

  mutating func interrupt(_ reason: StabilityBreak = .trackingLost) { restart(reason) }

  @discardableResult
  mutating func observe(_ frame: FrameRecord, requireDocument: Bool, blurLimit: Double) -> Bool {
    guard let flow = frame.flow else { return restart(.trackingLost) }
    if count > 0 && flow.fromNs != chainSensorNs { restart(.trackingLost) }
    let speed: Double
    switch flow.status {
    case .measured:
      guard flow.lostRegions == 0, let measured = flow.speedPxPerSecond, flow.sourceWidth > 0 else {
        return restart(.trackingLost)
      }
      speed = measured / Double(flow.sourceWidth)
    case .noReference, .trackingFailed, .tooFewFeatures, .poorCoverage:
      // Without a gyroscope fallback, too little texture is unknown motion.
      return restart(.trackingLost)
    }
    if speed > Self.maxSpeed { return restart(.moving) }
    let smearUnknown = blurLimit > 0 && frame.exposureNs == nil
    if blurLimit > 0 && !smearUnknown {
      guard let blur = frame.blur, blur <= blurLimit else { return restart(.smeared) }
    }
    if requireDocument {
      guard let corners = frame.guide else { return restart(.noDocument) }
      if let reference, Self.drift(from: reference, to: corners) != nil { restart(.documentMoved) }
      if reference == nil { reference = corners }
    }
    if smearUnknown {
      if count > 0 { chainSensorNs = frame.sensorNs }
      return stable
    }
    chainSensorNs = frame.sensorNs
    latestSensorNs = frame.sensorNs
    count += 1
    return stable
  }

  @discardableResult
  private mutating func restart(_ reason: StabilityBreak) -> Bool {
    reset()
    lastBreak = reason
    return false
  }

  /// Which of position, scale or shape `to` moved beyond its limit from `from`; nil while it holds.
  static func drift(from a: LiveCorners, to b: LiveCorners) -> String? {
    guard a.count == 8, b.count == 8 else { return "shape" }
    func centroid(_ c: LiveCorners) -> (Double, Double) { ((c[0] + c[2] + c[4] + c[6]) / 4, (c[1] + c[3] + c[5] + c[7]) / 4) }
    func area(_ c: LiveCorners) -> Double {
      var sum = 0.0
      for i in 0..<4 {
        let j = (i + 1) % 4
        sum += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]
      }
      return abs(sum) / 2
    }
    let (ax, ay) = centroid(a), (bx, by) = centroid(b)
    if hypot(bx - ax, by - ay) > maxShift { return "position" }
    let scale = (area(b) / area(a)).squareRoot()
    if !scale.isFinite || abs(scale - 1) > maxScale { return "scale" }
    var shape = 0.0
    for i in 0..<4 {
      let dx = (b[i * 2] - bx) / scale - (a[i * 2] - ax)
      let dy = (b[i * 2 + 1] - by) / scale - (a[i * 2 + 1] - ay)
      shape = max(shape, hypot(dx, dy))
    }
    return shape > maxShape ? "shape" : nil
  }
}

// MARK: - Decisions

/// What a waiting capture waits for, in the order it is reported.
enum GateWait: Equatable, Sendable {
  case document, focus, light, steady

  var publicReason: CaptureWaitReason {
    switch self {
    case .document: return .document
    case .focus: return .focus
    case .light: return .light
    case .steady: return .steady
    }
  }
}

/// Why a capture's first focus scan starts. Android's unseen-page trigger belongs to its light
/// fallback and has no iOS counterpart.
enum ScanTrigger: Equatable, Sendable { case quality, staleFocus, dark }

/// The capture's own focus scan as the controls last published it. `settled` is a scan that ended
/// with an unknown result, which is every completed AVFoundation scan: neither proof of focus nor a
/// failure to retry. `failed` is a known failure: the device refused the scan or it timed out.
enum OwnScan: Equatable, Sendable { case none, scanning, settled, locked, failed }

enum CaptureDecision: Equatable, Sendable {
  /// Re-evaluate on the next event, and at `untilMs` at the latest.
  case wait(GateWait, untilMs: Int64)
  /// Start the capture's own scan, its `attempt`th; `trigger` is why the first started.
  case startFocusScan(ScanTrigger, attempt: Int)
  case shoot
  case reject(ScannerErrorCode, GateWait?)
}

/// Inputs to one readiness judgment, the same fields as Kotlin's `CaptureState`. `bufferCheckEnabled`
/// and `bufferReady` are a zero-shutter-lag buffer's eligibility; the iOS adapter never enables
/// them, since AVFoundation exposes no such evidence. `focusingUntilMs` is until when autofocus
/// counts as still moving, `Int64.max` while a scan is pending.
struct CaptureEvidence: Equatable, Sendable {
  var nowMs: Int64
  var focusCheckEnabled: Bool
  var documentRequired: Bool
  var requireFocus: Bool
  var focusTimeoutMs: Int64
  var documentFound: Bool
  var focused: Bool
  var bufferCheckEnabled = false
  var bufferReady = true
  var lightReady: Bool
  var motionCheckEnabled: Bool
  var steady: Bool
  var focusingUntilMs: Int64? = nil
  var scanAvailable = false
  var scanTrigger: ScanTrigger? = nil
  var ownScan = OwnScan.none
  var frameSinceFocusChange = true
  var postFocusInferenceFinished: Bool? = nil
}

/// The single owner of a capture's readiness, its own focus scans and its deadlines; Kotlin's
/// `CaptureGate` implements the same rules and both pass `tests/fixtures/parity/capture-events.tsv`.
/// Focus, with its page when one is required, comes first and waits up to the focus timeout from
/// the first judgment; then everything must align within `steadyTimeoutMs`, which restarts when focus
/// and its page are accepted again after a loss. Autofocus still moving
/// extends a focus wait past either deadline. A capture that does not require focus shoots without
/// it at a deadline, still needing light and steadiness. A skipped gate waits only for a scan in
/// progress and never scans. The first judgment where everything holds shoots.
///
/// Scans: a first scan starts when its trigger holds before the focus deadline. A known failure is
/// retried once whatever its trigger shows by then. Motion never starts a scan: it only delays the
/// shot, or rejects it at the steady deadline. A scan that settles with an unknown result is never retried. A scan in progress
/// holds the page and focus waits past their deadline. After it settles, a missing page is judged by fresh
/// inference. The focus check allows up to `settledFrameWaitMs` for fresh frame evidence. Main only.
struct CaptureGate {
  static let focusTimeoutMs: Int64 = 4_000
  static let steadyTimeoutMs: Int64 = 1_000
  static let maxScans = 2
  static let settledFrameWaitMs: Int64 = 500

  private let steadyTimeoutMs: Int64
  private let skipLowQualityControl: Bool
  private let maxScans: Int
  private let settledFrameWaitMs: Int64
  private var focusDeadlineMs: Int64 = 0
  private var steadyDeadlineMs: Int64 = 0
  /// Focus, with its page when required, held at the previous judgment since the steady deadline was set.
  private var focusHeld = false
  private(set) var focusSkipped = false
  private(set) var scans = 0
  private var trigger: ScanTrigger?
  private var lastOwnScan = OwnScan.none
  private var scanSettledMs: Int64?

  init(steadyTimeoutMs: Int64 = CaptureGate.steadyTimeoutMs, skipLowQualityControl: Bool = false,
       maxScans: Int = CaptureGate.maxScans, settledFrameWaitMs: Int64 = CaptureGate.settledFrameWaitMs) {
    self.steadyTimeoutMs = steadyTimeoutMs
    self.skipLowQualityControl = skipLowQualityControl
    self.maxScans = maxScans
    self.settledFrameWaitMs = settledFrameWaitMs
  }

  mutating func next(_ e: CaptureEvidence) -> CaptureDecision {
    let now = e.nowMs
    if e.ownScan == .scanning {
      scanSettledMs = nil
    } else if lastOwnScan == .scanning {
      scanSettledMs = now
    }
    lastOwnScan = e.ownScan
    if skipLowQualityControl {
      if e.focusCheckEnabled, !e.focused, let until = e.focusingUntilMs, until > now { return .wait(.focus, untilMs: until) }
      return .shoot
    }
    if let scan = scanToStart(e) { return scan }
    func focusingUntil(_ reason: GateWait?) -> Int64? {
      guard reason == .document || reason == .focus else { return nil }
      if e.ownScan == .scanning { return .max }
      if let settled = scanSettledMs {
        let until = settled + settledFrameWaitMs
        if !e.frameSinceFocusChange && until > now { return until }
      }
      if reason == .focus, let until = e.focusingUntilMs, until > now { return until }
      return nil
    }
    func blocker() -> GateWait? {
      let needsFocus = e.focusCheckEnabled && !focusSkipped
      if needsFocus && e.documentRequired && !e.documentFound { return .document }
      if needsFocus && !e.focused { return .focus }
      if !e.lightReady || e.bufferCheckEnabled && !e.bufferReady { return .light }
      if e.motionCheckEnabled && !e.steady { return .steady }
      return nil
    }
    if blocker() == .document, e.requireFocus, let finished = e.postFocusInferenceFinished {
      return finished ? .reject(.captureDocumentTimeout, .document) : .wait(.document, untilMs: .max)
    }
    if steadyDeadlineMs == 0 {
      let reason = blocker()
      if reason == .document || reason == .focus {
        if focusDeadlineMs == 0 { focusDeadlineMs = now + e.focusTimeoutMs }
        if now < focusDeadlineMs { return .wait(reason!, untilMs: focusDeadlineMs) }
        if let until = focusingUntil(reason) { return .wait(reason!, untilMs: until) }
        if e.requireFocus { return .reject(Self.timeoutCode(reason!), reason) }
        focusSkipped = true
      }
      steadyDeadlineMs = now + steadyTimeoutMs
      focusHeld = true
    }
    let blocked = blocker()
    // Focus and its page accepted again after a loss restart the steadiness window, as the view
    // restarts its frame count: a refocus that ends late must still leave time for the steady frames.
    let holds = blocked != .document && blocked != .focus
    if holds && !focusHeld { steadyDeadlineMs = now + steadyTimeoutMs }
    focusHeld = holds
    guard var reason = blocked else { return .shoot }
    if now < steadyDeadlineMs { return .wait(reason, untilMs: steadyDeadlineMs) }
    if let until = focusingUntil(reason) { return .wait(reason, untilMs: until) }
    if (reason == .document || reason == .focus) && !e.requireFocus {
      focusSkipped = true
      guard let next = blocker() else { return .shoot }
      reason = next
    }
    switch reason {
    case .document, .focus: return .reject(Self.timeoutCode(reason), reason)
    // Light that never settled is a capture failure, as on Android.
    case .light: return .reject(.captureFailed, reason)
    case .steady: return .reject(.captureMotionTimeout, reason)
    }
  }

  /// A missing page and a missing focus fail with their own codes; the page is checked first.
  private static func timeoutCode(_ reason: GateWait) -> ScannerErrorCode {
    reason == .document ? .captureDocumentTimeout : .captureFocusTimeout
  }

  private mutating func scanToStart(_ e: CaptureEvidence) -> CaptureDecision? {
    guard e.scanAvailable, !focusSkipped else { return nil }
    let retry = e.ownScan == .failed && scans < maxScans
    let first = scans == 0 && e.scanTrigger != nil && (focusDeadlineMs == 0 || e.nowMs < focusDeadlineMs)
    if first { trigger = e.scanTrigger }
    guard first || retry, let why = trigger else { return nil }
    scans += 1
    return .startFocusScan(why, attempt: scans)
  }
}
