import AVFoundation
import XCTest
import UIKit
@testable import ReactNativeDocumentScanner

private final class FakeCameraAuthorization: CameraAuthorization {
  var current: AVAuthorizationStatus = .notDetermined
  var failure: ScannerErrorCode?
  var promptCount = 0
  var pending: (@Sendable () -> Void)?

  func authorizationStatus() -> AVAuthorizationStatus { current }
  func requestFailure() -> ScannerErrorCode? { failure }
  func requestAccess(completion: @escaping @Sendable () -> Void) {
    promptCount += 1
    pending = completion
  }

  func finish(_ status: AVAuthorizationStatus) -> (@Sendable () -> Void)? {
    current = status
    let completion = pending
    pending = nil
    completion?()
    return completion
  }
}

final class CameraPermissionsTests: XCTestCase {
  @MainActor
  func testInitialStatusMappingWithoutCameraView() {
    let authorization = FakeCameraAuthorization()
    let owner = CameraPermissions(authorization: authorization)
    for (native, expected) in [(AVAuthorizationStatus.notDetermined, CameraPermissionStatus.notDetermined),
                               (.authorized, .granted), (.denied, .blocked), (.restricted, .blocked)] {
      authorization.current = native
      XCTAssertEqual(owner.status(), expected)
    }
  }

  @MainActor
  func testConcurrentRequestsSharePromptAndSettleExactlyOnce() async {
    let authorization = FakeCameraAuthorization()
    let owner = CameraPermissions(authorization: authorization)
    var values: [CameraPermissionStatus] = []
    owner.request { values.append($0.asType(CameraPermissionValue.self)!.status) }
    authorization.failure = .permissionRequestFailed // system prompt made the app inactive
    owner.request { values.append($0.asType(CameraPermissionValue.self)!.status) }
    XCTAssertEqual(authorization.promptCount, 1)
    XCTAssertTrue(values.isEmpty)
    let duplicate = authorization.finish(.authorized)
    await drainMain()
    XCTAssertEqual(values, [.granted, .granted])
    duplicate?()
    await drainMain()
    XCTAssertEqual(values.count, 2)
  }

  @MainActor
  func testDeniedAndRestrictedNeverPromptAgain() {
    let authorization = FakeCameraAuthorization()
    let owner = CameraPermissions(authorization: authorization)
    for status in [AVAuthorizationStatus.authorized, .denied, .restricted] {
      authorization.current = status
      owner.request { result in
        XCTAssertEqual(result.asType(CameraPermissionValue.self)?.status,
                       status == .authorized ? .granted : .blocked)
      }
    }
    XCTAssertEqual(authorization.promptCount, 0)
  }

  @MainActor
  func testDenialPublishesOnceAndResolvesEveryCaller() async {
    let authorization = FakeCameraAuthorization()
    let owner = CameraPermissions(authorization: authorization)
    var changes: [CameraPermissionStatus] = []
    let remove = owner.addListener { changes.append($0) }
    var results = 0
    for _ in 0..<3 {
      owner.request {
        XCTAssertEqual($0.asType(CameraPermissionValue.self)?.status, .blocked)
        results += 1
      }
    }
    authorization.finish(.denied)?() // also simulate a repeated platform completion
    await drainMain()
    XCTAssertEqual(results, 3)
    XCTAssertEqual(changes, [.blocked])
    remove()
  }

  @MainActor
  func testUnavailablePresentationReturnsTypedFailureAndCanRetry() async {
    let authorization = FakeCameraAuthorization()
    let owner = CameraPermissions(authorization: authorization)
    for code in [ScannerErrorCode.permissionRequestFailed, .permissionUnavailable] {
      authorization.failure = code
      owner.request { XCTAssertEqual($0.asType(ScannerFailure.self)?.code, code) }
    }
    XCTAssertEqual(authorization.promptCount, 0)
    authorization.failure = nil
    var resolved = false
    owner.request { _ in resolved = true }
    authorization.finish(.authorized)
    await drainMain()
    XCTAssertTrue(resolved)
    XCTAssertEqual(authorization.promptCount, 1)
  }

