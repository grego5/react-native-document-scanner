import Foundation

/// Owns one session's immutable files. Tokens never reach JS; every file user holds a
/// lease, and retirement rejects new leases immediately. Render, thumbnail and frame
/// files live in the session directory; originals stay in SourceStore, which retires
/// them physically once this session's last pin drains.
final class SessionResources: @unchecked Sendable {
  enum Kind: String { case original, frame, render, thumbnail }

  final class Resource: @unchecked Sendable {
    let id = UUID().uuidString.lowercased()
    let kind: Kind
    let owner: SessionResources
    let url: URL
    fileprivate let temporary: URL?
    // Guarded by owner.lock.
    fileprivate var owned = true
    fileprivate var published: Bool
    fileprivate var pins: Int
    fileprivate var cleanupScheduled = false
    let deleted = NativeCompletion()

    fileprivate init(kind: Kind, owner: SessionResources, url: URL, temporary: URL?, published: Bool, pins: Int) {
      self.kind = kind; self.owner = owner; self.url = url; self.temporary = temporary
      self.published = published; self.pins = pins
    }
  }

  final class Lease: @unchecked Sendable {
    let resource: Resource
    private let lock = NSLock()
    private var released = false
    var url: URL { resource.url }
    fileprivate init(_ resource: Resource) { self.resource = resource }
    deinit { close() }
    func close() {
      lock.lock()
      let release = !released
      released = true
      lock.unlock()
      if release { resource.owner.unpin(resource) }
    }
  }

  /// Single-producer transaction: write `url`, then commit. Closing without a commit
  /// retires the output, and a committed output can still be discarded when stale.
  final class Output: @unchecked Sendable {
    let resource: Resource
    private var ended = false
    private var committed = false
    var url: URL { resource.temporary! }
    fileprivate init(_ resource: Resource) { self.resource = resource }
    deinit { close() }

    @discardableResult
    func commit() throws -> Resource {
      let owner = resource.owner
      try owner.locked {
        guard !ended, !committed else { throw ScannerCodeError.inputInvalid }
        try owner.requireOpen()
        guard resource.owned else { throw ScannerCodeError(code: .processorSuperseded) }
      }
      guard !FileManager.default.fileExists(atPath: resource.url.path) else { throw ScannerCodeError(code: .storageWriteFailed) }
      do { try FileManager.default.moveItem(at: url, to: resource.url) }
      catch { throw ScannerCodeError(code: .storageWriteFailed) }
      try owner.locked {
        // Close may race encoding or the move; the producer pin still protects both paths.
        try owner.requireOpen()
        guard resource.owned else { throw ScannerCodeError(code: .processorSuperseded) }
        resource.published = true
        committed = true
      }
      return resource
    }

    func discard() {
      resource.owner.locked { resource.owned = false }
      resource.owner.cleanupReady()
    }

    func close() {
      let owner = resource.owner
      let ending: Bool = owner.locked {
        if ended { return false }
        ended = true
        if !committed { resource.owned = false }
        resource.pins -= 1
        return true
      }
      if ending { owner.cleanupReady() }
    }
  }

  final class RenderDestination {
    let image: Output
    let thumbnail: Output
    fileprivate init(image: Output, thumbnail: Output) { self.image = image; self.thumbnail = thumbnail }
    func discard() { image.discard(); thumbnail.discard() }
    func close() { thumbnail.close(); image.close() }
  }

  let registry: ScanSessionRegistry
  let directory: URL
  let workOwner: ScannerWorkQueue.Owner
  var sessionId: String { workOwner.sessionId }
  private let lock = NSLock()
  private var records: [ObjectIdentifier: Resource] = [:]
  private var closed = false
  private var workDrained = false
  private var directoryCleanupScheduled = false
  private var failure: Error?
  private let completion = NativeCompletion()

  init(registry: ScanSessionRegistry, directory: URL, workOwner: ScannerWorkQueue.Owner) {
    self.registry = registry; self.directory = directory; self.workOwner = workOwner
  }

  fileprivate func locked<T>(_ action: () throws -> T) rethrows -> T {
    lock.lock(); defer { lock.unlock() }
    return try action()
  }

  fileprivate func requireOpen() throws {
    if closed { throw ScannerCodeError(code: .processorDisposed) }
  }

