import Foundation

/// Who holds the lens: a tap's or a quality capture's scan, each ending in a held lens.
enum FocusOwner: Equatable, Sendable { case tap, capture }

/// Focus as everything else reads it. `generation` changes whenever a scan starts, settles or ends;
/// `changedNs` is that moment on the host clock (mach absolute time, the domain of `DispatchTime`
/// uptime and of normalized frame times). `owner` is nil while nothing holds the lens and continuous
/// autofocus runs. `locked` is nil while a scan scans and after a scan whose result is unknown,
/// which is every completed scan on iOS: AVFoundation reports that a scan ended, not that it focused.
struct FocusSnapshot: Equatable, Sendable {
  var generation: Int64 = 0
  var owner: FocusOwner?
  var locked: Bool?
  var pending = false
  var heldUntilNs: Int64 = 0
  var changedNs: Int64 = 0

  /// A tap's or a capture's scan still holds the lens, scanning or settled.
  func scanHolds(nowNs: Int64) -> Bool { owner != nil && nowNs < heldUntilNs }

  /// A tap's or a capture's scan has not settled yet.
  func scanning(nowNs: Int64) -> Bool { scanHolds(nowNs: nowNs) && pending }
}

/// Focus ownership without hardware or a clock: who holds the lens, the current scan's token and
/// result, the tap's hold, and a generation that rises on every change. Without an owner,
/// continuous autofocus runs. Times are monotonic nanoseconds. Kotlin's `FocusPolicy` implements
/// the same rules. Main only.
struct FocusPolicy {
  static let tapHoldNs: Int64 = 5_000_000_000
  static let scanTimeoutNs: Int64 = 2_000_000_000

  private(set) var generation: Int64 = 0
  private(set) var scanToken: Int64 = 0
  private(set) var owner: FocusOwner?
  /// A settled scan's result; nil while it scans and after an unknown result.
  private(set) var locked: Bool?
  /// Until when a tap holds the lens; `Int64.max` for a capture, 0 without an owner.
  private(set) var heldUntilNs: Int64 = 0
  /// The owner's scan has not settled.
  private(set) var pending = false
  private var scanDeadlineNs: Int64 = 0

  /// Gives the lens to `owner` and returns the new scan's token.
  mutating func start(_ owner: FocusOwner, nowNs: Int64) -> Int64 {
    generation += 1
    scanToken = generation
    self.owner = owner
    locked = nil
    pending = true
    heldUntilNs = owner == .tap ? nowNs + Self.tapHoldNs : .max
    scanDeadlineNs = nowNs + Self.scanTimeoutNs
    return scanToken
  }

  /// Settles scan `token`: true for a lock, false for a failure, nil for an unknown result.
  mutating func complete(_ token: Int64, success: Bool?) -> Bool {
    guard owner != nil, token == scanToken, pending else { return false }
    pending = false
    locked = success
    generation += 1
    return true
  }

  mutating func expireTap(_ token: Int64, nowNs: Int64, shot: Bool) -> Bool {
    guard !shot, owner == .tap, token == scanToken, nowNs >= heldUntilNs else { return false }
    release()
    return true
  }

  /// A shot finished: a tap past its hold and a capture's scan end.
  mutating func afterCapture(nowNs: Int64) -> Bool {
    switch owner {
    case .tap: return expireTap(scanToken, nowNs: nowNs, shot: false)
    case .capture: release(); return true
    case nil: return false
    }
  }

  mutating func unbind() { release() }

  var tapDeadline: Int64? { owner == .tap ? heldUntilNs : nil }
  var scanDeadline: Int64? { owner == .capture && pending ? scanDeadlineNs : nil }

  private mutating func release() {
    owner = nil
    locked = nil
    heldUntilNs = 0
    pending = false
    generation += 1
  }
}

/// Auto-torch decision, confined by its caller. Auto-exposure already measures the room: exposure
/// time times ISO grows as light falls, so sustained dark frames light the torch with no pixel work.
/// Once lit, that measure reflects the torch rather than the room, so the decision holds until
/// `reset`. Kotlin implements the same policy; both pass `tests/fixtures/parity/torch.tsv`.
struct TorchPolicy {
  /// About 1/15 s at ISO 1600, as in Kotlin: 20,000 counted a lamp-lit room as dark on the SM-G998U.
  static let darkIsoMs = 100_000.0
  static let sustainNs: Int64 = 1_000_000_000

  private var darkSince: Int64?
  private(set) var lit = false

  mutating func reset() {
    interrupt()
    lit = false
  }

  mutating func interrupt() { darkSince = nil }

  /// Returns true on the observation that lights the torch. `observationNs` is a monotonic time,
  /// separate from the exposure duration; `iso` is nil when unreported.
  mutating func observe(observationNs: Int64, exposureNs: Int64, iso: Double?) -> Bool {
    if lit { return false }
    guard let iso, iso > 0, exposureNs > 0, Double(exposureNs) / 1e6 * iso >= Self.darkIsoMs else {
      darkSince = nil
      return false
    }
    let since = darkSince ?? observationNs
    darkSince = since
    // Wrapping subtraction: observation times are monotonic and may exceed 2^53.
    guard observationNs &- since >= Self.sustainNs else { return false }
    lit = true
    return true
  }
}

/// A frame-rate interval the active format supports, or one chosen within it.
struct FrameRateRange: Equatable, Sendable {
  let min: Double
  let max: Double
}

/// Maps the exposure props onto what the configured device advertises.
enum CameraExposurePolicy {
  static let preferredMaxFps = 30.0

  /// The target bias for `ev` stops, clamped to the device's range; nil when the device has no
  /// adjustable bias. AVFoundation's bias is continuous, so there is no step to round to.
  static func bias(_ ev: Double, range: ClosedRange<Double>?) -> Double? {
    guard let range, range.lowerBound < range.upperBound else { return nil }
    return Swift.min(Swift.max(ev.isFinite ? ev : 0, range.lowerBound), range.upperBound)
  }

  /// Android's choice over AVFoundation's continuous intervals: the lowest minimum the format can
  /// run at or above `minFps` (the highest reachable minimum when none reaches it), then the maximum
  /// nearest 30 within that interval. Nil when `minFps` is not positive or nothing is advertised,
  /// which restores the format's own durations.
  static func frameRate(minFps: Double, ranges: [FrameRateRange]) -> FrameRateRange? {
    guard minFps > 0, minFps.isFinite else { return nil }
    let valid = ranges.filter { $0.min > 0 && $0.min <= $0.max && $0.max.isFinite }
    let reaching = valid.filter { $0.max >= minFps }
    let candidates: [(lower: Double, range: FrameRateRange)] = reaching.isEmpty
      ? valid.map { ($0.max, $0) }
      : reaching.map { (Swift.max(minFps, $0.min), $0) }
    let best = reaching.isEmpty
      ? candidates.max { a, b in a.lower < b.lower || a.lower == b.lower && nearest(a) > nearest(b) }
      : candidates.min { a, b in a.lower < b.lower || a.lower == b.lower && nearest(a) < nearest(b) }
    guard let best else { return nil }
    return FrameRateRange(min: best.lower, max: upper(best))
  }

  private static func upper(_ candidate: (lower: Double, range: FrameRateRange)) -> Double {
    Swift.min(Swift.max(preferredMaxFps, candidate.lower), candidate.range.max)
  }

  private static func nearest(_ candidate: (lower: Double, range: FrameRateRange)) -> Double {
    abs(upper(candidate) - preferredMaxFps)
  }
}
