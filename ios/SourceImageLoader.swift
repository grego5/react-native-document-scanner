import Foundation
import NitroModules
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// Internal errors remain codes at the module boundary; no display copy is native.
enum SourceStoreError: Error, Equatable {
  case invalidInput, loadFailed, writeFailed, memoryLimit, disposed

  var code: ScannerErrorCode {
    switch self {
    case .invalidInput: return .inputInvalid
    case .loadFailed: return .imageLoadFailed
    case .writeFailed: return .storageWriteFailed
    case .memoryLimit: return .processorUnavailable
    case .disposed: return .processorDisposed
    }
  }

  func scannerFailure() -> ScannerFailure { ScannerFailure(code: code) }

}

struct SourceImageHeader {
  let width: Int
  let height: Int
  let orientation: Int
  let properties: [CFString: Any]
  var uprightWidth: Int { orientation >= 5 ? height : width }
  var uprightHeight: Int { orientation >= 5 ? width : height }
  var rgbaBytes: Int { width * height * 4 }
}

/// Independently locked accounting includes resident, evicted/leased and staged pixels.
/// Tickets follow storage, not dictionary membership, including C++/CGImage owners.
final class SourceMemoryBudget: @unchecked Sendable {
  private let lock = NSLock()
  let limit: Int
  private var used = 0
  init(limit: Int) { self.limit = limit }
  var liveBytes: Int { lock.lock(); defer { lock.unlock() }; return used }

  func reserve(_ bytes: Int) throws -> SourceMemoryTicket {
    lock.lock(); defer { lock.unlock() }
    guard bytes > 0, bytes <= limit - used else { throw SourceStoreError.memoryLimit }
    used += bytes
    return SourceMemoryTicket(budget: self, bytes: bytes)
  }

  fileprivate func release(_ bytes: Int) {
    lock.lock(); defer { lock.unlock() }
    used -= bytes
  }
}

final class SourceMemoryTicket {
  private let budget: SourceMemoryBudget
  let bytes: Int
  init(budget: SourceMemoryBudget, bytes: Int) { self.budget = budget; self.bytes = bytes }
  deinit { budget.release(bytes) }
}

/// Immutable, tightly packed, opaque sRGB RGBA8. The ticket lives as long as bytes.
final class SourcePixels: @unchecked Sendable {
  let width: Int
  let height: Int
  var rowStride: Int { width * 4 }
  var byteCount: Int { rowStride * height }
  fileprivate let bytes: UnsafeMutableRawPointer
  private let ticket: SourceMemoryTicket

  init(image: CGImage, orientation: Int, crop: CGRect?, maxDimension: Int? = nil, ticket: SourceMemoryTicket) throws {
    try SourceImageLoader.validateSize(image.width, image.height)
    guard (1...8).contains(orientation) else { throw SourceStoreError.invalidInput }
    var transform = CGAffineTransform.identity
    guard ScannerSourceTransform(UInt32(image.width), UInt32(image.height), UInt8(orientation), &transform) else {
      throw SourceStoreError.invalidInput
    }
    let full = CGRect(x: 0, y: 0, width: orientation >= 5 ? image.height : image.width,
                      height: orientation >= 5 ? image.width : image.height)
    let region = crop ?? full
    guard !region.isNull, !region.isEmpty, region.origin.x.isFinite, region.origin.y.isFinite,
          region.width.isFinite, region.height.isFinite, region == region.integral,
          full.contains(region) else { throw SourceStoreError.invalidInput }
    let scale: CGFloat
    if let maximum = maxDimension {
      scale = Swift.min(1, CGFloat(maximum) / Swift.max(region.width, region.height))
    } else { scale = 1 }
    width = max(1, Int(floor(region.width * scale)))
    height = max(1, Int(floor(region.height * scale)))
    guard width * height * 4 <= ticket.bytes else { throw SourceStoreError.memoryLimit }
    self.ticket = ticket
    guard let allocation = calloc(width * height, 4) else { throw SourceStoreError.memoryLimit }
    bytes = allocation
    guard let context = CGContext(data: allocation, width: width, height: height, bitsPerComponent: 8,
                                  bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                    | CGBitmapInfo.byteOrder32Big.rawValue) else {
      throw SourceStoreError.loadFailed
    }
    context.setFillColor(CGColor(gray: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.scaleBy(x: Double(width) / region.width, y: Double(height) / region.height)
    // Shared geometry uses top-left image coordinates. Quartz's drawing space
    // is bottom-left: F(upright) * sharedTransform * F(encoded).
    let encodedHeight = CGFloat(image.height)
    let quartz = CGAffineTransform(a: transform.a, b: -transform.b, c: -transform.c, d: transform.d,
      tx: transform.c * encodedHeight + transform.tx,
      ty: full.height - transform.d * encodedHeight - transform.ty)
    context.translateBy(x: -region.minX, y: -(full.height - region.maxY))
    context.concatenate(quartz)
    context.interpolationQuality = .none
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
  }

  deinit { free(bytes) }

  func withUnsafeBytes<T>(_ operation: (UnsafeRawBufferPointer) throws -> T) rethrows -> T {
    try withExtendedLifetime(self) {
      try operation(UnsafeRawBufferPointer(start: bytes, count: byteCount))
    }
  }

  /// The provider retains this allocation/ticket if a native image consumer retains its CGImage.
  func makeImage(owner: AnyObject? = nil) throws -> CGImage {
    let token = Unmanaged.passRetained(owner ?? self).toOpaque()
    guard let provider = CGDataProvider(dataInfo: token, data: bytes, size: byteCount, releaseData: {
      info, _, _ in if let info { Unmanaged<AnyObject>.fromOpaque(info).release() }
    }) else {
      Unmanaged<AnyObject>.fromOpaque(token).release()
      throw SourceStoreError.loadFailed
    }
    guard let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                              bytesPerRow: rowStride, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue
                                | CGBitmapInfo.byteOrder32Big.rawValue),
                              provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
      throw SourceStoreError.loadFailed
    }
    return image
  }
}

