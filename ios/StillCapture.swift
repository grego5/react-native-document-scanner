import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The still's encoding: native JPEG where the output offers it (the provisional default),
/// otherwise uncompressed BGRA. Either file representation is decoded through ImageIO. A
/// deliberate TIFF/BGRA choice and its cost against JPEG wait for physical-device measurements.
enum StillFormat: Equatable, Sendable {
  case jpeg, bgra

  static func choose(codecs: [AVVideoCodecType], pixelFormats: [OSType]) -> StillFormat? {
    if codecs.contains(.jpeg) { return .jpeg }
    if pixelFormats.contains(kCVPixelFormatType_32BGRA) { return .bgra }
    return nil
  }
}

/// Per-shot still settings, frozen when a takePhoto is accepted.
struct StillSettings: Equatable, Sendable {
  enum Priority: Equatable, Sendable { case quality, balanced, speed }

  /// AVFoundation's quality prioritization for the shot.
  let priority: Priority
  /// JPEG quality from 0.01 to 1, for the native encoder and again for source publication.
  let quality: Double
  /// The clockwise capture rotation angle the still is tagged with, as live analysis uses it.
  var rotationAngle: Double = 0
  /// `quality` maximizes quality (JPEG quality 1); `latency` minimizes the time to
  /// deliver (0.95); `zsl`, the default, uses the output's zero-shutter-lag buffer with balanced
  /// prioritization (0.95) and falls back to `latency` when the configuration has no buffer.
  static func resolve(mode: CaptureMode?, zeroShutterLag: Bool) -> StillSettings {
    var effective = mode ?? .zsl
    if effective == .zsl && !zeroShutterLag { effective = .latency }
    let priority: Priority
    switch effective {
    case .quality: priority = .quality
    case .zsl: priority = .balanced
    default: priority = .speed
    }
    let quality = effective == .quality ? 1.0 : 0.95
    return StillSettings(priority: priority, quality: quality)
  }
}

/// Where a still's pixels land: the upright centered square, and how live corners map onto it.
enum StillGeometry {
  /// The integral centered square of an upright size.
  static func squareCrop(_ size: PixelSize) -> CGRect {
    let side = size.shortSide
    return CGRect(x: (size.width - side) / 2, y: (size.height - side) / 2, width: side, height: side)
  }

  /// The framed region of an upright image: its centered square or the whole image.
  static func framed(_ size: PixelSize, square: Bool) -> CGRect {
    square ? squareCrop(size) : CGRect(x: 0, y: 0, width: size.width, height: size.height)
  }

  /// Maps a normalized upright corners detected on a live framing onto the upright framed still.
  /// The video stream and the still are centered on the sensor and share its long side, so a
  /// stream of another aspect is a centered crop of the still across its short side. Corners up
  /// to 0.5% outside the still are clamped; nil when any lies further out.
  static func map(_ corners: CornerCoordinates, from framing: CameraFraming, toStill still: PixelSize,
                  square: Bool) -> CornerCoordinates? {
    guard corners.count == 8, !still.isEmpty, corners.allSatisfy(\.isFinite) else { return nil }
    let video = framing.upright
    let source = framed(video, square: framing.square)
    let target = framed(still, square: square)
    let videoLong = Double(video.longSide), stillLong = Double(still.longSide)
    var mapped: CornerCoordinates = []
    mapped.reserveCapacity(8)
    for corner in 0..<4 {
      let x = Double(source.minX) + corners[corner * 2] * Double(source.width)
      let y = Double(source.minY) + corners[corner * 2 + 1] * Double(source.height)
      let stillX = (x - Double(video.width) / 2) / videoLong * stillLong + Double(still.width) / 2
      let stillY = (y - Double(video.height) / 2) / videoLong * stillLong + Double(still.height) / 2
      for value in [(stillX - Double(target.minX)) / Double(target.width),
                    (stillY - Double(target.minY)) / Double(target.height)] {
        guard value >= -0.005, value <= 1.005 else { return nil }
        mapped.append(min(1, max(0, value)))
      }
    }
    return mapped
  }
}

/// A still published to the source store, with the upright size its pixels had before any crop.
struct PublishedStill {
  let url: URL
  let upright: PixelSize
}

