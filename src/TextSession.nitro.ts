import type { HybridObject } from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'
import type { TextCapture, TextScanStatus } from './TextCamera.nitro'

/** Full immutable session state; `version` sequences native changes. */
export interface TextSessionSnapshot {
  readonly version: number
  readonly closed: boolean
  /** What the scan is doing; `off` while no camera shows it or once closed. */
  readonly status: TextScanStatus
  /** The still being picked from, until `clearCapture` or the next capture. */
  readonly capture?: TextCapture
}

/** Registration and initial snapshot are atomic. No eager callback; unsubscribe is idempotent. */
export interface TextSessionObservation {
  readonly snapshot: TextSessionSnapshot
  readonly unsubscribe: () => void
}

/**
 * What one text scan owns, apart from any camera view: the capture being picked from and the attached camera's status.
 * It outlives the views that show it, so a remounted `TextCamera` finds the capture still there; one camera shows it at
 * a time. Raw result transport; public operations translate `ScannerFailure` to `ScannerError`.
 */
export interface TextSession extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  /**
   * Captures with the attached camera and resolves with the snapshot holding the capture. The timeout is in
   * milliseconds and defaults to 2000. A camera that stops while a capture waits uses its retained frames.
   * Fails with `camera/unavailable` without a running camera or when stopped without a retained frame.
   * Fails with `capture/not-ready` when the deadline passes without a frame, `capture/failed` when the frame
   * cannot be read or encoded, and `processor/disposed` once closed.
   * See `.agents/skills/docs/references/android/text-camera.md#capture-selection` and
   * `.agents/skills/docs/references/ios/camera.md#text-camera` for platform selection rules.
   */
  captureResult(timeout?: number): Promise<TextSessionSnapshot | ScannerFailure>
  /** Drops the capture, for a retake. */
  clearCapture(): void
  getSnapshot(): TextSessionSnapshot
  observeResult(onChange: (snapshot: TextSessionSnapshot) => void): TextSessionObservation | ScannerFailure
  /** Fails pending captures with `processor/disposed`, drops the capture and publishes nothing more. */
  closeResult(): Promise<boolean | ScannerFailure>
}
