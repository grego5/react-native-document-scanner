import Foundation
import NitroModules

/// Scaffold boundary until the corresponding iOS feature task is implemented.
enum ScannerUnsupported {
  static var failure: ScannerFailure { ScannerFailure(code: .featureUnsupported) }
  static var error: NSError {
    NSError(domain: "ReactNativeDocumentScanner", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "feature/unsupported"])
  }
}