/// Turns one delivered still into a published source and an optional retained shutter frame.
/// Synchronous; runs on the bound session's image worker, never main.
enum StillProcessor {
  /// Decodes the delivered JPEG (or uncompressed TIFF) once, checks it against the still ceiling,
  /// crops the centered square in upright pixels when `square`, and publishes a provisional JPEG
  /// at `quality`. The store normalizes orientation, corrects dimensions and keeps the capture
  /// metadata; ImageIO writes no thumbnail, so none goes stale.
  static func publish(_ data: Data, square: Bool, quality: Double, store: SourceStore) throws -> PublishedStill {
    guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
          CGImageSourceGetCount(source) > 0,
          let image = CGImageSourceCreateImageAtIndex(source, 0, [
            kCGImageSourceShouldCache: false, kCGImageSourceShouldAllowFloat: false,
          ] as CFDictionary) else { throw ScannerCodeError(code: .captureFailed) }
    guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
      throw ScannerCodeError(code: .captureMetadataFailed)
    }
    let encoded = PixelSize(image.width, image.height)
    // The configured ceiling bounds every delivered still; a larger one is a capture fault.
    guard CameraStillPolicy.isBounded(encoded) else { throw ScannerCodeError(code: .captureFailed) }
    let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
    guard (1...8).contains(orientation) else { throw ScannerCodeError(code: .captureMetadataFailed) }
    let upright = orientation >= 5 ? PixelSize(encoded.height, encoded.width) : encoded
    var metadata = properties
    for key in [kCGImagePropertyPixelWidth, kCGImagePropertyPixelHeight, kCGImagePropertyOrientation,
                kCGImagePropertyDepth, kCGImagePropertyColorModel, kCGImagePropertyHasAlpha] {
      metadata.removeValue(forKey: key)
    }
    let url = try store.publish(image: image, orientation: orientation,
                                crop: square ? StillGeometry.squareCrop(upright) : nil,
                                metadata: metadata, quality: quality)
    return PublishedStill(url: url, upright: upright)
  }

  static let frameQuality = 0.85

  /// Writes the shutter frame upright as a JPEG, straightened to `corners` (normalized in the
  /// frame's upright pixels) when given, and commits it as the page's retained frame.
  static func writeFrame(_ frame: LiveFrame, corners: CornerCoordinates?,
                         to output: SessionResources.Output) throws -> SessionResources.Resource {
    defer { output.close() }
    guard output.resource.kind == .frame, frame.width > 0, frame.height > 0,
          frame.pixels.count >= frame.width * frame.height * 4,
          let orientation = CGImagePropertyOrientation(rawValue: UInt32(frame.orientation)),
          let provider = CGDataProvider(data: frame.pixels as CFData),
          let raw = CGImage(width: frame.width, height: frame.height, bitsPerComponent: 8, bitsPerPixel: 32,
                            bytesPerRow: frame.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue
                              | CGBitmapInfo.byteOrder32Little.rawValue),
                            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    else { throw ScannerCodeError(code: .captureFailed) }
    var image = CIImage(cgImage: raw).oriented(orientation)
    let extent = image.extent
    if let corners, corners.count == 8, let filter = CIFilter(name: "CIPerspectiveCorrection") {
      // Core Image's origin is bottom-left.
      func point(_ index: Int) -> CIVector {
        CIVector(x: extent.minX + corners[index * 2] * extent.width,
                 y: extent.minY + (1 - corners[index * 2 + 1]) * extent.height)
      }
      filter.setValue(image, forKey: kCIInputImageKey)
      filter.setValue(point(0), forKey: "inputTopLeft")
      filter.setValue(point(1), forKey: "inputTopRight")
      filter.setValue(point(2), forKey: "inputBottomRight")
      filter.setValue(point(3), forKey: "inputBottomLeft")
      if let output = filter.outputImage, !output.extent.isEmpty, !output.extent.isInfinite { image = output }
    }
    let bounds = image.extent.integral
    guard let rendered = CIContext(options: [.cacheIntermediates: false]).createCGImage(image, from: bounds),
          let destination = CGImageDestinationCreateWithURL(output.url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)
    else { throw ScannerCodeError(code: .captureFailed) }
    CGImageDestinationAddImage(destination, rendered, [kCGImageDestinationLossyCompressionQuality: frameQuality] as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { throw ScannerCodeError(code: .storageWriteFailed) }
    return try output.commit()
  }
}

/// One accepted shot's publication claim and exactly-once settlement. Main invalidates it when
/// the bound session is replaced or closes, or the view unmounts; a worker admits the page only
/// while holding the claim, so an invalidated shot never publishes and an admitted page is never
/// reported as failed. Any thread.
final class CaptureTicket: @unchecked Sendable {
  private let lock = NSLock()
  private var settled = false
  private let deliver: (Result<CameraCaptureResult, ScannerCodeError>) -> Void

  init(_ deliver: @escaping (Result<CameraCaptureResult, ScannerCodeError>) -> Void) { self.deliver = deliver }

  var isSettled: Bool { lock.lock(); defer { lock.unlock() }; return settled }

  /// Rejects once; false when the shot already settled.
  @discardableResult
  func reject(_ code: ScannerErrorCode) -> Bool {
    lock.lock()
    let first = !settled
    settled = true
    lock.unlock()
    if first { deliver(.failure(ScannerCodeError(code: code))) }
    return first
  }

