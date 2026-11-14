import Foundation

/// The app-wide corner model's state. Selections stay native.
enum CornerModelStatus: @unchecked Sendable {
  case absent
  case loading(source: String)
  case ready(source: String, selection: CornerInference.Selection)
  case failed(source: String, code: ScannerErrorCode)
}

/// The app-wide model loader. File reads and validation run on one background worker, and only the newest request
/// may activate its model. A request for the source already loading or ready joins it. The plugin default never
/// replaces a requested source and follows whichever request replaces it.
final class CornerModelLoader: @unchecked Sendable {
  static let shared = CornerModelLoader()
  /// The app bundle resource the Expo plugin installs `cornerDetectionModel` as.
  static let defaultSource = "asset:///corner-detection-model.tflite"
  static let bundledDefault: String? = (try? url(defaultSource)) == nil ? nil : defaultSource

  private typealias Done = (ScannerErrorCode?) -> Void

  /// A `follows` waiter moves to a replacing request instead of being superseded.
  private struct Waiter {
    let follows: Bool
    let done: Done
  }

  /// Calls never overlap and run without a lock; a status that arrives during a call waits as the latest.
  private final class Observer: @unchecked Sendable {
    private let lock = NSLock()
    private let listener: (CornerModelStatus) -> Void
    private var delivered = -1
    private var latest: (version: Int, status: CornerModelStatus)?
    private var draining = false
    private var removed = false
    init(_ listener: @escaping (CornerModelStatus) -> Void) { self.listener = listener }
    func remove() { lock.withLock { removed = true } }
    func deliver(_ version: Int, _ status: CornerModelStatus) {
      let starts: Bool = lock.withLock {
        guard version > (latest?.version ?? delivered) else { return false }
        latest = (version, status)
        guard !draining else { return false }
        draining = true
        return true
      }
      guard starts else { return }
      while let next = lock.withLock({ () -> CornerModelStatus? in
        defer { latest = nil }
        guard let pending = latest, !removed else { draining = false; return nil }
        delivered = pending.version
        return pending.status
      }) {
        listener(next)
      }
    }
  }

  private let inference: CornerInference
  private let defaultSource: String?
  private let worker = DispatchQueue(label: "ReactNativeDocumentScanner.models", qos: .userInitiated)
  private let lock = NSLock()
  private var request = 0
  private var version = 0
  private var current: CornerModelStatus = .absent
  /// Callbacks of the loading request: nil once it is active, else its code.
  private var waiters: [Waiter] = []
  private var observers: [ObjectIdentifier: Observer] = [:]

  init(inference: CornerInference = .shared, defaultSource: String? = CornerModelLoader.bundledDefault) {
    self.inference = inference
    self.defaultSource = defaultSource
  }

  var status: CornerModelStatus { lock.withLock { current } }

  /// An explicit source: replaces any other source. Completions arrive on main, once.
  func load(_ source: String, completion: @escaping (ScannerErrorCode?) -> Void) {
    request(source, replace: true, Self.onMain(completion))
  }

  /// Starts the packaged default, if the app contains it, with nobody waiting on it; see `loadCameraSource`.
  func startDefault() {
    if let defaultSource { request(defaultSource, replace: false, nil) }
  }

  /// A camera's source: any non-empty one is explicit. Empty follows the plugin default, which must exist: it starts
  /// only when nothing was requested yet, otherwise joins the current request, and a request that replaces it
  /// completes in its place. Completions arrive on main, once.
  func loadCameraSource(_ source: String, completion: @escaping (ScannerErrorCode?) -> Void) {
    guard source.isEmpty else { return load(source, completion: completion) }
    guard let defaultSource else { return Self.onMain(completion)(.inputInvalid) }
    request(defaultSource, replace: false, Self.onMain(completion))
  }

  /// Reports the current status, then the latest status after changes; a burst can collapse into its last status.
  /// Calls never overlap and may request loads. The returned removal is idempotent.
  func observe(_ listener: @escaping (CornerModelStatus) -> Void) -> () -> Void {
    let observer = Observer(listener)
    let (at, snapshot) = lock.withLock { observers[ObjectIdentifier(observer)] = observer; return (version, current) }
    observer.deliver(at, snapshot)
    return { [self] in
      observer.remove()
      lock.withLock { observers[ObjectIdentifier(observer)] = nil }
    }
  }

