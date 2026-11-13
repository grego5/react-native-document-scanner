import Foundation

/// Transactional PDF output over the shared serializer. Callers supply already
/// pinned page files; destination ownership policy belongs to the session.
enum PdfService {
  /// An absolute path or file:// URI, standardized.
  static func localURL(_ value: String) throws -> URL {
    guard !value.isEmpty else { throw ScannerCodeError.inputInvalid }
    let url: URL
    if value.hasPrefix("/") {
      url = URL(fileURLWithPath: value)
    } else if let parsed = URL(string: value), parsed.scheme?.lowercased() == "file",
              parsed.host == nil || parsed.host == "" || parsed.host == "localhost",
              parsed.query == nil, parsed.fragment == nil, parsed.path.hasPrefix("/") {
      url = parsed
    } else { throw ScannerCodeError.inputInvalid }
    return url.standardizedFileURL
  }

  /// Streams into a private sibling, then renames over `output`. A failed write
  /// removes only that sibling, so an existing file at `output` survives.
  static func write(pages: [URL], options: SessionPdfOptions, to output: URL) throws -> URL {
    guard !pages.isEmpty else { throw ScannerCodeError.inputInvalid }
    let parent = output.deletingLastPathComponent()
    var isDirectory: ObjCBool = false
    guard FileManager.default.fileExists(atPath: parent.path, isDirectory: &isDirectory), isDirectory.boolValue,
          FileManager.default.isWritableFile(atPath: parent.path) else { throw ScannerCodeError.inputInvalid }
    if FileManager.default.fileExists(atPath: output.path, isDirectory: &isDirectory), isDirectory.boolValue {
      throw ScannerCodeError.inputInvalid
    }
    let temporary = parent.appendingPathComponent(".\(output.lastPathComponent).\(UUID().uuidString).tmp")
    defer { try? FileManager.default.removeItem(at: temporary) }
    do {
      try ScannerPdfWriter.writePages(pages, to: temporary,
        pageWidth: options.pageWidth.map(NSNumber.init(value:)),
        pageHeight: options.pageHeight.map(NSNumber.init(value:)),
        pageAspectRatio: options.pageAspectRatio.map(NSNumber.init(value:)),
        targetDpi: options.targetDpi.map(NSNumber.init(value:)),
        jpegQuality: options.jpegQuality.map(NSNumber.init(value:)),
        fit: fitIndex(options.imageFit), autoRotateExif: options.autoRotateExif ?? true)
    } catch { throw ScannerCodeError.bridged(error, failure: .pdfWriteFailed) }
    // Same-directory POSIX rename atomically replaces the destination.
    guard rename(temporary.path, output.path) == 0 else { throw ScannerCodeError(code: .pdfWriteFailed) }
    return output
  }

  private static func fitIndex(_ fit: ImageFit?) -> Int {
    switch fit {
    case .some(.fill): return 1
    case .some(.contain): return 2
    case .some(.cover): return 3
    default: return 0
    }
  }
}
