import AVFoundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import ReactNativeDocumentScanner

/// Engine actions that record the live quad and hold analysis, so a page keeps its retained frame
/// as its thumbnail while a test inspects it.
private final class HeldActions: ScanSessionActions, @unchecked Sendable {
  let gate = DispatchSemaphore(value: 0)
  private let lock = NSLock()
  private var quads: [QuadCoordinates?] = []
  var liveQuads: [QuadCoordinates?] { lock.lock(); defer { lock.unlock() }; return quads }

  func prepareAnalysis(_ source: SessionResources.Lease, liveQuad: QuadCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue {
    lock.lock(); quads.append(liveQuad); lock.unlock()
    return { [gate] in
      gate.wait()
      throw ScannerCodeError(code: .analysisFailed)
    }
  }

  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact {
    throw ScannerCodeError(code: .renderFailed)
  }

  func close() {}
}

/// A still as AVFoundation would deliver it: encoded pixels in sensor axes with an EXIF
/// orientation tag, capture metadata and, optionally, an embedded thumbnail.
func encodedStill(width: Int, height: Int, orientation: Int = 1, type: UTType = .jpeg, quality: Double = 0.95,
                  thumbnail: Bool = false) throws -> Data {
  let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
  context.setFillColor(CGColor(red: 0.2, green: 0.3, blue: 0.4, alpha: 1))
  context.fill(CGRect(x: 0, y: 0, width: width, height: height))
  // A red block in the encoded top-left corner shows where orientation puts it.
  context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
  context.fill(CGRect(x: 0, y: height - height / 4, width: width / 4, height: height / 4))
  for x in stride(from: 0, to: width, by: 7) {
    context.setFillColor(CGColor(gray: Double(x % 255) / 255, alpha: 1))
    context.fill(CGRect(x: x, y: 0, width: 3, height: height / 2))
  }
  let image = try XCTUnwrap(context.makeImage())
  let data = NSMutableData()
  let destination = try XCTUnwrap(CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil))
  var properties: [CFString: Any] = [
    kCGImagePropertyOrientation: orientation,
    kCGImagePropertyExifDictionary: [kCGImagePropertyExifLensModel: "Test Lens",
                                     kCGImagePropertyExifISOSpeedRatings: [100]] as [CFString: Any],
    kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Test",
                                     kCGImagePropertyTIFFOrientation: orientation] as [CFString: Any],
  ]
  if type == .jpeg { properties[kCGImageDestinationLossyCompressionQuality] = quality }
  if thumbnail { properties[kCGImageDestinationEmbedThumbnail] = true }
  CGImageDestinationAddImage(destination, image, properties as CFDictionary)
  XCTAssertTrue(CGImageDestinationFinalize(destination))
  return data as Data
}

/// An embedded EXIF thumbnail is a second JPEG inside the file: another start-of-image marker.
private func hasEmbeddedThumbnail(_ data: Data) -> Bool {
  data.dropFirst(2).range(of: Data([0xFF, 0xD8, 0xFF])) != nil
}

