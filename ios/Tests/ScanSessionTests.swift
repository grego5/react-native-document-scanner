import XCTest
import CoreGraphics
import NitroModules
@testable import ReactNativeDocumentScanner

private let candidateQuad: QuadCoordinates = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8]

private final class CandidateRuntime: CornerRuntime {
  let inputWidth = 320
  let inputHeight = 192
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
    CornerPrediction(quad: candidateQuad, score: 0.9, preprocessingMs: 0, inferenceMs: 0)
  }
}

/// Scripted engine actions with real session files, so ordering, gating and failures
/// are deterministic while resources follow production ownership.
private final class ScriptedActions: ScanSessionActions, @unchecked Sendable {
  let resources: SessionResources
  private let lock = NSLock()
  private var entries: [String] = []
  var analysisGate: DispatchSemaphore?
  var renderGate: DispatchSemaphore?
  var renderStarted: DispatchSemaphore?
  var failRenders = 0
  var candidate: QuadCoordinates? = candidateQuad
  var analysisError: Error?

  init(resources: SessionResources) { self.resources = resources }

  var log: [String] { lock.lock(); defer { lock.unlock() }; return entries }
  private func record(_ entry: String) { lock.lock(); entries.append(entry); lock.unlock() }

  func prepareAnalysis(_ source: SessionResources.Lease, liveQuad: QuadCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue {
    { [self] in
      record("analysis")
      analysisGate?.wait()
      if let analysisError { throw analysisError }
      return AnalysisValue(width: 480, height: 360, duration: 1, candidate: candidate, score: 0.9)
    }
  }

  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact {
    record("render:\(key.settings.filter.stringValue)")
    renderStarted?.signal()
    renderGate?.wait()
    lock.lock()
    let fail = failRenders > 0
    if fail { failRenders -= 1 }
    lock.unlock()
    if fail { throw ScannerCodeError(code: .renderFailed) }
    let destination = try resources.renderDestination(extension: "jpg")
    defer { destination.close() }
    try Data([1]).write(to: destination.image.url)
    try Data([2]).write(to: destination.thumbnail.url)
    return ScanPage.Artifact(image: try destination.image.commit(), thumbnail: try destination.thumbnail.commit(),
                             width: 10, height: 10, validContentPolygon: [])
  }

  func close() { record("close") }
}

final class ScanSessionTests: XCTestCase {
  private var root: URL!
  private var store: SourceStore!
  private var registry: ScanSessionRegistry!
  private var failures: [Error] = []

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    store = SourceStore(directory: root.appendingPathComponent("sources"))
    registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                   queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.images")),
                                   report: { [weak self] in self?.failures.append($0) })
  }

  override func tearDown() {
    store.shutdown(); store.drain()
    try? FileManager.default.removeItem(at: root)
  }

  // MARK: Helpers

  private func photo(width: Int = 480, height: Int = 360) throws -> CGImage {
    let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
      bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    context.setFillColor(CGColor(red: 0.2, green: 0.25, blue: 0.3, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.setFillColor(CGColor(gray: 0.95, alpha: 1))
    context.fill(CGRect(x: Double(width) * 0.2, y: Double(height) * 0.2, width: Double(width) * 0.6, height: Double(height) * 0.6))
    return try XCTUnwrap(context.makeImage())
  }

  private func scripted() throws -> (ScanSessionState, ScriptedActions) {
    var actions: ScriptedActions!
    let state = try registry.createState(actions: { resources in
      actions = ScriptedActions(resources: resources)
      return actions
    })
    return (state, actions)
  }

  private func production() throws -> (ScanSessionState, CornerInference.Selection) {
    let inference = CornerInference(makeRuntime: { _ in CandidateRuntime() })
    let selection = try inference.replace(data: Data([1]))
    let state = try registry.createState(actions: { SessionPageProcessing(resources: $0, inference: inference) })
    return (state, selection)
  }

  private func admit(_ state: ScanSessionState, _ id: String, selection: CornerInference.Selection? = nil,
                     options: DocumentRenderOptions? = nil) throws -> URL {
    let source = try store.publish(image: photo())
    try state.admitSource(id: id, source: source, options: options, selection: selection)
    return source
  }

  private func waitUntil(_ message: String, timeout: TimeInterval = 20, _ condition: () -> Bool) {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
      if Date() > deadline { return XCTFail("Timed out: \(message)") }
      RunLoop.main.run(until: Date().addingTimeInterval(0.01))
    }
  }

  private func page(_ state: ScanSessionState, _ id: String) -> ScanPageMetadata? { state.snapshot().pages[id] }

  private func ready(_ state: ScanSessionState, _ id: String) -> Bool { page(state, id)?.ready == true }

  @discardableResult
  private func closed(_ state: ScanSessionState) -> Error? {
    var result: Error??
    state.close().whenComplete { result = .some($0) }
    waitUntil("close") { result != nil }
    return result ?? nil
  }

  private func sessionDirectories() -> [String] {
    (try? FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("sessions").path)) ?? []
  }

  // MARK: Hybrid boundary and observation

  func testEmptySessionCreatesObservesAndClosesThroughHybridBoundary() throws {
    let (state, _) = try scripted()
    let session = HybridScanSession(state: state)
    let initial = try session.getSession()
    XCTAssertEqual(initial.sourceVersion, 0)
    XCTAssertFalse(initial.closed)
    XCTAssertEqual(initial.pageIds, [])
    XCTAssertEqual(sessionDirectories().count, 1)

    var changes: [ScanSessionChange] = []
    guard case .first(let observation) = try session.observeResult(onChange: { changes.append($0) }) else {
      return XCTFail("Observation failed")
    }
    XCTAssertEqual(observation.snapshot.sourceVersion, 0)
    guard case .second(let missing) = try awaitBool(session.removePageResult(pageId: "missing")) else { return XCTFail() }
    XCTAssertEqual(missing.code, .inputInvalid)

    guard case .first(true) = try awaitBool(session.closeResult()) else { return XCTFail("Close failed") }
    waitUntil("terminal delivery") { changes.last?.closed == true }
    XCTAssertTrue(try session.getSession().closed)
    XCTAssertEqual(sessionDirectories(), [])
    guard case .second(let disposed) = try session.observeResult(onChange: { _ in }) else { return XCTFail() }
    XCTAssertEqual(disposed.code, .processorDisposed)
    guard case .second(let rejected) = try awaitBool(session.setPageOrderResult(pageIds: [])) else { return XCTFail() }
    XCTAssertEqual(rejected.code, .processorDisposed)
    observation.unsubscribe(); observation.unsubscribe()
    guard case .first(true) = try awaitBool(session.closeResult()) else { return XCTFail("Repeated close failed") }
  }

  private func awaitBool(_ promise: Promise<Variant_Bool_ScannerFailure>) throws -> Variant_Bool_ScannerFailure {
    var value: Variant_Bool_ScannerFailure?
    _ = promise.then { value = $0 }
    waitUntil("promise") { value != nil }
    return try XCTUnwrap(value)
  }

  func testProcessorDefersSampleCollection() throws {
    let processor = HybridDocumentScannerProcessor()
    XCTAssertFalse(try processor.getSampleFolder().collecting)
    XCTAssertNoThrow(try processor.clearSampleFolder())
    XCTAssertNoThrow(try processor.clearSampleFolder())
    XCTAssertNoThrow(try processor.setSampleCollection(on: false))
    XCTAssertThrowsError(try processor.setSampleCollection(on: true))
    XCTAssertThrowsError(try processor.setSampleFolder(uri: "file:///samples"))
    var picked: Variant_ScannerFailure_SampleFolder?
    _ = try processor.pickSampleFolderResult().then { picked = $0 }
    waitUntil("pick") { picked != nil }
    guard case .first(let failure) = picked else { return XCTFail() }
    XCTAssertEqual(failure.code, .featureUnsupported)
  }

  // MARK: Admission, analysis and rendering

  func testSyntheticAdmissionAnalyzesBeforeRenderingThroughProductionEngine() throws {
    let (state, selection) = try production()
    var changes: [ScanSessionChange] = []
    let registration = try state.observe { changes.append($0) }
    XCTAssertEqual(registration.snapshot.pageIds, [])
    _ = try admit(state, "a", selection: selection)
    // Analysis may finish before the next snapshot; inspect the frozen admission event.
    waitUntil("admission delivery") { !changes.isEmpty }
    let admitted = try XCTUnwrap(changes.first?.updatedPages["a"])
    XCTAssertTrue(admitted.analyzing)
    XCTAssertFalse(admitted.ready)

    waitUntil("render") { ready(state, "a") }
    let metadata = try XCTUnwrap(page(state, "a"))
    XCTAssertEqual(metadata.revision, 2) // The candidate replaced full-frame geometry once.
    XCTAssertEqual(metadata.readyRevision, 2)
    XCTAssertEqual(SessionQuad.coordinates(metadata.quad), candidateQuad)
    XCTAssertEqual(metadata.analysis?.sourceWidth, 480)
    XCTAssertEqual(metadata.analysis?.candidate?.score, 0.9)
    XCTAssertNil(metadata.analysisFailure)
    waitUntil("delivery") { changes.last?.updatedPages["a"]?.ready == true }
    XCTAssertEqual(changes.map(\.sourceVersion), (1...changes.count).map(Double.init))
    XCTAssertFalse(changes.contains { $0.updatedPages["a"]?.ready == true && $0.updatedPages["a"]?.analyzing == true })

    let source = try state.imageSource("a")
    XCTAssertEqual(source.url.pathExtension, "jpg")
    XCTAssertTrue(source.url.path.hasPrefix(root.appendingPathComponent("sessions").path))
    source.close()
    XCTAssertNil(closed(state))
  }

  func testMissingModelSelectionRendersFullFrameAndRequiresExplicitCropForExport() throws {
    let (state, _) = try production()
    _ = try admit(state, "a", selection: nil)
    waitUntil("render") { ready(state, "a") }
    let metadata = try XCTUnwrap(page(state, "a"))
    XCTAssertEqual(metadata.analysisFailure?.code, .modelLoadFailed)
    XCTAssertEqual(SessionQuad.coordinates(metadata.quad), SessionQuad.fullFrame)
    XCTAssertThrowsError(try state.exportSnapshot(nil)) { XCTAssertEqual(($0 as? ScannerCodeError)?.code, .analysisFailed) }
    try state.setDocumentCrop("a", quad: SessionQuad.quad(SessionQuad.fullFrame))
    XCTAssertNoThrow(try state.exportSnapshot(nil).close())
    XCTAssertNil(closed(state))
  }

  func testExplicitCropDuringAnalysisWinsOverLaterCandidate() throws {
    let (state, actions) = try scripted()
    actions.analysisGate = DispatchSemaphore(value: 0)
    _ = try admit(state, "a")
    let explicit: QuadCoordinates = [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]
    try state.setDocumentCrop("a", quad: SessionQuad.quad(explicit))
    XCTAssertEqual(page(state, "a")?.revision, 2)
    XCTAssertEqual(page(state, "a")?.rendering, false) // Rendering waits for analysis.
    actions.analysisGate?.signal()
    waitUntil("render") { ready(state, "a") }
    let metadata = try XCTUnwrap(page(state, "a"))
    XCTAssertEqual(SessionQuad.coordinates(metadata.quad), explicit)
    XCTAssertEqual(metadata.revision, 2)
    XCTAssertEqual(metadata.analysis?.candidate?.score, 0.9) // Reported, but not applied.
    XCTAssertEqual(actions.log, ["analysis", "render:none"])
    XCTAssertNil(closed(state))
  }

  func testNoOpEditsRetryFailuresAndKeepPreviousDisplayedRender() throws {
    let (state, actions) = try scripted()
    _ = try admit(state, "a")
    waitUntil("first render") { ready(state, "a") }
    let first = try state.imageSource("a")
    let version = state.snapshot().sourceVersion

    try state.setFilter("a", filter: DocumentFilter.none) // Unchanged settings: no revision or work.
    try state.setShadowRemoval("a", enabled: true)
    XCTAssertEqual(state.snapshot().sourceVersion, version)

    actions.failRenders = 1
    try state.setFilter("a", filter: .grayscale)
    waitUntil("failure") { page(state, "a")?.renderFailure != nil }
    var metadata = try XCTUnwrap(page(state, "a"))
    XCTAssertEqual(metadata.renderFailure?.code, .renderFailed)
    XCTAssertEqual(metadata.readyRevision, 2)
    XCTAssertFalse(metadata.rendering)
    let kept = try state.imageSource("a")
    XCTAssertEqual(kept.key, first.key) // The failed revision never replaced the displayed render.
    kept.close()

    try state.setFilter("a", filter: .grayscale) // Same settings retry after a failure.
    waitUntil("retry") { ready(state, "a") }
    metadata = try XCTUnwrap(page(state, "a"))
    XCTAssertEqual(metadata.revision, 4)
    XCTAssertNil(metadata.renderFailure)
    XCTAssertTrue(FileManager.default.fileExists(atPath: first.url.path)) // Pinned until this read closes.
    first.close()
    XCTAssertNil(closed(state))
  }

  func testCompatibleFilterRendersAreReusedWithoutNewWork() throws {
    let (state, actions) = try scripted()
    _ = try admit(state, "a")
    waitUntil("render") { ready(state, "a") }
    try state.setFilter("a", filter: .enhance)
    waitUntil("enhance") { ready(state, "a") }
    try state.setFilter("a", filter: DocumentFilter.none)
    XCTAssertTrue(ready(state, "a")) // Cached slot published synchronously.
    XCTAssertEqual(actions.log, ["analysis", "render:none", "render:enhance"])
    try state.setDocumentCrop("a", quad: SessionQuad.quad(SessionQuad.fullFrame))
    waitUntil("geometry") { ready(state, "a") }
    XCTAssertEqual(actions.log.last, "render:none")
    XCTAssertNil(closed(state))
  }

  // MARK: Membership and resources

  func testOrderRemovalAndIndependentReadsRetireFilesAndSources() throws {
    let (state, _) = try scripted()
    let sourceA = try admit(state, "a")
    _ = try admit(state, "b")
    waitUntil("renders") { ready(state, "a") && ready(state, "b") }
    XCTAssertThrowsError(try state.setPageOrder(["a"]))
    XCTAssertThrowsError(try state.setPageOrder(["a", "a"]))
    try state.setPageOrder(["b", "a"])
    XCTAssertEqual(state.snapshot().pageIds, ["b", "a"])

    let read = try state.imageSource("a")
    let original = try state.imageSource("a", original: true)
    XCTAssertEqual(original.url, sourceA.standardizedFileURL)
    try state.removePage("a")
    XCTAssertEqual(state.snapshot().pageIds, ["b"])
    XCTAssertThrowsError(try state.imageSource("a"))
    store.drain()
    XCTAssertTrue(FileManager.default.fileExists(atPath: read.url.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: sourceA.path))
    read.close()
    original.close()
    waitUntil("retirement") {
      !FileManager.default.fileExists(atPath: read.url.path) && !FileManager.default.fileExists(atPath: sourceA.path)
    }
    XCTAssertFalse(store.owns(sourceA))
    XCTAssertNil(closed(state))
    XCTAssertEqual(sessionDirectories(), [])
    XCTAssertTrue(failures.isEmpty)
  }

  func testAdmissionHandoffFailureAndClaimsRetireOnlyOwnedSources() throws {
    let (state, _) = try scripted()
    let first = try admit(state, "a")
    let duplicate = try store.publish(image: photo())
    XCTAssertThrowsError(try state.admitSource(id: "a", source: duplicate, selection: nil))
    waitUntil("rejected source retired") { !FileManager.default.fileExists(atPath: duplicate.path) }
    // An already admitted source cannot be claimed again, and a foreign file is never retired.
    XCTAssertThrowsError(try state.admitSource(id: "b", source: first, selection: nil))
    XCTAssertTrue(FileManager.default.fileExists(atPath: first.path))
    let foreign = root.appendingPathComponent("host.jpg")
    try Data([1]).write(to: foreign)
    XCTAssertThrowsError(try state.admitSource(id: "c", source: foreign, selection: nil))
    XCTAssertTrue(FileManager.default.fileExists(atPath: foreign.path))
    XCTAssertEqual(state.snapshot().pageIds, ["a"])

    XCTAssertNil(closed(state))
    let late = try store.publish(image: photo())
    XCTAssertThrowsError(try state.admitSource(id: "z", source: late, selection: nil)) {
      XCTAssertEqual(($0 as? ScannerCodeError)?.code, .processorDisposed)
    }
    waitUntil("obsolete admission retired") { !FileManager.default.fileExists(atPath: late.path) }
    XCTAssertFalse(FileManager.default.fileExists(atPath: first.path))
  }

  // MARK: Queue and close

  func testKeyedReplacementFIFOAndExactSettlementAcrossOwners() {
    let queue = ScannerWorkQueue(worker: DispatchQueue(label: "test.fifo"))
    let a = queue.newOwner(), b = queue.newOwner()
    let gate = DispatchSemaphore(value: 0)
    let started = DispatchSemaphore(value: 0)
    let lock = NSLock()
    var ran: [String] = [], settled: [String: Int] = [:], failures: [String: ScannerErrorCode] = [:]
    func job(_ owner: ScannerWorkQueue.Owner, _ name: String, page: String?, block: Bool = false) {
      queue.submit(owner, kind: .render, pageId: page, operation: { () -> String in
        if block { started.signal(); gate.wait() }
        lock.lock(); ran.append(name); lock.unlock()
        return name
      }, onSuccess: { _ in lock.lock(); settled[name, default: 0] += 1; lock.unlock() },
         onFailure: { error in
        lock.lock(); settled[name, default: 0] += 1; failures[name] = ScannerCodeError.code(error, fallback: .renderFailed); lock.unlock()
      })
    }
    job(a, "running", page: "p", block: true)
    started.wait() // Running work is never replaced; only waiting work is.
    job(a, "a1", page: "p")
    job(b, "b1", page: "p")
    job(a, "unkeyed", page: nil)
    job(a, "a2", page: "p") // Replaces a1 only, appended at the tail.
    gate.signal()
    let done = expectation(description: "drained")
    queue.submit(b, kind: .decode, operation: {}, onSuccess: { _ in done.fulfill() }, onFailure: { _ in })
    wait(for: [done], timeout: 5)
    let drained = expectation(description: "a closed")
    queue.close(a).whenComplete { _ in drained.fulfill() }
    wait(for: [drained], timeout: 5)
    lock.lock(); defer { lock.unlock() }
    XCTAssertEqual(ran, ["running", "b1", "unkeyed", "a2"])
    XCTAssertEqual(failures["a1"], .processorSuperseded)
    XCTAssertEqual(Set(settled.values), [1])
    XCTAssertEqual(settled.count, 5)
  }

  func testClosingOneSessionCancelsQueuedWorkWaitsForRunningWorkAndSparesTheOther() throws {
    let (closing, actions) = try scripted()
    let (other, _) = try scripted()
    actions.renderStarted = DispatchSemaphore(value: 0)
    actions.renderGate = DispatchSemaphore(value: 0)
    _ = try admit(closing, "a")
    _ = try admit(closing, "b")
    XCTAssertEqual(actions.renderStarted?.wait(timeout: .now() + 10), .success) // a renders; b waits.
    var result: Error??
    closing.close().whenComplete { result = .some($0) }
    XCTAssertThrowsError(try closing.setFilter("a", filter: .enhance))
    XCTAssertTrue(closing.snapshot().closed)
    RunLoop.main.run(until: Date().addingTimeInterval(0.2))
    XCTAssertNil(result) // Running work still holds the barrier.
    _ = try admit(other, "c")
    actions.renderGate?.signal()
    waitUntil("close") { result != nil }
    XCTAssertNil(result ?? nil)
    XCTAssertEqual(actions.log.filter { $0.hasPrefix("render") }.count, 1)
    XCTAssertEqual(actions.log.last, "close")
    waitUntil("other session") { ready(other, "c") }
    XCTAssertEqual(sessionDirectories().count, 1)
    XCTAssertNil(closed(other))
    XCTAssertEqual(sessionDirectories(), [])
  }

  func testRegistryRemovesOnlyAbandonedGeneratedSessionDirectories() throws {
    let sessions = root.appendingPathComponent("sessions")
    try FileManager.default.createDirectory(at: sessions, withIntermediateDirectories: true)
    let abandoned = sessions.appendingPathComponent("session-\(UUID().uuidString.lowercased())")
    try FileManager.default.createDirectory(at: abandoned, withIntermediateDirectories: true)
    try Data([1]).write(to: abandoned.appendingPathComponent("render-x.jpg"))
    let host = sessions.appendingPathComponent("session-not-a-uuid")
    try FileManager.default.createDirectory(at: host, withIntermediateDirectories: true)
    let (state, _) = try scripted()
    let swept = expectation(description: "sweep")
    registry.cleanupAbandoned().whenComplete { _ in swept.fulfill() }
    wait(for: [swept], timeout: 5)
    XCTAssertFalse(FileManager.default.fileExists(atPath: abandoned.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: host.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: state.resources.directory.path)) // Registered: protected.
    XCTAssertNil(closed(state))
  }

  func testStartupRecoveryRemovesOnlyStrandedStoreOriginals() throws {
    let (state, _) = try scripted()
    let admitted = try admit(state, "a")
    let pending = try store.publish(image: photo()) // Published, awaiting admission.
    let sources = store.directory
    // An earlier process crashed before and after admission, and mid-publish.
    let stranded = [UUID().uuidString + ".jpg", UUID().uuidString + ".jpg", UUID().uuidString + ".partial"]
      .map { sources.appendingPathComponent($0) }
    try stranded.forEach { try Data([1]).write(to: $0) }
    let lowercase = sources.appendingPathComponent(UUID().uuidString.lowercased() + ".jpg")
    let host = sources.appendingPathComponent("host.jpg")
    let target = root.appendingPathComponent("target.jpg")
    let link = sources.appendingPathComponent(UUID().uuidString + ".jpg")
    try [lowercase, host, target].forEach { try Data([1]).write(to: $0) }
    try FileManager.default.createSymbolicLink(at: link, withDestinationURL: target)

    var result: Error??
    registry.cleanupAbandoned().whenComplete { result = .some($0) }
    waitUntil("sweep") { result != nil }
    XCTAssertNil(result ?? nil)
    for file in stranded { XCTAssertFalse(FileManager.default.fileExists(atPath: file.path)) }
    for file in [admitted, pending, lowercase, host, target] { XCTAssertTrue(FileManager.default.fileExists(atPath: file.path)) }
    XCTAssertNotNil(try? FileManager.default.destinationOfSymbolicLink(atPath: link.path))
    XCTAssertTrue(store.owns(pending))
    try state.admitSource(id: "b", source: pending, selection: nil)
    XCTAssertNil(closed(state))
    XCTAssertTrue(failures.isEmpty)
  }

  // MARK: View bindings

  func testBindingTreatsLateRemovalAsRemovalAndRejectsUnknownPages() throws {
    // Background analysis/render completions enqueue too; callbacks run outside the lock.
    let lock = NSLock()
    var deliveries: [() -> Void] = []
    let state = try registry.createState(dispatcher: { delivery in lock.lock(); deliveries.append(delivery); lock.unlock() },
                                         actions: { ScriptedActions(resources: $0) })
    func deliver() {
      while true {
        lock.lock()
        let next = deliveries.isEmpty ? nil : deliveries.removeFirst()
        lock.unlock()
        guard let next else { return }
        next()
      }
    }
    _ = try admit(state, "a")
    deliver()
    var sources: [SessionPageSource?] = [], errors: [ScannerCodeError] = []
    let binding = SessionPageBinding(state: state, id: "a", changed: { sources.append($0) }, failed: { errors.append($0) })
    binding.start()
    XCTAssertNotNil(sources.last ?? nil)
    // The update is still queued for main when the page is removed.
    try state.setFilter("a", filter: .enhance)
    try state.removePage("a")
    deliver()
    XCTAssertTrue(errors.isEmpty)
    XCTAssertNil(sources.last ?? nil)
    sources.compactMap { $0 }.forEach { $0.close() }
    // A restart (a view attaching again) that finds its seen page gone is removal too.
    binding.start()
    XCTAssertTrue(errors.isEmpty)
    XCTAssertNil(sources.last ?? nil)

    // Removal between registration and the first read is removal too.
    _ = try admit(state, "b")
    var racedSources: [SessionPageSource?] = []
    let raced = SessionPageBinding(state: state, id: "b", changed: { racedSources.append($0) }, failed: { errors.append($0) })
    raced.start(afterRegistration: { try? state.removePage("b") })
    deliver()
    XCTAssertTrue(errors.isEmpty)
    XCTAssertEqual(racedSources.count, 1)
    XCTAssertNil(racedSources.last ?? nil)

    let unknown = SessionPageBinding(state: state, id: "missing", changed: { _ in }, failed: { errors.append($0) })
    unknown.start()
    XCTAssertEqual(errors.map(\.code), [.inputInvalid])
    deliver()
    XCTAssertNil(closed(state))
    deliver()
  }

  // MARK: Exports

  private func exportPdf(_ state: ScanSessionState, _ ids: [String]?, to output: URL) -> Result<String, Error> {
    var result: Result<String, Error>?
    SessionExports(state: state).createPdf(ids, options: SessionPdfOptions(outputPath: output.path, imageFit: nil,
      autoRotateExif: nil, jpegQuality: nil, targetDpi: nil, pageWidth: nil, pageHeight: nil, pageAspectRatio: nil)) { result = $0 }
    waitUntil("pdf") { result != nil }
    return result!
  }

  private func pageCount(_ url: URL) -> Int { CGPDFDocument(url as CFURL)?.numberOfPages ?? 0 }

  func testExportsHonorOrderDuplicatesRequestedRevisionsAndRemoval() throws {
    let (state, selection) = try production()
    _ = try admit(state, "a", selection: selection)
    _ = try admit(state, "b", selection: selection)
    let outputs = root.appendingPathComponent("outputs")
    try FileManager.default.createDirectory(at: outputs, withIntermediateDirectories: true)

    // Admitted while analysis may still be pending: the export waits in FIFO order.
    let pdf = outputs.appendingPathComponent("scan.pdf")
    guard case .success(let uri) = exportPdf(state, ["b", "a", "b"], to: pdf) else { return XCTFail("PDF failed") }
    XCTAssertEqual(uri, pdf.absoluteString)
    XCTAssertEqual(pageCount(pdf), 3)

    // A requested revision not yet rendered is produced from the pinned original, even after removal.
    try state.setFilter("a", filter: .blackwhite)
    var images: Result<[String], Error>?
    SessionExports(state: state).exportImages(["a"], directory: outputs.path) { images = $0 }
    try state.removePage("a")
    waitUntil("images") { images != nil }
    guard case .success(let files) = images, files.count == 1 else { return XCTFail("Image export failed") }
    XCTAssertTrue(files[0].hasPrefix(outputs.absoluteString))
    let names = try FileManager.default.contentsOfDirectory(atPath: outputs.path)
    XCTAssertFalse(names.contains { $0.hasPrefix(".") }) // No staged leftovers.

    XCTAssertNil(closed(state))
    XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.path)) // External outputs survive close.
    XCTAssertTrue(FileManager.default.fileExists(atPath: URL(string: files[0])!.path))
  }

  func testExportsRefuseSessionAndSourceAliasesAndRollBackFailures() throws {
    let (state, selection) = try production()
    let source = try admit(state, "a", selection: selection)
    waitUntil("render") { ready(state, "a") }
    let sessionFile = state.resources.directory.appendingPathComponent("out.pdf")
    let aliasRoot = root.appendingPathComponent("alias")
    try FileManager.default.createSymbolicLink(at: aliasRoot, withDestinationURL: state.resources.directory)
    for target in [sessionFile, aliasRoot.appendingPathComponent("out.pdf"), source,
                   URL(fileURLWithPath: state.resources.directory.path.uppercased()).appendingPathComponent("x.pdf")] {
      guard case .failure(let error) = exportPdf(state, nil, to: target) else { return XCTFail("Accepted \(target)") }
      XCTAssertEqual(ScannerCodeError.code(error, fallback: .pdfWriteFailed), .inputInvalid)
    }
    var refused: Result<[String], Error>?
    SessionExports(state: state).exportImages(nil, directory: aliasRoot.path) { refused = $0 }
    guard case .failure = refused else { return XCTFail("Accepted a session directory alias") }

    // A failed write keeps the existing external file and leaves no staging file.
    let outputs = root.appendingPathComponent("outputs")
    try FileManager.default.createDirectory(at: outputs, withIntermediateDirectories: true)
    let existing = outputs.appendingPathComponent("keep.pdf")
    try Data("host".utf8).write(to: existing)
    let bad = SessionPdfOptions(outputPath: existing.path, imageFit: nil, autoRotateExif: nil, jpegQuality: 2,
                                targetDpi: nil, pageWidth: nil, pageHeight: nil, pageAspectRatio: nil)
    var failed: Result<String, Error>?
    SessionExports(state: state).createPdf(nil, options: bad) { failed = $0 }
    waitUntil("failed pdf") { failed != nil }
    guard case .failure = failed else { return XCTFail("Invalid quality accepted") }
    XCTAssertEqual(try Data(contentsOf: existing), Data("host".utf8))
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: outputs.path), ["keep.pdf"])
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    try FileManager.default.removeItem(at: aliasRoot)
    XCTAssertNil(closed(state))
  }

  func testCloseBeforeExportPublicationRemovesStagedOutput() throws {
    let (state, actions) = try scripted()
    _ = try admit(state, "a")
    waitUntil("render") { ready(state, "a") }
    let outputs = root.appendingPathComponent("outputs")
    try FileManager.default.createDirectory(at: outputs, withIntermediateDirectories: true)
    actions.renderGate = DispatchSemaphore(value: 0)
    try state.setFilter("a", filter: .enhance) // Keyed render runs first and blocks the FIFO.
    var images: Result<[String], Error>?
    SessionExports(state: state).exportImages(["a"], directory: outputs.path) { images = $0 }
    var result: Error??
    state.close().whenComplete { result = .some($0) }
    actions.renderGate?.signal()
    waitUntil("export settled") { images != nil && result != nil }
    guard case .failure(let error) = images else { return XCTFail("Export published after close") }
    XCTAssertEqual(ScannerCodeError.code(error, fallback: .storageWriteFailed), .processorDisposed)
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: outputs.path), [])
    XCTAssertNil(result ?? nil)
  }
}

private extension DocumentFilter {
  var stringValue: String {
    switch self {
    case .none: return "none"
    case .enhance: return "enhance"
    case .grayscale: return "grayscale"
    case .blackwhite: return "blackWhite"
    @unknown default: return "unknown"
    }
  }
}
