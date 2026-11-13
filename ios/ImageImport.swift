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

  /// `options` must be effective, so a paper size carries its dpi.
  static func target(width: Int, height: Int, options: DocumentRenderOptions) -> (width: Int, height: Int) {
    precondition(width > 0 && height > 0, "Image dimensions must be positive.")
    let long = Double(max(width, height)), short = Double(min(width, height))
    let scale: Double
    if let paper = options.paperSize {
      let dpi = options.dpi ?? 200
      scale = max(max(paper.width, paper.height) / 2.54 * dpi / long, min(paper.width, paper.height) / 2.54 * dpi / short)
    } else {
      scale = Double(renderLimit) / long
    }
    guard scale < 1 else { return (width, height) }
    // A tiny epsilon keeps exact products such as 1654.0000001 from rounding up a pixel.
    func scaled(_ side: Int) -> Int { min(side, max(1, Int((Double(side) * scale - 1e-6).rounded(.up)))) }
    return (scaled(width), scaled(height))
  }
}

/// The model an import call freezes: the camera's active one, else the bundled model validated once without
/// activating it, so the camera's own model choice is never replaced.
enum ImportModel {
  private static let worker = DispatchQueue(label: "ReactNativeDocumentScanner.importModel", qos: .userInitiated)
  /// Owned by `worker`: every read and write runs there, so the cache needs no lock.
  nonisolated(unsafe) private static var bundled: CornerInference.Selection?

  /// `completion` runs once, on any thread.
  static func resolve(inference: CornerInference = .shared,
                      completion: @escaping (Result<CornerInference.Selection, Error>) -> Void) {
    if let active = try? inference.selection() { return completion(.success(active)) }
    nonisolated(unsafe) let completion = completion
    worker.async {
      completion(Result {
        if let bundled { return bundled }
        do {
          let data = try Data(contentsOf: CornerModelLoader.url("asset:///doccornernet/model.tflite"))
          let made = try inference.detached(data: data)
          bundled = made
          return made
        } catch let error as ScannerCodeError {
          throw error
        } catch {
          throw ScannerCodeError(code: .modelLoadFailed)
        }
      })
    }
  }
}

/// Turns a picked image into a session original and admits it like a camera still: the same analysis, crop and
/// render, without a frame or live corners.
enum ImageImport {
  /// Decodes `url` on the session's image worker and admits it; `completion` runs once, on that worker.
  static func admit(_ session: ScanSessionState, url: URL, options: DocumentRenderOptions,
                    selection: CornerInference.Selection?, autoCrop: Bool,
                    completion: @escaping (Result<String, Error>) -> Void) {
    let id = UUID().uuidString
    let resources = session.resources
    let store = resources.registry.store
    resources.workOwner.queue.submit(resources.workOwner, kind: .capture, pageId: id,
      operation: { () throws -> URL in
        try store.publish(image: upright(url, options: options))
      },
      onSuccess: { source in
        // admitSource retires the source itself when admission fails.
        do {
          try session.admitSource(id: id, source: source, options: options, selection: selection, autoCrop: autoCrop)
          completion(.success(id))
        } catch { completion(.failure(error)) }
      },
      onFailure: { completion(.failure($0)) },
      onDiscard: { store.retire($0) { _ in } })
  }

  /// The image upright at its import size, opaque on white paper. ImageIO samples while decoding and applies EXIF.
  static func upright(_ url: URL, options: DocumentRenderOptions) throws -> CGImage {
    guard url.isFileURL else { throw ScannerCodeError.inputInvalid }
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
          let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
          width > 0, height > 0 else { throw ScannerCodeError(code: .imageLoadFailed) }
    let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
    let transposed = (5...8).contains(orientation)
    let target = ImportSizePolicy.target(width: transposed ? height : width, height: transposed ? width : height,
                                         options: options)
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
  /// ends a picker that is still open and deletes picks still being staged. Main thread; `completion` runs once.
  /// Only the picker is main-actor work: admission closures run on the session's image worker.
  static func fromLibrary(_ session: ScanSessionState, maxCount: Int, options: DocumentRenderOptions, autoCrop: Bool,
                          selection: CornerInference.Selection,
                          completion: @escaping (Result<LibraryImportResult, Error>) -> Void) {
    let operation = LibraryImport(maxCount: maxCount, admit: { url, done in
      admit(session, url: url, options: options, selection: selection, autoCrop: autoCrop, completion: done)
    }, completion: completion)
    operation.start(register: { try session.onClose($0) }) { onPicked in
      nonisolated(unsafe) let onPicked = onPicked
      let end: @Sendable () -> NativeCompletion = try MainActor.assumeIsolated {
        let picker = try LibraryPicker.pick(maxCount: maxCount) { onPicked($0) }
        return {
          let released = NativeCompletion()
          DispatchQueue.main.async {
            MainActor.assumeIsolated { picker.end().whenComplete { _ in released.complete() } }
          }
          return released
        }
      }
      return end
    }
  }
}

/// One `importFromLibrary` call, from presentation through admission. It holds its frozen defaults through `admit`,
/// owns every staged file handed to it, submits picks in picked order, so the session's serial image worker keeps
/// that order, and settles `completion` once: when every pick is admitted or has failed, on a picker failure, or
/// when the session closes first.
final class LibraryImport: @unchecked Sendable {
  typealias Admit = (URL, @escaping (Result<String, Error>) -> Void) -> Void
  private let lock = NSLock()
  private let maxCount: Int
  private let admit: Admit
  private let discard: (URL) -> Void
  private let completion: (Result<LibraryImportResult, Error>) -> Void
  private var settled = false
  private var presenting = false
  private var unregister: (() -> Void)?
  private var endPick: (() -> NativeCompletion)?
  /// Completes once no picker of this call holds the one-picker slot; session close waits for it.
  private let released = NativeCompletion()