final class StillCaptureTests: XCTestCase {
  private var root: URL!
  private var store: SourceStore!
  private var registry: ScanSessionRegistry!

  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    store = SourceStore(directory: root.appendingPathComponent("sources"))
    registry = ScanSessionRegistry(root: root.appendingPathComponent("sessions"), store: store,
                                   queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.capture")),
                                   report: { _ in })
  }

  override func tearDown() {
    store.shutdown(); store.drain()
    try? FileManager.default.removeItem(at: root)
  }

  // MARK: - Settings

  func testQualityFollowsTheModeUnlessExplicit() {
    XCTAssertEqual(StillSettings.resolve(mode: .quality, jpegQuality: nil, zeroShutterLag: true),
                   StillSettings(priority: .quality, quality: 1))
    XCTAssertEqual(StillSettings.resolve(mode: .latency, jpegQuality: nil, zeroShutterLag: true),
                   StillSettings(priority: .speed, quality: 0.95))
    XCTAssertEqual(StillSettings.resolve(mode: nil, jpegQuality: nil, zeroShutterLag: true),
                   StillSettings(priority: .balanced, quality: 0.95), "zsl is the default")
    XCTAssertEqual(StillSettings.resolve(mode: .zsl, jpegQuality: nil, zeroShutterLag: false),
                   StillSettings(priority: .speed, quality: 0.95), "zsl without a buffer uses latency")
    XCTAssertEqual(StillSettings.resolve(mode: .quality, jpegQuality: 0.5, zeroShutterLag: false).quality, 0.5)
    XCTAssertEqual(StillSettings.resolve(mode: .latency, jpegQuality: 3, zeroShutterLag: false).quality, 1)
    XCTAssertEqual(StillSettings.resolve(mode: .latency, jpegQuality: 0, zeroShutterLag: false).quality, 0.01)
    XCTAssertEqual(StillSettings.resolve(mode: .quality, jpegQuality: -1, zeroShutterLag: false).quality, 1, "omitted")
    XCTAssertEqual(StillSettings.resolve(mode: .latency, jpegQuality: .nan, zeroShutterLag: false).quality, 0.95)
  }

  func testJpegIsPreferredAndUncompressedBGRAIsTheFallback() {
    XCTAssertEqual(StillFormat.choose(codecs: [.hevc, .jpeg], pixelFormats: [kCVPixelFormatType_32BGRA]), .jpeg)
    XCTAssertEqual(StillFormat.choose(codecs: [.hevc], pixelFormats: [kCVPixelFormatType_32BGRA]), .bgra)
    XCTAssertNil(StillFormat.choose(codecs: [.hevc], pixelFormats: []))
  }

  func testPhotoSettingsCarryTheCeilingQualityAndPriority() throws {
    let still = StillSettings(priority: .quality, quality: 0.7)
    let jpeg = AVCameraSession.photoSettings(still, format: .jpeg, dimensions: PixelSize(4032, 3024), maxPriority: .balanced)
    XCTAssertEqual(jpeg.maxPhotoDimensions.width, 4032)
    XCTAssertEqual(jpeg.maxPhotoDimensions.height, 3024)
    XCTAssertEqual(jpeg.photoQualityPrioritization, .balanced, "never above the output's maximum")
    let compression = try XCTUnwrap(jpeg.format?[AVVideoCompressionPropertiesKey] as? [String: Any])
    XCTAssertEqual(compression[AVVideoQualityKey] as? Double, 0.7)
    let bgra = AVCameraSession.photoSettings(StillSettings(priority: .speed, quality: 1), format: .bgra,
                                             dimensions: PixelSize(1920, 1440), maxPriority: .quality)
    XCTAssertEqual(bgra.format?[kCVPixelBufferPixelFormatTypeKey as String] as? OSType, kCVPixelFormatType_32BGRA)
    XCTAssertEqual(bgra.photoQualityPrioritization, .speed)
  }

  // MARK: - Geometry

  func testSquareCropIsIntegralAndCentered() {
    XCTAssertEqual(StillGeometry.squareCrop(PixelSize(3024, 4032)), CGRect(x: 0, y: 504, width: 3024, height: 3024))
    XCTAssertEqual(StillGeometry.squareCrop(PixelSize(641, 480)), CGRect(x: 80, y: 0, width: 480, height: 480))
  }

  func testLiveQuadMapsOntoTheFramedStill() throws {
    let quad: QuadCoordinates = [0.1, 0.2, 0.9, 0.2, 0.9, 0.8, 0.1, 0.8]
    // Same aspect, portrait: normalized corners carry over.
    let portrait = try XCTUnwrap(CameraFraming(buffer: PixelSize(1920, 1440), rotationAngle: 90, square: false))
    let same = try XCTUnwrap(StillGeometry.map(quad, from: portrait, toStill: PixelSize(3024, 4032), square: false))
    for (a, b) in zip(same, quad) { XCTAssertEqual(a, b, accuracy: 1e-9) }
    // Square on both sides: the squares coincide.
    let square = try XCTUnwrap(CameraFraming(buffer: PixelSize(1920, 1440), rotationAngle: 90, square: true))
    let squared = try XCTUnwrap(StillGeometry.map(quad, from: square, toStill: PixelSize(3024, 4032), square: true))
    for (a, b) in zip(squared, quad) { XCTAssertEqual(a, b, accuracy: 1e-9) }
    // A square-framed guide on a full still lands in the still's centered band.
    let banded = try XCTUnwrap(StillGeometry.map([0, 0, 1, 0, 1, 1, 0, 1], from: square, toStill: PixelSize(3024, 4032),
                                                 square: false))
    XCTAssertEqual(banded[1], 0.125, accuracy: 1e-9)
    XCTAssertEqual(banded[5], 0.875, accuracy: 1e-9)
    // A 16:9 stream is a crop of the 4:3 still across its short side.
    let wide = try XCTUnwrap(CameraFraming(buffer: PixelSize(1920, 1080), rotationAngle: 0, square: false))
    let narrowed = try XCTUnwrap(StillGeometry.map([0, 0, 1, 0, 1, 1, 0, 1], from: wide, toStill: PixelSize(4032, 3024),
                                                   square: false))
    XCTAssertEqual(narrowed[0], 0, accuracy: 1e-9)
    XCTAssertEqual(narrowed[1], 0.125, accuracy: 1e-9)
    XCTAssertEqual(narrowed[5], 0.875, accuracy: 1e-9)
    // A full-frame guide outside a square still does not fit.
    XCTAssertNil(StillGeometry.map([0, 0, 1, 0, 1, 1, 0, 1], from: portrait, toStill: PixelSize(3024, 4032), square: true))
    XCTAssertNil(StillGeometry.map([0, 0, 1], from: portrait, toStill: PixelSize(3024, 4032), square: false))
  }

  // MARK: - Processing

  func testPublishedStillIsUprightWithCorrectedMetadataAndNoStaleThumbnail() throws {
    let data = try encodedStill(width: 640, height: 480, orientation: 6, thumbnail: true)
    XCTAssertTrue(hasEmbeddedThumbnail(data), "the fixture carries a thumbnail")
    let still = try StillProcessor.publish(data, square: false, quality: 0.9, store: store)
    XCTAssertEqual(still.upright, PixelSize(480, 640))
    let source = try XCTUnwrap(CGImageSourceCreateWithURL(still.url as CFURL, nil))
    XCTAssertEqual(CGImageSourceGetType(source) as String?, UTType.jpeg.identifier)
    let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
    XCTAssertEqual(properties[kCGImagePropertyPixelWidth] as? Int, 480)
    XCTAssertEqual(properties[kCGImagePropertyPixelHeight] as? Int, 640)
    XCTAssertEqual(properties[kCGImagePropertyOrientation] as? Int, 1)
    let exif = try XCTUnwrap(properties[kCGImagePropertyExifDictionary] as? [CFString: Any])
    XCTAssertEqual(exif[kCGImagePropertyExifPixelXDimension] as? Int, 480)
    XCTAssertEqual(exif[kCGImagePropertyExifPixelYDimension] as? Int, 640)
    XCTAssertEqual(exif[kCGImagePropertyExifLensModel] as? String, "Test Lens", "capture metadata is kept")
    XCTAssertFalse(hasEmbeddedThumbnail(try Data(contentsOf: still.url)),
                   "no thumbnail of the uncropped, unrotated pixels survives")
    // Orientation 6 turns the encoded top-left corner to the upright top-right.
    let pixels = try store.acquire(still.url)
    pixels.withUnsafeBytes { bytes in
      let topRight = (10 * pixels.width + pixels.width - 10) * 4
      XCTAssertGreaterThan(bytes[topRight], 200)
      XCTAssertLessThan(bytes[topRight + 1], 60)
    }
  }

  func testSquareStillIsCroppedBeforePublication() throws {
    let data = try encodedStill(width: 640, height: 480, orientation: 6)
    let still = try StillProcessor.publish(data, square: true, quality: 0.9, store: store)
    let header = try SourceImageLoader.inspect(still.url)
    XCTAssertEqual(PixelSize(header.width, header.height), PixelSize(480, 480))
    XCTAssertEqual(still.upright, PixelSize(480, 640), "the upright size before the crop maps the live quad")
  }

  func testRequestedQualityIsPreservedThroughPublication() throws {
    let data = try encodedStill(width: 640, height: 480, quality: 1)
    let low = try StillProcessor.publish(data, square: false, quality: 0.2, store: store)
    let high = try StillProcessor.publish(data, square: false, quality: 1, store: store)
    func bytes(_ url: URL) throws -> Int { try XCTUnwrap(url.resourceValues(forKeys: [.fileSizeKey]).fileSize) }
    XCTAssertLessThan(try bytes(low.url), try bytes(high.url))
  }

  func testUncompressedTiffStillPublishesAsJpeg() throws {
    let data = try encodedStill(width: 320, height: 240, orientation: 3, type: .tiff)
    let still = try StillProcessor.publish(data, square: false, quality: 0.9, store: store)
    let header = try SourceImageLoader.inspect(still.url)
    XCTAssertEqual(PixelSize(header.width, header.height), PixelSize(320, 240))
    XCTAssertEqual(header.orientation, 1)
    let source = try XCTUnwrap(CGImageSourceCreateWithURL(still.url as CFURL, nil))
    XCTAssertEqual(CGImageSourceGetType(source) as String?, UTType.jpeg.identifier)
  }

  func testDeliveredStillsMayBeSmallerButNeverBreakEitherCeiling() throws {
    XCTAssertNoThrow(try StillProcessor.publish(try encodedStill(width: 160, height: 120), square: false, quality: 0.9,
                                                store: store), "smaller than requested")
    for (width, height) in [(4097, 16), (3600, 3600)] {
      XCTAssertThrowsError(try StillProcessor.publish(try encodedStill(width: width, height: height), square: false,
                                                      quality: 0.9, store: store), "\(width)x\(height)") {
        XCTAssertEqual(($0 as? ScannerCodeError)?.code, .captureFailed)
      }
    }
    XCTAssertThrowsError(try StillProcessor.publish(Data([0xFF, 0xD8, 0x00]), square: false, quality: 0.9, store: store)) {
      XCTAssertEqual(($0 as? ScannerCodeError)?.code, .captureFailed)
    }
    XCTAssertEqual(try sourceFiles(), 1, "only the accepted still was published")
  }

  private func sourceFiles(_ name: String = "sources") throws -> Int {
    let directory = root.appendingPathComponent(name)
    guard FileManager.default.fileExists(atPath: directory.path) else { return 0 }
    return try FileManager.default.contentsOfDirectory(atPath: directory.path).count
  }

  private func liveFrame(width: Int, height: Int, orientation: UInt8) -> LiveFrame {
    let bgra: [UInt8] = [200, 100, 50, 255]
    let pixels = Data(Array(repeating: bgra, count: width * height).joined())
    return LiveFrame(pixels: pixels, width: width, height: height, orientation: orientation, sensorNs: 1)
  }

  func testShutterFrameIsWrittenUprightAndStraightened() throws {
    let state = try registry.createState(actions: { _ in HeldActions() })
    let whole = try StillProcessor.writeFrame(liveFrame(width: 64, height: 48, orientation: 6), quad: nil,
                                              to: state.resources.output(.frame))
    let header = try SourceImageLoader.inspect(whole.url)
    XCTAssertEqual(PixelSize(header.width, header.height), PixelSize(48, 64))
    let page = try StillProcessor.writeFrame(liveFrame(width: 64, height: 48, orientation: 6),
                                             quad: [0.25, 0.25, 0.75, 0.25, 0.75, 0.75, 0.25, 0.75],
                                             to: state.resources.output(.frame))
    let straightened = try SourceImageLoader.inspect(page.url)
    XCTAssertEqual(Double(straightened.width), 24, accuracy: 2)
    XCTAssertEqual(Double(straightened.height), 32, accuracy: 2)
  }

  // MARK: - Settlement

  func testTicketSettlesOnceAndAnInvalidatedTicketNeverPublishes() {
    var results: [Result<String, ScannerCodeError>] = []
    let ticket = CaptureTicket { results.append($0) }
    XCTAssertTrue(ticket.reject(.processorSuperseded))
    XCTAssertFalse(ticket.reject(.captureFailed))
    var ran = false
    XCTAssertFalse(ticket.publish { ran = true; return "page" })
    XCTAssertFalse(ran)
    XCTAssertEqual(results.count, 1)
    let failing = CaptureTicket { results.append($0) }
    XCTAssertTrue(failing.publish { throw SourceStoreError.writeFailed })
    XCTAssertEqual(results.last, .failure(ScannerCodeError(code: .storageWriteFailed)))
    let admitted = CaptureTicket { results.append($0) }
    XCTAssertTrue(admitted.publish { "page" })
    XCTAssertFalse(admitted.reject(.processorDisposed), "an admitted page is never reported as failed")
    XCTAssertEqual(results.last, .success("page"))
  }

  private func shot(_ state: ScanSessionState, ticket: CaptureTicket, square: Bool = false,
                    guide: (quad: QuadCoordinates, framing: CameraFraming)? = nil) -> StillShot {
    StillShot(ticket: ticket, session: state, options: DocumentRenderOptions(removeShadows: nil, jpegQuality: nil,
      filter: nil, format: nil, paperSize: nil, dpi: nil, followPageEdges: nil), selection: nil,
      settings: StillSettings(priority: .speed, quality: 0.9), square: square, guide: guide)
  }

  private func save(_ shot: StillShot, _ data: Data) {
    let done = expectation(description: "saved")
    shot.save(data) { done.fulfill() }
    wait(for: [done], timeout: 20)
  }

  func testSavedShotAdmitsOnePageWithItsFrameAndLiveQuad() throws {
    var actions: HeldActions!
    let state = try registry.createState(actions: { _ in actions = HeldActions(); return actions })
    defer { actions.gate.signal() }
    var results: [Result<String, ScannerCodeError>] = []
    let framing = try XCTUnwrap(CameraFraming(buffer: PixelSize(64, 48), rotationAngle: 90, square: false))
    let quad: QuadCoordinates = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8]
    let shot = shot(state, ticket: CaptureTicket { results.append($0) }, guide: (quad, framing))
    shot.frame.fill(liveFrame(width: 64, height: 48, orientation: 6))
    save(shot, try encodedStill(width: 640, height: 480, orientation: 6))
    XCTAssertEqual(results, [.success(shot.id)])
    XCTAssertNotNil(state.snapshot().pages[shot.id])
    XCTAssertEqual(actions.liveQuads.count, 1)
    for (a, b) in zip(try XCTUnwrap(actions.liveQuads.first ?? nil), quad) { XCTAssertEqual(a, b, accuracy: 1e-9) }
    let thumbnail = try XCTUnwrap(state.thumbnailSource(shot.id), "the shutter frame is the page's thumbnail")
    let header = try SourceImageLoader.inspect(thumbnail.url)
    XCTAssertLessThan(header.width, 48, "straightened to the guide")
  }

  func testAFrameRotatedAfterAcceptanceIsNotStraightenedToTheFrozenGuide() throws {
    var actions: HeldActions!
    let state = try registry.createState(actions: { _ in actions = HeldActions(); return actions })
    defer { actions.gate.signal() }
    var results: [Result<String, ScannerCodeError>] = []
    let framing = try XCTUnwrap(CameraFraming(buffer: PixelSize(64, 48), rotationAngle: 90, square: false))
    let quad: QuadCoordinates = [0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8]
    let shot = shot(state, ticket: CaptureTicket { results.append($0) }, guide: (quad, framing))
    // The device turned half way after acceptance: same upright size, opposite orientation.
    let rotated = try XCTUnwrap(CameraFraming(buffer: PixelSize(64, 48), rotationAngle: 270, square: false))
    XCTAssertEqual(rotated.framed, framing.framed)
    XCTAssertNotEqual(rotated.orientation, framing.orientation)
    shot.frame.fill(liveFrame(width: 64, height: 48, orientation: rotated.orientation))
    save(shot, try encodedStill(width: 640, height: 480, orientation: 6))
    XCTAssertEqual(results, [.success(shot.id)])
    let thumbnail = try XCTUnwrap(state.thumbnailSource(shot.id), "the frame is still retained")
    let header = try SourceImageLoader.inspect(thumbnail.url)
    XCTAssertEqual(PixelSize(header.width, header.height), PixelSize(48, 64), "upright, not straightened")
  }

  func testInvalidatedShotWritesNothingAndRetiresAnUnadmittedSource() throws {
    let state = try registry.createState(actions: { _ in HeldActions() })
    var results: [Result<String, ScannerCodeError>] = []
    let early = shot(state, ticket: CaptureTicket { results.append($0) })
    early.ticket.reject(.processorSuperseded)
    save(early, try encodedStill(width: 320, height: 240))
    XCTAssertEqual(try sourceFiles(), 0, "an invalidated shot writes nothing")

    // Invalidated while the still is being written: published, never admitted, then retired.
    var late: StillShot!
    let racing = SourceStore(directory: root.appendingPathComponent("racing-sources"), writeJPEG: { pixels, url, metadata, quality in
      late.ticket.reject(.processorDisposed)
      try SourceImageLoader.writeJPEG(pixels, to: url, metadata: metadata, quality: quality)
    })
    let racingRegistry = ScanSessionRegistry(root: root.appendingPathComponent("racing"), store: racing,
                                             queue: ScannerWorkQueue(worker: DispatchQueue(label: "test.racing")),
                                             report: { _ in })
    let racingState = try racingRegistry.createState(actions: { _ in HeldActions() })
    late = shot(racingState, ticket: CaptureTicket { results.append($0) })
    save(late, try encodedStill(width: 320, height: 240))
    racing.drain()
    XCTAssertEqual(results.map { try? $0.get() }, [nil, nil])
    XCTAssertTrue(racingState.snapshot().pages.isEmpty)
    let deadline = Date().addingTimeInterval(10)
    while try sourceFiles("racing-sources") > 0 && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
    XCTAssertEqual(try sourceFiles("racing-sources"), 0, "the published source was retired")
    racing.shutdown(); racing.drain()
  }

  func testClosedSessionRejectsTheShotWithoutLeakingFiles() throws {
    let state = try registry.createState(actions: { _ in HeldActions() })
    let closed = expectation(description: "closed")
    state.close().whenComplete { _ in closed.fulfill() }
    wait(for: [closed], timeout: 10)
    var results: [Result<String, ScannerCodeError>] = []
    save(shot(state, ticket: CaptureTicket { results.append($0) }), try encodedStill(width: 320, height: 240))
    XCTAssertEqual(results, [.failure(ScannerCodeError(code: .processorDisposed))])
    XCTAssertEqual(try sourceFiles(), 0)
  }
}
