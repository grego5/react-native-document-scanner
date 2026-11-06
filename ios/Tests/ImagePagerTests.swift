import XCTest
import UIKit
@testable import ReactNativeDocumentScanner

/// Records what a pager reports.
@MainActor
private final class Pager {
  var settled: [Int] = []
  var loaded: [String] = []
  var errors: [ScannerErrorCode] = []
  private(set) var view: ImagePagerView!

  init(thumbnails: PageThumbnails) {
    view = ImagePagerView(thumbnails: thumbnails, onPageSettled: { [unowned self] in settled.append($0) },
                          onImageLoaded: { [unowned self] in loaded.append($0) },
                          onImageFailed: { [unowned self] in errors.append($0.code) })
    view.frame = CGRect(x: 0, y: 0, width: 320, height: 480)
  }

  /// Where the pager's left edge rests in the pages.
  var offset: CGFloat { view.collectionView.contentOffset.x + view.collectionView.contentInset.left }
  var swipes: Bool { view.collectionView.isScrollEnabled }

  /// Scrolls as a finished user swipe would, then reports the rest.
  func swipe(to index: Int) { rest(view, on: index) }
}

/// Rests `pager` on `index` as a finished user swipe would, then reports the rest.
@MainActor
private func rest(_ pager: ImagePagerView, on index: Int) {
  let collection = pager.collectionView
  let frame = collection.layoutAttributesForItem(at: IndexPath(item: index, section: 0))!.frame
  collection.setContentOffset(CGPoint(x: frame.minX - collection.contentInset.left, y: 0), animated: false)
  collection.layoutIfNeeded()
  pager.scrollSettled()
}

