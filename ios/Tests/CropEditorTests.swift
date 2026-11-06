import XCTest
import UIKit
import CryptoKit
import NitroModules
import UniformTypeIdentifiers
@testable import ReactNativeDocumentScanner

private let inset: QuadCoordinates = [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]

private func assertQuad(_ actual: QuadCoordinates?, _ expected: QuadCoordinates, file: StaticString = #filePath,
                        line: UInt = #line) {
  guard let actual, actual.count == 8 else { return XCTFail("Missing quad", file: file, line: line) }
  for (a, b) in zip(actual, expected) { XCTAssertEqual(a, b, accuracy: 1e-9, "\(actual)", file: file, line: line) }
}

/// Finger input with distinct IDs and a clock; gestures far apart in time never pair into a double tap.
@MainActor
private final class Fingers {
  let editor: CropEditorView
  var time: TimeInterval = 100
  init(_ editor: CropEditorView) { self.editor = editor }

  func down(_ id: Int, _ x: CGFloat, _ y: CGFloat) { editor.touchDown(id, at: CGPoint(x: x, y: y), time: time) }
  func move(_ positions: [Int: (CGFloat, CGFloat)]) {
    var points: [CropEditorView.Touch: CGPoint] = [:]
    for (id, point) in positions { points[id] = CGPoint(x: point.0, y: point.1) }
    editor.touchMoved(points)
  }
  func up(_ id: Int, _ x: CGFloat, _ y: CGFloat, cancelled: Bool = false) {
    editor.touchUp(id, at: CGPoint(x: x, y: y), time: time, cancelled: cancelled)
  }
  func tap(_ x: CGFloat, _ y: CGFloat, after delay: TimeInterval) {
    time += delay
    down(1, x, y)
    up(1, x, y)
  }
  /// Spreads two fingers from (150, 200)/(250, 200) to (100, 200)/(300, 200): 2x around (200, 200).
  func zoomIn() {
    time += 5
    down(1, 150, 200); down(2, 250, 200)
    move([1: (100, 200), 2: (300, 200)])
    up(1, 100, 200); up(2, 300, 200)
  }
}

