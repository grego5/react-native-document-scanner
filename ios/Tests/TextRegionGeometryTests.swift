import XCTest
@testable import ReactNativeDocumentScanner

/// Hardware-free text region geometry.
final class TextRegionGeometryTests: XCTestCase {
  func testRegionMapsThroughAspectFill() {
    let region = TextRegion(x: 0.1, y: 0.4, width: 0.8, height: 0.2)
    // A 9:16 view over a 9:16 buffer maps one to one.
    let same = textRegionInBuffer(region, view: CGSize(width: 90, height: 160), buffer: CGSize(width: 720, height: 1280))
    XCTAssertEqual(same.minX, 0.1, accuracy: 1e-9)
    XCTAssertEqual(same.minY, 0.4, accuracy: 1e-9)
    XCTAssertEqual(same.width, 0.8, accuracy: 1e-9)
    // A square view over a 9:16 buffer shows its vertical middle.
    let square = textRegionInBuffer(region, view: CGSize(width: 90, height: 90), buffer: CGSize(width: 90, height: 160))
    XCTAssertEqual(square.minX, 0.1, accuracy: 1e-9)
    XCTAssertEqual(square.minY, (36 + 35) / 160.0, accuracy: 1e-9)
    XCTAssertEqual(square.height, 18 / 160.0, accuracy: 1e-9)
  }

  func testRegionClampsToTheView() {
    let region = clampedTextRegion(TextRegion(x: 0.5, y: -0.2, width: 0.9, height: 1.5))
    XCTAssertTrue(sameTextRegion(region, TextRegion(x: 0.5, y: 0, width: 0.5, height: 1)))
  }
}