final class ImagePagerTests: XCTestCase {
  private var root: URL!
  private var store: SourceStore!
  private var registry: ScanSessionRegistry!
  private var thumbnails: PageThumbnails!

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    store = SourceStore(directory: root.appendingPathComponent("sources"))
    registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                   queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.pager-images")))
    thumbnails = PageThumbnails()
  }

  override func tearDown() {
    store.shutdown(); store.drain()
    try? FileManager.default.removeItem(at: root)
  }

  // MARK: Helpers

  /// Renders fail, so pages show their originals.
  private func session() throws -> ScanSessionState {
    try registry.createState(actions: { PictureActions(resources: $0, failures: .max) })
  }

  @discardableResult
  private func admit(_ state: ScanSessionState, _ id: String, width: Int = 480, height: Int = 360) throws -> URL {
    let source = try store.publish(image: picture(width: width, height: height))
    let options = DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil, format: nil,
                                        paperSize: nil, dpi: nil, followPageEdges: nil)
    try state.admitSource(id: id, source: source, options: options, selection: nil)
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
      registry.queue.submit(owner, kind: .decode, operation: {}, onSuccess: { _ in done.fulfill() }, onFailure: { _ in done.fulfill() })
      wait(for: [done], timeout: 20)
      registry.queue.close(owner)
    }
    pump()
  }

  @MainActor
  private func host(_ view: UIView) {
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
    window.isHidden = false
    window.addSubview(view)
    view.layoutIfNeeded()
    // A visible window outlives the test; later tests must not see its views.
    addTeardownBlock { @MainActor in
      view.removeFromSuperview()
      window.isHidden = true
    }
  }

  private func close(_ state: ScanSessionState) {
    let done = expectation(description: "closed")
    state.close().whenComplete { _ in done.fulfill() }
    wait(for: [done], timeout: 20)
  }

  private func size(_ image: CGImage?) -> CGSize? { image.map { CGSize(width: $0.width, height: $0.height) } }

  // MARK: Pager

  @MainActor
  func testIndicesClampAndProgrammaticJumpsFitTheLeftPage() throws {
    let pager = Pager(thumbnails: thumbnails)
    host(pager.view)
    pager.view.show(3)
    XCTAssertEqual(pager.view.current, 0)
    XCTAssertEqual(pager.view.collectionView.numberOfItems(inSection: 0), 0)

    let state = try session()
    try ["a", "b", "c"].forEach { try admit(state, $0) }
    drain()
    pager.view.setPages(["a", "b", "c"], state: state)
    pager.view.show(10)
    XCTAssertEqual(pager.view.current, 2)
    XCTAssertEqual(pager.offset, 640)
    pager.view.show(-1)
    XCTAssertEqual(pager.view.current, 0)
    XCTAssertEqual(pager.offset, 0)

    pager.view.show(1)
    waitUntil("shown page") { pager.loaded.contains("b") }
    let left = try XCTUnwrap(pager.view.shownCell)
    left.image.scrollView.setZoomScale(2, animated: false)
    XCTAssertFalse(pager.swipes)
    // A jump is not blocked by the zoom, and the page it leaves fits again.
    pager.view.show(2)
    XCTAssertEqual(pager.view.current, 2)
    XCTAssertEqual(pager.offset, 640)
    XCTAssertEqual(left.image.zoomScale, 1)
    XCTAssertTrue(pager.swipes)
    XCTAssertEqual(pager.settled, [])
    XCTAssertEqual(pager.errors, [])
    pager.view.release()
    close(state)
  }

  @MainActor
  func testZoomBlocksSwipesAndUserSwipesReportTheirPage() throws {
    let state = try session()
    try ["a", "b", "c"].forEach { try admit(state, $0) }
    drain()
    let pager = Pager(thumbnails: thumbnails)
    host(pager.view)
    pager.view.setPages(["a", "b", "c"], state: state)
    pager.view.show(0)
    waitUntil("first page") { pager.loaded.contains("a") }
    XCTAssertTrue(pager.swipes)
    pager.view.swipeEnabled = false
    XCTAssertFalse(pager.swipes)
    pager.view.swipeEnabled = true
    let shown = try XCTUnwrap(pager.view.shownCell)
    shown.image.scrollView.setZoomScale(2, animated: false)
    XCTAssertFalse(pager.swipes)
    waitUntil("full image") { shown.loader?.fullImage != nil }
    shown.image.resetZoom()
    XCTAssertTrue(pager.swipes)

    pager.swipe(to: 1)
    XCTAssertEqual(pager.settled, [1])
    XCTAssertEqual(pager.view.current, 1)
    // A rest on the same page reports nothing.
    pager.view.scrollSettled()
    XCTAssertEqual(pager.settled, [1])
    // A swipe snaps one page in its direction, or back at a slow release.
    let inset = pager.view.collectionView.contentInset.left
    for (velocity, page) in [(CGFloat(3), CGFloat(2)), (-3, 0), (0, 1)] {
      var target = CGPoint.zero
      pager.view.scrollViewWillEndDragging(pager.view.collectionView, withVelocity: CGPoint(x: velocity, y: 0),
                                           targetContentOffset: &target)
      XCTAssertEqual(target.x + inset, page * 320)
    }
    pager.view.release()
    close(state)
  }

  @MainActor
  func testOrderChangesInsertionRemovalAndSessionReplacementKeepTheSelectedId() throws {
    let state = try session()
    try ["a", "b", "c", "d"].forEach { try admit(state, $0) }
    let other = try session()
    try admit(other, "a", width: 200, height: 400)
    drain()
    let pager = Pager(thumbnails: thumbnails)
    host(pager.view)
    pager.view.setPages(["a", "b", "c"], state: state)
    pager.view.show(1)
    pager.view.setPages(["b", "c", "a"], state: state)
    XCTAssertEqual(pager.view.current, 0)
    XCTAssertEqual(pager.offset, 0)
    pager.view.setPages(["c", "d", "b", "a"], state: state)
    XCTAssertEqual(pager.view.current, 2)
    XCTAssertEqual(pager.offset, 640)
    waitUntil("selected page") { pager.view.shownCell?.image.image != nil }
    // Removing the shown page leaves the next selection to the host.
    pager.view.setPages(["c", "d", "a"], state: state)
    XCTAssertEqual(pager.view.current, 2)
    XCTAssertEqual(pager.view.shownCell?.boundId, "a")
    XCTAssertEqual(pager.settled, [])

    // A replaced session releases every old binding, even for the same IDs.
    pager.view.setPages(["a"], state: other)
    XCTAssertEqual(pager.view.current, 0)
    waitUntil("replacement page") { size(pager.view.shownCell?.image.image) == CGSize(width: 200, height: 400) }
    for cell in pager.view.cells where cell.boundId != nil { XCTAssertEqual(cell.boundId, "a") }
    XCTAssertEqual(pager.errors, [])
    pager.view.release()
    close(state)
    close(other)
  }

  @MainActor
  func testFailedNeighbourLoadsAgainAndReportsOnlyOnceShown() throws {
    let state = try session()
    try admit(state, "a")
    let neighbour = try admit(state, "b")
    let broken = try admit(state, "c")
    drain()
    let bytes = try Data(contentsOf: neighbour)
    try Data([0, 1, 2]).write(to: neighbour)
    try Data([0, 1, 2]).write(to: broken)
    let pager = Pager(thumbnails: thumbnails)
    host(pager.view)
    pager.view.setPages(["a", "b", "c"], state: state)
    pager.view.show(0)
    waitUntil("first page") { pager.loaded.contains("a") }
    drain()
    // The neighbour failed out of view, silently.
    XCTAssertEqual(pager.errors, [])
    XCTAssertNil(pager.view.cell(at: 1)?.image.image)

    try bytes.write(to: neighbour)
    pager.view.show(1)
    waitUntil("neighbour retried") { pager.loaded.contains("b") }
    XCTAssertEqual(pager.errors, [])
    pager.view.show(2)
    waitUntil("shown failure") { !pager.errors.isEmpty }
    XCTAssertEqual(pager.errors, [.imageLoadFailed])
    pager.view.release()
    close(state)
  }

  @MainActor
  func testVisitingManyPagesKeepsImageMemoryBounded() throws {
    let state = try session()
    let ids = (0..<12).map { "p\($0)" }
    try ids.forEach { try admit(state, $0) }
    drain()
    let pager = Pager(thumbnails: thumbnails)
    host(pager.view)
    pager.view.setPages(ids, state: state)
    for (index, id) in ids.enumerated() {
      pager.view.show(index)
      waitUntil("page \(id)") { pager.loaded.contains(id) }
      let shown = try XCTUnwrap(pager.view.shownCell)
      shown.image.scrollView.setZoomScale(2, animated: false)
      waitUntil("full image \(id)") { shown.loader?.fullImage != nil }
      if index == 5 {
        // One page beyond each side is laid out and loaded.
        waitUntil("neighbours") { pager.view.cell(at: 4)?.image.image != nil && pager.view.cell(at: 6)?.image.image != nil }
      }
      let cells = pager.view.cells
      XCTAssertLessThanOrEqual(cells.count, 6)
      XCTAssertLessThanOrEqual(cells.filter { $0.loader?.fullImage != nil }.count, 1)
      XCTAssertLessThanOrEqual(cells.filter { $0.image.image != nil }.count, 3)
      for cell in cells where pager.view.collectionView.indexPath(for: cell) == nil {
        XCTAssertNil(cell.image.image)
        XCTAssertNil(cell.boundId)
      }
    }
    XCTAssertEqual(pager.errors, [])
    pager.view.release()
    for cell in pager.view.cells { XCTAssertNil(cell.image.image) }
    close(state)
  }

  @MainActor
  func testDirectionMirrorsPagesAndKeepsTheShownPage() throws {
    let state = try session()
    try ["a", "b", "c"].forEach { try admit(state, $0) }
    drain()
    let pager = Pager(thumbnails: thumbnails)
    host(pager.view)
    pager.view.setPages(["a", "b", "c"], state: state)
    pager.view.show(2)
    XCTAssertEqual(pager.offset, 640)
    pager.view.setDirection(.rtl)
    XCTAssertEqual(pager.view.collectionView.effectiveUserInterfaceLayoutDirection, .rightToLeft)
    XCTAssertEqual(pager.view.current, 2)
    XCTAssertEqual(pager.offset, 0)
    let first = try XCTUnwrap(pager.view.collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0)))
    XCTAssertEqual(first.frame.minX, 640)
    pager.swipe(to: 1)
    XCTAssertEqual(pager.settled, [1])
    pager.view.setDirection(.ltr)
    XCTAssertEqual(pager.view.current, 1)
    XCTAssertEqual(pager.offset, 320)
    pager.view.release()
    close(state)
  }

  // MARK: Hybrid

  @MainActor
  func testHybridSelectsByIdAcrossOrderChangesAndRejectsUnknownSelection() throws {
    let state = try session()
    try ["a", "b", "c"].forEach { try admit(state, $0) }
    let hybrid = HybridImagePagerView()
    var errors: [ScannerErrorCode] = []
    hybrid.onError = { errors.append($0.code) }
    hybrid.session = HybridScanSession(state: state)
    hybrid.pageIds = ["a", "b", "c"]
    hybrid.selectedPageId = "b"
    hybrid.afterUpdate()
    let pager = try XCTUnwrap(hybrid.view as? ImagePagerView)
    XCTAssertEqual(pager.current, 1)
    hybrid.pageIds = ["b", "c", "a"]
    hybrid.afterUpdate()
    XCTAssertEqual(pager.current, 0)
    XCTAssertEqual(state.snapshot().pageIds, ["b", "c", "a"])
    // Following the session does not reset its retained order.
    hybrid.pageIds = nil
    hybrid.afterUpdate()
    try state.setPageOrder(["c", "b", "a"])
    waitUntil("observed order") { pager.pageIds == ["c", "b", "a"] }
    XCTAssertEqual(pager.current, 1)
    hybrid.selectedPageId = "a"
    hybrid.afterUpdate()
    XCTAssertEqual(pager.current, 2)
    XCTAssertEqual(errors, [])
    hybrid.selectedPageId = "missing"
    hybrid.afterUpdate()
    XCTAssertEqual(errors, [.inputInvalid])
    XCTAssertEqual(pager.collectionView.numberOfItems(inSection: 0), 0)
    hybrid.onDropView()
    close(state)
  }

  @MainActor
  func testHybridPagesCanDisappearBeforeReactUpdatesItsProps() throws {
    let state = try session()
    try ["a", "b"].forEach { try admit(state, $0) }
    let hybrid = HybridImagePagerView()
    var errors: [ScannerErrorCode] = []
    var changes: [String] = []
    hybrid.onError = { errors.append($0.code) }
    hybrid.onPageChange = { changes.append($0) }
    hybrid.session = HybridScanSession(state: state)
    hybrid.pageIds = ["a", "b"]
    hybrid.selectedPageId = "a"
    hybrid.afterUpdate()
    let pager = try XCTUnwrap(hybrid.view as? ImagePagerView)
    pager.frame = CGRect(x: 0, y: 0, width: 320, height: 480)
    host(pager)
    // A settled swipe reports the page ID.
    rest(pager, on: 1)
    XCTAssertEqual(changes, ["b"])

    try state.removePage("a")
    hybrid.afterUpdate() // An unrelated React update still carries the deleted ID.
    XCTAssertEqual(pager.collectionView.numberOfItems(inSection: 0), 1)
    try state.removePage("b")
    hybrid.afterUpdate()
    XCTAssertEqual(pager.collectionView.numberOfItems(inSection: 0), 0)
    hybrid.pageIds = []
    hybrid.selectedPageId = ""
    hybrid.afterUpdate()
    XCTAssertEqual(errors, [])
    hybrid.pageIds = ["unknown"]
    hybrid.selectedPageId = "unknown"
    hybrid.afterUpdate()
    XCTAssertEqual(errors, [.inputInvalid])

    // Closing the session empties the pager without an error.
    hybrid.pageIds = nil
    hybrid.selectedPageId = ""
    try admit(state, "c")
    hybrid.afterUpdate()
    XCTAssertEqual(pager.pageIds, ["c"])
    close(state)
    waitUntil("closed") { pager.pageIds.isEmpty }
    XCTAssertEqual(errors, [.inputInvalid])

    // A session that is not native is invalid input.
    hybrid.session = HybridScanSession.placeholder()
    hybrid.afterUpdate()
    XCTAssertEqual(errors, [.inputInvalid, .inputInvalid])
    XCTAssertEqual(changes, ["b"])
    hybrid.onDropView()
  }
}