final class CropEditorTests: XCTestCase {
  private var root: URL!
  private var store: SourceStore!
  private var registry: ScanSessionRegistry!

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    store = SourceStore(directory: root.appendingPathComponent("sources"))
    registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                   queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.crop-editor")))
  }

  override func tearDown() {
    store.shutdown(); store.drain()
    try? FileManager.default.removeItem(at: root)
  }

  // MARK: Helpers

  /// A 400 x 400 editor over a 100 x 100 photo, so the photo fills it at the fit.
  @MainActor
  private func editor(_ check: (CropEditorView, Fingers) throws -> Void) rethrows {
    let editor = CropEditorView(frame: CGRect(x: 0, y: 0, width: 400, height: 400))
    editor.image = picture(width: 100, height: 100)
    editor.layoutIfNeeded()
    try check(editor, Fingers(editor))
  }

  private func session() throws -> ScanSessionState {
    try registry.createState(dispatcher: { DispatchQueue.main.async(execute: $0) },
                             actions: { PictureActions(resources: $0, failures: 0) })
  }

  @discardableResult
  private func admit(_ state: ScanSessionState, _ id: String, width: Int = 300, height: Int = 200,
                     crop: QuadCoordinates? = nil) throws -> URL {
    let source = try store.publish(image: picture(width: width, height: height))
    try state.admitSource(id: id, source: source, selection: nil)
    if let crop { try state.setDocumentCrop(id, quad: SessionQuad.quad(crop)) }
    return source
  }

  private func waitUntil(_ message: String, timeout: TimeInterval = 20, _ condition: () -> Bool) {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
      if Date() > deadline { return XCTFail("Timed out: \(message)") }
      RunLoop.main.run(until: Date().addingTimeInterval(0.01))
    }
  }

  private func pump(_ seconds: TimeInterval = 0.1) { RunLoop.main.run(until: Date().addingTimeInterval(seconds)) }

  /// Waits for already queued image work, including work it enqueues as it settles.
  private func drain() {
    for _ in 0..<3 {
      let done = expectation(description: "drained")
      let owner = registry.queue.newOwner()
      registry.queue.submit(owner, kind: .decode, operation: {}, onSuccess: { _ in done.fulfill() },
                            onFailure: { _ in done.fulfill() })
      wait(for: [done], timeout: 20)
      registry.queue.close(owner)
    }
    pump()
  }

  /// Blocks the shared image worker until the returned gate is signalled.
  private func block() -> DispatchSemaphore {
    let gate = DispatchSemaphore(value: 0)
    let started = DispatchSemaphore(value: 0)
    let owner = registry.queue.newOwner()
    registry.queue.submit(owner, kind: .decode, operation: { started.signal(); gate.wait() },
                          onSuccess: { _ in }, onFailure: { _ in })
    started.wait()
    return gate
  }

  @MainActor
  private func host(_ view: UIView) {
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
    window.isHidden = false
    view.frame = window.bounds
    window.addSubview(view)
    view.layoutIfNeeded()
    addTeardownBlock { @MainActor in
      view.removeFromSuperview()
      window.isHidden = true
    }
  }

  private final class Box<T>: @unchecked Sendable { var value: T?; init() {} }

  private func value<T>(_ promise: Promise<T>) -> T? {
    let box = Box<T>()
    let done = expectation(description: "promise")
    _ = promise.then { box.value = $0; done.fulfill() }
    wait(for: [done], timeout: 10)
    return box.value
  }

  /// Records what a hybrid editor reports.
  @MainActor
  private final class Reports {
    var presets: [CropDraftPreset] = []
    var loads: [CGSize] = []
    var errors: [ScannerErrorCode] = []

    func attach(_ hybrid: HybridDocumentCropEditorView) {
      hybrid.onPresetChange = { [unowned self] in presets.append($0) }
      hybrid.onLoad = { [unowned self] in loads.append(CGSize(width: $0, height: $1)) }
      hybrid.onError = { [unowned self] in errors.append($0.code) }
    }
  }

  @MainActor
  private func makeHybrid(_ state: ScanSessionState?, _ id: String, filter: DocumentFilter? = nil,
                      reports: Reports) -> HybridDocumentCropEditorView {
    let hybrid = HybridDocumentCropEditorView()
    reports.attach(hybrid)
    hybrid.session = state.map { HybridScanSession(state: $0) } ?? HybridScanSession.placeholder()
    hybrid.pageId = id
    hybrid.filter = filter
    hybrid.afterUpdate()
    return hybrid
  }

  private func editorView(_ hybrid: HybridDocumentCropEditorView) -> CropEditorView { hybrid.view as! CropEditorView }

  private func draft(_ hybrid: HybridDocumentCropEditorView) -> QuadCoordinates? {
    guard case .second(let quad) = value(try! hybrid.getQuadResult()) else { return nil }
    return SessionQuad.coordinates(quad)
  }

  private func failure(_ result: Variant_Bool_ScannerFailure?) -> ScannerErrorCode? {
    if case .second(let failure) = result { return failure.code }
    return nil
  }

  private func savedQuad(_ state: ScanSessionState, _ id: String) -> QuadCoordinates? {
    state.snapshot().pages[id].map { SessionQuad.coordinates($0.quad) }
  }

  private func pixel(_ image: CGImage, _ x: Int, _ y: Int) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 4)
    let context = CGContext(data: &bytes, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.draw(image, in: CGRect(x: -x, y: y - image.height + 1, width: image.width, height: image.height))
    return bytes
  }

  // MARK: Editing

  @MainActor
  func testCornerDragSnapsUnderTheFingerClampsToThePhotoAndPublishesOnRelease() {
    editor { editor, fingers in
      var published: [QuadCoordinates] = []
      editor.onQuadEdited = { published.append($0) }
      XCTAssertEqual(editor.content, CGRect(x: 0, y: 0, width: 400, height: 400))
      fingers.down(1, 10, 10)
      assertQuad(editor.quad, [0.025, 0.025, 1, 0, 1, 1, 0, 1])
      fingers.move([1: (-50, -50)])
      assertQuad(editor.quad, CropQuadEditing.fullFrame)
      fingers.move([1: (50, 30)])
      assertQuad(editor.quad, [0.125, 0.075, 1, 0, 1, 1, 0, 1])
      XCTAssertTrue(published.isEmpty)
      fingers.up(1, 50, 30)
      XCTAssertEqual(published.count, 1)
      assertQuad(published.first, [0.125, 0.075, 1, 0, 1, 1, 0, 1])

      // A press and release that leaves the quad unchanged publishes nothing.
      fingers.down(1, 400, 0)
      fingers.up(1, 400, 0)
      XCTAssertEqual(published.count, 1)
    }
  }

  @MainActor
  func testRectangleCropMovesCornersOnlyKeepsItUprightAndNeverZooms() throws {
    try editor { editor, fingers in
      try editor.replaceQuad([0.1, 0.05, 0.9, 0.1, 0.95, 0.9, 0.05, 0.85])
      editor.rectangle = true
      assertQuad(editor.quad, [0.05, 0.05, 0.95, 0.05, 0.95, 0.9, 0.05, 0.9])
      // The top-left corner drags its neighbours along; the bottom-right stays.
      fingers.down(1, 20, 20)
      fingers.move([1: (80, 120)])
      fingers.up(1, 80, 120)
      assertQuad(editor.quad, [0.2, 0.3, 0.95, 0.3, 0.95, 0.9, 0.2, 0.9])
      // An edge middle is no handle, and two fingers away from the corners do not zoom.
      let before = editor.quad
      fingers.down(1, 230, 120)
      fingers.move([1: (230, 200)])
      fingers.up(1, 230, 200)
      assertQuad(editor.quad, before)
      fingers.down(1, 150, 200)
      fingers.down(2, 250, 200)
      fingers.move([1: (100, 200), 2: (300, 200)])
      XCTAssertEqual(editor.zoom, 1)
    }
    XCTAssertEqual(CropQuadEditing.moveRectangleCorner([0.2, 0.1, 0.8, 0.1, 0.8, 0.9, 0.2, 0.9], 0, x: 0.9, y: 0.3), nil,
                   "a corner past the opposite one turns the rectangle inside out")
  }

  @MainActor
  func testEdgeDragKeepsTheLastValidQuadAndShortensAtThePhotoBounds() throws {
    try editor { editor, fingers in
      try editor.replaceQuad(inset)
      var published: [QuadCoordinates] = []
      editor.onQuadEdited = { published.append($0) }
      fingers.down(1, 200, 40)
      fingers.move([1: (200, 80)])
      let valid: QuadCoordinates = [0.1, 0.2, 0.9, 0.2, 0.9, 0.9, 0.1, 0.9]
      assertQuad(editor.quad, valid)
      // Past the bottom edge the quad would cross, so the last valid one stays.
      fingers.move([1: (200, 380)])
      assertQuad(editor.quad, valid)
      fingers.up(1, 200, 380)
      XCTAssertEqual(published.count, 1)
      assertQuad(published.last, valid)

      // The left edge (bottom-left to top-left) stops at the photo's edge.
      fingers.down(1, 40, 220)
      fingers.move([1: (-100, 220)])
      assertQuad(editor.quad, [0, 0.2, 0.9, 0.2, 0.9, 0.9, 0, 0.9])
      fingers.up(1, -100, 220, cancelled: true)
      XCTAssertEqual(published.count, 2)
    }
  }

  @MainActor
  func testInvalidMovesAndQuadsAreRejectedAndReplacementCancelsADrag() throws {
    try editor { editor, fingers in
      try editor.replaceQuad(inset)
      var published = 0
      editor.onQuadEdited = { _ in published += 1 }
      // The top-left corner past the bottom-right one would cross the quad.
      fingers.down(1, 40, 40)
      fingers.move([1: (380, 380)])
      assertQuad(editor.quad, inset)
      fingers.up(1, 380, 380)
      XCTAssertEqual(published, 0)

      for invalid: QuadCoordinates in [[-0.1, 0, 1, 0, 1, 1, 0, 1], [0, 0, 1, 1, 1, 0, 0, 1],
                                        [0.5, 0.5, 0.501, 0.5, 0.501, 0.501, 0.5, 0.501], [0, 0, 1, 0, 1, 1],
                                        [Double.nan, 0, 1, 0, 1, 1, 0, 1]] {
        XCTAssertThrowsError(try editor.replaceQuad(invalid))
      }
      assertQuad(editor.quad, inset)

      fingers.down(1, 40, 40)
      fingers.move([1: (60, 60)])
      XCTAssertTrue(editor.isDragging)
      try editor.replaceQuad(CropQuadEditing.fullFrame)
      XCTAssertFalse(editor.isDragging)
      XCTAssertTrue(editor.magnifier.isHidden)
      fingers.move([1: (80, 80)])
      fingers.up(1, 80, 80)
      assertQuad(editor.quad, CropQuadEditing.fullFrame)
      XCTAssertEqual(published, 0)
    }
  }

  @MainActor
  func testTouchesThatStartBeforeThePhotoIsShownAreIgnored() {
    let editor = CropEditorView(frame: CGRect(x: 0, y: 0, width: 400, height: 400))
    let fingers = Fingers(editor)
    var published = 0
    editor.onQuadEdited = { _ in published += 1 }
    fingers.down(1, 0, 0)
    editor.image = picture(width: 100, height: 100)
    fingers.move([1: (100, 100)])
    fingers.up(1, 100, 100)
    assertQuad(editor.quad, CropQuadEditing.fullFrame)
    XCTAssertEqual(published, 0)
  }

  // MARK: Zoom

  @MainActor
  func testTwoFingerPanWorksWithoutPinchingAndStopsWhenOneFingerLifts() {
    editor { editor, fingers in
      var published = 0
      editor.onQuadEdited = { _ in published += 1 }
      fingers.zoomIn()
      XCTAssertEqual(editor.zoom, 2)
      XCTAssertEqual(editor.content, CGRect(x: -200, y: -200, width: 800, height: 800))
      fingers.time += 5
      fingers.down(1, 150, 200); fingers.down(2, 250, 200)
      fingers.move([1: (230, 200), 2: (330, 200)])
      XCTAssertEqual(editor.zoom, 2)
      XCTAssertEqual(editor.content.minX, -120)
      fingers.up(2, 330, 200)
      fingers.move([1: (330, 200)])
      XCTAssertEqual(editor.content.minX, -120)
      fingers.up(1, 330, 200)
      // The pan stops at the photo's edge.
      fingers.time += 5
      fingers.down(1, 150, 200); fingers.down(2, 250, 200)
      fingers.move([1: (750, 200), 2: (850, 200)])
      XCTAssertEqual(editor.content.minX, 0)
      fingers.up(1, 750, 200); fingers.up(2, 850, 200)
      assertQuad(editor.quad, CropQuadEditing.fullFrame)
      XCTAssertEqual(published, 0, "Viewport gestures do not edit the crop")
    }
  }

  @MainActor
  func testSimultaneousPinchAndPanCountsTranslationOnceAndCapsTheZoom() {
    editor { editor, fingers in
      fingers.down(1, 150, 200); fingers.down(2, 250, 200)
      fingers.move([1: (100, 200), 2: (300, 200)])
      XCTAssertEqual(editor.zoom, 2)
      // Doubles the spread while moving the centroid 80 points right.
      fingers.move([1: (80, 200), 2: (480, 200)])
      XCTAssertEqual(editor.zoom, 4)
      XCTAssertEqual(editor.content.midX, 280, accuracy: 1e-9)
      XCTAssertEqual(editor.content.midY, 200, accuracy: 1e-9)
      fingers.move([1: (-1000, 200), 2: (1600, 200)])
      XCTAssertEqual(editor.zoom, CropEditorView.maxZoom)
      fingers.up(1, -1000, 200, cancelled: true); fingers.up(2, 1600, 200, cancelled: true)
      assertQuad(editor.quad, CropQuadEditing.fullFrame)
    }
  }

  @MainActor
  func testDoubleTapFitsThePhotoAndAHandlePressBreaksTheTapSequence() throws {
    try editor { editor, fingers in
      try editor.replaceQuad([0.3, 0.3, 0.7, 0.3, 0.7, 0.7, 0.3, 0.7])
      fingers.zoomIn()
      XCTAssertEqual(editor.corner(0), CGPoint(x: 40, y: 40))
      fingers.tap(200, 300, after: 5)
      // A handle press between taps; the next tap starts a new sequence.
      fingers.time += 0.1
      fingers.down(1, 40, 40); fingers.up(1, 40, 40)
      fingers.tap(200, 300, after: 0.1)
      XCTAssertEqual(editor.zoom, 2)
      // Too slow or too far is not a double tap.
      fingers.tap(200, 300, after: 0.5)
      fingers.tap(390, 30, after: 0.1)
      XCTAssertEqual(editor.zoom, 2)
      fingers.tap(390, 40, after: 0.1)
      XCTAssertEqual(editor.zoom, 1)
      XCTAssertEqual(editor.content, CGRect(x: 0, y: 0, width: 400, height: 400))

      // A long press is not the first tap of a double tap.
      fingers.zoomIn()
      fingers.time += 5
      fingers.down(1, 200, 300)
      fingers.time += 1
      fingers.up(1, 200, 300)
      fingers.tap(200, 300, after: 0.1)
      XCTAssertEqual(editor.zoom, 2)
    }
  }

  @MainActor
  func testHandlesKeepTheirScreenSizeAndZoomedEditsStayInPhotoCoordinates() throws {
    try editor { editor, fingers in
      try editor.replaceQuad([0.3, 0.3, 0.7, 0.3, 0.7, 0.7, 0.3, 0.7])
      let radius = CropEditorView.cornerRadius
      XCTAssertEqual(editor.handles.path!.boundingBox, CGRect(x: 120 - radius, y: 120 - radius,
                                                               width: 160 + radius * 2, height: 160 + radius * 2))
      fingers.zoomIn()
      XCTAssertEqual(editor.handles.path!.boundingBox, CGRect(x: 40 - radius, y: 40 - radius,
                                                               width: 320 + radius * 2, height: 320 + radius * 2))
      XCTAssertEqual(editor.outline.lineWidth, 2)
      var published: [QuadCoordinates] = []
      editor.onQuadEdited = { published.append($0) }
      fingers.time += 5
      fingers.down(1, 40, 40)
      fingers.move([1: (80, 60)])
      fingers.up(1, 80, 60)
      assertQuad(published.last, [0.35, 0.325, 0.7, 0.3, 0.7, 0.7, 0.3, 0.7])
    }
  }

  @MainActor
  func testMagnifierFollowsCornerDragsBesideOrAboveTheFingerAndCompensatesForZoom() throws {
    try editor { editor, fingers in
      try editor.replaceQuad(inset)
      XCTAssertTrue(editor.magnifier.isHidden)
      // No room above the top-left corner: level with the finger, on the side away from the left edge.
      fingers.down(1, 40, 40)
      XCTAssertFalse(editor.magnifier.isHidden)
      XCTAssertEqual(editor.magnifier.center, CGPoint(x: 140, y: 72))
      XCTAssertEqual(editor.magnifier.bounds.size, CGSize(width: 120, height: 120))
      // The corner sits under the crosshair at three times the fit.
      XCTAssertEqual(editor.magnifiedImage.frame, CGRect(x: -60, y: -60, width: 1200, height: 1200))
      fingers.up(1, 40, 40)
      XCTAssertTrue(editor.magnifier.isHidden)

      // Room above the bottom-right corner; kept inside the view.
      fingers.down(1, 360, 360)
      XCTAssertEqual(editor.magnifier.center, CGPoint(x: 328, y: 260))
      XCTAssertEqual(editor.magnifiedImage.frame, CGRect(x: -1020, y: -1020, width: 1200, height: 1200))
      fingers.up(1, 360, 360)

      // Edge drags show no magnifier.
      fingers.down(1, 200, 40)
      XCTAssertTrue(editor.magnifier.isHidden)
      fingers.up(1, 200, 40)

      // Zoomed in, the magnifier adds only what keeps the total near 3x, but at least 1.5x.
      fingers.zoomIn()
      fingers.time += 5
      let corner = editor.corner(0)
      fingers.down(1, corner.x, corner.y)
      XCTAssertEqual(editor.magnifiedImage.frame.width, 800 * CropEditorView.minMagnification)
      fingers.up(1, corner.x, corner.y)
    }
  }

  // MARK: Session editor

  @MainActor
  func testDraftPresetsAndProgrammaticGeometryNeverSubmitPageEdits() throws {
    let state = try session()
    try admit(state, "page", crop: inset)
    waitUntil("analysis") { state.snapshot().pages["page"]?.analysis != nil }
    let reports = Reports()
    let hybrid = makeHybrid(state, "page", reports: reports)
    let revision = state.snapshot().pages["page"]?.revision
    XCTAssertEqual(reports.presets, [.custom])
    assertQuad(draft(hybrid), inset)

    if case .first(true) = value(try hybrid.setPresetResult(preset: .none)) {} else { XCTFail("none preset") }
    assertQuad(draft(hybrid), CropQuadEditing.fullFrame)
    if case .first(true) = value(try hybrid.setPresetResult(preset: .auto)) {} else { XCTFail("auto preset") }
    assertQuad(draft(hybrid), [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8])
    let editor = editorView(hybrid)
    try editor.replaceQuad([0.25, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8])
    editor.onQuadEdited?(editor.quad)
    XCTAssertEqual(reports.presets, [.custom, .none, .auto, .custom])
    // Nothing reached the page yet.
    assertQuad(savedQuad(state, "page"), inset)
    XCTAssertEqual(state.snapshot().pages["page"]?.revision, revision)

    if case .first(true) = value(try hybrid.setPresetResult(preset: .none)) {} else { XCTFail("reset") }
    if case .first(true) = value(try hybrid.submitResult()) {} else { XCTFail("submit") }
    assertQuad(savedQuad(state, "page"), CropQuadEditing.fullFrame)

    // Unmounting with an unsaved draft sends nothing; afterwards every method rejects.
    try editor.replaceQuad(inset)
    let submitted = state.snapshot().pages["page"]?.revision
    hybrid.onDropView()
    XCTAssertEqual(state.snapshot().pages["page"]?.revision, submitted)
    if case .first(let failure) = value(try hybrid.getQuadResult()) {
      XCTAssertEqual(failure.code, .inputInvalid)
    } else { XCTFail("getQuad after drop") }
    XCTAssertEqual(failure(value(try hybrid.submitResult())), .inputInvalid)
    XCTAssertEqual(failure(value(try hybrid.setPresetResult(preset: .none))), .inputInvalid)
    XCTAssertTrue(reports.errors.isEmpty)
  }

  @MainActor
  func testAutoPresetNeedsACandidateAndAnInvalidSubmitIsRejected() throws {
    let state = try registry.createState(dispatcher: { DispatchQueue.main.async(execute: $0) },
                                         actions: { _ in NoCandidateActions() })
    try admit(state, "page")
    waitUntil("analysis") { state.snapshot().pages["page"]?.analysis != nil }
    let reports = Reports()
    let hybrid = makeHybrid(state, "page", reports: reports)
    XCTAssertEqual(failure(value(try hybrid.setPresetResult(preset: .auto))), .inputInvalid)
    assertQuad(draft(hybrid), CropQuadEditing.fullFrame)
    XCTAssertEqual(reports.presets, [.none])
  }

  @MainActor
  func testANewPageWithANewModeKeepsItsSavedPerspectiveCrop() throws {
    let state = try session()
    let perspective: QuadCoordinates = [0.1, 0.05, 0.9, 0.1, 0.95, 0.9, 0.05, 0.85]
    try admit(state, "photo", crop: inset)
    try admit(state, "page", crop: perspective)
    let hybrid = makeHybrid(state, "photo", reports: Reports())
    hybrid.rectangle = true
    hybrid.afterUpdate()
    hybrid.pageId = "page"
    hybrid.rectangle = false
    hybrid.afterUpdate()
    assertQuad(draft(hybrid), perspective)
  }

  @MainActor
  func testPropReplacementRebindsTheDraftOnlyForANewSessionOrPage() throws {
    let state = try session()
    try admit(state, "a", crop: inset)
    // Not the analysis candidate, so it classifies as custom.
    try admit(state, "b", crop: [0.3, 0.3, 0.7, 0.3, 0.7, 0.7, 0.3, 0.7])
    let reports = Reports()
    let hybrid = makeHybrid(state, "a", reports: reports)
    _ = value(try hybrid.setPresetResult(preset: .none))
    // The same props keep the draft.
    hybrid.afterUpdate()
    assertQuad(draft(hybrid), CropQuadEditing.fullFrame)
    hybrid.pageId = "b"
    hybrid.afterUpdate()
    assertQuad(draft(hybrid), [0.3, 0.3, 0.7, 0.3, 0.7, 0.7, 0.3, 0.7])
    // Back on "a", the draft starts again from its saved crop.
    hybrid.pageId = "a"
    hybrid.afterUpdate()
    assertQuad(draft(hybrid), inset)
    XCTAssertEqual(reports.presets, [.custom, .none, .custom, .custom])

    // A saved crop outside the photo, which renders allow, is invalid input. The editor shows
    // its clamp as a suggestion, but nothing reads or submits it until the user chooses.
    let other = try session()
    let outside: QuadCoordinates = [-0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]
    try admit(other, "a", crop: outside)
    try admit(other, "b", crop: outside)
    hybrid.session = HybridScanSession(state: other)
    hybrid.afterUpdate()
    XCTAssertEqual(reports.errors, [.inputInvalid])
    assertQuad(editorView(hybrid).quad, [0, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9])
    XCTAssertNil(draft(hybrid))
    XCTAssertEqual(failure(value(try hybrid.submitResult())), .inputInvalid)
    assertQuad(savedQuad(other, "a"), outside)
    XCTAssertEqual(reports.presets, [.custom, .none, .custom, .custom])
    // A preset is a choice.
    if case .first(true) = value(try hybrid.setPresetResult(preset: .none)) {} else { XCTFail("none preset") }
    assertQuad(draft(hybrid), CropQuadEditing.fullFrame)
    XCTAssertEqual(reports.presets, [.custom, .none, .custom, .custom, .none])
    // So is an edit.
    hybrid.pageId = "b"
    hybrid.afterUpdate()
    XCTAssertEqual(reports.errors, [.inputInvalid, .inputInvalid])
    XCTAssertNil(draft(hybrid))
    let edited: QuadCoordinates = [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]
    let editor = editorView(hybrid)
    host(editor)
    waitUntil("suggestion photo") { editor.image != nil }
    let fingers = Fingers(editor)
    let start = editor.corner(0)
    let target = CGPoint(x: editor.content.minX + editor.content.width * 0.1, y: start.y)
    let presetCount = reports.presets.count
    fingers.down(1, start.x, start.y)
    fingers.move([1: (target.x, target.y)])
    assertQuad(editor.quad, edited)
    XCTAssertNil(draft(hybrid))
    assertQuad(savedQuad(other, "b"), outside)
    XCTAssertEqual(reports.presets.count, presetCount)
    fingers.up(1, target.x, target.y)
    assertQuad(draft(hybrid), edited)
    XCTAssertEqual(reports.presets.count, presetCount + 1)
    assertQuad(savedQuad(other, "b"), outside)
    if case .first(true) = value(try hybrid.submitResult()) {} else { XCTFail("submit") }
    assertQuad(savedQuad(other, "b"), edited)

    hybrid.session = HybridScanSession.placeholder()
    hybrid.afterUpdate()
    XCTAssertEqual(reports.errors, [.inputInvalid, .inputInvalid, .inputInvalid])
    if case .first(let failure) = value(try hybrid.getQuadResult()) { XCTAssertEqual(failure.code, .inputInvalid) }
    else { XCTFail("getQuad without a native session") }

    // An unknown page is invalid input.
    hybrid.session = HybridScanSession(state: state)
    hybrid.pageId = "missing"
    hybrid.afterUpdate()
    waitUntil("missing page") { reports.errors.count == 4 }
    XCTAssertEqual(reports.errors, [.inputInvalid, .inputInvalid, .inputInvalid, .inputInvalid])
  }

  // MARK: Sources

  @MainActor
  func testTIFFPNGAndJPEGOriginalsLoadUprightWithTheirDecodedSize() throws {
    let state = try session()
    for (index, type) in [UTType.tiff, .png, .jpeg].enumerated() {
      let id = "page\(index)"
      let url = try admit(state, id)
      // A 300 x 200 picture stored rotated: EXIF orientation 6 shows it 200 x 300.
      try write(picture(width: 300, height: 200), to: url, type: type, orientation: 6)
      let reports = Reports()
      let hybrid = makeHybrid(state, id, reports: reports)
      XCTAssertNil(editorView(hybrid).image, "Nothing loads while detached")
      host(hybrid.view)
      waitUntil("\(type) load") { !reports.loads.isEmpty }
      XCTAssertEqual(reports.loads, [CGSize(width: 200, height: 300)], "\(type)")
      XCTAssertEqual(editorView(hybrid).image?.width, 200)
      XCTAssertTrue(reports.errors.isEmpty)
      hybrid.onDropView()
    }
  }

  @MainActor
  func testFailedLoadReportsAndLeavesTheEditorEmpty() throws {
    let state = try session()
    let url = try admit(state, "page")
    try Data("not an image".utf8).write(to: url)
    let reports = Reports()
    let hybrid = makeHybrid(state, "page", reports: reports)
    host(hybrid.view)
    waitUntil("failure") { !reports.errors.isEmpty }
    XCTAssertEqual(reports.errors, [.imageLoadFailed])
    XCTAssertTrue(reports.loads.isEmpty)
    XCTAssertNil(editorView(hybrid).image)
    // The draft is still the page's saved crop and can be read.
    assertQuad(draft(hybrid), savedQuad(state, "page")!)
  }

  @MainActor
  func testFilterPreviewsAreCappedFilteredOffMainAndNeverChangeThePage() throws {
    let state = try session()
    let url = try admit(state, "page", width: 3000, height: 2000, crop: inset)
    let original = SHA256.hash(data: try Data(contentsOf: url))
    let before = state.snapshot().pages["page"]
    let reports = Reports()
    let hybrid = makeHybrid(state, "page", reports: reports)
    host(hybrid.view)
    waitUntil("load") { !reports.loads.isEmpty }
    let editor = editorView(hybrid)
    let photo = try XCTUnwrap(hybrid.photo)
    XCTAssertEqual(reports.loads, [CGSize(width: 3000, height: 2000)])
    XCTAssertTrue(editor.image === photo)

    // While the worker is busy the editor keeps its current image.
    let gate = block()
    hybrid.filter = .grayscale
    hybrid.afterUpdate()
    pump()
    XCTAssertTrue(editor.image === photo)
    gate.signal()
    waitUntil("preview") { editor.image !== photo }
    let preview = try XCTUnwrap(editor.image)
    XCTAssertEqual(CGSize(width: preview.width, height: preview.height), CGSize(width: 2400, height: 1600))
    let dark = pixel(preview, 2000, 400)
    XCTAssertEqual(dark[0], dark[1])
    XCTAssertEqual(dark[1], dark[2])
    // A replacement image of the same photo keeps the zoom and the draft.
    XCTAssertEqual(editor.content.size.width / editor.content.size.height, 1.5, accuracy: 1e-6)

    hybrid.filter = DocumentFilter.none
    hybrid.afterUpdate()
    XCTAssertTrue(editor.image === photo)
    _ = value(try hybrid.setPresetResult(preset: .none))

    let after = state.snapshot().pages["page"]
    XCTAssertEqual(after?.revision, before?.revision)
    XCTAssertEqual(after?.options.filter, before?.options.filter)
    assertQuad(savedQuad(state, "page"), inset)
    XCTAssertEqual(SHA256.hash(data: try Data(contentsOf: url)), original)
    XCTAssertTrue(reports.errors.isEmpty)
  }

  @MainActor
  func testOutdatedFilterAndPhotoResultsAreDiscarded() throws {
    let state = try session()
    try admit(state, "a", width: 300, height: 200)
    try admit(state, "b", width: 480, height: 360)
    let reports = Reports()
    let hybrid = makeHybrid(state, "a", reports: reports)
    host(hybrid.view)
    waitUntil("load a") { reports.loads.count == 1 }
    let editor = editorView(hybrid)
    let photo = try XCTUnwrap(hybrid.photo)

    // A newer filter discards an older one's result.
    var gate = block()
    hybrid.filter = .grayscale
    hybrid.afterUpdate()
    hybrid.filter = DocumentFilter.none
    hybrid.afterUpdate()
    XCTAssertTrue(editor.image === photo)
    gate.signal()
    drain()
    XCTAssertTrue(editor.image === photo)

    // A newer photo discards a pending preview of the previous one.
    gate = block()
    hybrid.filter = .enhance
    hybrid.afterUpdate()
    hybrid.pageId = "b"
    hybrid.afterUpdate()
    XCTAssertNil(editor.image)
    gate.signal()
    waitUntil("load b") { reports.loads.count == 2 }
    drain()
    XCTAssertEqual(reports.loads, [CGSize(width: 300, height: 200), CGSize(width: 480, height: 360)])
    XCTAssertEqual(editor.image?.width, 480)
    XCTAssertFalse(editor.image === hybrid.photo, "b shows its enhanced preview")
    XCTAssertTrue(reports.errors.isEmpty)
  }

  // MARK: Lifecycle

  @MainActor
  func testDetachReleasesTheImageAndRemovalCancelsADragAndInvalidatesTheDraftSilently() throws {
    let state = try session()
    let url = try admit(state, "page")
    let reports = Reports()
    let hybrid = makeHybrid(state, "page", reports: reports)
    host(hybrid.view)
    waitUntil("load") { reports.loads.count == 1 }
    let editor = editorView(hybrid)
    let window = try XCTUnwrap(editor.window)
    editor.removeFromSuperview()
    XCTAssertNil(editor.image)
    XCTAssertNil(hybrid.photo)
    window.addSubview(editor)
    waitUntil("reload") { reports.loads.count == 2 }

    // Removal during a corner drag abandons it: the rest of the touch sequence changes nothing.
    let before = editor.quad
    let presets = reports.presets
    let start = editor.corner(0)
    let fingers = Fingers(editor)
    fingers.down(1, start.x, start.y)
    fingers.move([1: (start.x + 20, start.y + 20)])
    XCTAssertTrue(editor.isDragging)
    XCTAssertFalse(editor.magnifier.isHidden)
    try state.removePage("page")
    waitUntil("cleared") { editor.image == nil }
    XCTAssertFalse(editor.isDragging)
    XCTAssertTrue(editor.magnifier.isHidden)
    assertQuad(editor.quad, before)
    fingers.move([1: (start.x + 40, start.y + 40)])
    fingers.up(1, start.x + 40, start.y + 40)
    assertQuad(editor.quad, before)
    XCTAssertEqual(reports.presets, presets)
    if case .first(let failure) = value(try hybrid.getQuadResult()) { XCTAssertEqual(failure.code, .inputInvalid) }
    else { XCTFail("getQuad after removal") }
    XCTAssertEqual(failure(value(try hybrid.submitResult())), .inputInvalid)
    XCTAssertTrue(reports.errors.isEmpty)
    // No pin outlives the removal.
    drain()
    waitUntil("original deleted") { !FileManager.default.fileExists(atPath: url.path) }
  }
}

/// Analysis finds no page.
private final class NoCandidateActions: ScanSessionActions, @unchecked Sendable {
  func prepareAnalysis(_ source: SessionResources.Lease, liveQuad: QuadCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue {
    { AnalysisValue(width: 480, height: 360, duration: 1, candidate: nil, score: 0) }
  }

  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact {
    throw ScannerCodeError(code: .renderFailed)
  }

  func close() {}
}
