import XCTest
@testable import ReactNativeDocumentScanner

/// Hardware-free text region geometry.
final class TextRegionGeometryTests: XCTestCase {
  func testRegionClampsToTheView() {
    let region = clampedTextRegion(TextRegion(x: 0.5, y: -0.2, width: 0.9, height: 1.5))
    XCTAssertTrue(sameTextRegion(region, TextRegion(x: 0.5, y: 0, width: 0.5, height: 1)))
  }
}
