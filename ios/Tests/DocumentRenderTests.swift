import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import ReactNativeDocumentScanner

final class DocumentRenderTests: XCTestCase {
  private let corners: DocumentCorners = .init(topLeft: .init(x: 0.2, y: 0.2), topRight: .init(x: 0.8, y: 0.2),
                                         bottomRight: .init(x: 0.8, y: 0.8), bottomLeft: .init(x: 0.2, y: 0.8))

  private func folder() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    addTeardownBlock { try? FileManager.default.removeItem(at: url) }
    return url.resolvingSymlinksInPath()
  }

  /// A dark desk with a white page between 20% and 80% and dark "ink" bars.
  private func photo(width: Int = 480, height: Int = 360) throws -> CGImage {
    let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
      bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    context.setFillColor(CGColor(red: 0.2, green: 0.25, blue: 0.3, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let page = CGRect(x: Double(width) * 0.2, y: Double(height) * 0.2, width: Double(width) * 0.6, height: Double(height) * 0.6)
    context.setFillColor(CGColor(gray: 0.95, alpha: 1))
    context.fill(page)
    context.setFillColor(CGColor(red: 0.1, green: 0.1, blue: 0.5, alpha: 1))
    for line in 0..<6 {
      context.fill(CGRect(x: page.minX + 20, y: page.minY + 20 + Double(line) * 25, width: page.width - 40, height: 4))
    }
    return try XCTUnwrap(context.makeImage())
  }

  private func source(_ store: SourceStore) throws -> (URL, SourcePixelLease) {
    let url = try store.publish(image: photo())
    return (url, try store.acquire(url))
  }

  private func dimensions(_ url: URL) throws -> (Int, Int, String) {
    let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil))
    let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
    return (try XCTUnwrap(properties[kCGImagePropertyPixelWidth] as? Int),
            try XCTUnwrap(properties[kCGImagePropertyPixelHeight] as? Int),
            try XCTUnwrap(CGImageSourceGetType(source) as String?))
  }

  private func code(_ operation: () throws -> Any) -> ScannerErrorCode? {
    do { _ = try operation(); return nil }
    catch let error as ScannerCodeError { return error.code }
    catch { XCTFail("Unexpected \(error)"); return nil }
  }

  func testJpegRenderWritesPairedArtifactsAndKeepsOriginal() throws {
    let directory = try folder()
    let store = SourceStore(directory: directory.appendingPathComponent("sources"))
    defer { store.shutdown() }
    let (url, lease) = try source(store)
    let originalBytes = try Data(contentsOf: url)
    let originalPixels = lease.withUnsafeBytes { Data($0) }
    let result = try DocumentRenderService.render(lease, corners: corners, options: DocumentRenderOptions(
      removeShadows: nil, jpegQuality: 0.8, filter: .enhance, format: nil, paperSize: nil, dpi: nil, simpleDewarp: false),
      image: directory.appendingPathComponent("page.jpg"), thumbnail: directory.appendingPathComponent("thumb.jpg"))
    let (width, height, type) = try dimensions(result.image)
    XCTAssertEqual(type, UTType.jpeg.identifier)
    XCTAssertEqual([width, height], [result.width, result.height])
    XCTAssertEqual(width, 288, accuracy: 2)
    XCTAssertEqual(height, 216, accuracy: 2)
    XCTAssertFalse(result.curved)
    XCTAssertGreaterThanOrEqual(result.validContentPolygon.count, 4)
    let thumbnail = try dimensions(result.thumbnail)
    XCTAssertEqual(thumbnail.0, width)
    XCTAssertEqual(thumbnail.2, UTType.jpeg.identifier)
    XCTAssertEqual(try Data(contentsOf: url), originalBytes)
    XCTAssertEqual(lease.withUnsafeBytes { Data($0) }, originalPixels)
  }

  func testGrayPngIsFourBitAndThumbnailIsBounded() throws {
    let directory = try folder()
    let store = SourceStore(directory: directory.appendingPathComponent("sources"))
    defer { store.shutdown() }
    let url = try store.publish(image: photo(width: 2400, height: 1800))
    let lease = try store.acquire(url)
    let options = DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: .blackwhite, format: .auto,
                                        paperSize: nil, dpi: nil, simpleDewarp: nil)
    XCTAssertEqual(DocumentRenderService.outputFormat(options), .png)
    let result = try DocumentRenderService.render(lease, corners: corners, options: options,
      image: directory.appendingPathComponent("page.png"), thumbnail: directory.appendingPathComponent("thumb.jpg"))
    let png = try Data(contentsOf: result.image)
    XCTAssertEqual(Array(png.prefix(8)), [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    XCTAssertEqual(png[24], 4) // bit depth
    XCTAssertEqual(png[25], 0) // grayscale
    let thumbnail = try dimensions(result.thumbnail)
    XCTAssertEqual(max(thumbnail.0, thumbnail.1), DocumentRenderService.thumbnailLongSide)
  }

  func testOptionValidationAndDefaults() throws {
    let defaults = try DocumentRenderService.effectiveOptions(nil)
    XCTAssertEqual(defaults.removeShadows, true)
    XCTAssertEqual(defaults.jpegQuality, 0.92)
    XCTAssertEqual(defaults.filter, DocumentFilter.none)
    XCTAssertEqual(defaults.format, .jpeg)
    XCTAssertEqual(defaults.simpleDewarp, true)
    XCTAssertNil(defaults.dpi)
    let paper = try DocumentRenderService.effectiveOptions(DocumentRenderOptions(removeShadows: nil, jpegQuality: nil,
      filter: nil, format: nil, paperSize: PaperSize(width: 21, height: 29.7), dpi: nil, simpleDewarp: nil))
    XCTAssertEqual(paper.dpi, 200)
    let invalid: [DocumentRenderOptions] = [
      DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil, format: nil, paperSize: nil, dpi: 300, simpleDewarp: nil),
      DocumentRenderOptions(removeShadows: nil, jpegQuality: 1.5, filter: nil, format: nil, paperSize: nil, dpi: nil, simpleDewarp: nil),
      DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: .enhance, format: .png, paperSize: nil, dpi: nil, simpleDewarp: nil),
      DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil, format: nil,
                            paperSize: PaperSize(width: 100, height: 100), dpi: 600, simpleDewarp: nil),
      DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil, format: nil,
                            paperSize: PaperSize(width: 0.5, height: 10), dpi: nil, simpleDewarp: nil),
    ]
    for options in invalid { XCTAssertEqual(code { try DocumentRenderService.validate(options) }, .inputInvalid) }
    let crossed = DocumentCorners(topLeft: corners.topLeft, topRight: corners.bottomRight, bottomRight: corners.topRight, bottomLeft: corners.bottomLeft)
    XCTAssertEqual(code { try DocumentRenderService.validate(crossed) }, .inputInvalid)
  }

  func testPaperSizeScalesWithoutEnlarging() throws {
    let directory = try folder()
    let store = SourceStore(directory: directory.appendingPathComponent("sources"))
    defer { store.shutdown() }
    let (_, lease) = try source(store)
    let result = try DocumentRenderService.render(lease, corners: corners, options: DocumentRenderOptions(removeShadows: false,
      jpegQuality: nil, filter: nil, format: nil, paperSize: PaperSize(width: 21, height: 29.7), dpi: 50, simpleDewarp: false),
      image: directory.appendingPathComponent("page.jpg"), thumbnail: directory.appendingPathComponent("thumb.jpg"))
    // 29.7 cm at 50 dpi is 585 px, more than the page's 288 px in the photo.
    XCTAssertLessThanOrEqual(max(result.width, result.height), 290)
  }

  func testFailuresLeaveNoArtifacts() throws {
    let directory = try folder()
    let store = SourceStore(directory: directory.appendingPathComponent("sources"))
    defer { store.shutdown() }
    let (_, lease) = try source(store)
    let image = directory.appendingPathComponent("page.jpg")
    let missing = directory.appendingPathComponent("missing/thumb.jpg")
    XCTAssertEqual(code { try DocumentRenderService.render(lease, corners: self.corners, options: nil, image: image, thumbnail: missing) },
                   .renderFailed)
    XCTAssertFalse(FileManager.default.fileExists(atPath: image.path))
    let png = directory.appendingPathComponent("page.png")
    XCTAssertEqual(code { try DocumentRenderService.render(lease, corners: self.corners, options: nil, image: png,
      thumbnail: directory.appendingPathComponent("thumb.jpg")) }, .inputInvalid)
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["sources"])
  }

  private func pdfOptions(_ path: String, fit: ImageFit? = nil, dpi: Double? = nil) -> SessionPdfOptions {
    SessionPdfOptions(outputPath: path, imageFit: fit, autoRotateExif: nil, jpegQuality: nil, targetDpi: dpi,
                      pageWidth: nil, pageHeight: nil, pageAspectRatio: nil)
  }

  func testPdfEmbedsJpegAndGrayPngUnchangedAndReencodesTiff() throws {
    let directory = try folder()
    let store = SourceStore(directory: directory.appendingPathComponent("sources"))
    defer { store.shutdown() }
    let (_, lease) = try source(store)
    let jpeg = try DocumentRenderService.render(lease, corners: corners, options: nil,
      image: directory.appendingPathComponent("a.jpg"), thumbnail: directory.appendingPathComponent("a-thumb.jpg"))
    let png = try DocumentRenderService.render(lease, corners: corners, options: DocumentRenderOptions(removeShadows: nil,
      jpegQuality: nil, filter: .grayscale, format: .png, paperSize: nil, dpi: nil, simpleDewarp: nil),
      image: directory.appendingPathComponent("b.png"), thumbnail: directory.appendingPathComponent("b-thumb.jpg"))
    let tiff = directory.appendingPathComponent("c.tiff")
    let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(tiff as CFURL, UTType.tiff.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(destination, try photo(), [kCGImagePropertyOrientation: 6] as CFDictionary)
    XCTAssertTrue(CGImageDestinationFinalize(destination))
    // A TIFF original reopens through the source store like any other format.
    XCTAssertEqual(try store.acquire(tiff).width, 360)

    let output = directory.appendingPathComponent("out.pdf")
    XCTAssertEqual(try PdfService.write(pages: [jpeg.image, png.image, tiff], options: pdfOptions(output.path), to: output), output)
    let pdf = try Data(contentsOf: output)
    XCTAssertEqual(Array(pdf.prefix(5)), Array("%PDF-".utf8))
    XCTAssertNotNil(pdf.range(of: try Data(contentsOf: jpeg.image)), "JPEG is embedded byte for byte")
    XCTAssertNotNil(pdf.range(of: Data("/BitsPerComponent 4".utf8)))
    XCTAssertEqual(pdf.ranges(of: Data("/DCTDecode".utf8)).count, 2, "TIFF falls back to JPEG")
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path).filter { $0.hasSuffix(".tmp") }, [])
  }

  func testPdfFailureKeepsExistingOutput() throws {
    let directory = try folder()
    let output = directory.appendingPathComponent("out.pdf")
    try Data("previous".utf8).write(to: output)
    let missing = directory.appendingPathComponent("missing.jpg")
    XCTAssertEqual(code { try PdfService.write(pages: [missing], options: self.pdfOptions(output.path), to: output) },
                   .pdfWriteFailed)
    XCTAssertEqual(try Data(contentsOf: output), Data("previous".utf8))
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["out.pdf"])
    XCTAssertEqual(code { try PdfService.write(pages: [missing], options: self.pdfOptions(output.path, dpi: 10), to: output) },
                   .inputInvalid)
    XCTAssertEqual(code { try PdfService.write(pages: [], options: self.pdfOptions(output.path), to: output) }, .inputInvalid)
    let nowhere = directory.appendingPathComponent("absent/out.pdf")
    XCTAssertEqual(code { try PdfService.write(pages: [missing], options: self.pdfOptions(nowhere.path), to: nowhere) },
                   .inputInvalid)
    XCTAssertEqual(try PdfService.localURL("file:///tmp/a/../b.pdf").path, "/tmp/b.pdf")
    XCTAssertEqual(code { try PdfService.localURL("relative.pdf") }, .inputInvalid)
    XCTAssertEqual(code { try PdfService.localURL("https://example.com/a.pdf") }, .inputInvalid)
  }
}
