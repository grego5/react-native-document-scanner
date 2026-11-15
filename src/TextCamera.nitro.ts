import type { CameraState } from './DocumentScannerCamera.nitro'
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

/**
 * `native` draws the region's frame with a crosshair at its centre, where readings are chosen, dims the preview around
 * it and highlights the text being read; `none` leaves the overlay to the host.
 */
export type TextGuide = 'native' | 'none'

/** Back-camera torch for text reading. */
export type TextTorchMode = 'off' | 'on'

/** One line read in the region, after filtering, agreed across recent frames. */
export interface TextReading {
  /** The filtered line; empty when nothing was read. */
  text: string
  /** The recognizer's confidence from 0 to 1, averaged over the agreeing frames; Android reports 0 when unknown. */
  confidence: number
  /** How many of the recent frames read this text. */
  votes: number
  /** The region read, clamped to the view. */
  region: TextRegion
  /**
   * The box around the agreed text, as fractions of the view, updated with each recognition; absent when nothing was
   * read. A host drawing its own highlight with `textGuide: 'none'` can use it.
   */
  bounds?: TextRegion
}

/**
 * Reads one line of text inside a region of the back-camera preview, with the platform recognizer (Vision on iOS, ML Kit
 * on Android). It needs no scan session and saves no images. Each callback fires on change only, starting with the state
 * at the time it is set.
 */
export interface TextCameraProps extends HybridViewProps {
  /** Whether the camera runs; defaults to true. False releases the camera and clears the reading. */
  active?: boolean
  /** The area read, as fractions of the view; clamped to it. Changes apply on the next frame without restarting. */
  textRegion: TextRegion
  /** Defaults to `native`. */
  textGuide?: TextGuide
  /** ARGB color of the native frame; the TextCamera wrapper takes a color string. */
  guideColor?: number
  /**
   * The characters a reading may contain, listed literally, e.g. `ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-`. Other
   * characters are swapped for an allowed look-alike (O/0, I/1, Z/2, S/5, G/6, B/8, letter case). A character with
   * none, whitespace included unless a space is listed, splits the line, and the piece nearest the region's centre is
   * read. Empty reads the whole line nearest the centre and only collapses whitespace.
   */
  allowedCharacters?: string
  /**
   * A regular expression the whole filtered reading must match, or empty for none. Use syntax common to Java and ICU
   * regular expressions. An invalid pattern reports `input/invalid` and matches nothing.
   */
  textPattern?: string
  /** Shortest filtered reading that counts; defaults to 1. */
  minLength?: number
  /** Tapping the preview focuses there for 5 s, then focus returns to the region; defaults to true. */
  tapToFocus?: boolean
  torch?: TextTorchMode
  /**
   * Most recognitions per second, from 1 to 15; defaults to 5. Frames in between are dropped, and a recognizer slower
   * than the rate sets its own pace. Lower saves power; higher agrees sooner.
   */
  recognitionRate?: number
  /**
   * Requested width in pixels, before rotation, of the 4:3 frames read; the camera picks the closest size it supports.
   * Defaults to 1280. A thin region holding small text needs more. Changing it restarts the camera.
   */
  analysisFrameWidth?: number
  onCameraStateChange?: (state: CameraState) => void
  /**
   * The agreed reading after each recognized frame, or an empty one when the region holds no accepted text. Fires when
   * its text, votes or bounds change.
   */
  onTextChange?: (reading: TextReading) => void
  /** Camera failures, an invalid `textPattern`, and `feature/unsupported` when the recognizer cannot start. */
  onError?: (error: ScannerFailure) => void
}

export interface TextCameraMethods extends HybridViewMethods {
  /**
   * Native result transport for public `readText`: the agreed reading now, or, when there is none yet, the first one
   * within `timeoutMs` (defaults to 1500). An empty text means nothing was read in time.
   */
  readTextResult(timeoutMs?: number): Promise<TextReading | ScannerFailure>
}

export type TextCameraView = HybridView<
  TextCameraProps,
  TextCameraMethods,
  { ios: 'swift'; android: 'kotlin' }
>