  init(maxCount: Int, admit: @escaping Admit,
       discard: @escaping (URL) -> Void = { try? FileManager.default.removeItem(at: $0) },
       completion: @escaping (Result<LibraryImportResult, Error>) -> Void) {
    self.maxCount = maxCount; self.admit = admit; self.discard = discard; self.completion = completion
  }

  /// `register` ties the call to its session's close and returns the unregistration; `present` shows the picker,
  /// reports through its callback and returns a handle that ends the platform request from any thread and reports
  /// when its slot is free.
  func start(register: (@escaping @Sendable () -> NativeCompletion) throws -> () -> Void,
             present: (@escaping (Result<[URL?], Error>) -> Void) throws -> () -> NativeCompletion) {
    let unregistered: () -> Void
    do {
      unregistered = try register { [self] in cancel(ScannerCodeError(code: .processorDisposed)) }
    } catch {
      released.complete()
      return settle(.failure(error))
    }
    guard lock.withLock({ () -> Bool in
      if !settled { unregister = unregistered; presenting = true }
      return !settled
    }) else { return unregistered() }
    let end: () -> NativeCompletion
    do {
      end = try present { [self] in picked($0) }
    } catch {
      // No picker opened, so nothing holds the slot for this call.
      let raced = lock.withLock { () -> Bool in presenting = false; return settled }
      released.complete()
      if !raced { settle(.failure(error)) }
      return
    }
    // Close may have won while presenting: the picker is then ended straight away.
    if lock.withLock({ () -> Bool in
      presenting = false
      if !settled { endPick = end }
      return settled
    }) { end().whenComplete { [released] _ in released.complete() } }
  }

  /// Settles with `error` unless already settled; a still-open picker is ended and later picks are deleted. The
  /// returned completion fires once this call's picker no longer holds the one-picker slot.
  @discardableResult
  func cancel(_ error: Error) -> NativeCompletion {
    settle(.failure(error))
    return released
  }

  private func picked(_ result: Result<[URL?], Error>) {
    let urls: [URL?]
    switch result {
    case .failure(let error): return settle(.failure(error))
    case .success(let picked): urls = picked
    }
    // The picker honours the limit; any pick past it is dropped like the Android fallback's.
    let picks = maxCount > 0 ? Array(urls.prefix(maxCount)) : urls
    urls.dropFirst(picks.count).compactMap { $0 }.forEach(discard)
    if lock.withLock({ settled }) { return picks.compactMap { $0 }.forEach(discard) }
    if picks.isEmpty { return settle(.success(LibraryImportResult(pageIds: [], failedCount: 0))) }
    var outcomes = [Result<String, Error>?](repeating: nil, count: picks.count)
    var remaining = picks.count
    for (index, url) in picks.enumerated() {
      let done = { [self] (outcome: Result<String, Error>) in
        if let url { discard(url) }
        let finished: Bool = lock.withLock {
          outcomes[index] = outcome
          remaining -= 1
          return remaining == 0
        }
        guard finished else { return }
        let ids = lock.withLock { outcomes.compactMap { try? $0?.get() } }
        settle(.success(LibraryImportResult(pageIds: ids, failedCount: Double(picks.count - ids.count))))
      }
      if let url { admit(url, done) } else { done(.failure(ScannerCodeError(code: .imageLoadFailed))) }
    }
  }

  private func settle(_ result: Result<LibraryImportResult, Error>) {
    let taken: (unregister: (() -> Void)?, end: (() -> NativeCompletion)?, presenting: Bool)? = lock.withLock {
      guard !settled else { return nil }
      settled = true
      defer { unregister = nil; endPick = nil }
      return (unregister, endPick, presenting)
    }
    guard let taken else { return }
    taken.unregister?()
    // While presenting, `start` ends the picker and completes the release once `present` returns.
    if let end = taken.end { end().whenComplete { [released] _ in released.complete() } }
    else if !taken.presenting { released.complete() }
    completion(result)
  }
}
