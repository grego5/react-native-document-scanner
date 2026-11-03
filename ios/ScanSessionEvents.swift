import Foundation

/// Owns observer lifetimes and ordered delivery, sharing the page owner's lock.
/// Changes are copied under that lock and delivered by the asynchronous dispatcher
/// (main in production), never inside state synchronization.
final class ScanSessionEvents {
  struct Registration {
    let snapshot: ScanSessionSnapshot
    let unsubscribe: () -> Void
  }

  private final class Observer {
    var callback: ((ScanSessionChange) -> Void)?
    init(_ callback: @escaping (ScanSessionChange) -> Void) { self.callback = callback }
  }

  private struct Delivery {
    let change: ScanSessionChange
    let closed: Bool
    let observers: [Observer]
  }

  private let lock: NSRecursiveLock
  private let dispatcher: (@escaping () -> Void) -> Void
  private var observers: [Observer] = []
  private var pending: [Delivery] = []
  private var scheduled = false
  private var order: [String] = []
  private var pages: [String: PageMetadata] = [:]
  private var version = 0.0
  private var closed = false
  private var cached: ScanSessionSnapshot?

  init(lock: NSRecursiveLock, dispatcher: @escaping (@escaping () -> Void) -> Void) {
    self.lock = lock
    self.dispatcher = dispatcher
  }

  /// Caller holds the lock.
  var pageIds: [String] { order }

  /// Caller holds the lock.
  var latest: ScanSessionSnapshot {
    if let cached { return cached }
    let snapshot = ScanSessionSnapshot(sourceVersion: version, closed: closed, pageIds: order,
      pages: Dictionary(uniqueKeysWithValues: pages.map { ($0.key, $0.value.nitro) }))
    cached = snapshot
    return snapshot
  }

  func updatePage(_ page: PageMetadata) {
    if pages[page.id] == page { return }
    if pages[page.id] == nil { order.append(page.id) }
    pages[page.id] = page
    enqueue(updated: [page.id: page.nitro], removed: [])
  }

  func setPageOrder(_ ids: [String]) throws {
    guard ids.count == order.count, Set(ids) == Set(order) else { throw ScannerCodeError.inputInvalid }
    if ids == order { return }
    order = ids
    enqueue(updated: [:], removed: [], order: ids)
  }

  func removePage(_ id: String) {
    guard pages.removeValue(forKey: id) != nil else { return }
    order.removeAll { $0 == id }
    enqueue(updated: [:], removed: [id])
  }

  func close() {
    if closed { return }
    closed = true
    pages.removeAll()
    order.removeAll()
    pending.removeAll()
    enqueue(updated: [:], removed: [])
    observers.removeAll()
  }

  private func enqueue(updated: [String: ScanPageMetadata], removed: [String], order: [String]? = nil) {
    cached = nil
    version += 1
    let change = ScanSessionChange(sourceVersion: version, closed: closed, updatedPages: updated,
                                   removedPageIds: removed, pageIds: order)
    if !observers.isEmpty { pending.append(Delivery(change: change, closed: closed, observers: observers)) }
  }

  /// Caller holds the lock: registration and the initial snapshot are atomic.
  func observe(_ callback: @escaping (ScanSessionChange) -> Void) -> Registration {
    let observer = Observer(callback)
    observers.append(observer)
    return Registration(snapshot: latest) { [weak self, lock] in
      lock.lock(); defer { lock.unlock() }
      observer.callback = nil
      self?.observers.removeAll { $0 === observer }
    }
  }

  /// Schedules ordered delivery after a state transition.
  func flush() {
    lock.lock()
    let start = !pending.isEmpty && !scheduled
    if start { scheduled = true }
    lock.unlock()
    if start { dispatcher { [self] in drain() } }
  }

  private func drain() {
    while true {
      lock.lock()
      guard !pending.isEmpty else { scheduled = false; lock.unlock(); return }
      let delivery = pending.removeFirst()
      lock.unlock()
      for observer in delivery.observers {
        // Claim the callback before a concurrent unsubscribe/close, then run unlocked.
        lock.lock()
        let callback = closed && !delivery.closed ? nil : observer.callback
        if delivery.closed { observer.callback = nil }
        lock.unlock()
        callback?(delivery.change)
      }
    }
  }
}
