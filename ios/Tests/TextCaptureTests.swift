import CoreGraphics
import XCTest
@testable import ReactNativeDocumentScanner

/// Hardware-free text capture: the kept frames, crop geometry, word order, the capture workflow and the session's
/// captures.
final class TextCaptureTests: XCTestCase {
  private let region = TextRegion(x: 0.1, y: 0.4, width: 0.8, height: 0.2)

  /// What captures settled with, collected from `@Sendable` callbacks.
  private final class Results: @unchecked Sendable {
    private let lock = NSLock()
    private var captures: [TextCapture] = []
    private var failures: [ScannerErrorCode] = []

    func add(_ capture: TextCapture?, _ code: ScannerErrorCode?) {
      lock.lock()
      defer { lock.unlock() }
      if let capture { captures.append(capture) }
      if let code { failures.append(code) }
    }

    var taken: [TextCapture] { lock.lock(); defer { lock.unlock() }; return captures }
    var codes: [ScannerErrorCode] { lock.lock(); defer { lock.unlock() }; return failures }
  }

  private func frame(_ id: Int, time: Double, sharpness: Double) -> KeptTextFrame {
    KeptTextFrame(id: id, time: time, sharpness: sharpness, region: region,
                  crop: LumaCrop(width: 4, height: 2, bytes: [UInt8](repeating: UInt8(id), count: 8)))
  }

  private func word(_ text: String) -> TextWord {
    let p = DocumentPoint(x: 0, y: 0)
    return TextWord(text: text, bounds: TextRegion(x: 0, y: 0, width: 0, height: 0),
                    corners: DocumentCorners(topLeft: p, topRight: p, bottomRight: p, bottomLeft: p), line: 0, confidence: 1)
  }

  private func target(_ revision: Int = 0) -> TextFrameTarget {
    TextFrameTarget(region: region, crop: CGRect(x: 0.4, y: 0.1, width: 0.2, height: 0.8), turn: 90,
                    revision: revision)
  }

  private func pending(deadline: Double, _ results: Results) -> PendingTextCapture {
    PendingTextCapture(deadline: deadline, done: { results.add($0, $1) }, settled: {})
  }

  func testRingKeepsTheSharpestOfTheLastSecond() {
    var ring = TextFrameRing()
    for (id, sharpness) in [(1, 5.0), (2, 1.0), (3, 9.0), (4, 3.0), (5, 7.0)] {
      if ring.wants(time: Double(id) * 0.1, sharpness: sharpness) { ring.add(frame(id, time: Double(id) * 0.1, sharpness: sharpness)) }
    }
    XCTAssertEqual(ring.frames.map(\.id).sorted(), [1, 3, 4, 5])
    XCTAssertEqual(ring.sharpest()?.id, 3)
    // A frame over a second newer ages out the rest.
    ring.add(frame(6, time: 2, sharpness: 0.5))
    XCTAssertEqual(ring.frames.map(\.id), [6])
  }

  func testRegionPixelsStayInsideThePlane() {
    // The bottom edge rounds past the last row when origin and size round apart: 337.5 + 382.5.
    let rect = textRegionPixels(CGRect(x: 0, y: 0.46875, width: 1, height: 0.53125), width: 1_280, height: 720)
    XCTAssertEqual(rect?.minY, 338)
    XCTAssertEqual(rect?.maxY, 720)
    XCTAssertNil(textRegionPixels(CGRect(x: 0, y: 0, width: 0.01, height: 1), width: 1_280, height: 720))
  }

  func testCropsTurnClockwiseAsThePreviewDoes() {
    // 3 by 2: a b c / d e f.
    let crop = LumaCrop(width: 3, height: 2, bytes: [1, 2, 3, 4, 5, 6])
    let right = turnedLuma(crop, clockwise: 90)
    XCTAssertEqual([right.width, right.height], [2, 3])
    XCTAssertEqual(right.bytes, [4, 1, 5, 2, 6, 3])
    XCTAssertEqual(turnedLuma(crop, clockwise: 180).bytes, [6, 5, 4, 3, 2, 1])
    XCTAssertEqual(turnedLuma(crop, clockwise: 270).bytes, [3, 6, 2, 5, 1, 4])
    XCTAssertEqual(turnedLuma(crop, clockwise: 0).bytes, crop.bytes)
  }

