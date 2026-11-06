import Foundation
import CoreGraphics
import ImageIO
import UIKit

/// An upright display decode handed from a read to the main thread; immutable.
struct DisplayImage: @unchecked Sendable {
  let image: CGImage
}

/// Bounded upright display decodes of session files. JPEG, PNG and TIFF only; EXIF
/// orientation is applied. Any thread.
enum DisplayImageDecoder {
  /// A full-resolution rendered page, so zooming in stays sharp.
  static let fullLongSide = 4096

  static func decode(_ url: URL, longSide: Int) throws -> CGImage {
    guard longSide > 0 else { throw ScannerCodeError(code: .imageLoadFailed) }
    do { _ = try SourceImageLoader.inspect(url) } catch { throw ScannerCodeError(code: .imageLoadFailed) }
    guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: longSide,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceShouldAllowFloat: false
          ] as CFDictionary) else { throw ScannerCodeError(code: .imageLoadFailed) }
    return image
  }
}

/// Thumbnail-size copies of page images, shared across views and found by the source's
/// cache key, so a render's viewer and tiles share one decode. Thread-safe. Cached
/// images are immutable, so views still showing an evicted one keep it. Each cache
/// empties itself in the background, viewers or not, where a smaller process is less
/// likely to be killed; thumbnails come back from their small sidecar files.
final class PageThumbnails: @unchecked Sendable {
  static let shared = PageThumbnails()

  /// No thumbnail is larger; matches the render thumbnail sidecar.
  static let longSide = DocumentRenderService.thumbnailLongSide

  private final class Entry {
    let image: CGImage
    init(_ image: CGImage) { self.image = image }
  }

  private let memory = NSCache<NSString, Entry>()
  private var background: NSObjectProtocol?

  /// About ten 640 x 480 thumbnails.
  init(costLimit: Int = 12 * 1024 * 1024) {
    memory.totalCostLimit = costLimit
    background = NotificationCenter.default.addObserver(
      forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil
    ) { [weak self] _ in self?.memory.removeAllObjects() }
  }

  deinit { background.map(NotificationCenter.default.removeObserver) }

  func cached(_ key: String) -> CGImage? { memory.object(forKey: key as NSString)?.image }

  func put(_ key: String, _ image: CGImage) {
    memory.setObject(Entry(image), forKey: key as NSString, cost: image.bytesPerRow * image.height)
  }

  /// The source's thumbnail: cached, from its pinned sidecar, or decoded small from the
  /// page itself. Worker threads, while the source is pinned.
  func load(_ source: SessionPageSource) throws -> CGImage {
    if let image = cached(source.cacheKey) { return image }
    let image = try DisplayImageDecoder.decode(source.thumbnailURL ?? source.url, longSide: Self.longSide)
    put(source.cacheKey, image)
    return image
  }

  /// Tile-only path over a thumbnail source (never an original or full image): the
  /// shared thumbnail when it is no larger than the tile, else a never-published decode
  /// at the tile's pixels.
  func tile(_ source: SessionPageSource, longSide: Int) throws -> CGImage {
    if let image = cached(source.cacheKey), max(image.width, image.height) <= longSide { return image }
    return try DisplayImageDecoder.decode(source.url, longSide: min(longSide, Self.longSide))
  }
}

/// A main-confined counter; a read is current while its generation is.
final class LoadGeneration: @unchecked Sendable {
  private let lock = NSLock()
  private var value = 0

  @discardableResult
  func advance() -> Int { lock.lock(); defer { lock.unlock() }; value += 1; return value }

  func isCurrent(_ generation: Int) -> Bool { lock.lock(); defer { lock.unlock() }; return value == generation }
}

/// Whether an admitted read began; a read rejected before it began still owns its pin.
private final class ReadStart: @unchecked Sendable {
  private let lock = NSLock()
  private var value = false
  func mark() { lock.lock(); value = true; lock.unlock() }
  var marked: Bool { lock.lock(); defer { lock.unlock() }; return value }
}

/// View reads share the session's image FIFO. Each read owns one pin until it runs, or
/// until its rejection; it never runs after the session closed.
enum ScannerImageReads {
  /// `operation` runs on the worker while `pin` is pinned, unless already obsolete;
  /// `completion` runs on main. A skipped or rejected read completes with
  /// processor/superseded or the queue's rejection.
  static func read(_ pin: SessionPageSource, generation: LoadGeneration, _ current: Int,
                   _ operation: @escaping @Sendable (SessionPageSource) throws -> CGImage,
                   completion: @escaping @MainActor @Sendable (Result<DisplayImage, Error>) -> Void) {
    let started = ReadStart()
    let deliver: @Sendable (Result<DisplayImage, Error>) -> Void = { result in
      DispatchQueue.main.async { MainActor.assumeIsolated { completion(result) } }
    }
    let owner = pin.workOwner
    owner.queue.submit(owner, kind: .decode, operation: { () throws -> DisplayImage in
      started.mark()
      defer { pin.close() }
      guard generation.isCurrent(current) else { throw ScannerCodeError(code: .processorSuperseded) }
      return DisplayImage(image: try operation(pin))
    }, onSuccess: { deliver(.success($0)) }, onFailure: { error in
      if !started.marked { pin.close() }
      deliver(.failure(error))
    })
  }
}
