import type { HybridObject } from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'

/**
 * `not-determined`: never requested; `denied`: refused, and a request prompts again; `blocked`: refused for good, so
 * only the app settings can grant it.
 */
export type CameraPermissionStatus = 'not-determined' | 'granted' | 'denied' | 'blocked'

/** Keeps the permission enum distinct from the failure alternative in native result transport. */
export interface CameraPermissionValue {
  status: CameraPermissionStatus
}

/** The app's camera permission, usable without a mounted Camera view. */
export interface CameraPermission extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  getStatus(): CameraPermissionStatus
  /**
   * Prompts for access unless it is granted or blocked, and resolves with the status after the prompt. A call while
   * the prompt is open resolves with that prompt's result. Native failures are typed payloads; the public wrapper
   * rejects with ScannerError, including when no foreground application can show the prompt.
   * iOS denial/restriction is blocked; the app must provide NSCameraUsageDescription.
   */
  requestResult(): Promise<CameraPermissionValue | ScannerFailure>
  /**
   * Calls `listener` after every status change, including access granted in system settings, which is reported when
   * the app returns. Returns a function that removes it.
   */
  addStatusListener(listener: (status: CameraPermissionStatus) => void): () => void
}
