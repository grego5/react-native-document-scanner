import Foundation

/// Process-wide session directory protection, independent of JS contexts. States stay
/// registered until close finishes deleting their files, not until JS releases them.
final class ScanSessionRegistry: @unchecked Sendable {
  static let shared: ScanSessionRegistry = {
    let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    return ScanSessionRegistry(root: caches.appendingPathComponent("DocumentScannerSessions", isDirectory: true),
                               store: .shared, queue: .images)
  }()

  private static let prefix = "session-"

  let root: URL
  let store: SourceStore
  let queue: ScannerWorkQueue
  /// Serial file-removal owner; never the image worker.
  let cleanup = DispatchQueue(label: "document-scanner.session-cleanup")
  private let reportFailure: (Error) -> Void
  private let lock = NSLock()
  private var registered: [URL: SessionResources] = [:]
  private var cleaning = Set<URL>()
  private var sources = Set<URL>()
  private var states: [String: ScanSessionState] = [:]

  init(root: URL, store: SourceStore, queue: ScannerWorkQueue,
       report: @escaping (Error) -> Void = { NSLog("DocumentScanner session cleanup failed: %@", String(describing: $0)) }) {
    self.root = Self.canonical(root)
    self.store = store
    self.queue = queue
    reportFailure = report
  }

  /// Resolves the existing prefix, so not-yet-created outputs compare as their aliases.
  static func canonical(_ url: URL) -> URL {
    let standard = url.standardizedFileURL
    var existing = standard
    var suffix: [String] = []
    while !FileManager.default.fileExists(atPath: existing.path), existing.path != "/" {
      suffix.insert(existing.lastPathComponent, at: 0)
      existing.deleteLastPathComponent()
    }
    return suffix.reduce(existing.resolvingSymlinksInPath()) { $0.appendingPathComponent($1) }
  }

  func open() throws -> SessionResources {
    let owner = queue.newOwner()
    let directory = root.appendingPathComponent(Self.prefix + owner.sessionId, isDirectory: true)
    let resources = SessionResources(registry: self, directory: directory, workOwner: owner)
    try locked {
      guard registered[directory] == nil, !cleaning.contains(directory) else { throw ScannerCodeError(code: .processorUnavailable) }
      registered[directory] = resources
    }
    // Registration precedes mkdir and every operation that might create an output.
    do {
      try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
      guard Self.canonical(root).path == root.path else { throw ScannerCodeError.inputInvalid }
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
    } catch {
      resources.close()
      throw ScannerCodeError(code: .storageWriteFailed)
    }
    cleanupAbandoned()
    return resources
  }

  /// Explicit lifetime: the state survives JS wrapper collection until close cleans up.
  func createState(dispatcher: @escaping (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) },
                   actions: (SessionResources) -> ScanSessionActions = { SessionPageProcessing(resources: $0) }) throws -> ScanSessionState {
    let resources = try open()
    let state = ScanSessionState(resources: resources, actions: actions(resources), dispatcher: dispatcher)
    locked { states[resources.sessionId] = state }
    return state
  }

  /// Every session start sweeps abandoned generated session directories and stranded
  /// store originals. Registered and failed-close directories and this process's
  /// sources stay protected; nothing else under either root is touched.
  @discardableResult
  func cleanupAbandoned() -> NativeCompletion {
    let sources = NativeCompletion()
    store.recoverAbandoned { [self] error in
      if let error { report(error) }
      sources.complete(error)
    }
    let completion = NativeCompletion()
    cleanup.async { [self] in
      let children: [URL]
      do {
        children = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isSymbolicLinkKey, .isDirectoryKey])
      } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
        return completion.complete()
      } catch { report(error); return completion.complete(error) }
      var failure: Error?
      for child in children {
        let name = child.lastPathComponent
        guard name.hasPrefix(Self.prefix), let id = UUID(uuidString: String(name.dropFirst(Self.prefix.count))),
              id.uuidString.lowercased() == String(name.dropFirst(Self.prefix.count)),
              let values = try? child.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey]),
              values.isSymbolicLink != true, values.isDirectory == true else { continue }
        let directory = root.appendingPathComponent(name, isDirectory: true)
        let claimed = locked { registered[directory] == nil && cleaning.insert(directory).inserted }
        guard claimed else { continue }
        do { try FileManager.default.removeItem(at: directory) }
        catch { report(error); failure = failure ?? error }
        _ = locked { cleaning.remove(directory) }
      }
      completion.complete(failure)
    }
    return NativeCompletion.all([completion, sources])
  }

  /// Exports never target session directories (registered or reserved) or source
  /// files, including case and symbolic-link aliases.
  func requireExternalOutput(_ url: URL) throws {
    let path = Self.canonical(url).path.lowercased()
    for protected in [root, Self.canonical(store.directory)] {
      let base = protected.path.lowercased()
      if path == base || path.hasPrefix(base + "/") { throw ScannerCodeError.inputInvalid }
    }
  }

  func claimSource(_ url: URL) throws {
    try locked { guard sources.insert(url).inserted else { throw ScannerCodeError.inputInvalid } }
  }

  func releaseSource(_ url: URL) { _ = locked { sources.remove(url) } }

  /// Removes the session directory, then its registration. A failure keeps the
  /// directory protected and reaches that session's close result.
  func release(_ resources: SessionResources) throws {
    let directory = resources.directory
    do {
      let values = try directory.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey])
      guard values.isSymbolicLink != true, values.isDirectory == true else { throw ScannerCodeError(code: .cacheDeleteFailed) }
      try FileManager.default.removeItem(at: directory)
    } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {}
    locked {
      if registered[directory] === resources { registered.removeValue(forKey: directory) }
      states.removeValue(forKey: resources.sessionId)
    }
  }

  func report(_ error: Error) { reportFailure(error) }

  private func locked<T>(_ action: () throws -> T) rethrows -> T {
    lock.lock(); defer { lock.unlock() }
    return try action()
  }
}
