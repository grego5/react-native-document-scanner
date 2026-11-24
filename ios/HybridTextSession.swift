import Foundation
import NitroModules

/// One text scan, apart from any camera view: the attached camera and its status, and the capture being picked
/// from. Capturing text is Android only for now, so `captureResult` reports `feature/unsupported` while open. Every
/// change publishes a whole snapshot with a new version, delivered outside the lock. Any thread.
final class HybridTextSession: HybridTextSessionSpec, @unchecked Sendable {
  private let lock = NSLock()
  private var version = 0
  private var closed = false
  private weak var camera: AnyObject?
  private var status: TextScanStatus = .off
  private var nextObserver = 0
  private var observers: [Int: (TextSessionSnapshot) -> Void] = [:]

  /// Shows the session in `camera`; false while another camera shows it.
  func attach(_ camera: AnyObject) -> Bool {
    var attached = true
    change {
      if self.camera === camera { return false }
      if self.camera != nil { attached = false; return false }
      self.camera = camera
      return true
    }
    return attached
  }

  func detach(_ camera: AnyObject) {
    change {
      guard self.camera === camera else { return false }
      self.camera = nil
      self.status = .off
      return true
    }
  }

  /// The attached `camera`'s scan does `status`.
  func show(_ camera: AnyObject, status: TextScanStatus) {
    change {
      guard self.camera === camera, self.status != status else { return false }
      self.status = status
      return true
    }
  }

  func captureResult(timeout: Double?) throws -> Promise<Variant_ScannerFailure_TextSessionSnapshot> {
    lock.lock()
    let code: ScannerErrorCode = closed ? .processorDisposed : .featureUnsupported
    lock.unlock()
    return .resolved(withResult: .first(ScannerFailure(code: code)))
  }

  func clearCapture() throws {}

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
    version += 1
    let snapshot = snapshotLocked()
    let to = Array(observers.values)
    observers = [:]
    lock.unlock()
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
    TextSessionSnapshot(version: Double(version), closed: closed, status: status, capture: nil)
  }
}
