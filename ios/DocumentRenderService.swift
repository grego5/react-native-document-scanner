import Foundation

/// A public failure code raised by native services; sessions transport it unchanged.
struct ScannerCodeError: Error, Equatable {
  let code: ScannerErrorCode
  static let inputInvalid = ScannerCodeError(code: .inputInvalid)

  /// Bridge code 1 rejects the input; anything else is the operation's own failure.
  static func bridged(_ error: Error, failure: ScannerErrorCode) -> ScannerCodeError {
    if let error = error as? ScannerCodeError { return error }
    let native = error as NSError
    return ScannerCodeError(code: native.domain == ScannerRenderErrorDomain && native.code == 1 ? .inputInvalid : failure)
  }

  /// The public code of a native failure; untyped errors become the operation's fallback.
  static func code(_ error: Error, fallback: ScannerErrorCode) -> ScannerErrorCode {
    switch error {
    case let error as ScannerCodeError: return error.code
    case let error as SourceStoreError: return error.code
    case let error as CornerInferenceError:
      switch error {
      case .modelUnavailable, .modelLoadFailed: return .modelLoadFailed
      case .disposed: return .processorDisposed
      case .inferenceFailed, .staleSelection: return fallback
      }
    default: return fallback
    }
  }

  static func failure(_ error: Error, fallback: ScannerErrorCode) -> ScannerFailure {
    ScannerFailure(code: code(error, fallback: fallback))
  }
}

/// Private staged render artifacts. The session owner decides publication and lifetime.
struct RenderArtifacts: Sendable {
  let image: URL
  let thumbnail: URL
  let width: Int
  let height: Int
  let validContentPolygon: [DocumentPoint]
  let curved: Bool
}

/// Synchronous engine entry for the session's native image worker: rectifies the
/// upright original so the quad's corners land exactly on the output corners.
enum DocumentRenderService {
  static let thumbnailLongSide = 640
  private static let defaultQuality = 92
  private static let defaultDpi = 200.0
  private static let maxOutputDimension = 4096.0

  struct Config: Equatable {
    let removeShadows: Bool
    /// Percent, as Android's encoders consume it.
    let quality: Int
    /// The page's longer side in pixels from paper size and dpi, or nil for the page's own size.
    let paperLongEdge: Double?
    let filter: DocumentFilter
    /// Resolved: never `auto`.
    let format: DocumentImageFormat
    let followPageEdges: Bool
  }

  static func outputFormat(_ options: DocumentRenderOptions?) -> DocumentImageFormat {
    switch options?.format {
    case .some(.auto): return options?.filter == .some(.blackwhite) ? .png : .jpeg
    case .some(let format): return format
    case nil: return .jpeg
    }
  }

  /// One public range for capture, rendering and PDF.
  static func jpegQualityPercent(_ value: Double) throws -> Int {
    guard value.isFinite, (0...1).contains(value) else { throw ScannerCodeError.inputInvalid }
    return Int((value * 100).rounded())
  }

  static func validate(_ options: DocumentRenderOptions?) throws -> Config {
    let paper = options?.paperSize
    guard paper != nil || options?.dpi == nil else { throw ScannerCodeError.inputInvalid }
    var paperLongEdge: Double?
    if let paper {
      guard [paper.width, paper.height].allSatisfy({ $0.isFinite && (1...200).contains($0) }) else {
        throw ScannerCodeError.inputInvalid
      }
      let dpi = options?.dpi ?? defaultDpi
      guard dpi.isFinite, (50...600).contains(dpi) else { throw ScannerCodeError.inputInvalid }
      let pixels = max(paper.width, paper.height) / 2.54 * dpi
      guard pixels <= maxOutputDimension else { throw ScannerCodeError.inputInvalid }
      paperLongEdge = pixels
    }
    let format = outputFormat(options)
    let quality = format == .png && options?.format != .some(.auto)
      ? defaultQuality : try options?.jpegQuality.map(jpegQualityPercent) ?? defaultQuality
    let filter = options?.filter ?? DocumentFilter.none
    guard format != .png || filter == .grayscale || filter == .blackwhite else { throw ScannerCodeError.inputInvalid }
    return Config(removeShadows: options?.removeShadows ?? true, quality: quality, paperLongEdge: paperLongEdge,
                  filter: filter, format: format, followPageEdges: options?.followPageEdges ?? true)
  }

  /// Validated at command admission, so omitted defaults compare equal to explicit defaults.
  static func effectiveOptions(_ options: DocumentRenderOptions?) throws -> DocumentRenderOptions {
    let config = try validate(options)
    return DocumentRenderOptions(removeShadows: config.removeShadows, jpegQuality: Double(config.quality) / 100,
      filter: config.filter, format: options?.format ?? .jpeg, paperSize: options?.paperSize,
      dpi: options?.paperSize.map { _ in options?.dpi ?? defaultDpi }, followPageEdges: config.followPageEdges)
  }

  static func coordinates(_ quad: DocumentQuad) -> [NSNumber] {
    [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft].flatMap { [NSNumber(value: $0.x), NSNumber(value: $0.y)] }
  }

  static func validate(_ quad: DocumentQuad) throws {
    do { try ScannerDocumentRenderer.validateQuad(coordinates(quad)) }
    catch { throw ScannerCodeError.bridged(error, failure: .inputInvalid) }
  }

  static func fileExtension(_ format: DocumentImageFormat) -> String { format == .png ? "png" : "jpg" }

  /// Writes `image` and its JPEG `thumbnail` from the leased original; both are
  /// removed on failure. The original's bytes are only borrowed.
  static func render(_ source: SourcePixelLease, quad: DocumentQuad, options: DocumentRenderOptions?,
                     image: URL, thumbnail: URL) throws -> RenderArtifacts {
    let config = try validate(options)
    guard image.isFileURL, thumbnail.isFileURL, image != thumbnail,
          image.pathExtension == fileExtension(config.format), thumbnail.pathExtension == "jpg" else {
      throw ScannerCodeError.inputInvalid
    }
    do {
      let page = try source.withUnsafeBytes { bytes in
        try ScannerDocumentRenderer.renderOwner(source, bytes: bytes.baseAddress!, byteCount: UInt(bytes.count),
          width: UInt32(source.width), height: UInt32(source.height), rowStride: UInt(source.rowStride),
          quad: coordinates(quad), paperLongEdge: config.paperLongEdge ?? -1,
          followPageEdges: config.followPageEdges, filter: filterIndex(config.filter),
          removeShadows: config.removeShadows, grayPng: config.format == .png,
          jpegQuality: Double(config.quality) / 100, image: image, thumbnail: thumbnail,
          thumbnailLongSide: thumbnailLongSide)
      }
      let values = page.validContentPolygon.map(\.doubleValue)
      return RenderArtifacts(image: image, thumbnail: thumbnail, width: page.width, height: page.height,
        validContentPolygon: stride(from: 0, to: values.count - 1, by: 2).map { DocumentPoint(x: values[$0], y: values[$0 + 1]) },
        curved: page.curved)
    } catch {
      try? FileManager.default.removeItem(at: image)
      try? FileManager.default.removeItem(at: thumbnail)
      throw ScannerCodeError.bridged(error, failure: .renderFailed)
    }
  }

  static func filterIndex(_ filter: DocumentFilter) -> Int {
    switch filter {
    case .none: return 0
    case .enhance: return 1
    case .grayscale: return 2
    case .blackwhite: return 3
    @unknown default: return 0
    }
  }
}
