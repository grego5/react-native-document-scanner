import XCTest
import UIKit
import CryptoKit
import TensorFlowLiteC
@testable import ReactNativeDocumentScanner

final class ScaffoldTests: XCTestCase {
  func testUnfinishedOperationsReturnTypedFailures() throws {
    let result = try HybridDocumentScannerProcessor().createSessionResult()
    XCTAssertEqual(result.asType(ScannerFailure.self)?.code, .featureUnsupported)
    XCTAssertThrowsError(try HybridCameraPermission().getStatus())
    XCTAssertThrowsError(try HybridScanSession().getSession())
  }

  func testBundledModelMatchesCanonicalBytes() throws {
    let owners = [Bundle(for: HybridCameraPermission.self), Bundle.main] + Bundle.allBundles + Bundle.allFrameworks
    let url = try XCTUnwrap(owners.compactMap {
      $0.url(forResource: "DocumentScannerModels", withExtension: "bundle")
    }.first)
    let bundle = try XCTUnwrap(Bundle(url: url))
    let model = try XCTUnwrap(bundle.url(forResource: "model", withExtension: "tflite"))
    let digest = SHA256.hash(data: try Data(contentsOf: model)).map { String(format: "%02x", $0) }.joined()
    XCTAssertEqual(digest, "830ece3373c3ce6ff67a9023f10f56e3576c257de22d662f985ce27b747f3b35")
  }

  func testPinnedTensorFlowRuntimeLinks() {
    XCTAssertEqual(String(cString: TfLiteVersion()), "2.17.0")
  }

  @MainActor
  func testGeneratedRegistrationFactoriesLink() {
    _ = ReactNativeDocumentScannerAutolinking.createCameraPermission()
    _ = ReactNativeDocumentScannerAutolinking.createDocumentScannerProcessor()
    _ = ReactNativeDocumentScannerAutolinking.createScanSession()
    _ = ReactNativeDocumentScannerAutolinking.createDocumentScannerCameraView()
    _ = ReactNativeDocumentScannerAutolinking.createDocumentCropEditorView()
    _ = ReactNativeDocumentScannerAutolinking.createImageViewerView()
    _ = ReactNativeDocumentScannerAutolinking.createImagePagerView()
    _ = ReactNativeDocumentScannerAutolinking.createPageThumbnailView()
  }

  @MainActor
  func testAllViewScaffoldsMountAndRetainUIKitIdentity() {
    let camera = HybridDocumentScannerCameraView()
    let editor = HybridDocumentCropEditorView()
    let viewer = HybridImageViewerView()
    let pager = HybridImagePagerView()
    let thumbnail = HybridPageThumbnailView()
    let container = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
    for view in [camera.view, editor.view, viewer.view, pager.view, thumbnail.view] {
      container.addSubview(view)
      container.layoutIfNeeded()
      XCTAssertTrue(view.superview === container)
      view.removeFromSuperview()
      XCTAssertNil(view.superview)
    }
    XCTAssertTrue(camera.view === camera.view)
    XCTAssertTrue(editor.view === editor.view)
    XCTAssertTrue(viewer.view === viewer.view)
    XCTAssertTrue(pager.view === pager.view)
    XCTAssertTrue(thumbnail.view === thumbnail.view)
  }
}