  func testWordsRunInReadingOrderByLine() {
    func box(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> [CGPoint] {
      [CGPoint(x: x, y: y), CGPoint(x: x + w, y: y), CGPoint(x: x + w, y: y + h), CGPoint(x: x, y: y + h)]
    }
    // Lines and words arrive out of order, as Vision may give them.
    let lines = [
      ReadTextLine(corners: box(10, 60, 80, 20), confidence: 0.5,
                   words: [("world", box(50, 60, 40, 20)), ("hello", box(10, 60, 30, 20))]),
      ReadTextLine(corners: box(10, 10, 80, 20), confidence: 0.9, words: [("TITLE", box(10, 10, 50, 20))]),
    ]
    let words = textWords(lines, width: 100, height: 100)
    XCTAssertEqual(words.map(\.text), ["TITLE", "hello", "world"])
    XCTAssertEqual(words.map(\.line), [0, 1, 1])
    XCTAssertEqual(words[1].bounds.x, 0.1, accuracy: 1e-9)
    XCTAssertEqual(words[1].bounds.width, 0.3, accuracy: 1e-9)
    XCTAssertEqual(words[2].corners.bottomRight.y, 0.8, accuracy: 1e-9)
    XCTAssertEqual(words[0].confidence, 0.9, accuracy: 1e-6)
  }

  /// Records which frames were read, by id.
  private final class Reads: @unchecked Sendable {
    var ids: [Int] = []
  }

  /// A capture workflow reading frames by `texts`, keyed by frame id; a missing id fails the read.
  private func work(_ texts: [Int: [String]]) -> (TextCaptureWork, Reads) {
    let work = TextCaptureWork()
    let reads = Reads()
    let word = word
    work.recognize = { crop in
      let id = Int(crop.bytes[0])
      reads.ids.append(id)
      guard let words = texts[id] else { throw NSError(domain: "test", code: 1) }
      return words.map(word)
    }
    work.encode = { crop in "data:\(crop.bytes[0])" }
    return (work, reads)
  }

  func testPressTakesTheSharpestFrameWithText() {
    let (work, reads) = work([1: ["dim"], 2: []])
    let results = Results()
    work.keep(frame(1, time: 0, sharpness: 3), target: target())
    work.keep(frame(2, time: 0.1, sharpness: 9), target: target())
    work.enqueue(pending(deadline: 10, results), target: target(), now: 0)
    // The sharpest found nothing; the next served it.
    XCTAssertTrue(results.taken.isEmpty)
    XCTAssertEqual(reads.ids, [2])
    work.serve(target: target(), now: 1)
    XCTAssertEqual(results.taken.first?.uri, "data:1")
    XCTAssertEqual(results.taken.first?.words.map(\.text), ["dim"])
    XCTAssertEqual(reads.ids, [2, 1])
  }

  func testDeadlineTakesTheSharpestFrameEvenWithoutText() {
    let (work, reads) = work([1: []])
    let results = Results()
    work.keep(frame(1, time: 0, sharpness: 3), target: target())
    work.enqueue(pending(deadline: 2, results), target: target(), now: 0)
    XCTAssertTrue(results.taken.isEmpty)
    work.serve(target: target(), now: 2)
    XCTAssertEqual(results.taken.first?.uri, "data:1")
    XCTAssertEqual(results.taken.first?.words.count, 0)
    // The frame was read once.
    XCTAssertEqual(reads.ids, [1])
  }

  func testAnExpiredPressLeavesALongerOneWaiting() {
    let (work, _) = work([1: [], 2: ["late"]])
    let short = Results()
    let long = Results()
    work.keep(frame(1, time: 0, sharpness: 3), target: target())
    let shortPress = pending(deadline: 1, short)
    work.enqueue(shortPress, target: target(), now: 0)
    work.enqueue(pending(deadline: 5, long), target: target(), now: 0)
    work.deadlineReached(for: shortPress, target: target(), now: 1)
    XCTAssertEqual(short.taken.first?.uri, "data:1")
    XCTAssertTrue(long.taken.isEmpty)
    // A frame with text serves the one still waiting.
    work.keep(frame(2, time: 0.1, sharpness: 1), target: target())
    work.serve(target: target(), now: 2)
    XCTAssertEqual(long.taken.first?.words.map(\.text), ["late"])
  }

  func testSettledPressDeadlineDoesNotReadForTheNextPress() {
    let (work, reads) = work([1: ["first"], 2: [], 3: ["second"]])
    let first = Results()
    let firstPress = pending(deadline: 1, first)
    work.keep(frame(1, time: 0, sharpness: 3), target: target())
    work.enqueue(firstPress, target: target(), now: 0)
    XCTAssertEqual(first.taken.first?.words.map(\.text), ["first"])

    let second = Results()
    let secondPress = pending(deadline: 5, second)
    let nextCrop = target(1)
    work.keep(frame(2, time: 0.1, sharpness: 3), target: nextCrop)
    work.keep(frame(3, time: 0.2, sharpness: 1), target: nextCrop)
    work.enqueue(secondPress, target: nextCrop, now: 0.2)
    XCTAssertEqual(reads.ids, [1, 2])

    work.deadlineReached(for: firstPress, target: nextCrop, now: 1)
    XCTAssertEqual(reads.ids, [1, 2])
    XCTAssertTrue(second.taken.isEmpty)

    work.deadlineReached(for: secondPress, target: nextCrop, now: 5)
    XCTAssertEqual(second.taken.first?.words.map(\.text), ["second"])
  }

  func testDeadlineWithoutAFrameIsNotReady() {
    let (work, _) = work([:])
    let results = Results()
    work.enqueue(pending(deadline: 1, results), target: target(), now: 0)
    XCTAssertTrue(results.codes.isEmpty)
    work.serve(target: target(), now: 1)
    XCTAssertEqual(results.codes, [.captureNotReady])
  }

  func testANewCropDropsTheKeptFrames() {
    let (work, _) = work([1: ["old"]])
    let results = Results()
    work.keep(frame(1, time: 0, sharpness: 3), target: target(0))
    work.enqueue(pending(deadline: 0, results), target: target(1), now: 0)
    XCTAssertEqual(results.codes, [.captureNotReady])
  }

  func testSettleTakesWhatIsKeptOrFails() {
    let (work, _) = work([1: []])
    let results = Results()
    work.enqueue(pending(deadline: 10, results), target: target(), now: 0)
    work.settle(target: target())
    XCTAssertEqual(results.codes, [.cameraUnavailable])
    work.keep(frame(1, time: 0, sharpness: 3), target: target())
    work.enqueue(pending(deadline: 10, results), target: target(), now: 0)
    XCTAssertTrue(results.taken.isEmpty)
    work.settle(target: target())
    XCTAssertEqual(results.taken.first?.uri, "data:1")
    work.keep(frame(2, time: 0.1, sharpness: 9), target: target())
    work.enqueue(pending(deadline: 10, results), target: target(), now: 0)
    work.settle(target: target())
    XCTAssertEqual(results.codes, [.cameraUnavailable, .captureFailed])
    // Frames kept for another crop are not the settlement's to take, and stay for their own.
    work.enqueue(pending(deadline: 10, results), target: target(), now: 0)
    work.settle(target: target(1))
    XCTAssertEqual(results.codes, [.cameraUnavailable, .captureFailed, .cameraUnavailable])
    // Still kept: the next press finds them, and frame 2 fails to read, rather than finding nothing.
    let later = Results()
    work.enqueue(pending(deadline: 0, later), target: target(), now: 0)
    XCTAssertEqual(later.codes, [.captureFailed])
  }

  private final class FakeCamera: TextSessionCamera {
    var done: (@Sendable (TextCapture?, ScannerErrorCode?) -> Void)?
    var timeout: Int?
    var session: HybridTextSession?

    func capture(for session: HybridTextSession, timeout: Int, done: @escaping @Sendable (TextCapture?, ScannerErrorCode?) -> Void) {
      self.session = session
      self.timeout = timeout
      self.done = done
    }
  }

  func testSessionPublishesTheCaptureAndClosesWaiting() throws {
    let session = HybridTextSession()
    let camera = FakeCamera()
    var unattached: Variant_ScannerFailure_TextSessionSnapshot?
    _ = try session.captureResult(timeout: nil).then { unattached = $0 }
    waitUntil("unattached") { unattached != nil }
    guard case .first(let failure) = unattached else { return XCTFail() }
    XCTAssertEqual(failure.code, .cameraUnavailable)

    XCTAssertTrue(session.attach(camera))
    XCTAssertFalse(session.attach(FakeCamera()))
    var published: [TextSessionSnapshot] = []
    _ = try session.observeResult { published.append($0) }
    var result: Variant_ScannerFailure_TextSessionSnapshot?
    _ = try session.captureResult(timeout: 500).then { result = $0 }
    XCTAssertEqual(camera.timeout, 500)
    XCTAssertTrue(camera.session === session)
    camera.done?(TextCapture(uri: "data:x", width: 4, height: 2, region: region, words: [word("hi")]), nil)
    waitUntil("capture") { result != nil }
    guard case .second(let snapshot) = result else { return XCTFail() }
    XCTAssertEqual(snapshot.capture?.uri, "data:x")
    XCTAssertEqual(try session.getSnapshot().capture?.words.first?.text, "hi")
    XCTAssertEqual(published.last?.capture?.uri, "data:x")
    try session.clearCapture()
    XCTAssertNil(try session.getSnapshot().capture)

    var closedResult: Variant_ScannerFailure_TextSessionSnapshot?
    _ = try session.captureResult(timeout: nil).then { closedResult = $0 }
    _ = try session.closeResult()
    waitUntil("close") { closedResult != nil }
    guard case .first(let disposed) = closedResult else { return XCTFail() }
    XCTAssertEqual(disposed.code, .processorDisposed)
    // A result after close finds its capture gone.
    camera.done?(TextCapture(uri: "data:late", width: 4, height: 2, region: region, words: []), nil)
    XCTAssertNil(try session.getSnapshot().capture)
    XCTAssertTrue(try session.getSnapshot().closed)
  }

  private func waitUntil(_ message: String, timeout: TimeInterval = 20, _ condition: () -> Bool) {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
      if Date() > deadline { return XCTFail("Timed out: \(message)") }
      RunLoop.main.run(until: Date().addingTimeInterval(0.01))
    }
  }
}
