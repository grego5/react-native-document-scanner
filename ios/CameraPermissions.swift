import AVFoundation
import Foundation
import UIKit

/// Injected platform boundary. All calls are owned by the main queue; AVFoundation's
/// completion may arrive on any queue.
protocol CameraAuthorization {
  func authorizationStatus() -> AVAuthorizationStatus
  func requestFailure() -> ScannerErrorCode?
  func requestAccess(completion: @escaping @Sendable () -> Void)
}

struct SystemCameraAuthorization: CameraAuthorization {
  func authorizationStatus() -> AVAuthorizationStatus {
    AVCaptureDevice.authorizationStatus(for: .video)
  }

  func requestFailure() -> ScannerErrorCode? {
    guard let purpose = Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String,
          !purpose.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      return .permissionUnavailable
    }
    return MainActor.assumeIsolated {
      UIApplication.shared.applicationState == .active ? nil : .permissionRequestFailed
    }
  }

  func requestAccess(completion: @escaping @Sendable () -> Void) {
    AVCaptureDevice.requestAccess(for: .video) { _ in completion() }
  }
}

/// Process-wide permission owner. Mutable state and callbacks run only on main.
/// Sendable permits AVFoundation/NotificationCenter to enqueue work to that owner;
/// it does not permit direct off-main access to the state below.
final class CameraPermissions: @unchecked Sendable {
  static let shared = CameraPermissions()

  private let authorization: any CameraAuthorization
  private let notifications: NotificationCenter
  private var activationObserver: NSObjectProtocol?
  private var listeners: [UUID: (CameraPermissionStatus) -> Void] = [:]
  private var reported: CameraPermissionStatus?
  private var prompt: UUID?
  private var waiting: [(Variant_CameraPermissionValue_ScannerFailure) -> Void] = []

  init(authorization: any CameraAuthorization = SystemCameraAuthorization(),
       notifications: NotificationCenter = .default) {
    self.authorization = authorization
    self.notifications = notifications
  }

  deinit {
    if let activationObserver { notifications.removeObserver(activationObserver) }
  }

  /// Nitro's synchronous methods can arrive on the JS thread. Never dispatch-sync
  /// when already on main (native camera subscribers use the same owner).
  static func onMain<T>(_ operation: () -> T) -> T {
    if Thread.isMainThread { return operation() }
    return DispatchQueue.main.sync(execute: operation)
  }

  func status() -> CameraPermissionStatus {
    dispatchPrecondition(condition: .onQueue(.main))
    switch authorization.authorizationStatus() {
    case .notDetermined: return .notDetermined
    case .authorized: return .granted
    case .denied, .restricted: return .blocked
    @unknown default: return .blocked
    }
  }

  func request(_ completion: @escaping (Variant_CameraPermissionValue_ScannerFailure) -> Void) {
    dispatchPrecondition(condition: .onQueue(.main))
    // A prompt temporarily makes the app inactive. Join it before checking
    // presentation availability or reading a possibly already updated status.
    if prompt != nil {
      waiting.append(completion)
      return
    }
    let current = status()
    guard current == .notDetermined else {
      completion(.first(CameraPermissionValue(status: current)))
      refresh()
      return
    }
    if let failure = authorization.requestFailure() {
      completion(.second(ScannerFailure(code: failure)))
      return
    }
    let token = UUID()
    prompt = token
    waiting.append(completion)
    // Retain this owner until all callers settle, even if its HybridObject dies.
    authorization.requestAccess { [self] in
      DispatchQueue.main.async { self.finishPrompt(token) }
    }
  }

  func addListener(_ listener: @escaping (CameraPermissionStatus) -> Void) -> () -> Void {
    dispatchPrecondition(condition: .onQueue(.main))
    if listeners.isEmpty {
      reported = status()
      activationObserver = notifications.addObserver(
        forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
      ) { [weak self] _ in self?.refresh() }
    }
    let id = UUID()
    listeners[id] = listener
    return { [weak self] in
      Self.onMain { self?.removeListener(id) }
    }
  }

  private func removeListener(_ id: UUID) {
    dispatchPrecondition(condition: .onQueue(.main))
    listeners.removeValue(forKey: id)
    if listeners.isEmpty {
      if let activationObserver { notifications.removeObserver(activationObserver) }
      activationObserver = nil
      reported = nil
    }
  }

  private func finishPrompt(_ token: UUID) {
    dispatchPrecondition(condition: .onQueue(.main))
    guard prompt == token else { return }
    prompt = nil
    let callers = waiting
    waiting.removeAll()
    let result = Variant_CameraPermissionValue_ScannerFailure.first(CameraPermissionValue(status: status()))
    callers.forEach { $0(result) }
    refresh()
  }

  private func refresh() {
    dispatchPrecondition(condition: .onQueue(.main))
    guard !listeners.isEmpty else { return }
    let current = status()
    guard current != reported else { return }
    reported = current
    // A listener can remove itself/another listener reentrantly.
    let ids = Array(listeners.keys)
    for id in ids { listeners[id]?(current) }
  }
}
