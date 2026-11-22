import type { AutofocusState, CameraState } from './DocumentScannerCamera.nitro'
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
 * `crosshair` marks the region's centre, the point readings are chosen nearest to. `line` shows a single guide below
 * the target text. `dot` marks the region's centre with a point, the selector alone. `none` leaves the overlay to the
 * host.
 */
export type TextGuide = 'none' | 'crosshair' | 'line' | 'dot'

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
  /** Defaults to `none`. Choose `crosshair`, `line`, or `dot` to show a built-in guide. */
  textGuide?: TextGuide
  /**
   * Highlights the text being read with a soft fill in the guide colour, following it as the camera moves until its
   * centre leaves the region; defaults to false. It is independent of `textGuide`, so it also shows with `none`. A host drawing its own highlight can use
   * the reading's `bounds` instead.
   */
  textHighlight?: boolean
  /** ARGB color of the built-in guide; the TextCamera wrapper takes a color string. */
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
  /**
   * Helps continuous autofocus, which settles soft or on the wrong depth at close range, with the camera's own locking
   * scan, a sweep that finds the sharpest distance. Continuous autofocus follows the region while the phone moves. On a
   * steady hold its report is confirmed: the region is locked once continuous autofocus settles or reports no focus,
   * at once in a dark scene with the torch off, or after 0.6 s of searching. The first lock permits reading, since unconfirmed
   * continuous autofocus misreads; after it, reading goes on while the lock is released, pausing only while a lock scans.
   * Every line read in the region is kept where it is and moves with the page; the region's centre selects one, which
   * shows once two reads agree, so moving onto a line already read shows it at once. The region is read as new text
   * enters it, and until the aimed line agrees. A lock under which four reads find no agreed text locks again; a hold gets two locks. Turning or
   * moving the phone, moving it nearer or farther, as the read text changing size shows, or a new region, releases the
   * lock and starts over. A tap focuses there for its 5 s, then continuous autofocus resumes. Needs single-shot autofocus
   * and motion sensors (a gyroscope and linear acceleration); otherwise continuous autofocus alone follows the region.
   * Android only; the native view defaults to false, and `TextCamera` turns it on.
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
   * Most recognitions per second, from 1 to 15; defaults to 5. Frames in between are dropped, and a recognizer slower
   * than the rate sets its own pace. Lower saves power; higher agrees sooner. On Android with `focusLock`, this paces
   * each lock's few reads. Otherwise, once 4 recognitions in a row have read the agreed text and it holds still,
   * recognition slows to once a second, back to this rate when a reading disagrees, the text moves or the phone turns.
   */
  recognitionRate?: number
  /**
   * Requested width in pixels, before rotation, of the 4:3 frames read; the camera picks the closest size it supports.
   * Defaults to 1280. A thin region holding small text needs more. Changing it restarts the camera.
   */
  analysisFrameWidth?: number
  onCameraStateChange?: (state: CameraState) => void
  /**
   * Focus on the region. A lock or a tap reports `scan` while its scan runs and `locked` once the camera reports the
   * lock; continuous autofocus reports its Camera2 state. The built-in guide shows it too: yellow while scanning, a
   * green flash when focus lands, a red one when it fails, and a swell when a lock starts. iOS reports `inactive`.
   */
  onAutofocusChange?: (state: AutofocusState) => void
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
