import Foundation
import NitroModules
import UIKit

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridImagePagerView: HybridImagePagerViewSpec {
  // Nitro mounts and accesses this view on the UIKit main thread.
  lazy var view: UIView = MainActor.assumeIsolated { UIView() }
  var session: (any HybridScanSessionSpec) = HybridScanSession()
  var pageIds: [String]? = nil
  var direction: ImagePagerDirection? = nil
  var selectedPageId: String = ""
  var swipeEnabled: Bool? = nil
  var onPageChange: ((_ pageId: String) -> Void)? = nil
  var onLoad: ((_ pageId: String) -> Void)? = nil
  var onError: ((_ failure: ScannerFailure) -> Void)? = nil

  func afterUpdate() {
    onError?(ScannerUnsupported.failure)
  }
}
