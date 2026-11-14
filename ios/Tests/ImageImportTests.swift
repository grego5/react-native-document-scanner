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

  private func options(paper: PaperSize? = nil, dpi: Double? = nil) throws -> RenderSettings {
    try RenderSettings(DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil,
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
    XCTAssert(ImportSizePolicy.target(width: 3000, height: 4000, settings: a4At200) == (1754, 2339))
    XCTAssert(ImportSizePolicy.target(width: 4000, height: 3000, settings: a4At200) == (2339, 1754))
    XCTAssert(ImportSizePolicy.target(width: 2000, height: 8000, settings: a4At200) == (1654, 6615))
    XCTAssert(ImportSizePolicy.target(width: 3000, height: 4000, settings: try options(paper: a4, dpi: 300)) == (2631, 3508))
    XCTAssert(ImportSizePolicy.target(width: 1200, height: 1600, settings: a4At200) == (1200, 1600))
    XCTAssert(ImportSizePolicy.target(width: 6000, height: 8000, settings: try options()) == (3072, 4096))
  }

  func testUprightAppliesOrientationDownscalesAndPutsTransparencyOnWhite() throws {
    // Orientation 6 displays the encoded 4000×3000 image rotated clockwise: upright it is 3000×4000.
    let image = try ImageImport.upright(file(width: 4000, height: 3000, orientation: 6), settings: options(paper: a4))
    XCTAssertEqual(image.width, 1754)
    XCTAssertEqual(image.height, 2339)
    // The encoded left (transparent) half becomes the upright top half.
    XCTAssertEqual(try pixel(image, x: 877, y: 200), [255, 255, 255])
    let red = try pixel(image, x: 877, y: 2100)
    XCTAssertGreaterThan(red[0], 200)
    XCTAssertLessThan(red[1], 50)
  }

  func testUprightKeepsSmallImagesAndRejectsUndecodableFiles() throws {
    let image = try ImageImport.upright(file(width: 640, height: 480, type: .jpeg), settings: options(paper: a4))
    XCTAssertEqual(image.width, 640)
    XCTAssertEqual(image.height, 480)
    let broken = root.appendingPathComponent("broken.jpg")
    try Data([1, 2, 3]).write(to: broken)
    XCTAssertThrowsError(try ImageImport.upright(broken, settings: options())) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .imageLoadFailed)
    }
  }

  private let models = ModelStatusStub()

  private func state(_ inference: CornerInference = CornerInference(makeRuntime: { _ in ImportCandidateRuntime() }))
    throws -> ScanSessionState {
    try registry.createState(models: models.observe, actions: { SessionPageProcessing(resources: $0, inference: inference) })
  }

  private func imported(_ state: ScanSessionState, _ url: URL, settings: RenderSettings? = nil) throws -> Result<String, Error> {
    let done = expectation(description: "imported")
    var result: Result<String, Error>?
    ImageImport.admit(state, url: url, settings: try settings ?? options(), autoCrop: true) { result = $0; done.fulfill() }
    wait(for: [done], timeout: 20)
    return try XCTUnwrap(result)
  }

  private func settle(_ message: String, _ condition: () -> Bool) {
    let deadline = Date().addingTimeInterval(20)
    while !condition() {
      if Date() > deadline { return XCTFail("Timed out: \(message)") }
      RunLoop.main.run(until: Date().addingTimeInterval(0.01))
    }
  }

  func testImportAdmitsAPageThatTheReadySharedModelCropsBeforeItsFirstRender() throws {
    let inference = CornerInference(makeRuntime: { _ in ImportCandidateRuntime() })
    models.ready(try inference.replace(data: Data([1])))
    let state = try state(inference)
    defer { _ = state.close() }
    var changes: [ScanSessionChange] = []
    _ = try state.observe { changes.append($0) }
    let id = try imported(state, try file(width: 900, height: 1200, type: .jpeg), settings: try options(paper: a4)).get()
    settle("render") { state.snapshot().pages[id]?.ready == true }
    let page = try XCTUnwrap(state.snapshot().pages[id])
    XCTAssertEqual(page.analysis?.sourceWidth, 900)
    XCTAssertEqual(page.corners.topLeft.x, 0.2, accuracy: 1e-6)
    XCTAssertEqual(page.options.paperSize?.width, 21)
    settle("delivery") { changes.last?.updatedPages[id]?.ready == true }
    XCTAssertEqual(changes.compactMap { $0.updatedPages[id]?.readyRevision }, [2], "no provisional full-frame render")
  }

  func testAnImportWhileTheModelLoadsShowsItsFullFrameThenCropsOnceTheModelIsReady() throws {
    let inference = CornerInference(makeRuntime: { _ in ImportCandidateRuntime() })
    models.loading()
    let state = try state(inference)
    defer { _ = state.close() }
    let id = try imported(state, try file(width: 900, height: 1200, type: .jpeg)).get()
    settle("full-frame render") { state.snapshot().pages[id]?.ready == true }
    let waiting = try XCTUnwrap(state.snapshot().pages[id])
    XCTAssertTrue(waiting.analyzing, "waiting for the model reads as analysis")
    XCTAssertNil(waiting.analysis)
    XCTAssertEqual(SessionCorners.coordinates(waiting.corners), SessionCorners.fullFrame)
    XCTAssertNoThrow(try state.imageSource(id).close())
    models.ready(try inference.replace(data: Data([1])))
    settle("crop") { state.snapshot().pages[id]?.readyRevision == 2 }
    let cropped = try XCTUnwrap(state.snapshot().pages[id])
    XCTAssertFalse(cropped.analyzing)
    XCTAssertEqual(cropped.revision, 2)
    XCTAssertEqual(cropped.corners.topLeft.x, 0.2, accuracy: 1e-6)
  }

  func testImportWithoutAModelRendersTheFullImage() throws {
    let state = try state()
    defer { _ = state.close() }
    let id = try imported(state, try file(width: 900, height: 1200, type: .jpeg)).get()
    settle("render") { state.snapshot().pages[id]?.ready == true }
    let page = try XCTUnwrap(state.snapshot().pages[id])
    XCTAssertNil(page.analysisFailure)
    XCTAssertNil(page.analysis?.candidate)
    XCTAssertEqual(SessionCorners.coordinates(page.corners), SessionCorners.fullFrame)
  }

  func testFailedImportLeavesNoPage() throws {
    let state = try state()
    defer { _ = state.close() }
    let broken = root.appendingPathComponent("broken.png")
    try Data([1, 2, 3]).write(to: broken)
    XCTAssertThrowsError(try imported(state, broken).get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .imageLoadFailed)
    }
    XCTAssertTrue(state.snapshot().pageIds.isEmpty)
  }

  func testStagingSkipsAnUnreadableProviderFileAndReportsAFailedCopyAsStorage() throws {
    let picked = root.appendingPathComponent("picked.jpg")
    try Data([1, 2, 3]).write(to: picked)
    let staging = root.appendingPathComponent("staging")
    let copy = try LibraryPicker.stage(picked, into: staging).get()
    XCTAssertEqual(try Data(contentsOf: copy), Data([1, 2, 3]))
    XCTAssertThrowsError(try LibraryPicker.stage(root.appendingPathComponent("missing.jpg"), into: staging).get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .imageLoadFailed)
    }
    // A file where the staging directory belongs makes the copy unwritable.
    let blocked = root.appendingPathComponent("blocked")
    try Data([0]).write(to: blocked)
    XCTAssertThrowsError(try LibraryPicker.stage(picked, into: blocked).get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .storageWriteFailed)
    }
  }

  /// A session's close registration, taken by hand.
  private final class Registration: CloseRegistration, @unchecked Sendable {
    private var cancel: (@Sendable () -> NativeCompletion)?
    private var registered = true
    var isRegistered: Bool { registered }
    func tryUnregister() -> Bool { defer { registered = false }; return registered }

    func register(_ cancel: @escaping @Sendable () -> NativeCompletion) -> CloseRegistration {
      self.cancel = cancel
      return self
    }

    /// Takes the registration as session close does, then runs its cancellation.
    func close() -> NativeCompletion {
      registered = false
      return cancel!()
    }
  }

  /// A picker request whose release the test controls.
  private final class Request: PickerRequest, @unchecked Sendable {
    let pickerReleased = NativeCompletion()
    var ended = 0
    /// Held as the real picker holds its result callback, until late staged files have arrived.
    var onPicked: ((Result<[Result<URL, Error>], Error>) -> Void)?
    init(released: Bool = true) { if released { pickerReleased.complete() } }
    func end() { ended += 1 }
  }

  private let unreadable = ScannerCodeError(code: .imageLoadFailed)

  private func operation(maxCount: Int = 0, discard: @escaping (URL) -> Void = { _ in },
                         post: @escaping (@escaping @Sendable () -> Void) -> Void = { $0() },
                         admit: @escaping LibraryImport.AdmitPickedImage = { _, _ in XCTFail("Nothing to admit.") },
                         completion: @escaping (Result<[String], Error>) -> Void) -> LibraryImport {
    LibraryImport(maxCount: maxCount, discard: discard, post: post, admit: admit, completion: completion)
  }

  private func assertDisposed(_ results: [Result<[String], Error>], file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertEqual(results.count, 1, "settles once", file: file, line: line)
    XCTAssertThrowsError(try results.first?.get(), file: file, line: line) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .processorDisposed, file: file, line: line)
    }
  }

  func testLibraryImportReturnsAdmittedIdsInPickedOrderSkipsUnreadablePicksAndDeletesEachStagedFile() {
    let a = URL(fileURLWithPath: "/a.jpg"), b = URL(fileURLWithPath: "/b.jpg")
    var pending: [(URL, (Result<String, Error>) -> Void)] = []
    var discarded: [URL] = []
    var result: Result<[String], Error>?
    let registration = Registration()
    operation(discard: { discarded.append($0) }, admit: { pending.append(($0, $1)) }, completion: { result = $0 })
      .start(register: registration.register) { $0(.success([.success(a), .failure(self.unreadable), .success(b)])); return Request() }
    XCTAssertEqual(pending.map(\.0), [a, b])
    pending[1].1(.success("page-b"))
    XCTAssertNil(result)
    pending[0].1(.failure(unreadable))
    XCTAssertEqual(try result?.get(), ["page-b"])
    XCTAssertEqual(Set(discarded), [a, b])
    XCTAssertFalse(registration.isRegistered, "a normal outcome claims the registration")
  }

  func testCancellingOrAnEntirelyUnreadableSelectionReturnsNoIds() {
    var cancelled: Result<[String], Error>?
    operation(completion: { cancelled = $0 }).start(register: Registration().register) { $0(.success([])); return Request() }
    XCTAssertEqual(try cancelled?.get(), [])
    var unreadablePicks: Result<[String], Error>?
    operation(completion: { unreadablePicks = $0 }).start(register: Registration().register) {
      $0(.success([.failure(self.unreadable), .failure(self.unreadable)])); return Request()
    }
    XCTAssertEqual(try unreadablePicks?.get(), [])
  }

  func testAGalleryBatchSkipsAnUnreadablePickAndADestinationFailureRejectsItWhileKeepingAdmittedPages() throws {
    let state = try state()
    defer { _ = state.close() }
    let broken = root.appendingPathComponent("broken.png")
    try Data([1, 2, 3]).write(to: broken)
    let options = try options(paper: a4)
    func batch(_ picks: [Result<URL, Error>]) -> Result<[String], Error>? {
      let finished = expectation(description: "batch")
      var result: Result<[String], Error>?
      operation(discard: { _ in }, admit: { url, done in
        ImageImport.admit(state, url: url, settings: options, autoCrop: true, completion: done)
      }, completion: { result = $0; finished.fulfill() }).start(register: { try state.registerCloseCancellation($0) }) {
        $0(.success(picks)); return Request()
      }
      wait(for: [finished], timeout: 20)
      return result
    }
    let skipped = try XCTUnwrap(batch([.success(try file(width: 90, height: 120)), .success(broken),
                                       .success(try file(width: 90, height: 120))]))
    XCTAssertEqual(try skipped.get(), state.snapshot().pageIds)
    XCTAssertEqual(try skipped.get().count, 2)
    let snapshot = state.snapshot()
    XCTAssertEqual(snapshot.pageIds.map { snapshot.pages[$0]?.options.paperSize?.width }, [21, 21],
                   "every pick is admitted with the call's settings")
    let rejected = try XCTUnwrap(batch([.success(try file(width: 90, height: 120)),
                                        .failure(ScannerCodeError(code: .storageWriteFailed))]))
    XCTAssertThrowsError(try rejected.get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .storageWriteFailed)
    }
    XCTAssertEqual(state.snapshot().pageIds.count, 3, "the admitted page stays")
  }

  func testClosingTheSessionSettlesTheImportEndsThePickerWaitsForItsReleaseAndDeletesLatePicks() throws {
    let state = try state()
    let late = URL(fileURLWithPath: "/late.jpg")
    var onPicked: ((Result<[Result<URL, Error>], Error>) -> Void)?
    var discarded: [URL] = []
    var results: [Result<[String], Error>] = []
    let request = Request(released: false)
    operation(discard: { discarded.append($0) }, completion: { results.append($0) })
      .start(register: { try state.registerCloseCancellation($0) }) { onPicked = $0; return request }
    let closing = state.close()
    assertDisposed(results)
    XCTAssertEqual(request.ended, 1)
    XCTAssertEqual(models.observers, 0, "close stops observing the shared model")
    // Close waits for the picker to free its slot, so a replacement session can open one at once.
    let closed = expectation(description: "closed")
    closing.whenComplete { _ in closed.fulfill() }
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) {
      XCTAssertFalse(closing.isComplete)
      request.pickerReleased.complete()
    }
    wait(for: [closed], timeout: 20)
    // Files the picker was still staging arrive after close and are deleted.
    onPicked?(.success([.success(late)]))
    XCTAssertEqual(discarded, [late])
    XCTAssertEqual(results.count, 1)
    XCTAssertThrowsError(try state.registerCloseCancellation { NativeCompletion() }) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .processorDisposed)
    }
  }

  func testCloseBeforeTheQueuedPresentationPresentsNothingAndCompletesAtOnce() {
    var main: [@Sendable () -> Void] = []
    var presented = 0
    var results: [Result<[String], Error>] = []
    let registration = Registration()
    operation(post: { main.append($0) }, completion: { results.append($0) })
      .start(register: registration.register) { _ in presented += 1; return Request() }
    XCTAssertEqual(presented, 0)
    XCTAssertTrue(registration.close().isComplete, "no presentation was authorized, so nothing holds the slot")
    main.forEach { $0() }
    XCTAssertEqual(presented, 0)
    assertDisposed(results)
  }

  func testCloseInsideThePresenterEndsTheRequestOnceItReturnsAndWaitsForItsRelease() {
    let registration = Registration()
    let request = Request(released: false)
    var results: [Result<[String], Error>] = []
    var closing: NativeCompletion?
    operation(completion: { results.append($0) })
      .start(register: registration.register) { _ in closing = registration.close(); return request }
    assertDisposed(results)
    XCTAssertEqual(request.ended, 1)
    XCTAssertEqual(closing?.isComplete, false)
    request.pickerReleased.complete()
    XCTAssertEqual(closing?.isComplete, true)
  }

  func testCloseWhileThePickerIsOpenEndsItOnMainAndDeletesFilesStagedAfterCancellation() {
    var main: [@Sendable () -> Void] = []
    let registration = Registration()
    let request = Request(released: false)
    let late = URL(fileURLWithPath: "/late.jpg")
    var onPicked: ((Result<[Result<URL, Error>], Error>) -> Void)?
    var discarded: [URL] = []
    var results: [Result<[String], Error>] = []
    operation(discard: { discarded.append($0) }, post: { main.append($0) }, completion: { results.append($0) })
      .start(register: registration.register) { onPicked = $0; return request }
    main.removeFirst()()
    let closing = registration.close()
    assertDisposed(results)
    XCTAssertEqual(request.ended, 0, "the request is ended on main")
    main.removeFirst()()
    XCTAssertEqual(request.ended, 1)
    onPicked?(.success([.success(late)]))
    XCTAssertEqual(discarded, [late])
    XCTAssertFalse(closing.isComplete)
    request.pickerReleased.complete()
    XCTAssertTrue(closing.isComplete)
    XCTAssertEqual(results.count, 1)
  }

  func testAFailedRegistrationSettlesDirectlyAndALaunchFailureReleasesAndUnregisters() {
    var result: Result<[String], Error>?
    operation(completion: { result = $0 }).start(register: { _ in throw ScannerCodeError(code: .processorDisposed) }) { _ in
      XCTFail("Not presented."); return Request()
    }
    XCTAssertThrowsError(try result?.get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .processorDisposed)
    }
    let registration = Registration()
    operation(maxCount: 1, completion: { result = $0 })
      .start(register: registration.register) { _ in throw ScannerCodeError(code: .captureBusy) }
    XCTAssertFalse(registration.isRegistered)
    XCTAssertThrowsError(try result?.get()) {
      XCTAssertEqual(ScannerCodeError.code($0, fallback: .inputInvalid), .captureBusy)
    }
  }

  func testALaunchFailureWhileCloseWaitsCompletesTheReleaseWithoutASecondResult() {
    let registration = Registration()
    var results: [Result<[String], Error>] = []
    var closing: NativeCompletion?
    operation(completion: { results.append($0) }).start(register: registration.register) { _ in
      closing = registration.close()
      throw ScannerCodeError(code: .captureBusy)
    }
    XCTAssertEqual(closing?.isComplete, true)
    assertDisposed(results)
  }

  func testANormalOutcomeWaitsForThePickerToReleaseItsSlot() {
    let registration = Registration()
    let request = Request(released: false)
    var result: Result<[String], Error>?
    operation(completion: { result = $0 }).start(register: registration.register) { $0(.success([])); return request }
    XCTAssertNil(result)
    XCTAssertTrue(registration.isRegistered, "the registration stays until the slot is free")
    request.pickerReleased.complete()
    XCTAssertEqual(try result?.get(), [])
    XCTAssertFalse(registration.isRegistered)
  }

  func testANormalOutcomeLeavesTheDismissalToThePicker() {
    var main: [@Sendable () -> Void] = []
    let registration = Registration()
    let request = Request(released: false)
    var onPicked: ((Result<[Result<URL, Error>], Error>) -> Void)?
    var result: Result<[String], Error>?
    operation(post: { main.append($0) }, completion: { result = $0 })
      .start(register: registration.register) { onPicked = $0; return request }
    main.removeFirst()()
    // The picker frees its slot before it reports.
    request.pickerReleased.complete()
    onPicked?(.success([]))
    XCTAssertTrue(main.isEmpty, "nothing is posted to end the picker")
    XCTAssertEqual(request.ended, 0)
    XCTAssertEqual(try result?.get(), [])
    XCTAssertFalse(registration.isRegistered)
  }

  func testAFinishedOrClosedImportDropsItsReleasedPickerSoNeitherRetainsTheOther() {
    let late = URL(fileURLWithPath: "/late.jpg")
    var discarded: [URL] = []
    weak var finishedCall: LibraryImport?
    weak var finishedRequest: Request?
    weak var closedCall: LibraryImport?
    weak var closedRequest: Request?
    do {
      let request = Request(released: false)
      let call = operation(completion: { _ in })
      call.start(register: Registration().register) { request.onPicked = $0; return request }
      request.onPicked?(.success([]))
      request.pickerReleased.complete()
      finishedCall = call; finishedRequest = request
    }
    XCTAssertNil(finishedCall)
    XCTAssertNil(finishedRequest)
    var keepCallback: ((Result<[Result<URL, Error>], Error>) -> Void)?
    do {
      let registration = Registration()
      let request = Request(released: false)
      let call = operation(discard: { discarded.append($0) }, completion: { _ in })
      call.start(register: registration.register) { request.onPicked = $0; return request }
      _ = registration.close()
      request.pickerReleased.complete()
      // The picker keeps its callback while staging continues.
      keepCallback = request.onPicked
      closedCall = call; closedRequest = request
    }
    XCTAssertNil(closedRequest, "the call drops its request once the slot is free")
    XCTAssertNotNil(closedCall, "the staging callback keeps the call alive")
    keepCallback?(.success([.success(late)]))
    XCTAssertEqual(discarded, [late], "late staged files are still deleted")
    keepCallback = nil
    XCTAssertNil(closedCall)
  }

  func testCloseRunsPendingCancellationsOnceAndAwaitsTheirCleanup() throws {
    let state = try state()
    nonisolated(unsafe) var cancelled = 0
    let cleanup = NativeCompletion()
    let pending = try state.registerCloseCancellation { cancelled += 1; return cleanup }
    let unregistered = try state.registerCloseCancellation { XCTFail("Unregistered work is not cancelled."); return cleanup }
    XCTAssertTrue(unregistered.tryUnregister())
    XCTAssertFalse(unregistered.tryUnregister())
    let closing = state.close()
    XCTAssertTrue(closing === state.close())
    XCTAssertEqual(cancelled, 1)
    XCTAssertFalse(pending.isRegistered, "close took the registration")
    XCTAssertFalse(pending.tryUnregister())
    let closed = expectation(description: "closed")
    closing.whenComplete { _ in closed.fulfill() }
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) {
      XCTAssertFalse(closing.isComplete, "close waits for the cancellation's cleanup")
      cleanup.complete()
    }
    wait(for: [closed], timeout: 20)
  }

  /// Runs `late` from a close callback that runs after close took the gallery call's registration.
  private func inTheCloseGap(post: @escaping (@escaping @Sendable () -> Void) -> Void, late: @escaping () -> Void,
                             discard: @escaping (URL) -> Void = { _ in },
                             present: @escaping (@escaping (Result<[Result<URL, Error>], Error>) -> Void) -> Void) throws
    -> [Result<[String], Error>] {
    let state = try state()
    nonisolated(unsafe) let late = late
    var results: [Result<[String], Error>] = []
    _ = try state.registerCloseCancellation {
      late()
      let released = NativeCompletion()
      released.complete()
      return released
    }
    operation(discard: discard, post: post, admit: { _, _ in XCTFail("Nothing is admitted after close.") },
              completion: { results.append($0) })
      .start(register: { try state.registerCloseCancellation($0) }) { present($0); return Request() }
    _ = state.close()
    return results
  }

  func testAPresentationQueuedWhenCloseTookTheRegistrationOpensNoPicker() throws {
    nonisolated(unsafe) var main: [@Sendable () -> Void] = []
    var presented = 0
    let results = try inTheCloseGap(post: { main.append($0) }, late: { main.forEach { $0() } }) { _ in presented += 1 }
    XCTAssertEqual(presented, 0)
    assertDisposed(results)
  }

  func testPickerResultsAfterCloseTookTheRegistrationAdmitNothingAndDeleteTheirFiles() throws {
    let pick = URL(fileURLWithPath: "/a.jpg")
    nonisolated(unsafe) var onPicked: ((Result<[Result<URL, Error>], Error>) -> Void)?
    var discarded: [URL] = []
    let results = try inTheCloseGap(post: { $0() }, late: { onPicked?(.success([.success(pick)])) },
                                    discard: { discarded.append($0) }) { onPicked = $0 }
    XCTAssertEqual(discarded, [pick])
    assertDisposed(results)
  }
}
