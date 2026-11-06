import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import ReactNativeDocumentScanner

func picture(width: Int, height: Int) -> CGImage {
  let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                          space: CGColorSpace(name: CGColorSpace.sRGB)!,
                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
  context.setFillColor(CGColor(red: 0.2, green: 0.25, blue: 0.3, alpha: 1))
  context.fill(CGRect(x: 0, y: 0, width: width, height: height))
  context.setFillColor(CGColor(gray: 0.95, alpha: 1))
  context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height / 2))
  return context.makeImage()!
}

func write(_ image: CGImage, to url: URL, type: UTType, orientation: Int = 1) throws {
  guard let destination = CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, 1, nil) else {
    throw ScannerCodeError(code: .storageWriteFailed)
  }
  CGImageDestinationAddImage(destination, image, [kCGImagePropertyOrientation: orientation] as CFDictionary)
  guard CGImageDestinationFinalize(destination) else { throw ScannerCodeError(code: .storageWriteFailed) }
}

/// Renders real JPEG/PNG pages (300 x 200) with JPEG thumbnail sidecars (150 x 100), so
/// viewer and pager reads decode production-shaped files. The first `failures` renders
/// fail. Shared by the image view tests.
final class PictureActions: ScanSessionActions, @unchecked Sendable {
  let resources: SessionResources
  private let lock = NSLock()
  private var failures: Int

  init(resources: SessionResources, failures: Int) { self.resources = resources; self.failures = failures }

  func prepareAnalysis(_ source: SessionResources.Lease, liveCorners: CornerCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue {
    { AnalysisValue(width: 480, height: 360, duration: 1, candidate: [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8], score: 0.9) }
  }

  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact {
    lock.lock()
    let fail = failures > 0
    if fail { failures -= 1 }
    lock.unlock()
    if fail { throw ScannerCodeError(code: .renderFailed) }
    let png = key.settings.format == .png
    let destination = try resources.renderDestination(extension: png ? "png" : "jpg")
    defer { destination.close() }
    try write(picture(width: 300, height: 200), to: destination.image.url, type: png ? .png : .jpeg)
    try write(picture(width: 150, height: 100), to: destination.thumbnail.url, type: .jpeg)
    return ScanPage.Artifact(image: try destination.image.commit(), thumbnail: try destination.thumbnail.commit(),
                             width: 300, height: 200, validContentPolygon: [])
  }

  func close() {}
}

/// Records what a viewer reports.
@MainActor
private final class Viewer {
  let view = ImageViewerView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
  var loaded: [String] = []
  var errors: [ScannerErrorCode] = []
  var zooms: [Bool] = []
  private(set) var loader: ImageViewerLoader!

  init(thumbnails: PageThumbnails) {
    loader = ImageViewerLoader(image: view, thumbnails: thumbnails, onLoaded: { [unowned self] in loaded.append($0) },
                               onFailed: { [unowned self] in errors.append($0.code) },
                               onZoomChanged: { [unowned self] in zooms.append($0) })
  }
}

private final class URLRecorder: @unchecked Sendable {
  private let lock = NSLock()
  private var values: [URL] = []
  func append(_ url: URL) { lock.lock(); values.append(url); lock.unlock() }
  var urls: [URL] { lock.lock(); defer { lock.unlock() }; return values }
}

final class ImageViewerTests: XCTestCase {
  private var root: URL!
  private var store: SourceStore!
  private var registry: ScanSessionRegistry!
  private var thumbnails: PageThumbnails!

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    store = SourceStore(directory: root.appendingPathComponent("sources"))
    registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                   queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.viewer-images")))
    thumbnails = PageThumbnails()
  }

  override func tearDown() {
    store.shutdown(); store.drain()
    try? FileManager.default.removeItem(at: root)
  }

  // MARK: Helpers

