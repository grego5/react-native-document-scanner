import Foundation
import NitroModules

class HybridCameraPermission: HybridCameraPermissionSpec {
  private let permissions: CameraPermissions

  init(permissions: CameraPermissions = .shared) {
    self.permissions = permissions
    super.init()
  }

  func getStatus() throws -> CameraPermissionStatus {
    CameraPermissions.onMain { permissions.status() }
  }

  func requestResult() throws -> Promise<Variant_CameraPermissionValue_ScannerFailure> {
    let promise = Promise<Variant_CameraPermissionValue_ScannerFailure>()
    let permissions = self.permissions
    DispatchQueue.main.async {
      permissions.request { promise.resolve(withResult: $0) }
    }
    return promise
  }

  func addStatusListener(listener: @escaping (_ status: CameraPermissionStatus) -> Void) throws -> () -> Void {
    CameraPermissions.onMain { permissions.addListener(listener) }
  }
}
