import Foundation
import NitroModules
import UIKit

/// Native paging between zoomable session pages; swipes and zoom never cross into
/// JavaScript. Nitro sets props and mounts the view on the main thread, where session
/// changes are also delivered.
final class HybridImagePagerView: HybridImagePagerViewSpec, @unchecked Sendable {
  private var boundState: ScanSessionState?
  private var previousSelection: String?
  private var previousInput: [String]?
  private var unsubscribe: (() -> Void)?
  private var displayedPageIds: [String] = []

  private lazy var pager: ImagePagerView = MainActor.assumeIsolated {
    ImagePagerView(
      onPageSettled: { [weak self] index in
        guard let self, displayedPageIds.indices.contains(index) else { return }
        onPageChange?(displayedPageIds[index])
      },
      onImageLoaded: { [weak self] in self?.onLoad?($0) },
      onImageFailed: { [weak self] in self?.onError?(ScannerFailure(code: $0.code)) })
  }

  var view: UIView { pager }
  var session: (any HybridScanSessionSpec) = HybridScanSession.placeholder()
  var pageIds: [String]? = nil
  var direction: ImagePagerDirection? = nil
  var selectedPageId: String = ""
  var swipeEnabled: Bool? = nil
  var onPageChange: ((_ pageId: String) -> Void)? = nil
  var onLoad: ((_ pageId: String) -> Void)? = nil
  var onError: ((_ failure: ScannerFailure) -> Void)? = nil

  func afterUpdate() {
    MainActor.assumeIsolated {
      pager.swipeEnabled = swipeEnabled ?? true
      pager.setDirection(direction ?? .auto)
      let state = (session as? HybridScanSession)?.state
      if state !== boundState { bind(state) }
      guard let state else { return reject(ScannerCodeError.inputInvalid) }
      do {
        if let input = pageIds, input != previousInput, !state.snapshot().closed {
          try state.setPageOrder(input)
        }
        previousInput = pageIds
        if selectedPageId != previousSelection, !selectedPageId.isEmpty, !state.snapshot().closed,
           !state.hasPage(selectedPageId) {
          throw ScannerCodeError.inputInvalid
        }
        previousSelection = selectedPageId
        refreshPages()
      } catch {
        reject(error)
      }
    }
  }

  @MainActor
  private func bind(_ state: ScanSessionState?) {
    unsubscribe?(); unsubscribe = nil
    boundState = state
    previousInput = nil
    previousSelection = nil
    // A closed session refuses observation; it shows no pages.
    unsubscribe = (try? state?.observe { [weak self] change in
      guard let self else { return }
      let shown = displayedPageIds
      if change.closed || change.pageIds != nil || !change.removedPageIds.isEmpty ||
         change.updatedPages.keys.contains(where: { !shown.contains($0) }) {
        MainActor.assumeIsolated { self.refreshPages() }
      }
    })?.unsubscribe
  }

  @MainActor
  private func refreshPages() {
    guard let state = boundState else { return show([], nil) }
    let snapshot = state.snapshot()
    if snapshot.closed { return show([], nil) }
    show(snapshot.pageIds, state)
    if let selected = displayedPageIds.firstIndex(of: selectedPageId) { pager.show(selected) }
  }

  @MainActor
  private func show(_ ids: [String], _ state: ScanSessionState?) {
    displayedPageIds = ids
    pager.setPages(ids, state: state)
  }

  @MainActor
  private func reject(_ error: Error) {
    show([], nil)
    onError?(ScannerFailure(code: ScannerCodeError.code(error, fallback: .inputInvalid)))
  }

  // Explicit unmount detaches observation and invalidates even already queued decodes.
  func onDropView() {
    onPageChange = nil; onLoad = nil; onError = nil
    MainActor.assumeIsolated {
      unsubscribe?(); unsubscribe = nil
      boundState = nil
      pager.release()
    }
  }
}