  func output(_ kind: Kind, extension ext: String = "jpg") throws -> Output {
    guard kind != .original, ["jpg", "png"].contains(ext) else { throw ScannerCodeError.inputInvalid }
    let stem = "\(kind.rawValue)-\(UUID().uuidString.lowercased())"
    let resource = Resource(kind: kind, owner: self, url: directory.appendingPathComponent("\(stem).\(ext)"),
                            temporary: directory.appendingPathComponent(".\(stem).tmp.\(ext)"), published: false, pins: 1)
    try locked {
      try requireOpen()
      records[ObjectIdentifier(resource)] = resource
    }
    return Output(resource)
  }

  func renderDestination(extension ext: String) throws -> RenderDestination {
    let image = try output(.render, extension: ext)
    do { return RenderDestination(image: image, thumbnail: try output(.thumbnail)) }
    catch { image.close(); throw error }
  }

  /// Transfers a SourceStore-published original into this session. Only one session
  /// can claim a source; the claim ends when this session retires it.
  func adoptSource(_ url: URL) throws -> Resource {
    let key = url.standardizedFileURL
    guard registry.store.owns(key) else { throw ScannerCodeError.inputInvalid }
    try registry.claimSource(key)
    let resource = Resource(kind: .original, owner: self, url: key, temporary: nil, published: true, pins: 0)
    do {
      try locked {
        try requireOpen()
        records[ObjectIdentifier(resource)] = resource
      }
    } catch { registry.releaseSource(key); throw error }
    return resource
  }

  func lease(_ resource: Resource) throws -> Lease {
    precondition(resource.owner === self, "Resource belongs to another session.")
    return try locked {
      try requireOpen()
      guard resource.owned, resource.published else { throw ScannerCodeError.inputInvalid }
      resource.pins += 1
      return Lease(resource)
    }
  }

  /// Nonblocking retirement; `deleted` completes after the final pin and physical removal.
  @discardableResult
  func remove(_ resource: Resource) -> NativeCompletion {
    precondition(resource.owner === self, "Resource belongs to another session.")
    locked { resource.owned = false }
    cleanupReady()
    return resource.deleted
  }

  fileprivate func unpin(_ resource: Resource) {
    locked { resource.pins -= 1 }
    cleanupReady()
  }

  /// Idempotent and nonblocking. The directory stays registered through running work,
  /// leases and every removal; failures reach the returned barrier.
  @discardableResult
  func close() -> NativeCompletion {
    let first: Bool = locked {
      if closed { return false }
      closed = true
      records.values.forEach { $0.owned = false }
      return true
    }
    guard first else { return completion }
    registry.queue.close(workOwner).whenComplete { [self] _ in
      locked { workDrained = true }
      cleanupReady()
    }
    cleanupReady()
    return completion
  }

  fileprivate func cleanupReady() {
    let (ready, directoryReady): ([Resource], Bool) = locked {
      let ready = records.values.filter { !$0.owned && $0.pins == 0 && !$0.cleanupScheduled }
      ready.forEach { $0.cleanupScheduled = true }
      let directoryReady = closed && workDrained && records.isEmpty && !directoryCleanupScheduled
      if directoryReady { directoryCleanupScheduled = true }
      return (ready, directoryReady)
    }
    for resource in ready {
      if resource.kind == .original {
        registry.cleanup.async { [self] in
          registry.store.retire(resource.url) { [self] error in
            if error == nil { registry.releaseSource(resource.url) }
            registry.cleanup.async { [self] in finishRemoval(resource, error) }
          }
        }
      } else {
        registry.cleanup.async { [self] in
          var failure: Error?
          for url in [resource.temporary, resource.url].compactMap({ $0 }) {
            do { try FileManager.default.removeItem(at: url) }
            catch let error as CocoaError where error.code == .fileNoSuchFile {}
            catch { failure = failure ?? error }
          }
          finishRemoval(resource, failure)
        }
      }
    }
    if directoryReady {
      registry.cleanup.async { [self] in
        do {
          try registry.release(self)
          completion.complete(locked { failure })
        } catch {
          registry.report(error)
          completion.complete(ScannerCodeError(code: .cacheDeleteFailed))
        }
      }
    }
  }

  private func finishRemoval(_ resource: Resource, _ error: Error?) {
    locked {
      records.removeValue(forKey: ObjectIdentifier(resource))
      // Final directory removal recovers session files; a source is outside it.
      if error != nil, resource.kind == .original, failure == nil { failure = ScannerCodeError(code: .cacheDeleteFailed) }
    }
    if let error { registry.report(error) }
    resource.deleted.complete(error)
    cleanupReady()
  }
}
