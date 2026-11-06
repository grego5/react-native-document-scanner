import CoreMedia
import Foundation

/// Pixel dimensions as a device advertises them (sensor axes) or delivers them.
struct PixelSize: Hashable, Sendable, CustomStringConvertible {
  let width: Int
  let height: Int

  init(_ width: Int, _ height: Int) { self.width = width; self.height = height }
  init(_ dimensions: CMVideoDimensions) { self.init(Int(dimensions.width), Int(dimensions.height)) }

  var longSide: Int { max(width, height) }
  var shortSide: Int { min(width, height) }
  var pixels: Int { width * height }
  var isEmpty: Bool { width <= 0 || height <= 0 }
  /// Orientation independent, with the same tolerance as Android's capture ranking.
  var isFourByThree: Bool { !isEmpty && abs(Double(longSide) / Double(shortSide) - 4.0 / 3.0) < 0.01 }
  var dimensions: CMVideoDimensions { CMVideoDimensions(width: Int32(width), height: Int32(height)) }
  var description: String { "\(width)x\(height)" }
}

/// The strict still-resolution ceiling. 24 and 48 MP modes exceed it; a 12 MP 4:3 sensor
/// mode (4032x3024) and 4096x3072 fit.
enum CameraStillPolicy {
  static let maxLongSide = 4096
  static let maxPixels = 12_582_912

  static func isBounded(_ size: PixelSize) -> Bool {
    !size.isEmpty && size.longSide <= maxLongSide && size.pixels <= maxPixels
  }

  /// Prefer bounded 4:3 sizes, widest short side first. Other aspects are fallbacks.
  static func select(_ sizes: [PixelSize]) -> PixelSize? {
    sizes.filter(isBounded).min { rank($0) < rank($1) }
  }

  private static func rank(_ size: PixelSize) -> (Int, Int, Double, Int, Int) {
    let aspect = Double(size.longSide) / Double(size.shortSide)
    return (size.isFourByThree ? 0 : (aspect > 4.0 / 3.0 ? 1 : 2), -size.shortSide,
            abs(aspect - 4.0 / 3.0), size.pixels, size.longSide)
  }

  /// A short side of 1080 px, as 1440x1080: enough for an ordinary photo at a fraction of the file size.
  static let standardShortSide = 1080

  /// Prefer 4:3, then wider, then narrower fallback sizes. Within 4:3 and wider groups,
  /// choose the smallest short side reaching 1080, or the largest smaller one; narrower sizes rank largest first.
  /// Shared vectors live in standard-resolution.tsv.
  static func selectStandard(_ sizes: [PixelSize]) -> PixelSize? {
    func aspect(_ size: PixelSize) -> Double { Double(size.longSide) / Double(size.shortSide) }
    func tier(_ size: PixelSize) -> Int {
      if size.isFourByThree { return size.shortSide >= standardShortSide ? 0 : 1 }
      if aspect(size) > 4.0 / 3.0 { return size.shortSide >= standardShortSide ? 2 : 3 }
      return 4
    }
    func key(_ size: PixelSize) -> (Int, Int, Double, Int) {
      let tier = tier(size)
      return (tier, tier == 0 || tier == 2 ? size.shortSide : -size.shortSide, abs(aspect(size) - 4.0 / 3.0), size.pixels)
    }
    return sizes.filter(isBounded).min { key($0) < key($1) }
  }

  static func select(_ sizes: [PixelSize], highResolution: Bool) -> PixelSize? {
    highResolution ? select(sizes) : selectStandard(sizes)
  }

  /// A delivered photo may be smaller than the requested maximum, in either orientation, but
  /// never larger or outside the ceiling.
  static func accepts(_ delivered: PixelSize, requested: PixelSize) -> Bool {
    isBounded(delivered) && delivered.longSide <= requested.longSide && delivered.shortSide <= requested.shortSide
  }
}

/// One advertised device format: its video (preview and analysis) size and the still sizes
/// its photo output may be configured for.
struct CameraFormatOption: Sendable {
  let video: PixelSize
  let photos: [PixelSize]
}

/// The format and still size a camera owner configures before it starts.
struct CameraFormatChoice: Equatable, Sendable {
  /// Index into the options the choice was made from.
  let index: Int
  let photo: PixelSize
  let video: PixelSize
}

enum CameraFormatPolicy {
  static let defaultAnalysisWidth = 640

  /// Still size first: the best bounded still any format offers, ranked for text or, without `highResolution`, the
  /// standard one. Among the formats offering
  /// it, a 4:3 video stream comes first, so preview and analysis show what the photo shows;
  /// then the video width closest to `analysisFrameWidth` at or above it, then below it; then the
  /// first advertised format.
  static func choose(_ formats: [CameraFormatOption], analysisFrameWidth: Int,
                     highResolution: Bool = true) -> CameraFormatChoice? {
    let candidates = formats.enumerated().compactMap { index, format in
      CameraStillPolicy.select(format.photos, highResolution: highResolution)
        .map { CameraFormatChoice(index: index, photo: $0, video: format.video) }
    }
    guard let photo = CameraStillPolicy.select(candidates.map(\.photo), highResolution: highResolution) else { return nil }
    return candidates.filter { $0.photo == photo && !$0.video.isEmpty }.min {
      videoRank($0.video, analysisFrameWidth) < videoRank($1.video, analysisFrameWidth)
    }
  }

  /// The camera's own width before rotation is the long side of its sensor-axis video size.
  private static func videoRank(_ video: PixelSize, _ analysisFrameWidth: Int) -> (Int, Int, Int) {
    (video.isFourByThree ? 0 : 1, video.longSide >= analysisFrameWidth ? 0 : 1, abs(video.longSide - analysisFrameWidth))
  }

  /// Same bounds as Android's `analysisFrameWidth`.
  static func analysisFrameWidth(_ requested: Double?) -> Int {
    guard let requested, requested.isFinite else { return defaultAnalysisWidth }
    return min(max(Int(requested), 160), 1920)
  }
}
