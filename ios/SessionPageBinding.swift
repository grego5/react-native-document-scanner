import Foundation

/// One pinned page file shared by a binding and its queued reads. Removal or session
/// close cannot invalidate an admitted read; the last reader releases the pins.
final class SessionPageSource: @unchecked Sendable {
  /// Identifies the pinned resource; an unchanged key means the same image.
  let key: String
  let url: URL
  /// Thumbnail tiles key their cache by the render they summarize.
  let cacheKey: String
  private let leases: [SessionResources.Lease]
  private let lock = NSLock()
  private var readers = 1

  init(key: String, leases: [SessionResources.Lease], cacheKey: String? = nil) {
    self.key = key
    self.leases = leases
    url = leases[0].url
    self.cacheKey = cacheKey ?? leases[0].url.absoluteString
  }

  var workOwner: ScannerWorkQueue.Owner { leases[0].resource.owner.workOwner }

  func retain() -> SessionPageSource {
    lock.lock(); defer { lock.unlock() }
    precondition(readers > 0, "Source already released.")
    readers += 1
    return self
  }

  func close() {
    lock.lock()
    precondition(readers > 0, "Source already released.")
    readers -= 1
    let release = readers == 0
    lock.unlock()
    if release { leases.forEach { $0.close() } }
  }
}

/// Main-confined observation of one session page for native views. Page removal or
/// session close clears the source without an error, including when a queued update
/// arrives after the page is gone; only an initially invalid page id reports input/invalid. No JS metadata publication participates in image replacement.
final class SessionPageBinding {
  private let state: ScanSessionState
  private let id: String
  private let original: Bool
  private let thumbnailOnly: Bool
  private let changed: (SessionPageSource?) -> Void
  private let failed: (ScannerCodeError) -> Void
  private var unsubscribe: (() -> Void)?
  private var generation = 0
  private var key: String?
  /// Set once the page resolved; later disappearance is removal, not invalid input.
  private var established = false

  init(state: ScanSessionState, id: String, original: Bool = false, thumbnailOnly: Bool = false,
       changed: @escaping (SessionPageSource?) -> Void, failed: @escaping (ScannerCodeError) -> Void) {
    self.state = state; self.id = id; self.original = original; self.thumbnailOnly = thumbnailOnly
    self.changed = changed; self.failed = failed
  }

  /// `afterRegistration` is a test seam between observation and the first read.
  func start(afterRegistration: () -> Void = {}) {
    guard unsubscribe == nil else { return }
    generation += 1
    let current = generation
    do {
      let registration = try state.observe { [weak self] change in
        guard let self, generation == current else { return }
        if change.closed || change.removedPageIds.contains(id) {
          removed()
        } else if change.updatedPages[id] != nil {
          refresh()
        }
      }
      unsubscribe = registration.unsubscribe
      // Validity is decided atomically with registration, so a removal racing the
      // first read is removal, not invalid input.
      established = registration.snapshot.pageIds.contains(id)
      afterRegistration()
      refresh()
    } catch { invalid() }
  }

  var valid: Bool { state.hasPage(id) }

  private func refresh() {
    guard state.hasPage(id) else { return established ? removed() : invalid() }
    do {
      let source = thumbnailOnly ? try state.thumbnailSource(id) : try state.imageSource(id, original: original)
      established = true
      if source?.key == key {
        source?.close()
      } else {
        key = source?.key
        changed(source) // The receiver owns the source, including on a detached view.
      }
    } catch {
      // Removal may race the read itself.
      if established && !state.hasPage(id) { removed() } else { invalid() }
    }
  }

  private func removed() {
    close()
    changed(nil)
  }

  private func invalid() {
    close()
    changed(nil)
    failed(.inputInvalid)
  }

  /// Releases the observer; already delivered sources stay with their receivers.
  func close() {
    generation += 1
    unsubscribe?()
    unsubscribe = nil
    key = nil
  }
}