  private func session(failures: Int = 0,
                       dispatcher: @escaping (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) })
    throws -> ScanSessionState {
    try registry.createState(dispatcher: dispatcher, actions: { PictureActions(resources: $0, failures: failures) })
  }

  @discardableResult
  private func admit(_ state: ScanSessionState, _ id: String, width: Int = 480, height: Int = 360,
                     format: DocumentImageFormat? = nil, filter: DocumentFilter? = nil) throws -> URL {
    let source = try store.publish(image: picture(width: width, height: height))
    let options = DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: filter, format: format,
                                        paperSize: nil, dpi: nil, simpleDewarp: nil)
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
  private func host(_ view: UIView) -> UIWindow {
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
    window.isHidden = false
    window.addSubview(view)
    view.layoutIfNeeded()
    // A visible window outlives the test; later tests must not see its views.
    addTeardownBlock { @MainActor in
      view.removeFromSuperview()
      window.isHidden = true
    }
    return window
  }

  private func close(_ state: ScanSessionState) {
    let done = expectation(description: "closed")
    state.close().whenComplete { _ in done.fulfill() }
    wait(for: [done], timeout: 20)
  }

  private func size(_ image: CGImage?) -> CGSize? { image.map { CGSize(width: $0.width, height: $0.height) } }

  // MARK: View

  @MainActor
  func testZoomClampsCentersDoubleTapsAndReportsChanges() {
    let view = ImageViewerView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
    var scales: [CGFloat] = []
    view.onZoom = { scales.append($0) }
    view.fadeIn(picture(width: 400, height: 200))
    view.layoutIfNeeded()
    let scroll = view.scrollView
    XCTAssertEqual(scroll.contentSize, CGSize(width: 320, height: 160))
    XCTAssertEqual(scroll.contentInset, UIEdgeInsets(top: 160, left: 0, bottom: 160, right: 0))
    XCTAssertFalse(scroll.bounces)
    XCTAssertFalse(scroll.bouncesZoom)
    XCTAssertFalse(view.isZoomed)

    scroll.setZoomScale(10, animated: false)
    XCTAssertEqual(view.zoomScale, ImageViewerView.maxZoom)
    XCTAssertEqual(scroll.contentSize, CGSize(width: 1600, height: 800))
    XCTAssertEqual(scroll.contentInset, .zero)
    scroll.setZoomScale(0.2, animated: false)
    XCTAssertEqual(view.zoomScale, 1)
    XCTAssertEqual(scales, [5, 1])

    // The image point under the tap stays in place.
    let tap = CGPoint(x: 100, y: 240)
    let imagePoint = view.convert(tap, to: view.imageView)
    view.toggleZoom(at: tap, animated: false)
    XCTAssertEqual(view.zoomScale, ImageViewerView.doubleTapZoom, accuracy: 0.001)
    XCTAssertTrue(view.isZoomed)
    XCTAssertEqual(view.imageView.convert(imagePoint, to: view).x, tap.x, accuracy: 0.5)
    view.toggleZoom(at: tap, animated: false)
    XCTAssertEqual(view.zoomScale, 1)

    scroll.setZoomScale(3, animated: false)
    view.resetZoom()
    XCTAssertEqual(view.zoomScale, 1)
    XCTAssertEqual(scales.last, 1)
    // Fitting a new image does not pass through a reported zoom change.
    let count = scales.count
    view.frame = CGRect(x: 0, y: 0, width: 300, height: 300)
    view.layoutIfNeeded()
    XCTAssertEqual(scales.count, count)
    XCTAssertEqual(scroll.contentSize, CGSize(width: 300, height: 150))
  }

  func testDecoderAppliesOrientationBoundsAndFormats() throws {
    for (type, ext) in [(UTType.jpeg, "jpg"), (.tiff, "tiff"), (.png, "png")] {
      let url = root.appendingPathComponent("rotated.\(ext)")
      let rotated = type != .png
      try write(picture(width: 40, height: 20), to: url, type: type, orientation: rotated ? 6 : 1)
      let image = try DisplayImageDecoder.decode(url, longSide: 640)
      XCTAssertEqual(size(image), rotated ? CGSize(width: 20, height: 40) : CGSize(width: 40, height: 20), ext)
    }
    let wide = root.appendingPathComponent("wide.png")
    try write(picture(width: 5000, height: 10), to: wide, type: .png)
    XCTAssertEqual(try DisplayImageDecoder.decode(wide, longSide: DisplayImageDecoder.fullLongSide).width, 4096)
    let broken = root.appendingPathComponent("broken.jpg")
    try Data([0, 1, 2]).write(to: broken)
    XCTAssertThrowsError(try DisplayImageDecoder.decode(broken, longSide: 640)) {
      XCTAssertEqual(($0 as? ScannerCodeError)?.code, .imageLoadFailed)
    }
  }

  // MARK: Loader

  @MainActor
  func testThumbnailFirstFullImagePastPreloadAndRelease() throws {
    let state = try session()
    try admit(state, "a")
    drain()
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    viewer.loader.bind(state, "a")
    waitUntil("thumbnail") { viewer.loaded == ["a"] }
    // The render's thumbnail sidecar, not its full image.
    let thumbnail = try XCTUnwrap(viewer.view.image)
    XCTAssertEqual(size(thumbnail), CGSize(width: 150, height: 100))

    viewer.view.scrollView.setZoomScale(1.05, animated: false)
    drain()
    XCTAssertNil(viewer.loader.fullImage)
    viewer.view.scrollView.setZoomScale(2, animated: false)
    waitUntil("full image") { viewer.loader.fullImage != nil }
    XCTAssertEqual(size(viewer.view.image), CGSize(width: 300, height: 200))
    XCTAssertEqual(viewer.view.zoomScale, 2)
    XCTAssertEqual(viewer.zooms, [true])

    NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    XCTAssertNil(viewer.loader.fullImage)
    XCTAssertTrue(viewer.view.image === thumbnail)
    XCTAssertEqual(viewer.view.zoomScale, 2)
    NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
    waitUntil("restored full image") { viewer.loader.fullImage != nil }

    viewer.view.resetZoom()
    XCTAssertNil(viewer.loader.fullImage)
    XCTAssertTrue(viewer.view.image === thumbnail)
    XCTAssertEqual(viewer.zooms, [true, false])

    // Detaching drops every image; attaching again shows the shared thumbnail.
    viewer.view.scrollView.setZoomScale(2, animated: false)
    waitUntil("full image again") { viewer.loader.fullImage != nil }
    viewer.view.removeFromSuperview()
    XCTAssertNil(viewer.view.image)
    XCTAssertNil(viewer.loader.fullImage)
    XCTAssertNil(viewer.loader.thumbnail)
    XCTAssertEqual(viewer.view.zoomScale, 1)
    window.addSubview(viewer.view)
    waitUntil("reattached thumbnail") { viewer.loaded == ["a", "a"] }
    // The background event emptied this cache, so this is a fresh thumbnail decode.
    XCTAssertEqual(size(viewer.view.image), size(thumbnail))
    XCTAssertTrue(viewer.errors.isEmpty)
    viewer.loader.clear()
    close(state)
  }

  @MainActor
  func testOriginalThenPngRenderKeepsPreviousImageUntilReplacementInstalls() throws {
    let lock = NSLock()
    var deliveries: [() -> Void] = []
    func deliver() {
      while true {
        lock.lock()
        let next = deliveries.isEmpty ? nil : deliveries.removeFirst()
        lock.unlock()
        guard let next else { return }
        next()
      }
    }
    let state = try session(failures: 1, dispatcher: { delivery in lock.lock(); deliveries.append(delivery); lock.unlock() })
    // PNG renders are gray only.
    try admit(state, "a", format: .png, filter: .grayscale)
    drain(); deliver()
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    viewer.loader.bind(state, "a")
    waitUntil("original thumbnail") { viewer.loaded == ["a"] }
    let original = try XCTUnwrap(viewer.view.image)
    XCTAssertEqual(size(original), CGSize(width: 480, height: 360))

    try state.setFilter("a", filter: .blackwhite)
    drain()
    let gate = block()
    deliver()
    // The render's thumbnail read waits behind the gate; the original stays shown.
    pump()
    XCTAssertTrue(viewer.view.image === original)
    XCTAssertEqual(viewer.loaded, ["a"])
    gate.signal()
    waitUntil("render thumbnail") { viewer.loaded == ["a", "a"] }
    XCTAssertEqual(size(viewer.view.image), CGSize(width: 150, height: 100))
    viewer.view.scrollView.setZoomScale(2, animated: false)
    waitUntil("png render") { viewer.loader.fullImage != nil }
    XCTAssertEqual(size(viewer.view.image), CGSize(width: 300, height: 200))
    XCTAssertTrue(viewer.errors.isEmpty)
    viewer.loader.clear()
    close(state)
    deliver()
    _ = window
  }

  @MainActor
  func testObsoleteLoadsAreDiscardedAcrossSessionReplacement() throws {
    let first = try session(failures: .max)
    let second = try session(failures: .max)
    try admit(first, "a", width: 480, height: 360)
    try admit(second, "a", width: 200, height: 400)
    drain()
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    let gate = block()
    viewer.loader.bind(first, "a")
    viewer.loader.bind(second, "a")
    gate.signal()
    waitUntil("replacement") { !viewer.loaded.isEmpty }
    drain()
    XCTAssertEqual(viewer.loaded, ["a"])
    XCTAssertEqual(size(viewer.view.image), CGSize(width: 200, height: 400))

    close(first)
    pump()
    XCTAssertNotNil(viewer.view.image)
    try second.removePage("a")
    waitUntil("removal clears") { viewer.view.image == nil }
    XCTAssertTrue(viewer.errors.isEmpty)

    viewer.loader.bind(second, "missing")
    XCTAssertEqual(viewer.errors, [.inputInvalid])
    viewer.loader.clear()
    close(second)
    _ = window
  }

  @MainActor
  func testSessionClosedWhileDetachedClearsWithoutErrorOnReattach() throws {
    let state = try session(failures: .max)
    try admit(state, "a")
    drain()
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    viewer.loader.bind(state, "a")
    waitUntil("thumbnail") { viewer.loaded == ["a"] }
    viewer.view.removeFromSuperview()
    close(state)
    window.addSubview(viewer.view)
    pump()
    XCTAssertNil(viewer.view.image)
    XCTAssertTrue(viewer.errors.isEmpty)

    // A binding that never resolved its page still reports the closed session.
    let unresolved = Viewer(thumbnails: thumbnails)
    let other = host(unresolved.view)
    unresolved.loader.bind(state, "a")
    XCTAssertEqual(unresolved.errors, [.inputInvalid])
    viewer.loader.clear()
    unresolved.loader.clear()
    _ = other
  }

  @MainActor
  func testUnchangedPageUpdatesKeepTheShownImages() throws {
    let state = try session(failures: .max)
    try admit(state, "a")
    drain()
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    viewer.loader.bind(state, "a")
    waitUntil("thumbnail") { viewer.loaded == ["a"] }
    viewer.view.scrollView.setZoomScale(2, animated: false)
    waitUntil("full image") { viewer.loader.fullImage != nil }
    let full = try XCTUnwrap(viewer.view.image)

    // An edit whose render fails publishes the page twice, still with its original.
    try state.setFilter("a", filter: .grayscale)
    drain()
    XCTAssertEqual(viewer.loaded, ["a"])
    XCTAssertTrue(viewer.view.image === full)
    XCTAssertNotNil(viewer.loader.thumbnail)
    XCTAssertTrue(viewer.errors.isEmpty)
    viewer.loader.clear()
    close(state)
    _ = window
  }

  @MainActor
  func testThumbnailCacheEmptiesInBackgroundWithoutViewers() {
    let other = PageThumbnails()
    thumbnails.put("a", picture(width: 8, height: 6))
    other.put("a", picture(width: 8, height: 6))
    XCTAssertNotNil(thumbnails.cached("a"))
    NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    XCTAssertNil(thumbnails.cached("a"))
    XCTAssertNil(other.cached("a"))
  }

  @MainActor
  func testFailedThumbnailRetriesWhenVisibleAgain() throws {
    let state = try session(failures: .max)
    let original = try admit(state, "a")
    drain()
    let bytes = try Data(contentsOf: original)
    try Data([0, 1, 2]).write(to: original)
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    viewer.loader.bind(state, "a")
    waitUntil("failure") { !viewer.errors.isEmpty }
    XCTAssertEqual(viewer.errors, [.imageLoadFailed])
    XCTAssertNil(viewer.view.image)

    try bytes.write(to: original)
    NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
    waitUntil("retry") { viewer.loaded == ["a"] }
    XCTAssertEqual(viewer.errors, [.imageLoadFailed])
    viewer.loader.clear()
    close(state)
    _ = window
  }

  @MainActor
  func testDetachedReadsAndRemovalReleasePhysicalPinsWithoutErrors() throws {
    let state = try session(failures: .max)
    let original = try admit(state, "a")
    drain()
    let viewer = Viewer(thumbnails: thumbnails)
    let window = host(viewer.view)
    viewer.loader.bind(state, "a")
    waitUntil("thumbnail") { viewer.loaded == ["a"] }

    // A full read admitted before detach keeps its own pin until it runs.
    let gate = block()
    viewer.view.scrollView.setZoomScale(2, animated: false)
    viewer.view.removeFromSuperview()
    try state.removePage("a")
    pump()
    XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
    gate.signal()
    waitUntil("original deleted") { !FileManager.default.fileExists(atPath: original.path) }
    XCTAssertNil(viewer.view.image)
    XCTAssertNil(viewer.loader.fullImage)

    // Session close clears an attached viewer without an error.
    try admit(state, "b")
    drain()
    window.addSubview(viewer.view)
    viewer.loader.bind(state, "b")
    waitUntil("second page") { viewer.loaded == ["a", "b"] }
    close(state)
    waitUntil("close clears") { viewer.view.image == nil }
    XCTAssertTrue(viewer.errors.isEmpty)
    viewer.loader.clear()
  }

  // MARK: Tiles

  @MainActor
  func testTilesReadOnlyThumbnailsAtTheirPixelSize() throws {
    let state = try session(failures: 1)
    let original = try admit(state, "a")
    drain()
    let recorder = URLRecorder()
    let thumbnails = thumbnails!
    let tile = PageThumbnailView(decode: { source, side in
      recorder.append(source.url)
      return try thumbnails.tile(source, longSide: side)
    })
    tile.frame = CGRect(x: 0, y: 0, width: 40, height: 40)
    let window = host(tile)
    tile.bind(state, "a", mode: .contain)
    drain()
    // No render and no retained frame: the tile never reads the original.
    XCTAssertNil(tile.image)
    XCTAssertTrue(recorder.urls.isEmpty)

    try state.setFilter("a", filter: .enhance)
    waitUntil("tile image") { tile.image != nil }
    let image = try XCTUnwrap(tile.image)
    let side = Int(40 * window.screen.scale)
    XCTAssertLessThanOrEqual(max(image.width, image.height), side)
    XCTAssertFalse(recorder.urls.contains(original))
    XCTAssertTrue(recorder.urls.allSatisfy { $0.pathExtension == "jpg" && $0 != original })

    try state.removePage("a")
    waitUntil("tile cleared") { tile.image == nil }
    tile.dispose()
    close(state)
  }

  // MARK: Hybrid boundary

  @MainActor
  func testHybridViewsBindNativeSessionsAndRejectOthers() throws {
    let hybrid = HybridImageViewerView()
    var failures: [ScannerErrorCode] = [], loads: [String] = [], zooms: [Bool] = []
    hybrid.onError = { failures.append($0.code) }
    hybrid.onLoad = { loads.append($0) }
    hybrid.onZoomChange = { zooms.append($0) }
    hybrid.afterUpdate()
    XCTAssertEqual(failures, [.inputInvalid])
    XCTAssertFalse(try hybrid.isZoomed())

    let state = try session()
    try admit(state, "a")
    drain()
    hybrid.session = HybridScanSession(state: state)
    hybrid.pageId = "a"
    hybrid.view.frame = CGRect(x: 0, y: 0, width: 320, height: 480)
    let window = host(hybrid.view)
    hybrid.afterUpdate()
    waitUntil("hybrid load") { loads == ["a"] }
    (hybrid.view as? ImageViewerView)?.scrollView.setZoomScale(2, animated: false)
    XCTAssertTrue(try hybrid.isZoomed())
    try hybrid.resetZoom()
    waitUntil("reset") { zooms == [true, false] }
    XCTAssertFalse(try hybrid.isZoomed())

    let tile = HybridPageThumbnailView()
    tile.afterUpdate()
    hybrid.onDropView()
    tile.onDropView()
    close(state)
    _ = window
  }
}
