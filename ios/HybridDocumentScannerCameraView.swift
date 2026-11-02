import Foundation
import NitroModules
import UIKit

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridDocumentScannerCameraView: HybridDocumentScannerCameraViewSpec {
  // Nitro mounts and accesses this view on the UIKit main thread.
  lazy var view: UIView = MainActor.assumeIsolated { UIView() }
  var session: (any HybridScanSessionSpec) = HybridScanSession()
  var renderOptions: DocumentRenderOptions = DocumentRenderOptions(removeShadows: nil, jpegQuality: nil, filter: nil, format: nil, paperSize: nil, dpi: nil, followPageEdges: nil)
  var quadTensorModel: String? = nil
  var onError: ((_ error: ScannerFailure) -> Void)? = nil
  var mode: CameraMode? = nil
  var guide: Bool? = nil
  var adaptiveDetection: Bool? = nil
  var minCornerScore: Double? = nil
  var qualityGate: Bool? = nil
  var qualityGateConfig: QualityGateConfig? = nil
  var tapToFocus: Bool? = nil
  var exposureCompensation: Double? = nil
  var minFrameRate: Double? = nil
  var torch: TorchMode? = nil
  var captureMode: CaptureMode? = nil
  var jpegQuality: Double? = nil
  var guideTransitionDuration: Double? = nil
  var analysisWidth: Double? = nil
  var squareFrame: Bool? = nil
  var showStats: Bool? = nil
  var guideColor: Double? = nil
  var captureFeedback: Bool? = nil
  var captureColor: Double? = nil
  var onCameraStateChange: ((_ state: CameraState) -> Void)? = nil
  var onCaptureStateChange: ((_ state: CameraCaptureState) -> Void)? = nil
  var onAutofocusChange: ((_ state: AutofocusState) -> Void)? = nil
  var onDocumentStateChange: ((_ state: DocumentState) -> Void)? = nil
  var onCaptureStage: ((_ stage: CaptureStage, _ waitReason: CaptureWaitReason?) -> Void)? = nil

  func afterUpdate() {
    onError?(ScannerUnsupported.failure)
  }

  func takePhotoResult(options: TakePhotoOptions?) throws -> Promise<Variant_String_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }
}
