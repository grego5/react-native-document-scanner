import Foundation
import NitroModules

/// Session factory plus sample-folder controls. Sample collection is deferred on iOS:
/// no folder is ever collecting, clearing and disabling are idempotent no-ops, and
/// operations that would pick, enable or write a sample report feature/unsupported.
class HybridDocumentScannerProcessor: HybridDocumentScannerProcessorSpec {

  func createSessionResult() throws -> Variant__any_HybridScanSessionSpec__ScannerFailure {
    do { return .first(HybridScanSession(state: try ScanSessionRegistry.shared.createState())) }
    catch { return .second(ScannerCodeError.failure(error, fallback: .processorUnavailable)) }
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
}
