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

  /// Edge `index` moved by (dx, dy), shortened at the photo's bounds; nil when the result is invalid.
  static func moveEdge(_ quad: QuadCoordinates, _ index: Int, dx: Double, dy: Double) -> QuadCoordinates? {
    ScannerCropGeometry.move(numbers(quad), index: index, x: dx, y: dy, edge: true)?.map(\.doubleValue)
  }
}
