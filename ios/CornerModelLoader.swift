import Foundation

/// Prepares a camera's `cornerDetectionModel` for the shared interpreter. File reads and validation run
/// on one background worker; only the newest request may activate its model, and a request for the
/// already active source succeeds without reloading. Completions arrive on main, once.
final class CornerModelLoader: @unchecked Sendable {
  static let shared = CornerModelLoader()

  private let inference: CornerInference
  private let worker = DispatchQueue(label: "ReactNativeDocumentScanner.models", qos: .userInitiated)
  private let lock = NSLock()
  private var request = 0
  /// The source behind the active selection and that selection's generation.
  private var active: (source: String, generation: UInt64)?

  init(inference: CornerInference = .shared) { self.inference = inference }

  func load(_ source: String, completion: @escaping (ScannerErrorCode?) -> Void) {
    lock.lock()
    request += 1
    let current = request
    lock.unlock()
    // Called once, on main only.
    nonisolated(unsafe) let completion = completion
    worker.async { [self] in
      let outcome: ScannerErrorCode?
      do {
        lock.lock(); let previous = active, superseded = request != current; lock.unlock()
        let selected = try? inference.selection()
        if superseded {
          outcome = .modelSuperseded
        } else if let previous, previous.source == source, selected?.generation == previous.generation {
          outcome = nil
        } else {
          let data = try Data(contentsOf: try Self.url(source))
          // Ownership is checked and recorded under the request lock at the activation boundary, so a
          // newer request can never be outlived by an older model becoming the selection.
          try inference.replace(data: data) { [self] selection in
            lock.lock(); defer { lock.unlock() }
            guard request == current else { return false }
            active = (source, selection.generation)
            return true
          }
          outcome = nil
        }
      } catch CornerInferenceError.staleSelection {
        outcome = .modelSuperseded
      } catch let error as ScannerCodeError {
        outcome = error.code
      } catch {
        outcome = .modelLoadFailed
      }
      DispatchQueue.main.async { [self] in
        lock.lock(); let newest = request == current; lock.unlock()
        completion(newest ? outcome : .modelSuperseded)
      }
    }
  }

  /// The bundled model URI, an absolute path, a `file://` URL, or a main bundle resource name.
  static func url(_ source: String) throws -> URL {
    guard !source.isEmpty else { throw ScannerCodeError.inputInvalid }
    if source == "asset:///doccornernet/model.tflite" {
      let owners = [Bundle(for: HybridCameraPermission.self), Bundle.main] + Bundle.allBundles + Bundle.allFrameworks
      for owner in owners {
        if let bundleURL = owner.url(forResource: "DocumentScannerModels", withExtension: "bundle"),
           let model = Bundle(url: bundleURL)?.url(forResource: "model", withExtension: "tflite") {
          return model
        }
      }
      throw ScannerCodeError(code: .modelLoadFailed)
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
