import Foundation
import NitroModules

/// iOS package scaffold. Functional implementation belongs to later iOS tasks.
class HybridCameraPermission: HybridCameraPermissionSpec {

  func getStatus() throws -> CameraPermissionStatus {
    throw ScannerUnsupported.error
  }

  func requestResult() throws -> Promise<Variant_CameraPermissionValue_ScannerFailure> {
    return .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func addStatusListener(listener: @escaping (_ status: CameraPermissionStatus) -> Void) throws -> () -> Void {
    throw ScannerUnsupported.error
  }
}
