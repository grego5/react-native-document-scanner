import Foundation

/// Drawing values for a single stage, sampled in seconds from a monotonic clock.
enum CaptureFeedbackTimeline {
  struct Frame {
    let outline: Double
    var sweep: Double = 0
    var line: Double = 0
    var fill: Double = 0
    var finished = false
  }

  static func waiting(_ elapsed: Double) -> Frame {
    Frame(outline: 0.45 + 0.55 * (0.5 + 0.5 * cos(2 * .pi * max(0, elapsed) / 0.7)))
  }

  static func shooting(_ elapsed: Double) -> Frame {
    Frame(outline: 0.35, sweep: 0.9 * (1 - exp(-max(0, elapsed) / (0.35 / 1.9))), line: 1)
  }

  static func captured(_ elapsed: Double, from sweep: Double) -> Frame {
    let close = 0.09
    if elapsed < close {
      let progress = max(0, elapsed) / close
      return Frame(outline: 0.35, sweep: sweep + (1 - sweep) * progress, line: 1, fill: 0.3 * progress)
    }
    let fade = max(0, 1 - (elapsed - close) / 0.26)
    return Frame(outline: 0.35 * fade, sweep: 1, line: fade, fill: 0.3 * fade, finished: elapsed >= close + 0.26)
  }

  static func failed(_ elapsed: Double) -> Frame {
    let blink = 0.11
    let end = 4 * blink
    if elapsed < end {
      return Frame(outline: Int(max(0, elapsed) / blink).isMultiple(of: 2) ? 1 : 0.25)
    }
    return Frame(outline: max(0, 1 - (elapsed - end) / 0.26), finished: elapsed >= end + 0.26)
  }
}
