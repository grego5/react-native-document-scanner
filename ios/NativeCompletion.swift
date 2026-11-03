import Foundation

/// One-shot native barrier. The first completion wins; callbacks run once, outside
/// the lock, on the completing thread (or immediately when already complete).
final class NativeCompletion: @unchecked Sendable {
  private let lock = NSLock()
  private var outcome: Result<Void, Error>?
  private var waiters: [(Error?) -> Void] = []

  var isComplete: Bool { lock.lock(); defer { lock.unlock() }; return outcome != nil }

  func complete(_ error: Error? = nil) {
    lock.lock()
    guard outcome == nil else { lock.unlock(); return }
    outcome = error.map { .failure($0) } ?? .success(())
    let waiters = self.waiters
    self.waiters = []
    lock.unlock()
    waiters.forEach { $0(error) }
  }

  func whenComplete(_ callback: @escaping (Error?) -> Void) {
    lock.lock()
    guard let outcome else { waiters.append(callback); lock.unlock(); return }
    lock.unlock()
    if case .failure(let error) = outcome { callback(error) } else { callback(nil) }
  }

  /// Completes after every input; the first failure in argument order is reported.
  static func all(_ items: [NativeCompletion]) -> NativeCompletion {
    let result = NativeCompletion()
    let lock = NSLock()
    var remaining = items.count
    var errors = [Error?](repeating: nil, count: items.count)
    if items.isEmpty { result.complete() }
    for (index, item) in items.enumerated() {
      item.whenComplete { error in
        lock.lock()
        errors[index] = error
        remaining -= 1
        let done = remaining == 0
        lock.unlock()
        if done { result.complete(errors.compactMap { $0 }.first) }
      }
    }
    return result
  }
}
