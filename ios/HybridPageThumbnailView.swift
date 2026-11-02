import Foundation
import NitroModules
import UIKit

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridPageThumbnailView: HybridPageThumbnailViewSpec {
  // Nitro mounts and accesses this view on the UIKit main thread.
  lazy var view: UIView = MainActor.assumeIsolated { UIView() }
  var session: (any HybridScanSessionSpec) = HybridScanSession()
  var pageId: String = ""
  var resizeMode: PageThumbnailResizeMode? = nil

  func afterUpdate() {
  }
}
