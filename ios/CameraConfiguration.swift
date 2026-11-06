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

  /// The largest bounded 4:3 size, else the largest bounded size of another aspect; nil when
  /// nothing fits.
  static func select(_ sizes: [PixelSize]) -> PixelSize? {
    sizes.filter(isBounded).max { rank($0) < rank($1) }
  }

  private static func rank(_ size: PixelSize) -> (Int, Int, Int, Int) {
    (size.isFourByThree ? 1 : 0, size.pixels, size.longSide, size.width)
  }

  /// A short side of 1080 px, as 1440x1080: enough for an ordinary photo at a fraction of the file size.
  static let standardShortSide = 1080

  /// `camera` mode's standard photo, as Android's: among bounded sizes, 4:3 ones if any, the smallest with a short
  /// side of at least `standardShortSide`, else the widest smaller one; nil when nothing fits.
  static func selectStandard(_ sizes: [PixelSize]) -> PixelSize? {
    let bounded = sizes.filter(isBounded)
    let fourByThree = bounded.filter(\.isFourByThree)
    let pool = fourByThree.isEmpty ? bounded : fourByThree
    func key(_ size: PixelSize) -> (Int, Int) { (size.shortSide, size.pixels) }
    return pool.filter { $0.shortSide >= standardShortSide }.min { key($0) < key($1) } ?? pool.max { key($0) < key($1) }
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

  /// Still size first: the best bounded still any format offers, the largest or, without `highResolution`, the
  /// standard one. Among the formats offering
  /// it, a 4:3 video stream comes first, so preview and analysis show what the photo shows;
  /// then the video width closest to `analysisWidth` at or above it, then below it; then the
  /// first advertised format.
  static func choose(_ formats: [CameraFormatOption], analysisWidth: Int,
                     highResolution: Bool = true) -> CameraFormatChoice? {
    let candidates = formats.enumerated().compactMap { index, format in
      CameraStillPolicy.select(format.photos, highResolution: highResolution)
        .map { CameraFormatChoice(index: index, photo: $0, video: format.video) }
    }
    guard let photo = CameraStillPolicy.select(candidates.map(\.photo), highResolution: highResolution) else { return nil }
    return candidates.filter { $0.photo == photo && !$0.video.isEmpty }.min {
      videoRank($0.video, analysisWidth) < videoRank($1.video, analysisWidth)
    }
  }

  /// The camera's own width before rotation is the long side of its sensor-axis video size.
  private static func videoRank(_ video: PixelSize, _ analysisWidth: Int) -> (Int, Int, Int) {
    (video.isFourByThree ? 0 : 1, video.longSide >= analysisWidth ? 0 : 1, abs(video.longSide - analysisWidth))
  }

  /// Same bounds as Android's `analysisWidth`.
  static func analysisWidth(_ requested: Double?) -> Int {
    guard let requested, requested.isFinite else { return defaultAnalysisWidth }
    return min(max(Int(requested), 160), 1920)
  }
}
