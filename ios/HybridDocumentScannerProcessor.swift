import Foundation
import NitroModules

/// Session factory plus sample-folder controls. Sample collection is deferred on iOS:
/// no folder is ever collecting, clearing and disabling are idempotent no-ops, and
/// operations that would pick, enable or write a sample report feature/unsupported.
class HybridDocumentScannerProcessor: HybridDocumentScannerProcessorSpec {

  private let models: CornerModelLoader

  init(models: CornerModelLoader = .shared) { self.models = models }

  func createSessionResult() throws -> Variant__any_HybridScanSessionSpec__ScannerFailure {
    do {
      let session = HybridScanSession(state: try ScanSessionRegistry.shared.createState())
      // The shared default request outlives any session.
      models.startDefault()
      return .first(session)
    } catch { return .second(ScannerCodeError.failure(error, fallback: .processorUnavailable)) }
  }

  func waitForCornerDetectionModelResult() throws -> Promise<Variant_Bool_ScannerFailure> {
    let promise = Promise<Variant_Bool_ScannerFailure>()
    models.startDefault()
    models.wait { result in
      switch result {
      case .success(let ready): promise.resolve(withResult: .first(ready))
      case .failure(let error): promise.resolve(withResult: .second(ScannerFailure(code: error.code)))
      }
    }
    return promise
  }

  func pickSampleFolderResult() throws -> Promise<Variant_ScannerFailure_SampleFolder> {
    .resolved(withResult: .first(ScannerUnsupported.failure))
  }

  func getSampleFolder() throws -> SampleFolder {
    SampleFolder(uri: nil, name: nil, collecting: false)
  }

  func clearSampleFolder() throws {}

  /// The JS facade raises the typed failure for enabling; void transport cannot carry it.
  func setSampleCollection(on: Bool) throws {
    if on { throw ScannerUnsupported.error }
  }

  func setSampleFolder(uri: String) throws {
    throw ScannerUnsupported.error
  }

  /// The app bundle resource the Expo plugin installs `cornerDetectionModel` as.
}
