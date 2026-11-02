import Foundation
import NitroModules
import UIKit

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridImageViewerView: HybridImageViewerViewSpec {
  // Nitro mounts and accesses this view on the UIKit main thread.
  lazy var view: UIView = MainActor.assumeIsolated { UIView() }
  var session: (any HybridScanSessionSpec) = HybridScanSession()
  var pageId: String = ""
  var onError: ((_ failure: ScannerFailure) -> Void)? = nil
  var onLoad: ((_ pageId: String) -> Void)? = nil
  var onZoomChange: ((_ zoomed: Bool) -> Void)? = nil

  func afterUpdate() {
    onError?(ScannerUnsupported.failure)
  }

  func isZoomed() throws -> Bool {
    throw ScannerUnsupported.error
  }

  func resetZoom() throws -> Void {
    throw ScannerUnsupported.error
  }
}
