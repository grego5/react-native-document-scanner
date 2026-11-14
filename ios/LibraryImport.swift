import Foundation

/// One platform picker request, which owns its slot and the platform cleanup.
protocol PickerRequest: AnyObject, Sendable {
  /// Completes once this request no longer holds the one-picker slot.
  var pickerReleased: NativeCompletion { get }
  /// Ends the request on main: an open picker is dismissed and frees its slot once gone. Picks already being staged
  /// are still delivered, so their owner can delete them.
  func end()
}

/// One `importFromLibrary` call, from presentation through admission. It owns every staged file handed to it, submits
/// picks in picked order through `admit`, so the session's serial image worker keeps that order, and settles
/// `completion` once: with the admitted page IDs in picked order once every pick has settled, on a picker or
/// destination failure, or with `processor/disposed` when the session closes first. Unreadable picks are skipped;
/// cancelling or admitting none resolves `[]`.
///
/// Its close registration decides who settles: a normal outcome claims it with `tryUnregister()`, and once close has
/// taken it only the close cancellation settles. The registration stays until the picker has released its slot, and
/// close awaits that release.
final class LibraryImport: @unchecked Sendable {
  typealias AdmitPickedImage = (URL, @escaping (Result<String, Error>) -> Void) -> Void
  /// Presents the picker, which reports each pick's staged file, or why it has none, at most once. Throws when the
  /// launch failed, and then no request holds the slot.
  typealias PresentPicker = (@escaping (Result<[Result<URL, Error>], Error>) -> Void) throws -> PickerRequest
  typealias Register = (@escaping @Sendable () -> NativeCompletion) throws -> CloseRegistration
  private let lock = NSLock()
  private let maxCount: Int
  private let discard: (URL) -> Void
  private let post: (@escaping @Sendable () -> Void) -> Void
  private let admit: AdmitPickedImage
  private let completion: (Result<[String], Error>) -> Void
  /// Retains the session, so it is dropped once the call is settled.
  private var closeRegistration: CloseRegistration?
  /// The result is delivered, or left to the close cancellation; no picker may open any more.
  private var settled = false
  private var presenting = false
  private var pickerRequest: PickerRequest?
  /// Completes once no picker request of this call holds the slot.
  private let pickerReleased = NativeCompletion()

  init(maxCount: Int,
       discard: @escaping (URL) -> Void = { try? FileManager.default.removeItem(at: $0) },
       post: @escaping (@escaping @Sendable () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) },
       admit: @escaping AdmitPickedImage,
       completion: @escaping (Result<[String], Error>) -> Void) {
    self.maxCount = maxCount; self.discard = discard; self.post = post
    self.admit = admit; self.completion = completion
  }

  /// `register` ties the call to its session's close; `present` then runs through `post` unless close has taken the
  /// registration first.
  func start(register: Register, present: @escaping PresentPicker) {
    let registration: CloseRegistration
    do {
      registration = try register { [self] in cancelForClose() }
    } catch {
      // No cancellation was installed, so nothing else settles the call.
      return completion(.failure(error))
    }
    // Close may already have cancelled the call, which then holds no registration and presents nothing.
    lock.withLock { if !settled { closeRegistration = registration } }
    nonisolated(unsafe) let present = present
    post { [self] in open(present) }
  }

  /// Presents only while still registered; close that takes the registration later waits for the request it makes.
  private func open(_ present: PresentPicker) {
    guard lock.withLock({ () -> Bool in
      presenting = !settled && closeRegistration?.isRegistered == true
      return presenting
    }) else { return }
    let request: PickerRequest
    do {
      request = try present { [self] in picked($0) }
    } catch {
      lock.withLock { presenting = false }
      // The launch failed without holding the slot.
      pickerReleased.complete()
      return complete(.failure(error))
    }
    let ending = lock.withLock { () -> Bool in
      presenting = false
      pickerRequest = request
      return settled
    }
    request.pickerReleased.whenComplete { [self] _ in
      // The request's result callback retains this call, so holding the request past its release would keep both
      // alive. The callback itself stays with the picker until any late staged files arrive and are deleted.
      lock.withLock { pickerRequest = nil }
      pickerReleased.complete()
    }
    // Cancellation arrived while the presenter ran: end the request it returned, here on main.
    if ending { request.end() }
  }

  private func picked(_ result: Result<[Result<URL, Error>], Error>) {
    let staged: [Result<URL, Error>]
    switch result {
    case .failure(let error): return complete(.failure(error))
    case .success(let picked): staged = picked
    }
    // The picker honours the limit; any pick past it is dropped like the Android fallback's.
    let picks = maxCount > 0 ? Array(staged.prefix(maxCount)) : staged
    staged.dropFirst(picks.count).compactMap { try? $0.get() }.forEach(discard)
    // Close may have taken the registration without running its cancellation yet; that cancellation settles the call.
    if lock.withLock({ settled || closeRegistration?.isRegistered != true }) {
      return picks.compactMap { try? $0.get() }.forEach(discard)
    }
    if picks.isEmpty { return complete(.success([])) }
    var outcomes = [Result<String, Error>?](repeating: nil, count: picks.count)
    var remaining = picks.count
    for (index, pick) in picks.enumerated() {
      let url = try? pick.get()
      let done = { [self] (outcome: Result<String, Error>) in
        if let url { discard(url) }
        let finished: Bool = lock.withLock {
          outcomes[index] = outcome
          remaining -= 1
          return remaining == 0
        }
        guard finished else { return }
        complete(lock.withLock { Self.outcome(outcomes.compactMap { $0 }) })
      }
      switch pick {
      case .success(let url): admit(url, done)
      case .failure(let error): done(.failure(error))
      }
    }
  }

  /// The admitted IDs in picked order, skipping unreadable picks; any other failure rejects the call while the pages
  /// already admitted stay in the session.
  private static func outcome(_ outcomes: [Result<String, Error>]) -> Result<[String], Error> {
    var ids: [String] = []
    for outcome in outcomes {
      switch outcome {
      case .success(let id): ids.append(id)
      case .failure(let error) where ScannerCodeError.code(error, fallback: .processorUnavailable) == .imageLoadFailed: continue
      case .failure(let error): return .failure(error)
      }
    }
    return .success(ids)
  }

  /// A normal outcome, delivered once the picker has released its slot and only if it claims the registration first;
  /// otherwise the close cancellation owns settlement. The picker dismisses itself before it reports, so nothing is
  /// ended here.
  private func complete(_ result: Result<[String], Error>) {
    pickerReleased.whenComplete { [self] _ in
      let claimed = lock.withLock { () -> Bool in
        guard !settled, closeRegistration?.tryUnregister() == true else { return false }
        settled = true
        // The registration retains the session; a settled call has no use for it.
        closeRegistration = nil
        return true
      }
      if claimed { completion(result) }
    }
  }

  /// The close cancellation: settles with `processor/disposed`, ends this call's picker and returns its release, which
  /// close awaits. A presenter still running ends the request it returns; with no request, nothing holds the slot.
  private func cancelForClose() -> NativeCompletion {
    let (presenter, request) = lock.withLock { () -> (Bool, PickerRequest?) in
      settled = true
      closeRegistration = nil
      return (presenting, pickerRequest)
    }
    if !presenter {
      if let request { post { request.end() } } else { pickerReleased.complete() }
    }
    completion(.failure(ScannerCodeError(code: .processorDisposed)))
    return pickerReleased
  }
}
