import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import ReactNativeDocumentScanner

private final class ImportCandidateRuntime: CornerRuntime {
  let inputWidth = 320
  let inputHeight = 192
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
    CornerPrediction(corners: [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8], score: 0.9, preprocessingMs: 0, inferenceMs: 0)
  }
}

final class ImageImportTests: XCTestCase {
  private var root: URL!
  private var store: SourceStore!
  private var registry: ScanSessionRegistry!

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    store = SourceStore(directory: root.appendingPathComponent("sources"))
    registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                   queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.import")),
                                   report: { _ in })
  }

  override func tearDown() {
    store.shutdown(); store.drain()
    try? FileManager.default.removeItem(at: root)
  }

  private func options(paper: PaperSize? = nil, dpi: Double? = nil) throws -> DocumentRenderOptions {
    try DocumentRenderService.effectiveOptions(DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil,
      format: nil, paperSize: paper, dpi: dpi, simpleDewarp: nil))
  }

  private var a4: PaperSize { PaperSize(width: 21, height: 29.7) }

  /// Encodes `width`×`height` pixels whose left half is transparent and right half opaque red.
  private func file(width: Int, height: Int, type: UTType = .png, orientation: Int = 1) throws -> URL {
    let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
      bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
    context.fill(CGRect(x: width / 2, y: 0, width: width - width / 2, height: height))
    let url = root.appendingPathComponent(UUID().uuidString + "." + (type.preferredFilenameExtension ?? "img"))
    let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(destination, try XCTUnwrap(context.makeImage()),
                               [kCGImagePropertyOrientation: orientation] as CFDictionary)
    XCTAssertTrue(CGImageDestinationFinalize(destination))
    return url
  }

  private func pixel(_ image: CGImage, x: Int, y: Int) throws -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 4)
    let context = try XCTUnwrap(CGContext(data: &bytes, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    // CoreGraphics' origin is bottom-left; `y` counts from the top.
    context.draw(image, in: CGRect(x: -x, y: y - image.height + 1, width: image.width, height: image.height))
    return Array(bytes.prefix(3))
  }

  func testSizeCoversA4At200DpiInTheImagesOwnOrientationAndNeverEnlarges() throws {
    let a4At200 = try options(paper: a4)
    XCTAssert(ImportSizePolicy.target(width: 3000, height: 4000, options: a4At200) == (1754, 2339))
    XCTAssert(ImportSizePolicy.target(width: 4000, height: 3000, options: a4At200) == (2339, 1754))
    XCTAssert(ImportSizePolicy.target(width: 2000, height: 8000, options: a4At200) == (1654, 6615))
    XCTAssert(ImportSizePolicy.target(width: 3000, height: 4000, options: try options(paper: a4, dpi: 300)) == (2631, 3508))
    XCTAssert(ImportSizePolicy.target(width: 1200, height: 1600, options: a4At200) == (1200, 1600))
    XCTAssert(ImportSizePolicy.target(width: 6000, height: 8000, options: try options()) == (3072, 4096))
  }

  func testUprightAppliesOrientationDownscalesAndPutsTransparencyOnWhite() throws {
    // Orientation 6 displays the encoded 4000×3000 image rotated clockwise: upright it is 3000×4000.
    let image = try ImageImport.upright(file(width: 4000, height: 3000, orientation: 6), options: options(paper: a4))
    XCTAssertEqual(image.width, 1754)
    XCTAssertEqual(image.height, 2339)
    // The encoded left (transparent) half becomes the upright top half.
    XCTAssertEqual(try pixel(image, x: 877, y: 200), [255, 255, 255])
    let red = try pixel(image, x: 877, y: 2100)
    XCTAssertGreaterThan(red[0], 200)
    XCTAssertLessThan(red[1], 50)
  }

  func testUprightKeepsSmallImagesAndRejectsUndecodableFiles() throws {
    let image = try ImageImport.upright(file(width: 640, height: 480, type: .jpeg), options: options(paper: a4))
    XCTAssertEqual(image.width, 640)
    XCTAssertEqual(image.height, 480)
    let broken = root.appendingPathComponent("broken.jpg")
    try Data([1, 2, 3]).write(to: broken)
    XCTAssertThrowsError(try ImageImport.upright(broken, options: options())) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .imageLoadFailed)
    }
  }

  func testImportAdmitsAPageThatAnalysisCropsAndRenders() throws {
    let inference = CornerInference(makeRuntime: { _ in ImportCandidateRuntime() })
    let selection = try inference.replace(data: Data([1]))
    let state = try registry.createState(actions: { SessionPageProcessing(resources: $0, inference: inference) })
    defer { _ = state.close() }
    let done = expectation(description: "imported")
    var result: Result<String, Error>?
    ImageImport.admit(state, url: try file(width: 900, height: 1200, type: .jpeg), options: try options(paper: a4),
                      selection: selection, autoCrop: true) { result = $0; done.fulfill() }
    wait(for: [done], timeout: 20)
    let id = try XCTUnwrap(result).get()
    let deadline = Date().addingTimeInterval(20)
    while state.snapshot().pages[id]?.ready != true, Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
    let page = try XCTUnwrap(state.snapshot().pages[id])
    XCTAssertTrue(page.ready)
    XCTAssertEqual(page.analysis?.sourceWidth, 900)
    XCTAssertEqual(page.corners.topLeft.x, 0.2, accuracy: 1e-6)
    XCTAssertEqual(page.options.paperSize?.width, 21)
  }

  func testFailedImportLeavesNoPage() throws {
    let state = try registry.createState(actions: { SessionPageProcessing(resources: $0, inference: CornerInference(makeRuntime: { _ in ImportCandidateRuntime() })) })
    defer { _ = state.close() }
    let broken = root.appendingPathComponent("broken.png")
    try Data([1, 2, 3]).write(to: broken)
    let done = expectation(description: "failed")
    var result: Result<String, Error>?
    ImageImport.admit(state, url: broken, options: try options(), selection: nil, autoCrop: true) { result = $0; done.fulfill() }
    wait(for: [done], timeout: 20)
    XCTAssertThrowsError(try XCTUnwrap(result).get())
    XCTAssertTrue(state.snapshot().pageIds.isEmpty)
  }

  private let unregistered: (@escaping @Sendable () -> NativeCompletion) throws -> () -> Void = { _ in {} }

  private func done() -> NativeCompletion { let done = NativeCompletion(); done.complete(); return done }

  func testLibraryImportKeepsPickedOrderCountsUnreadablePicksAndDeletesEachStagedFile() {
    let a = URL(fileURLWithPath: "/a.jpg"), b = URL(fileURLWithPath: "/b.jpg")
    var pending: [(URL, (Result<String, Error>) -> Void)] = []
    var discarded: [URL] = []
    var result: Result<LibraryImportResult, Error>?
    LibraryImport(maxCount: 0, admit: { pending.append(($0, $1)) }, discard: { discarded.append($0) },
                  completion: { result = $0 })
      .start(register: unregistered) { $0(.success([a, nil, b])); return self.done }
    XCTAssertEqual(pending.map(\.0), [a, b])
    pending[1].1(.success("page-b"))
    XCTAssertNil(result)
    pending[0].1(.success("page-a"))
    XCTAssertEqual(try result?.get().pageIds, ["page-a", "page-b"])
    XCTAssertEqual(try result?.get().failedCount, 1)
    XCTAssertEqual(Set(discarded), [a, b])
    var empty: Result<LibraryImportResult, Error>?
    LibraryImport(maxCount: 0, admit: { _, _ in XCTFail("Nothing to admit.") }, completion: { empty = $0 })
      .start(register: unregistered) { $0(.success([])); return self.done }
    XCTAssertEqual(try empty?.get().pageIds, [])
  }

  func testClosingTheSessionSettlesTheImportEndsThePickerAndDeletesLatePicks() throws {
    let state = try registry.createState(actions: { SessionPageProcessing(resources: $0, inference: CornerInference(makeRuntime: { _ in ImportCandidateRuntime() })) })
    let late = URL(fileURLWithPath: "/late.jpg")
    var onPicked: ((Result<[URL?], Error>) -> Void)?
    var ended = 0
    var discarded: [URL] = []
    var results: [Result<LibraryImportResult, Error>] = []
    let operation = LibraryImport(maxCount: 0, admit: { _, _ in XCTFail("Nothing is admitted after close.") },
                                  discard: { discarded.append($0) }, completion: { results.append($0) })
    let released = NativeCompletion()
    operation.start(register: { try state.onClose($0) }) { onPicked = $0; return { ended += 1; return released } }
    let closing = state.close()
    XCTAssertEqual(results.count, 1)
    XCTAssertThrowsError(try results[0].get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .processorDisposed)
    }
    XCTAssertEqual(ended, 1)
    // Close waits for the picker to free its slot, so a replacement session can open one at once.
    let closed = expectation(description: "closed")
    closing.whenComplete { _ in closed.fulfill() }
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) {
      XCTAssertFalse(closing.isComplete)
      released.complete()
    }
    wait(for: [closed], timeout: 20)
    onPicked?(.success([late]))
    XCTAssertEqual(discarded, [late])
    XCTAssertEqual(results.count, 1)
    XCTAssertThrowsError(try state.onClose { NativeCompletion() }) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .processorDisposed)
    }
  }

  func testLibraryImportUnregistersWhenThePickerCannotOpen() {
    var unregisteredCount = 0
    var result: Result<LibraryImportResult, Error>?
    LibraryImport(maxCount: 1, admit: { _, _ in XCTFail() }, completion: { result = $0 })
      .start(register: { _ in { unregisteredCount += 1 } }) { _ in throw ScannerCodeError(code: .captureBusy) }
    XCTAssertEqual(unregisteredCount, 1)
    XCTAssertThrowsError(try result?.get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .captureBusy)
    }
  }

  func testDetachedModelNeverReplacesTheCameraSelection() throws {
    let inference = CornerInference(makeRuntime: { _ in ImportCandidateRuntime() })
    let detached = try inference.detached(data: Data([1]))
    XCTAssertEqual(detached.generation, 0)
    XCTAssertThrowsError(try inference.selection())
    let live = try inference.replace(data: Data([2]))
    XCTAssertTrue(inference.isCurrent(live))
    XCTAssertFalse(inference.isCurrent(detached))
    var resolved: CornerInference.Selection?
    ImportModel.resolve(inference: inference) { resolved = try? $0.get() }
    XCTAssertTrue(resolved === live)
  }
}
