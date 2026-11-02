import Foundation
import NitroModules

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridDocumentScannerProcessor: HybridDocumentScannerProcessorSpec {

  func createSessionResult() throws -> Variant__any_HybridScanSessionSpec__ScannerFailure {
    return .second(ScannerUnsupported.failure)
  }

  func pickSampleFolderResult() throws -> Promise<Variant_ScannerFailure_SampleFolder> {
    return .resolved(withResult: .first(ScannerUnsupported.failure))
  }

  func getSampleFolder() throws -> SampleFolder {
    throw ScannerUnsupported.error
  }

  func clearSampleFolder() throws -> Void {
    throw ScannerUnsupported.error
  }

  func setSampleCollection(on: Bool) throws -> Void {
    throw ScannerUnsupported.error
  }

  func setSampleFolder(uri: String) throws -> Void {
    throw ScannerUnsupported.error
  }
}