  @MainActor
  func testSettingsRefreshNotifiesOnlyChangesAndRemovalIsIdempotent() {
    let authorization = FakeCameraAuthorization()
    let notifications = NotificationCenter()
    let owner = CameraPermissions(authorization: authorization, notifications: notifications)
    var changes: [CameraPermissionStatus] = []
    let remove = owner.addListener { changes.append($0) }
    XCTAssertTrue(changes.isEmpty)
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    authorization.current = .authorized
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    authorization.current = .denied
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertEqual(changes, [.granted, .blocked])
    remove()
    remove()
    authorization.current = .authorized
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertEqual(changes, [.granted, .blocked])
    let removeAgain = owner.addListener { changes.append($0) }
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertEqual(changes.count, 2) // new subscription establishes a fresh baseline
    removeAgain()
  }

  @MainActor
  func testRemovalDuringCallbackAndReleaseOfListenerCapture() {
    let authorization = FakeCameraAuthorization()
    let notifications = NotificationCenter()
    let owner = CameraPermissions(authorization: authorization, notifications: notifications)
    final class Capture {}
    var capture: Capture? = Capture()
    weak var weakCapture = capture
    var remove: (() -> Void)?
    var calls = 0
    remove = owner.addListener { [retained = capture!] _ in
      _ = retained
      calls += 1
      remove?()
    }
    capture = nil
    XCTAssertNotNil(weakCapture)
    authorization.current = .authorized
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertNil(weakCapture)
    authorization.current = .denied
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertEqual(calls, 1)
  }

  @MainActor
  func testObserverDoesNotRetainOwner() {
    let authorization = FakeCameraAuthorization()
    let notifications = NotificationCenter()
    var owner: CameraPermissions? = CameraPermissions(authorization: authorization, notifications: notifications)
    weak var weakOwner = owner
    var calls = 0
    let remove = owner!.addListener { _ in calls += 1 }
    owner = nil
    XCTAssertNil(weakOwner)
    authorization.current = .authorized
    notifications.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    remove()
    XCTAssertEqual(calls, 0)
  }

  @MainActor
  func testNativeBoundarySharesOwnerAndDeliversTypedResults() async throws {
    let authorization = FakeCameraAuthorization()
    let owner = CameraPermissions(authorization: authorization)
    let first = HybridCameraPermission(permissions: owner)
    let second = HybridCameraPermission(permissions: owner)
    XCTAssertEqual(try first.getStatus(), .notDetermined)
    var results: [CameraPermissionStatus] = []
    let remove = try first.addStatusListener { results.append($0) }
    let one = try first.requestResult()
    let two = try second.requestResult()
    await drainMain()
    XCTAssertEqual(authorization.promptCount, 1)
    authorization.finish(.authorized)
    let valueOne = try await one.await()
    let valueTwo = try await two.await()
    XCTAssertEqual(valueOne.asType(CameraPermissionValue.self)?.status, .granted)
    XCTAssertEqual(valueTwo.asType(CameraPermissionValue.self)?.status, .granted)
    XCTAssertEqual(results, [.granted])
    remove()
  }

  @MainActor
  func testPendingOwnerSurvivesUntilBackgroundCompletionThenReleases() async {
    let authorization = FakeCameraAuthorization()
    var owner: CameraPermissions? = CameraPermissions(authorization: authorization)
    weak var weakOwner = owner
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      owner!.request { result in
        XCTAssertTrue(Thread.isMainThread)
        XCTAssertEqual(result.asType(CameraPermissionValue.self)?.status, .granted)
        continuation.resume()
      }
      owner = nil
      XCTAssertNotNil(weakOwner)
      authorization.current = .authorized
      let completion = authorization.pending!
      authorization.pending = nil
      DispatchQueue.global().async(execute: completion)
    }
    await drainMain()
    XCTAssertNil(weakOwner)
  }

  @MainActor
  private func drainMain() async {
    await withCheckedContinuation { continuation in
      DispatchQueue.main.async { continuation.resume() }
    }
  }
}
