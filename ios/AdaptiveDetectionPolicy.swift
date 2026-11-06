import Foundation

/// Normalized upright TL/TR/BR/BL corners as x/y pairs for the live guide.
typealias LiveCorners = [Double]

/// Adaptive inference pacing, confined to the analysis owner. Once a trusted model result is
/// confirmed, inference pauses while flow reliably carries it. Losing or degrading the track,
/// or a corner straying from the confirmed outline, asks the model to confirm again. Kotlin
/// implements the same policy; both pass `tests/fixtures/parity/adaptive.tsv`.
struct AdaptiveDetectionPolicy {
  static let revalidateGapNs: Int64 = 60_000_000
  static let paused = Int64.max

  private var reference: LiveCorners?
  private var revalidate = false
  /// Why the latest decision was made: acquiring, paused, revalidating or a wake reason.
  private(set) var status = "acquiring"
  /// The latest wake reason, kept across resets for diagnostics.
  private(set) var lastReset = "none"
  /// The wake reason the latest `next` found against the reference, if any.
  private(set) var doubt: String?
  var hasReference: Bool { reference != nil }
  var isPaused: Bool { status == "paused" }

  mutating func reset() {
    revalidate = false
    reference = nil
    status = "acquiring"
  }

  /// The model's latest result, or nil when it found no tracked page.
  mutating func reference(_ corners: LiveCorners?) { reference = corners }

  /// The model confirmed the reference; a pending revalidation is satisfied.
  mutating func confirmed() { revalidate = false }

  /// The gap before the next inference in nanoseconds; `paused` stops it. `tracking`: the guide
  /// still holds a page; `reliable`: flow carried it to this frame as `current`; `degraded`: some
  /// page region lost its features. `width` and `height` are the upright frame in pixels.
  mutating func next(tracking: Bool, reliable: Bool, degraded: Bool, current: LiveCorners?, width: Int,
                     height: Int) -> Int64 {
    let trusted = tracking && reference != nil
    var wake: String?
    if let reference, reliable, let current, Self.changed(reference, current, width: width, height: height) {
      wake = "scene changed"
    }
    doubt = wake
    if !trusted || !reliable || degraded || wake != nil {
      revalidate = trusted
      status = wake ?? (!trusted ? "unconfirmed" : !reliable ? "tracking lost" : "tracking degraded")
      lastReset = status
      return trusted ? Self.revalidateGapNs : 0
    }
    if revalidate {
      status = "revalidating"
      return Self.revalidateGapNs
    }
    status = "paused"
    return Self.paused
  }

  /// Any corner moved more than 5% of the upright frame width; translation counts.
  static func changed(_ a: LiveCorners, _ b: LiveCorners, width: Int, height: Int) -> Bool {
    guard a.count == 8, b.count == 8 else { return true }
    return stride(from: 0, to: 8, by: 2).contains { index in
      hypot((a[index] - b[index]) * Double(width), (a[index + 1] - b[index + 1]) * Double(height))
        > Double(width) * 0.05
    }
  }
}
