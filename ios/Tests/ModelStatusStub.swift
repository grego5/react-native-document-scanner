import Foundation
@testable import ReactNativeDocumentScanner

/// The app-wide model status, set by hand. An observer gets the current status at once, then each change.
final class ModelStatusStub: @unchecked Sendable {
  private(set) var status: CornerModelStatus = .absent
  private var listeners: [Int: (CornerModelStatus) -> Void] = [:]
  private var next = 0
  private let source = "asset:///corner-detection-model.tflite"
  var observers: Int { listeners.count }

  func observe(_ listener: @escaping (CornerModelStatus) -> Void) -> () -> Void {
    next += 1
    let key = next
    listeners[key] = listener
    listener(status)
    return { [self] in listeners[key] = nil }
  }

  func loading() { set(.loading(source: source)) }
  func ready(_ selection: CornerInference.Selection) { set(.ready(source: source, selection: selection)) }
  func failed() { set(.failed(source: source, code: .modelLoadFailed)) }

  private func set(_ status: CornerModelStatus) {
    self.status = status
    listeners.sorted { $0.key < $1.key }.forEach { $0.value(status) }
  }
}
