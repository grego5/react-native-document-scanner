import Foundation
import NitroModules
import UIKit

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridDocumentCropEditorView: HybridDocumentCropEditorViewSpec {
  // Nitro mounts and accesses this view on the UIKit main thread.
  lazy var view: UIView = MainActor.assumeIsolated { UIView() }
  var session: (any HybridScanSessionSpec) = HybridScanSession()
  var pageId: String = ""
  var filter: DocumentFilter? = nil
  var onPresetChange: ((_ preset: CropDraftPreset) -> Void)? = nil
  var onLoad: ((_ width: Double, _ height: Double) -> Void)? = nil
  var onError: ((_ failure: ScannerFailure) -> Void)? = nil

  func afterUpdate() {
    onError?(ScannerUnsupported.failure)
  }

  func submitResult() throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func getQuadResult() throws -> Promise<Variant_ScannerFailure_DocumentQuad> {
    return .resolved(withResult: .first(ScannerUnsupported.failure))
  }

  func setPresetResult(preset: CropPreset) throws -> Promise<Variant_Bool_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }
}
