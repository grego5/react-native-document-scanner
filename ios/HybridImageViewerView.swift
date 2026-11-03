import Foundation
import NitroModules
import UIKit

/// A native zoomable session page; its gestures never cross into JavaScript. Nitro sets
/// props and mounts the view on the main thread; methods may come from JavaScript's.
final class HybridImageViewerView: HybridImageViewerViewSpec, @unchecked Sendable {
  private let lock = NSLock()
  /// Written on main, read from JavaScript.
  private var zoomed = false

  private lazy var parts: (view: ImageViewerView, loader: ImageViewerLoader) = MainActor.assumeIsolated {
    let image = ImageViewerView()
    let loader = ImageViewerLoader(image: image, onLoaded: { [weak self] in self?.onLoad?($0) },
      onFailed: { [weak self] in self?.onError?(ScannerFailure(code: $0.code)) },
      onZoomChanged: { [weak self] zoomed in
        guard let self else { return }
        lock.lock(); self.zoomed = zoomed; lock.unlock()
        onZoomChange?(zoomed)
      })
    return (image, loader)
  }

  var view: UIView { parts.view }
  var session: (any HybridScanSessionSpec) = HybridScanSession.placeholder()
  var pageId: String = ""
  var onError: ((_ failure: ScannerFailure) -> Void)? = nil
  var onLoad: ((_ pageId: String) -> Void)? = nil
  var onZoomChange: ((_ zoomed: Bool) -> Void)? = nil

  func afterUpdate() {
    MainActor.assumeIsolated {
      guard let state = (session as? HybridScanSession)?.state else {
        parts.loader.clear()
        onError?(ScannerFailure(code: .inputInvalid))
        return
      }
      parts.loader.bind(state, pageId)
    }
  }

  // Explicit unmount detaches observation and invalidates even already queued decodes.
  func onDropView() {
    onLoad = nil; onError = nil; onZoomChange = nil
    MainActor.assumeIsolated { parts.loader.clear() }
  }

  func isZoomed() throws -> Bool {
    lock.lock(); defer { lock.unlock() }
    return zoomed
  }

  func resetZoom() throws {
    DispatchQueue.main.async { [weak self] in
      MainActor.assumeIsolated { self?.parts.view.resetZoom(animated: false) }
    }
  }
}
