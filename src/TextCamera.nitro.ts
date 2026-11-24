import type { AutofocusState, CameraState } from './DocumentScannerCamera.nitro'
import type { DocumentCorners } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'
import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'

/** A rectangle as fractions (0–1) of the camera view, origin top left. */
export interface TextRegion {
  x: number
  y: number
  width: number
  height: number
}

/** Back-camera torch for text reading. */
export type TextTorchMode = 'off' | 'on'

/**
 * Back-camera preview with a box marking the region text is picked from, in two steps. Live, the camera focuses on the
 * region and keeps its sharpest recent frames; under each new focus the sharpest of its first frames is read in the
 * background with the platform recognizer (ML Kit on Android). Nothing shows over the preview but the box. A capture
 * then returns the best frame with its words for the user to pick from. Each callback fires on change only, starting
 * with the state at the time it is set.
 */
export interface TextCameraProps extends HybridViewProps {
  /** Whether the camera runs; defaults to true. False releases the camera and the kept frames. */
  active?: boolean
  /** The area read, as fractions of the view; clamped to it. Changes apply on the next frame without restarting. */
  textRegion: TextRegion
  /** ARGB color of the box marking the region; the TextCamera wrapper takes a color string. */
  guideColor?: number
  /** Tapping the preview focuses there for 5 s, then focus returns to the region; defaults to true. */
  tapToFocus?: boolean
  /**
   * Focuses by the document scanner's rules: continuous autofocus follows the region while the phone moves, and every
   * steady hold locks with the camera's own locking scan, a sweep that finds the sharpest distance, at the region's
   * most textured spot, else its centre. Turning or moving the phone, or a new region, releases the lock and starts
   * over, as does the page moving under a steady phone, which the image shows; a lock whose background read finds no
   * text locks again, and once a hold's two locks are spent continuous autofocus follows the region until the phone
   * moves on. Moving nearer or farther until the view goes soft under the lock, or
   * the page shifting, locks again once steady. Frames count as in focus
   * only under a settled lock, since at close range continuous autofocus reports focus it has not reached; without
   * `focusLock`, they count while continuous autofocus reports focus. Needs single-shot autofocus and motion sensors (a
   * gyroscope and linear acceleration); otherwise continuous autofocus alone follows the region. Android only; the
   * native view defaults to false, and `TextCamera` turns it on.
   */
  focusLock?: boolean
  /**
   * Zoom ratio, clamped to the camera's range; defaults to 1. Zoom lets close-up text fill the region from farther away,
   * inside the lens's focus range, and gives small print more detail, since the camera crops its sensor before scaling
   * the frames read. Some phones switch to another lens at higher ratios. Android only.
   */
  zoom?: number
  torch?: TextTorchMode
  /**
   * Requested width in pixels, before rotation, of the 4:3 frames read; the camera picks the closest size it supports.
   * Defaults to 1280. A thin region holding small text needs more. Changing it restarts the camera.
   */
  analysisFrameWidth?: number
  onCameraStateChange?: (state: CameraState) => void
  /**
   * Focus on the region. A lock or a tap reports `scan` while its scan runs and `locked` once the camera reports the
   * lock; continuous autofocus reports its Camera2 state. iOS reports `inactive`.
   */
  onAutofocusChange?: (state: AutofocusState) => void
  /**
   * Whether the print in the region is blurred, as when nearer than the lens can focus or moving: its edges, measured
   * across the print in the frames read, spread past what reads reliably. The camera reports a lock focused even then,
   * and the recognizer misreads characters before it stops finding text, so the image decides. The box turns amber
   * meanwhile; a blurred read counts as nothing found for focus, and a capture waits for a sharp one until its
   * deadline. Android only; iOS reports false.
   */
  onBlurChange?: (blurred: boolean) => void
  /** Camera failures, and `feature/unsupported` when the recognizer cannot start. */
  onError?: (error: ScannerFailure) => void
}

/**
 * One recognized word of a capture: its text, its `corners`, turned with the text's slant, and the upright `bounds`
 * around them, both as fractions of the capture's image; the line it belongs to, numbered in reading order from 0;
 * and the recognizer's confidence for that line, 0 when it gives none.
 */
export interface TextWord {
  text: string
  bounds: TextRegion
  corners: DocumentCorners
  line: number
  confidence: number
}

/**
 * A still of the region for picking text: the sharpest recent frame, in focus where the camera could focus, and every
 * word recognized in it. `uri` is a greyscale JPEG data URI of `width` by `height` pixels showing `region` of the view,
 * for a preview: nothing is written to storage, so nothing needs cleaning up.
 */
export interface TextCapture {
  uri: string
  width: number
  height: number
  region: TextRegion
  words: TextWord[]
}

export interface TextCameraMethods extends HybridViewMethods {
  /**
   * Native result transport for public `captureText`. A recent background read in focus that found text returns at
   * once without another read, unless a kept frame of the last second is clearly sharper, which is read instead.
   * When neither finds text, with `focusLock`, the region locks again and the capture waits for that lock's read.
   * Past `timeoutMs` (defaults to 2000) the sharpest frame kept, in focus if any was, is read then. Android only; iOS
   * reports `feature/unsupported`.
   */
  captureTextResult(timeoutMs?: number): Promise<TextCapture | ScannerFailure>
}

export type TextCameraView = HybridView<
  TextCameraProps,
  TextCameraMethods,
  { ios: 'swift'; android: 'kotlin' }
>