  /// `true` once the current request is active, `false` without any request; fails with its code or supersession.
  /// `completion` runs once, on any thread.
  func wait(_ completion: @escaping (Result<Bool, ScannerCodeError>) -> Void) {
    lock.lock()
    let settled: Result<Bool, ScannerCodeError>?
    switch current {
    case .absent: settled = .success(false)
    case .ready: settled = .success(true)
    case .failed(_, let code): settled = .failure(ScannerCodeError(code: code))
    case .loading:
      settled = nil
      waiters.append(Waiter(follows: false) { code in
        completion(code.map { .failure(ScannerCodeError(code: $0)) } ?? .success(true))
      })
    }
    lock.unlock()
    if let settled { completion(settled) }
  }

  /// A nil `done` starts or joins the request without waiting on it.
  private func request(_ source: String, replace: Bool, _ done: Done?) {
    let waiter = done.map { Waiter(follows: !replace, done: $0) }
    lock.lock()
    let joined: ScannerErrorCode??
    switch current {
    case .loading(let loading) where !replace || loading == source:
      if let waiter { waiters.append(waiter) }
      lock.unlock()
      return
    case .ready(let ready, _) where !replace || ready == source: joined = .some(nil)
    case .failed(_, let code) where !replace: joined = .some(code)
    default: joined = nil
    }
    if let joined {
      lock.unlock()
      done?(joined)
      return
    }
    let superseded = waiters.filter { !$0.follows }
    waiters = waiters.filter(\.follows) + (waiter.map { [$0] } ?? [])
    request += 1
    let id = request
    let update = publish(.loading(source: source))
    lock.unlock()
    superseded.forEach { $0.done(.modelSuperseded) }
    notify(update)
    worker.async { [self] in run(id, source) }
  }

  private func run(_ id: Int, _ source: String) {
    guard lock.withLock({ id == request }) else { return }
    var settled: (done: [Waiter], update: (Int, CornerModelStatus))?
    let code: ScannerErrorCode?
    do {
      let data = try Data(contentsOf: try Self.url(source))
      // Ownership is checked and recorded under the request lock at the activation boundary, so a newer request
      // can never be outlived by an older model becoming the selection.
      try inference.replace(data: data) { [self] selection in
        lock.withLock {
          guard request == id else { return false }
          settled = (take(), publish(.ready(source: source, selection: selection)))
          return true
        }
      }
      code = nil
    } catch CornerInferenceError.staleSelection {
      return
    } catch let error as ScannerCodeError {
      code = error.code
    } catch {
      code = .modelLoadFailed
    }
    if let code {
      settled = lock.withLock {
        id == request ? (take(), publish(.failed(source: source, code: code))) : nil
      }
    }
    guard let settled else { return }
    notify(settled.update)
    settled.done.forEach { $0.done(code) }
  }

  /// Caller holds `lock`.
  private func take() -> [Waiter] {
    defer { waiters = [] }
    return waiters
  }

  /// Caller holds `lock`.
  private func publish(_ next: CornerModelStatus) -> (Int, CornerModelStatus) {
    current = next
    version += 1
    return (version, next)
  }

  private func notify(_ update: (Int, CornerModelStatus)) {
    lock.withLock { Array(observers.values) }.forEach { $0.deliver(update.0, update.1) }
  }

  private static func onMain(_ completion: @escaping (ScannerErrorCode?) -> Void) -> Done {
    nonisolated(unsafe) let completion = completion
    return { code in DispatchQueue.main.async { completion(code) } }
  }

  static func url(_ source: String) throws -> URL {
    guard !source.isEmpty else { throw ScannerCodeError.inputInvalid }
    if source.hasPrefix("asset:///") {
      let path = String(source.dropFirst("asset:///".count))
      guard !path.isEmpty, !path.split(separator: "/").contains(".."),
            let model = Bundle.main.resourceURL?.appendingPathComponent(path),
            FileManager.default.fileExists(atPath: model.path) else {
        throw ScannerCodeError(code: .modelLoadFailed)
      }
      return model
    }
    if source.hasPrefix("/") { return URL(fileURLWithPath: source) }
    if let url = URL(string: source), url.scheme != nil {
      guard url.isFileURL else { throw ScannerCodeError.inputInvalid }
      return url
    }
    let name = source as NSString
    guard let url = Bundle.main.url(forResource: name.deletingPathExtension,
                                    withExtension: name.pathExtension.isEmpty ? nil : name.pathExtension) else {
      throw ScannerCodeError(code: .modelLoadFailed)
    }
    return url
  }
}
