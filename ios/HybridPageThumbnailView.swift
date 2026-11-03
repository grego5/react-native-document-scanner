import Foundation
import NitroModules
import UIKit

/// A native session thumbnail tile. Nitro sets props, mounts and drops the view on main.
final class HybridPageThumbnailView: HybridPageThumbnailViewSpec, @unchecked Sendable {
  private lazy var tile: PageThumbnailView = MainActor.assumeIsolated { PageThumbnailView() }

  var view: UIView { tile }
  var session: (any HybridScanSessionSpec) = HybridScanSession.placeholder()
  var pageId: String = ""
  var resizeMode: PageThumbnailResizeMode? = nil

  func afterUpdate() {
    MainActor.assumeIsolated {
      tile.bind((session as? HybridScanSession)?.state, pageId, mode: resizeMode ?? .cover)
    }
  }

  func onDropView() {
    MainActor.assumeIsolated { tile.dispose() }
  }
}
