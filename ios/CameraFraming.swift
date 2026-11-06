import CoreGraphics
import Foundation

/// How a raw camera buffer becomes the upright, optionally square frame the user sees.
/// Buffers stay in sensor axes; the framing names the clockwise turn that makes them upright
/// and the centered region shown. The model's tensor resize is a separate, later transform:
/// live inference receives `orientation` and `crop` and resizes on its own.
struct CameraFraming: Equatable, Sendable {
  /// Raw buffer size, in sensor axes.
  let buffer: PixelSize
  /// Clockwise quarter turns that make the buffer upright: 0, 90, 180 or 270.
  let rotation: Int
  /// The virtual square: the sensor still streams its full frame, and only the centered
  /// square is shown and analyzed.
  let square: Bool

  /// `rotationAngle` is AVFoundation's clockwise video rotation angle in degrees; it is
  /// rounded to the nearest quarter turn. Nil for an empty buffer or a non-finite angle.
  init?(buffer: PixelSize, rotationAngle: Double, square: Bool) {
    guard !buffer.isEmpty, rotationAngle.isFinite else { return nil }
    let quarter = Int((rotationAngle / 90).rounded())
    self.buffer = buffer
    self.rotation = ((quarter % 4) + 4) % 4 * 90
    self.square = square
  }

  /// EXIF orientation of the raw buffer, as the live inference runtime takes it.
  var orientation: UInt8 {
    switch rotation {
    case 90: return 6
    case 180: return 3
    case 270: return 8
    default: return 1
    }
  }

  private var swapsAxes: Bool { rotation % 180 != 0 }

  /// Upright size of the whole buffer.
  var upright: PixelSize { swapsAxes ? PixelSize(buffer.height, buffer.width) : buffer }

  /// The framed region in raw buffer pixels: integral and centered. A square crop is the same
  /// region whichever way the buffer turns.
  var crop: CGRect {
    guard square else { return CGRect(x: 0, y: 0, width: buffer.width, height: buffer.height) }
    let side = buffer.shortSide
    return CGRect(x: (buffer.width - side) / 2, y: (buffer.height - side) / 2, width: side, height: side)
  }

  /// Upright size of the framed region.
  var framed: PixelSize {
    let crop = crop
    let size = PixelSize(Int(crop.width), Int(crop.height))
    return swapsAxes ? PixelSize(size.height, size.width) : size
  }

  /// A normalized point of the raw buffer as a normalized point of the upright framed image.
  func toFramed(_ point: CGPoint) -> CGPoint {
    let crop = crop
    let x = (point.x * CGFloat(buffer.width) - crop.minX) / crop.width
    let y = (point.y * CGFloat(buffer.height) - crop.minY) / crop.height
    switch rotation {
    case 90: return CGPoint(x: 1 - y, y: x)
    case 180: return CGPoint(x: 1 - x, y: 1 - y)
    case 270: return CGPoint(x: y, y: 1 - x)
    default: return CGPoint(x: x, y: y)
    }
  }

  /// The inverse of `toFramed`.
  func toBuffer(_ point: CGPoint) -> CGPoint {
    let local: CGPoint
    switch rotation {
    case 90: local = CGPoint(x: point.y, y: 1 - point.x)
    case 180: local = CGPoint(x: 1 - point.x, y: 1 - point.y)
    case 270: local = CGPoint(x: 1 - point.y, y: point.x)
    default: local = point
    }
    let crop = crop
    return CGPoint(x: (crop.minX + local.x * crop.width) / CGFloat(buffer.width),
                   y: (crop.minY + local.y * crop.height) / CGFloat(buffer.height))
  }

  /// Where the framed image appears in a view: aspect-fitted and centered. A preview layer
  /// placed here with aspect-fill shows exactly the framed region.
  func presentation(in bounds: CGRect) -> CGRect {
    guard bounds.width > 0, bounds.height > 0 else { return .zero }
    let framed = framed
    let scale = min(bounds.width / CGFloat(framed.width), bounds.height / CGFloat(framed.height))
    let size = CGSize(width: CGFloat(framed.width) * scale, height: CGFloat(framed.height) * scale)
    return CGRect(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2,
                  width: size.width, height: size.height)
  }
}