enum SourceImageLoader {
  static func inspect(_ url: URL) throws -> SourceImageHeader {
    guard url.isFileURL, let source = CGImageSourceCreateWithURL(url as CFURL,
      [kCGImageSourceShouldCache: false] as CFDictionary),
      let type = CGImageSourceGetType(source) as String?,
      [UTType.jpeg.identifier, UTType.png.identifier, UTType.tiff.identifier].contains(type),
      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
      let width = properties[kCGImagePropertyPixelWidth] as? Int,
      let height = properties[kCGImagePropertyPixelHeight] as? Int else { throw SourceStoreError.loadFailed }
    try validateSize(width, height)
    let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
    guard (1...8).contains(orientation), (properties[kCGImagePropertyDepth] as? Int ?? 8) <= 16 else {
      throw SourceStoreError.invalidInput
    }
    return SourceImageHeader(width: width, height: height, orientation: orientation, properties: properties)
  }

  static func validateSize(_ width: Int, _ height: Int) throws {
    guard width > 0, height > 0, width <= 16384, height <= 16384,
          width * height <= 32_000_000 else { throw SourceStoreError.invalidInput }
  }

  static func decode(_ url: URL, header: SourceImageHeader, maxDimension: Int?,
                     ticket: SourceMemoryTicket) throws -> SourcePixels {
    guard let source = CGImageSourceCreateWithURL(url as CFURL,
      [kCGImageSourceShouldCache: false] as CFDictionary) else { throw SourceStoreError.loadFailed }
    let image: CGImage?
    if let maxDimension {
      guard maxDimension > 0 else { throw SourceStoreError.invalidInput }
      image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceThumbnailMaxPixelSize: maxDimension,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceShouldAllowFloat: false
      ] as CFDictionary)
    } else {
      image = CGImageSourceCreateImageAtIndex(source, 0, [
        kCGImageSourceShouldCache: false, kCGImageSourceShouldAllowFloat: false
      ] as CFDictionary)
    }
    guard let image, image.bitsPerPixel <= 64 else { throw SourceStoreError.loadFailed }
    return try SourcePixels(image: image, orientation: maxDimension == nil ? header.orientation : 1,
                            crop: nil, ticket: ticket)
  }

  static func writeJPEG(_ pixels: SourcePixels, to url: URL, metadata: [CFString: Any], quality: Double) throws {
    guard quality.isFinite, (0...1).contains(quality),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
      throw SourceStoreError.writeFailed
    }
    var properties = metadata
    properties[kCGImagePropertyOrientation] = 1
    properties[kCGImagePropertyPixelWidth] = pixels.width
    properties[kCGImagePropertyPixelHeight] = pixels.height
    var exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
    exif[kCGImagePropertyExifPixelXDimension] = pixels.width
    exif[kCGImagePropertyExifPixelYDimension] = pixels.height
    properties[kCGImagePropertyExifDictionary] = exif
    var tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
    tiff[kCGImagePropertyTIFFOrientation] = 1
    properties[kCGImagePropertyTIFFDictionary] = tiff
    properties[kCGImageDestinationLossyCompressionQuality] = quality
    CGImageDestinationAddImage(destination, try pixels.makeImage(), properties as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { throw SourceStoreError.writeFailed }
  }
}
