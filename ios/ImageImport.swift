import CoreGraphics
import Foundation
import ImageIO

extension DocumentRenderOptions {
  /// Page defaults from the public correction and output props, shared by captures and imports.
  static func from(_ correction: ImageCorrectionOptions?, _ output: ImageOutputOptions?) -> DocumentRenderOptions {
    let paper: PaperSize? = output?.width == nil && output?.height == nil ? nil :
      PaperSize(width: output?.width ?? .nan, height: output?.height ?? .nan)
    return DocumentRenderOptions(removeShadows: correction?.removeShadows, jpegQuality: output?.jpegQuality,
      filter: correction?.filter, format: output?.format, paperSize: paper, dpi: output?.dpi,
      simpleDewarp: correction?.simpleDewarp)
  }
}

/// An imported original's upright pixel size: the smallest that still covers the export paper, never enlarged.
enum ImportSizePolicy {
  /// The renderer's own output limit, used when no paper size is configured.
  static let renderLimit = 4096

  /// Effective settings carry a dpi with their paper size.
  static func target(width: Int, height: Int, settings: RenderSettings) -> (width: Int, height: Int) {
    precondition(width > 0 && height > 0, "Image dimensions must be positive.")
    let long = Double(max(width, height)), short = Double(min(width, height))
    let scale: Double
    if let paperWidth = settings.paperWidth, let paperHeight = settings.paperHeight {
      let dpi = settings.dpi ?? 200
      scale = max(max(paperWidth, paperHeight) / 2.54 * dpi / long, min(paperWidth, paperHeight) / 2.54 * dpi / short)
    } else {
      scale = Double(renderLimit) / long
    }
    guard scale < 1 else { return (width, height) }
    // A tiny epsilon keeps exact products such as 1654.0000001 from rounding up a pixel.
    func scaled(_ side: Int) -> Int { min(side, max(1, Int((Double(side) * scale - 1e-6).rounded(.up)))) }
    return (scaled(width), scaled(height))
  }
}

/// Turns a picked image into a session original and admits it like a camera still: the same crop and render, without
/// a frame or live corners. The page follows the shared model's status.
enum ImageImport {
  /// Decodes the `file://` `url` on the session's image worker and admits it with `settings`; `completion` runs once,
  /// on that worker. Unreadable or undecodable images fail as `image/load-failed`; writing the session original fails
  /// as `storage/write-failed`.
  static func admit(_ session: ScanSessionState, url: URL, settings: RenderSettings, autoCrop: Bool,
                    completion: @escaping (Result<String, Error>) -> Void) {
    let id = UUID().uuidString
    let resources = session.resources
    let store = resources.registry.store
    resources.workOwner.queue.submit(resources.workOwner, kind: .capture, pageId: id,
      operation: { () throws -> URL in
        try store.publish(image: upright(url, settings: settings))
      },
      onSuccess: { source in
        // admitImport retires the source itself when admission fails.
        do {
          try session.admitImport(id: id, source: source, settings: settings, autoCrop: autoCrop)
          completion(.success(id))
        } catch { completion(.failure(error)) }
      },
      onFailure: { completion(.failure($0)) },
      onDiscard: { store.retire($0) { _ in } })
  }

  /// The image upright at its import size, opaque on white paper. ImageIO samples while decoding and applies EXIF.
  static func upright(_ url: URL, settings: RenderSettings) throws -> CGImage {
    guard url.isFileURL else { throw ScannerCodeError.inputInvalid }
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
          let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
          width > 0, height > 0 else { throw ScannerCodeError(code: .imageLoadFailed) }
    let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
    let transposed = (5...8).contains(orientation)
    let target = ImportSizePolicy.target(width: transposed ? height : width, height: transposed ? width : height,
                                         settings: settings)
    let decodeOptions: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceShouldCacheImmediately: true,
      kCGImageSourceThumbnailMaxPixelSize: max(target.width, target.height),
    ]
    guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, decodeOptions as CFDictionary),
          let context = CGContext(data: nil, width: target.width, height: target.height, bitsPerComponent: 8,
                                  bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
      throw ScannerCodeError(code: .imageLoadFailed)
    }
    let rect = CGRect(x: 0, y: 0, width: target.width, height: target.height)
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    context.fill(rect)
    context.interpolationQuality = .high
    context.draw(decoded, in: rect)
    guard let image = context.makeImage() else { throw ScannerCodeError(code: .imageLoadFailed) }
    return image
  }
}

extension ImageImport {
  /// Picks library images and admits them as one session-scoped `LibraryImport`: closing the session settles it,
  /// ends a picker that is still open, deletes picks still being staged and completes only once that picker has
  /// released its slot. Any thread; `completion` runs once. Only the picker is main-actor work: admission closures
  /// run on the session's image worker.
  static func fromLibrary(_ session: ScanSessionState, maxCount: Int, settings: RenderSettings, autoCrop: Bool,
                          completion: @escaping (Result<[String], Error>) -> Void) {
    LibraryImport(maxCount: maxCount, admit: { url, done in
      admit(session, url: url, settings: settings, autoCrop: autoCrop, completion: done)
    }, completion: completion).start(register: { try session.registerCloseCancellation($0) }) { onPicked in
      nonisolated(unsafe) let onPicked = onPicked
      return try MainActor.assumeIsolated { try LibraryPicker.pick(maxCount: maxCount) { onPicked($0) } }
    }
  }
}
