import Foundation

/// Normalized upright TL/TR/BR/BL edits through the shared crop geometry; the view keeps
/// the gesture state.
enum CropCornerEditing {
  static let fullFrame = SessionCorners.fullFrame

  private static func numbers(_ corners: CornerCoordinates) -> [NSNumber] { corners.map { NSNumber(value: $0) } }

  /// Inside the photo, clockwise and not collapsed: what the editor's draft must be.
  static func isValid(_ corners: CornerCoordinates) -> Bool { ScannerCropGeometry.validCrop(numbers(corners)) }

  static func clampToFrame(_ corners: CornerCoordinates) -> CornerCoordinates {
    ScannerCropGeometry.clamp(numbers(corners)).map(\.doubleValue)
  }

  /// Corner `index` moved to (x, y), clamped to the photo; nil when the result is invalid.
  static func moveCorner(_ corners: CornerCoordinates, _ index: Int, x: Double, y: Double) -> CornerCoordinates? {
    ScannerCropGeometry.move(numbers(corners), index: index, x: x, y: y, edge: false)?.map(\.doubleValue)
  }

  /// The narrowest a rectangle crop gets along either axis, of the photo's side.
  static let minRectangleSide = 0.02

  /// The upright rectangle bounding `corners`, clamped to the photo.
  static func boundingRectangle(_ corners: CornerCoordinates) -> CornerCoordinates {
    let xs = [corners[0], corners[2], corners[4], corners[6]].map { min(max($0, 0), 1) }
    let ys = [corners[1], corners[3], corners[5], corners[7]].map { min(max($0, 0), 1) }
    return rectangle(left: xs.min()!, top: ys.min()!, right: xs.max()!, bottom: ys.max()!)
  }

  /// Corner `index` of an upright rectangle moved to (x, y), clamped to the photo, with the
  /// opposite corner fixed and the other two following; nil when it would turn inside out or
  /// get narrower than `minRectangleSide`.
  static func moveRectangleCorner(_ corners: CornerCoordinates, _ index: Int, x: Double, y: Double) -> CornerCoordinates? {
    let opposite = (index + 2) % 4
    let cx = min(max(x, 0), 1), cy = min(max(y, 0), 1)
    let ox = corners[opposite * 2], oy = corners[opposite * 2 + 1]
    let leftSide = index == 0 || index == 3, topSide = index == 0 || index == 1
    let left = leftSide ? cx : ox, right = leftSide ? ox : cx
    let top = topSide ? cy : oy, bottom = topSide ? oy : cy
    guard right - left >= minRectangleSide, bottom - top >= minRectangleSide else { return nil }
    return rectangle(left: left, top: top, right: right, bottom: bottom)
  }

  private static func rectangle(left: Double, top: Double, right: Double, bottom: Double) -> CornerCoordinates {
    [left, top, right, top, right, bottom, left, bottom]
  }

  /// Edge `index` moved by (dx, dy), shortened at the photo's bounds; nil when the result is invalid.
  static func moveEdge(_ corners: CornerCoordinates, _ index: Int, dx: Double, dy: Double) -> CornerCoordinates? {
    ScannerCropGeometry.move(numbers(corners), index: index, x: dx, y: dy, edge: true)?.map(\.doubleValue)
  }
}
