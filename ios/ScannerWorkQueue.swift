import Foundation

/// Serial image FIFO shared by sessions. Admission, replacement and publication
/// decisions are locked; actions, settlement callbacks and close-barrier
/// continuations always run outside that lock.
///
/// A nil page ID is unkeyed work. Keyed replacement uses owner + kind + page ID,
/// removes only waiting entries and appends the replacement at the tail. Running
/// work is never interrupted, and owners never stop the shared worker.
final class ScannerWorkQueue: @unchecked Sendable {
  enum Kind { case capture, analysis, render, decode, pdf }

  final class Owner: @unchecked Sendable {
    let sessionId = UUID().uuidString.lowercased()
    let queue: ScannerWorkQueue
    // Guarded by queue.lock. Outstanding includes settlement callbacks still running.
    fileprivate var generation: UInt64 = 0
    fileprivate var closed = false
    fileprivate var outstanding = 0
    let completion = NativeCompletion()
    fileprivate init(queue: ScannerWorkQueue) { self.queue = queue }
  }

  private final class Entry {
    let owner: Owner
    let kind: Kind
    let pageId: String?
    var execute: () -> Void = {}
    var reject: (Error) -> Void = { _ in }
    init(owner: Owner, kind: Kind, pageId: String?) { self.owner = owner; self.kind = kind; self.pageId = pageId }
  }

  static let images = ScannerWorkQueue(worker: DispatchQueue(label: "document-scanner.images", qos: .userInitiated))

  private let worker: DispatchQueue
  private let lock = NSLock()
  private var waiting: [Entry] = []
  private var draining = false

  init(worker: DispatchQueue) { self.worker = worker }

  func newOwner() -> Owner { Owner(queue: self) }

  /// Callbacks own success/failure cleanup. onDiscard releases a successful result
  /// that can no longer publish; rejection and action failure go through onFailure.
  func submit<T>(_ owner: Owner, kind: Kind, pageId: String? = nil,
                 operation: @escaping () throws -> T,
                 onSuccess: @escaping (T) -> Void,
                 onFailure: @escaping (Error) -> Void,
                 onDiscard: @escaping (T) -> Void = { _ in }) {
    precondition(owner.queue === self, "Owner belongs to another queue.")
    let entry = Entry(owner: owner, kind: kind, pageId: pageId)
    var removed: [Entry] = []
    var start = false
    lock.lock()
    guard !owner.closed else {
      lock.unlock()
      onFailure(ScannerCodeError(code: .processorDisposed))
      return
    }
    let generation = owner.generation
    entry.execute = { [unowned self] in
      let result = Result { try operation() }
      // Linearization point: a publication claimed before close is committed.
      lock.lock()
      let publish = !owner.closed && owner.generation == generation
      lock.unlock()
      if !publish {
        if case .success(let value) = result { onDiscard(value) }
        onFailure(ScannerCodeError(code: .processorDisposed))
      } else {
        switch result {
        case .success(let value): onSuccess(value)
        case .failure(let error): onFailure(error)
        }
      }
      finished(owner)
    }
    entry.reject = { [unowned self] error in
      onFailure(error)
      finished(owner)
    }
    if let pageId {
      waiting.removeAll { candidate in
        let match = candidate.owner === owner && candidate.kind == kind && candidate.pageId == pageId
        if match { removed.append(candidate) }
        return match
      }
    }
    waiting.append(entry)
    owner.outstanding += 1
    if !draining { draining = true; start = true }
    lock.unlock()
    removed.forEach { $0.reject(ScannerCodeError(code: .processorSuperseded)) }
    if start { worker.async { [self] in drain() } }
  }

  /// Cancels this owner's waiting work; running actions finish and discard results
  /// claimed after close. The barrier completes after the owner's actions and
  /// callbacks finish. Never wait for it from an action or callback.
  @discardableResult
  func close(_ owner: Owner) -> NativeCompletion {
    precondition(owner.queue === self, "Owner belongs to another queue.")
    var removed: [Entry] = []
    lock.lock()
    if !owner.closed {
      owner.closed = true
      owner.generation += 1
      waiting.removeAll { candidate in
        let match = candidate.owner === owner
        if match { removed.append(candidate) }
        return match
      }
    }
    let idle = owner.outstanding == 0
    lock.unlock()
    removed.forEach { $0.reject(ScannerCodeError(code: .processorDisposed)) }
    if idle { owner.completion.complete() }
    return owner.completion
  }

  private func drain() {
    while true {
      lock.lock()
      guard !waiting.isEmpty else { draining = false; lock.unlock(); return }
      let entry = waiting.removeFirst()
      lock.unlock()
      entry.execute()
    }
  }

  private func finished(_ owner: Owner) {
    lock.lock()
    owner.outstanding -= 1
    let complete = owner.closed && owner.outstanding == 0
    lock.unlock()
    if complete { owner.completion.complete() }
  }
}
