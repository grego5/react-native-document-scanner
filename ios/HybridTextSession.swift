import Foundation
import NitroModules

/// What a text camera does for the session it shows.
protocol TextSessionCamera: AnyObject {
  /// Takes a capture for `session` within `timeout` milliseconds, calling `done` once with it, or with the failure's
  /// code. Any thread.
  func capture(for session: HybridTextSession, timeout: Int, done: @escaping @Sendable (TextCapture?, ScannerErrorCode?) -> Void)
}

/// One text scan, apart from any camera view: the capture being picked from and the attached camera's status. It
/// outlives the views that show it, so a remounted camera finds the capture still there; one camera shows it at a time.
/// Every change publishes a whole snapshot with a new version, delivered outside the lock, so an observer that sees an
/// older version after a newer one drops it. Any thread.
final class HybridTextSession: HybridTextSessionSpec, @unchecked Sendable {
  private let lock = NSLock()
  private var version = 0
  private var closed = false
  private weak var camera: (any TextSessionCamera)?
  private var status: TextScanStatus = .off
  private var capture: TextCapture?
  private var nextObserver = 0
  private var observers: [Int: (TextSessionSnapshot) -> Void] = [:]
  /// Captures not yet settled; close fails them, so a later result finds them gone.
  private var waiting: [ObjectIdentifier: Promise<Variant_ScannerFailure_TextSessionSnapshot>] = [:]

  /// Shows the session in `camera`; false while another camera shows it.
  func attach(_ camera: any TextSessionCamera) -> Bool {
    var attached = true
    change {
      if self.camera === camera { return false }
      if self.camera != nil { attached = false; return false }
      self.camera = camera
      return true
    }
    return attached
  }

  func detach(_ camera: any TextSessionCamera) {
    change {
      guard self.camera === camera else { return false }
      self.camera = nil
      self.status = .off
      return true
    }
  }

  /// The attached `camera`'s scan does `status`.
  func show(_ camera: any TextSessionCamera, status: TextScanStatus) {
    change {
      guard self.camera === camera, self.status != status else { return false }
      self.status = status
      return true
    }
  }

  func captureResult(timeout: Double?) throws -> Promise<Variant_ScannerFailure_TextSessionSnapshot> {
    lock.lock()
    if closed {
      lock.unlock()
      return .resolved(withResult: .first(ScannerFailure(code: .processorDisposed)))
    }
    guard let camera else {
      lock.unlock()
      return .resolved(withResult: .first(ScannerFailure(code: .cameraUnavailable)))
    }
    let promise = Promise<Variant_ScannerFailure_TextSessionSnapshot>()
    let id = ObjectIdentifier(promise)
    waiting[id] = promise
    lock.unlock()
    // Milliseconds.
    let wait = Int(min(max(timeout ?? 2_000, 0), 30_000))
    camera.capture(for: self, timeout: wait) { [weak self] taken, code in
      guard let self else { return }
      self.lock.lock()
      guard let promise = self.waiting.removeValue(forKey: id) else {
        self.lock.unlock()
        return
      }
      var settled: TextSessionSnapshot?
      var to: [(TextSessionSnapshot) -> Void] = []
      if let taken {
        self.capture = taken
        self.version += 1
        settled = self.snapshotLocked()
        to = Array(self.observers.values)
      }
      self.lock.unlock()
      if let settled {
        to.forEach { $0(settled) }
        promise.resolve(withResult: .second(settled))
      } else {
        promise.resolve(withResult: .first(ScannerFailure(code: code ?? .captureFailed)))
      }
    }
    return promise
  }

  func clearCapture() throws {
    change {
      guard capture != nil else { return false }
      capture = nil
      return true
    }
  }

  func getSnapshot() throws -> TextSessionSnapshot {
    lock.lock()
    defer { lock.unlock() }
    return snapshotLocked()
  }

  func observeResult(onChange: @escaping (_ snapshot: TextSessionSnapshot) -> Void) throws -> Variant_TextSessionObservation_ScannerFailure {
    lock.lock()
    let id = nextObserver
    nextObserver += 1
    if !closed { observers[id] = onChange }
    let snapshot = snapshotLocked()
    lock.unlock()
    let unsubscribe: () -> Void = { [weak self] in
      guard let self else { return }
      self.lock.lock()
      self.observers[id] = nil
      self.lock.unlock()
    }
    return .first(TextSessionObservation(snapshot: snapshot, unsubscribe: unsubscribe))
  }

  func closeResult() throws -> Promise<Variant_Bool_ScannerFailure> {
    lock.lock()
    if closed {
      lock.unlock()
      return .resolved(withResult: .first(true))
    }
    closed = true
    camera = nil
    status = .off
    capture = nil
    version += 1
    let failed = Array(waiting.values)
    waiting = [:]
    let snapshot = snapshotLocked()
    let to = Array(observers.values)
    observers = [:]
    lock.unlock()
    failed.forEach { $0.resolve(withResult: .first(ScannerFailure(code: .processorDisposed))) }
    to.forEach { $0(snapshot) }
    return .resolved(withResult: .first(true))
  }

  /// Runs `body` under the lock and publishes a new version when it returns true; a closed session changes no more.
  private func change(_ body: () -> Bool) {
    lock.lock()
    guard !closed, body() else {
      lock.unlock()
      return
    }
    version += 1
    let snapshot = snapshotLocked()
    let to = Array(observers.values)
    lock.unlock()
    to.forEach { $0(snapshot) }
  }

  private func snapshotLocked() -> TextSessionSnapshot {
    TextSessionSnapshot(version: Double(version), closed: closed, status: status, capture: capture)
  }
}
