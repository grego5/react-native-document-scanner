import Foundation
import NitroModules

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridScanSession: HybridScanSessionSpec {

  func createPdfResult(pageIds: [String]?, options: SessionPdfOptions) throws -> Promise<Variant_String_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func exportImagesResult(pageIds: [String]?, outputDirectory: String) throws -> Promise<Variant__String__ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func saveCaptureSampleResult(pageId: String) throws -> Promise<Variant_String_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func setDocumentCropResult(pageId: String, quad: DocumentQuad) throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func setFilterResult(pageId: String, filter: DocumentFilter) throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func setShadowRemovalResult(pageId: String, enabled: Bool) throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func setPageOrderResult(pageIds: [String]) throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func removePageResult(pageId: String) throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func closeResult() throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func getSession() throws -> ScanSessionSnapshot {
    throw ScannerUnsupported.error
  }

  func observeResult(onChange: @escaping (_ change: ScanSessionChange) -> Void) throws -> Variant_ScanSessionObservation_ScannerFailure {
    return .second(ScannerUnsupported.failure)
  }
}
