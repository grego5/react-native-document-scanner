import Foundation

/// Normalized upright TL/TR/BR/BL edits through the shared crop geometry; the view keeps
/// the gesture state.
enum CropQuadEditing {
  static let fullFrame = SessionQuad.fullFrame

  private static func numbers(_ quad: QuadCoordinates) -> [NSNumber] { quad.map { NSNumber(value: $0) } }

  /// Inside the photo, clockwise and not collapsed: what the editor's draft must be.
  static func isValid(_ quad: QuadCoordinates) -> Bool { ScannerCropGeometry.validCrop(numbers(quad)) }

  static func clampToFrame(_ quad: QuadCoordinates) -> QuadCoordinates {
    ScannerCropGeometry.clamp(numbers(quad)).map(\.doubleValue)
  }

  /// Corner `index` moved to (x, y), clamped to the photo; nil when the result is invalid.
  static func moveCorner(_ quad: QuadCoordinates, _ index: Int, x: Double, y: Double) -> QuadCoordinates? {
    ScannerCropGeometry.move(numbers(quad), index: index, x: x, y: y, edge: false)?.map(\.doubleValue)
  }

  /// The narrowest a rectangle crop gets along either axis, of the photo's side.
  static let minRectangleSide = 0.02

  /// The upright rectangle bounding `quad`, clamped to the photo.
  static func boundingRectangle(_ quad: QuadCoordinates) -> QuadCoordinates {
    let xs = [quad[0], quad[2], quad[4], quad[6]].map { min(max($0, 0), 1) }
    let ys = [quad[1], quad[3], quad[5], quad[7]].map { min(max($0, 0), 1) }
    return rectangle(left: xs.min()!, top: ys.min()!, right: xs.max()!, bottom: ys.max()!)
  }

  /// Corner `index` of an upright rectangle moved to (x, y), clamped to the photo, with the
  /// opposite corner fixed and the other two following; nil when it would turn inside out or
  /// get narrower than `minRectangleSide`.
  static func moveRectangleCorner(_ quad: QuadCoordinates, _ index: Int, x: Double, y: Double) -> QuadCoordinates? {
    let opposite = (index + 2) % 4
    let cx = min(max(x, 0), 1), cy = min(max(y, 0), 1)
    let ox = quad[opposite * 2], oy = quad[opposite * 2 + 1]
    let leftSide = index == 0 || index == 3, topSide = index == 0 || index == 1
    let left = leftSide ? cx : ox, right = leftSide ? ox : cx
    let top = topSide ? cy : oy, bottom = topSide ? oy : cy
    guard right - left >= minRectangleSide, bottom - top >= minRectangleSide else { return nil }
    return rectangle(left: left, top: top, right: right, bottom: bottom)
  }

  private static func rectangle(left: Double, top: Double, right: Double, bottom: Double) -> QuadCoordinates {
    [left, top, right, top, right, bottom, left, bottom]
  }

  /// Edge `index` moved by (dx, dy), shortened at the photo's bounds; nil when the result is invalid.
  static func moveEdge(_ quad: QuadCoordinates, _ index: Int, dx: Double, dy: Double) -> QuadCoordinates? {
    ScannerCropGeometry.move(numbers(quad), index: index, x: dx, y: dy, edge: true)?.map(\.doubleValue)
  }
}
