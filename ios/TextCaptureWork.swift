import CoreGraphics
import CoreVideo
import Foundation

/// A capture waiting for a read frame until `deadline`, in seconds of system uptime; `done` takes the capture or the
/// failure's code, once, and `settled` follows it. Immutable, so it passes from main to the video queue.
final class PendingTextCapture: Sendable {
  let deadline: Double
  private let done: @Sendable (TextCapture?, ScannerErrorCode?) -> Void
  private let settled: @Sendable () -> Void

  init(deadline: Double, done: @escaping @Sendable (TextCapture?, ScannerErrorCode?) -> Void,
       settled: @escaping @Sendable () -> Void) {
    self.deadline = deadline
    self.done = done
    self.settled = settled
  }

  func resolve(_ capture: TextCapture) {
    done(capture, nil)
    settled()
  }

  func fail(_ code: ScannerErrorCode) {
    done(nil, code)
    settled()
  }
}

/// What one frame is read against: the region; `crop`, where it lies in the sensor-oriented buffer, normalized; the
/// clockwise `turn` in degrees that shows it upright, as the preview does; and the revision that drops frames kept
/// for an earlier crop. Copied between main and the video queue.
struct TextFrameTarget: @unchecked Sendable {
  let region: TextRegion
  let crop: CGRect
  let turn: Int
  let revision: Int
}

/// The text camera's frames and captures. Confined to the video queue, which is why it may be handed to it.
///
/// Every frame's region crop is scored, and the sharpest of the last second are kept. A press reads the sharpest
/// kept frame it has not read yet, and each new frame while it waits, and is served by the first that finds text.
/// Past a capture's deadline the sharpest kept frame serves it, text or not; without a frame it fails with
/// `capture/not-ready`. When the camera stops or the session leaves, waiting captures take the sharpest kept frame
/// now, or fail with `camera/unavailable`.
final class TextCaptureWork: @unchecked Sendable {
  private var ring = TextFrameRing()
  private var revision: Int?
  private var nextFrame = 0
  private var captures: [PendingTextCapture] = []
  /// What each kept frame read, while captures wait: its words, or nil when the recognizer failed on it.
  private var reads: [Int: [TextWord]?] = [:]
  /// Reads `crop`; replaced in tests.
  var recognize: (LumaCrop) throws -> [TextWord] = { crop in
    guard let image = greyImage(crop) else { throw TextReadError() }
    return textWords(try readTextLines(image), width: Double(crop.width), height: Double(crop.height))
  }
  /// Encodes `crop` as the capture's URI; replaced in tests.
  var encode: (LumaCrop) -> String? = { crop in greyImage(crop).flatMap { jpegDataURI($0) } }

  /// Keeps an admitted buffer's crop, turned upright, when it is among the sharpest of the last second, then serves
  /// waiting captures.
  func observe(_ buffer: CVPixelBuffer, time: Double, target: TextFrameTarget, now: Double) {
    keep(for: target)
    let kept: KeptTextFrame? = withLumaPlane(buffer) { plane, rowBytes, width, height in
      guard let rect = textRegionPixels(target.crop, width: width, height: height) else { return nil }
      let sharpness = lumaSharpness(plane, rowBytes: rowBytes, rect: rect)
      guard ring.wants(time: time, sharpness: sharpness) else { return nil }
      nextFrame += 1
      return KeptTextFrame(id: nextFrame, time: time, sharpness: sharpness, region: target.region,
                           crop: turnedLuma(lumaCrop(plane, rowBytes: rowBytes, rect: rect), clockwise: target.turn))
    }
    if let kept { ring.add(kept) }
    serve(now: now)
  }

  /// Keeps `frame` as given; for tests.
  func keep(_ frame: KeptTextFrame, target: TextFrameTarget) {
    keep(for: target)
    ring.add(frame)
  }

  func enqueue(_ capture: PendingTextCapture, target: TextFrameTarget, now: Double) {
    captures.append(capture)
    serve(target: target, now: now)
  }

  /// A timer belongs to one press and can wake the worker only while that press is waiting.
  func deadlineReached(for capture: PendingTextCapture, target: TextFrameTarget, now: Double) {
    guard captures.contains(where: { $0 === capture }) else { return }
    serve(target: target, now: now)
  }

  /// Serves waiting captures from what is kept for `target` only, so a press right after a new region, before a frame
  /// of it arrives, never reads the old crop.
  func serve(target: TextFrameTarget, now: Double) {
    keep(for: target)
    serve(now: now)
  }

  /// The camera stopped, or the session the captures came from left it: they take the sharpest frame kept for
  /// `target`, as it was then. Frames kept for another crop, older or newer, are not theirs to take, and stay.
  func settle(target: TextFrameTarget) {
    guard !captures.isEmpty else { return }
    guard target.revision == revision, let frame = ring.sharpest() else {
      failAll(.cameraUnavailable)
      return
    }
    guard let words = wordsOf(frame) else {
      failAll(.captureFailed)
      return
    }
    deliver(frame, words)
  }

  /// Forgets the kept frames, as when the camera stops.
  func clear() {
    ring.clear()
    reads = [:]
  }

  private func keep(for target: TextFrameTarget) {
    guard target.revision != revision else { return }
    revision = target.revision
    clear()
  }

  private func serve(now: Double) {
    guard !captures.isEmpty else { return }
    // The sharpest frame not read yet; each found without text leaves the next sharpest for the next try.
    if let frame = ring.frames.filter({ reads[$0.id] == nil }).max(by: { $0.sharpness < $1.sharpness }),
       let words = wordsOf(frame), !words.isEmpty {
      deliver(frame, words)
      return
    }
    let expired = captures.filter { now >= $0.deadline }
    guard !expired.isEmpty else { return }
    guard let frame = ring.sharpest() else {
      fail(expired, .captureNotReady)
      return
    }
    guard let words = wordsOf(frame) else {
      fail(expired, .captureFailed)
      return
    }
    // Only the presses whose time is up take a frame without text; the others keep waiting for one with it.
    deliver(frame, words, to: expired)
  }

  /// `frame`'s words, read once while captures wait; nil when the recognizer failed on it.
  private func wordsOf(_ frame: KeptTextFrame) -> [TextWord]? {
    if let read = reads[frame.id] { return read }
    let words = try? recognize(frame.crop)
    reads[frame.id] = .some(words)
    return words
  }

  /// Resolves `to`, every waiting capture by default, with `frame` and its `words`, encoded once.
  private func deliver(_ frame: KeptTextFrame, _ words: [TextWord], to: [PendingTextCapture]? = nil) {
    let waiting = to ?? captures
    captures.removeAll { capture in waiting.contains { $0 === capture } }
    if captures.isEmpty { reads = [:] }
    guard let uri = encode(frame.crop) else {
      waiting.forEach { $0.fail(.captureFailed) }
      return
    }
    let capture = TextCapture(uri: uri, width: Double(frame.crop.width), height: Double(frame.crop.height),
                              region: frame.region, words: words)
    waiting.forEach { $0.resolve(capture) }
  }

  private func fail(_ expired: [PendingTextCapture], _ code: ScannerErrorCode) {
    captures.removeAll { capture in expired.contains { $0 === capture } }
    if captures.isEmpty { reads = [:] }
    expired.forEach { $0.fail(code) }
  }

  private func failAll(_ code: ScannerErrorCode) {
    let waiting = captures
    captures = []
    reads = [:]
    waiting.forEach { $0.fail(code) }
  }
}

private struct TextReadError: Error {}
