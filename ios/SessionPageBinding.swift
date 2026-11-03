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

  /// The pinned thumbnail sidecar of a displayed render, if any.
  var thumbnailURL: URL? { leases.count > 1 ? leases[1].url : nil }

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

/// Main-confined observation of one session page for native views. The binding alone
/// decides what an absent page means, the same way on registration, refresh, event
/// delivery and restart: a page it has seen (in a registration snapshot or a read) is
/// removed or closed, which clears the source silently; a page it never saw is invalid
/// input. No JS metadata publication participates in image replacement.
final class SessionPageBinding {
  /// What this binding knows about its page. `gone` is not terminal: a restart that
  /// finds the ID again (a later page reusing it) makes it available again.
  private enum Target { case unresolved, available, gone }

  private let state: ScanSessionState
  private let id: String
  private let original: Bool
  private let thumbnailOnly: Bool
  private let changed: (SessionPageSource?) -> Void
  private let failed: (ScannerCodeError) -> Void
  private var unsubscribe: (() -> Void)?
  private var generation = 0
  private var key: String?
  private var target = Target.unresolved

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
    let registration: ScanSessionEvents.Registration
    do {
      registration = try state.observe { [weak self] change in
        guard let self, generation == current else { return }
        if change.closed || change.removedPageIds.contains(id) {
          missing()
        } else if change.updatedPages[id] != nil {
          refresh()
        }
      }
    } catch { return missing() } // Only a closed session refuses observation.
    unsubscribe = registration.unsubscribe
    // Seen atomically with registration, so a removal racing the first read is removal.
    if registration.snapshot.pageIds.contains(id) { target = .available }
    afterRegistration()
    refresh()
  }

  var valid: Bool { state.hasPage(id) }

  private func refresh() {
    let source: SessionPageSource?
    // A read fails only when the page or its session is gone, including mid-read.
    do { source = thumbnailOnly ? try state.thumbnailSource(id) : try state.imageSource(id, original: original) }
    catch { return missing() }
    target = .available
    if source?.key == key {
      source?.close()
    } else {
      key = source?.key
      changed(source) // The receiver owns the source, including on a detached view.
    }
  }

  /// The one classification of an absent page.
  private func missing() {
    let seen = target != .unresolved
    if seen { target = .gone }
    close()
    changed(nil)
    if !seen { failed(.inputInvalid) }
  }

  /// Releases the observer; already delivered sources stay with their receivers.
  func close() {
    generation += 1
    unsubscribe?()
    unsubscribe = nil
    key = nil
  }
}