  /// Runs `admit` under the claim and settles with its page ID, or with its failure. False, without
  /// running `admit`, when the shot already settled.
  @discardableResult
  func publish(_ admit: () throws -> CameraCaptureResult) -> Bool {
    lock.lock()
    guard !settled else { lock.unlock(); return false }
    settled = true
    let result: Result<CameraCaptureResult, ScannerCodeError> = Result { try admit() }
      .mapError { ScannerCodeError(code: ScannerCodeError.code($0, fallback: .captureFailed)) }
    lock.unlock()
    deliver(result)
    return true
  }
}

/// The analysis frame requested at the shutter; filled once on the analysis owner and taken by
/// the shot's worker. A frame that has not arrived by then is not waited for.
final class ShutterFrameSlot: @unchecked Sendable {
  private let lock = NSLock()
  private var frame: LiveFrame?
  func fill(_ frame: LiveFrame) { lock.lock(); self.frame = frame; lock.unlock() }
  func take() -> LiveFrame? { lock.lock(); defer { frame = nil; lock.unlock() }; return frame }
}

/// One accepted takePhoto: everything frozen at acceptance, its ticket and its shutter frame.
final class StillShot: @unchecked Sendable {
  let id = UUID().uuidString.lowercased()
  let ticket: CaptureTicket
  let session: ScanSessionState
  let options: DocumentRenderOptions
  let selection: CornerInference.Selection?
  let settings: StillSettings
  let square: Bool
  /// `scanner` mode: the page crops to the outline analysis finds; `camera` mode keeps the whole photo.
  let autoCrop: Bool
  /// The live guide at the shutter, normalized in the upright framed region of its framing.
  let guide: (corners: CornerCoordinates, framing: CameraFraming)?
  let frame = ShutterFrameSlot()

  init(ticket: CaptureTicket, session: ScanSessionState, options: DocumentRenderOptions,
       selection: CornerInference.Selection?, settings: StillSettings, square: Bool,
       guide: (corners: CornerCoordinates, framing: CameraFraming)?, autoCrop: Bool = true) {
    self.ticket = ticket; self.session = session; self.options = options; self.selection = selection
    self.settings = settings; self.square = square; self.guide = guide; self.autoCrop = autoCrop
  }

  private struct Saved {
    let still: PublishedStill
    let frame: SessionResources.Resource?
  }

  /// Publishes the delivered still and admits it on the session's image worker, settling the
  /// ticket; `done` runs once, on that worker, after every file this shot wrote is either owned
  /// by the admitted page or retired.
  func save(_ data: Data, done: @escaping () -> Void) {
    let resources = session.resources
    let store = resources.registry.store
    let discard = { (saved: Saved) in
      store.retire(saved.still.url) { _ in }
      if let frame = saved.frame { resources.remove(frame) }
    }
    resources.workOwner.queue.submit(resources.workOwner, kind: .capture, pageId: id,
      operation: { [self] () throws -> Saved in
        // An invalidated shot writes nothing.
        if ticket.isSettled { throw ScannerCodeError(code: .processorSuperseded) }
        let still = try StillProcessor.publish(data, square: square, quality: settings.quality, store: store)
        return Saved(still: still, frame: retainFrame())
      },
      onSuccess: { [self] saved in
        let corners = guide.flatMap { StillGeometry.map($0.corners, from: $0.framing, toStill: saved.still.upright, square: square) }
          .flatMap { SessionCorners.renderable($0) ? $0 : nil }
        let admitted = ticket.publish {
          // admitSource retires the source and frame itself when admission fails.
          try session.admitSource(id: id, source: saved.still.url, frame: saved.frame,
                                  liveCorners: corners.map(SessionCorners.corners), options: options, selection: selection,
                                  autoCrop: autoCrop)
          return CameraCaptureResult(pageId: id, snapshot: session.snapshot())
        }
        if !admitted { discard(saved) }
        done()
      },
      onFailure: { [self] error in
        ticket.reject(ScannerCodeError.code(error, fallback: .captureFailed))
        done()
      },
      onDiscard: { saved in discard(saved) })
  }

  /// The shutter frame as a committed session frame, straightened to the guide when the guide was
  /// detected on the same framed size and orientation; nil when no frame arrived or it could not
  /// be written. The frame comes from analysis after acceptance, so a rotation in between (a half
  /// turn keeps the size) leaves it upright but unstraightened. Without `autoCrop` the page keeps
  /// the whole photo, so the frame stays unstraightened too.
  private func retainFrame() -> SessionResources.Resource? {
    guard let frame = frame.take(), let output = try? session.resources.output(.frame) else { return nil }
    let upright = frame.orientation >= 5 ? PixelSize(frame.height, frame.width) : PixelSize(frame.width, frame.height)
    let corners = (autoCrop ? guide : nil).flatMap {
      $0.framing.orientation == frame.orientation && $0.framing.framed == upright ? $0.corners : nil
    }
    do { return try StillProcessor.writeFrame(frame, corners: corners, to: output) }
    catch {
      CameraLog.logger.error("shutter frame not retained: \(String(describing: error), privacy: .public)")
      return nil
    }
  }
}
