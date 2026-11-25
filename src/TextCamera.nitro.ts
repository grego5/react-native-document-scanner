import type { DocumentCorners } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'
import type { TextSession } from './TextSession.nitro'
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

/** The scan statuses the guide shows while frames arrive, each with its own colour and label; see `TextScanStatus`. */
export type TextGuideState = 'ready' | 'focusing' | 'blurred' | 'capturing'

/**
 * What a text scan is doing, one status for everything the UI shows, picked natively, highest first:
 * - `off`: no camera shows the session, or it is suspended: inactive, hidden or without permission.
 * - `starting`: the camera opens, or recovers from an interruption, and no frame has come yet.
 * - `capturing`: a capture waits for a read.
 * - `blurred`: the region's print is blurred, as when nearer than the lens can focus or moving: its edges, measured
 *   across the print in the frames read, spread past what reads reliably. The camera reports a lock focused even then,
 *   and the recognizer misreads characters before it stops finding text, so the image decides. A blurred read counts as
 *   nothing found for focus, and a capture waits for a sharp one until its deadline.
 * - `focusing`: a lock's or a tap's scan runs.
 * - `ready`: frames arrive in focus, or continuous autofocus follows the region.
 * iOS reports `off`, `starting`, `capturing` and `ready` only.
 */
export type TextScanStatus = 'off' | 'starting' | 'ready' | 'focusing' | 'blurred' | 'capturing'

/** ARGB box colours per state; `off` and `starting` use `ready`'s. A missing state uses `ready`'s, except `blurred`, which defaults to amber. */
export interface TextGuideColors {
  ready?: number
  focusing?: number
  blurred?: number
  capturing?: number
}

/** Label per state; a missing state uses `ready`'s, and an empty string shows none. `off` and `starting` show none. */
export interface TextGuideLabels {
  ready?: string
  focusing?: string
  blurred?: string
  capturing?: string
}

export type TextGuideFontWeight = 'normal' | 'bold'
/** Which side of the box the label sits on. */
export type TextGuideLabelPlacement = 'bottom' | 'top'

/** The label, in the system font. Sizes are in points; `fontSize` scales with the system font size. */
export interface TextGuideLabelStyle {
  /** Defaults to 14. */
  fontSize?: number
  fontWeight?: TextGuideFontWeight
  /** ARGB; defaults to white. */
  color?: number
  /** ARGB; defaults to black at 60%. */
  backgroundColor?: number
  /** Space inside the background; defaults to 6. */
  padding?: number
  /** The background's corner radius; defaults to 6. */
  radius?: number
}

/**
 * The native guide: the box marking the region, and a label beside it per state. The label is centred, as wide as the
 * box at most, up to two lines, its direction taken from its text, and screen readers announce its changes.
 */
export interface TextGuide {
  /** Draws nothing natively; `onGuideLayout` still reports the box, for a guide drawn by the host. */
  hidden?: boolean
  colors?: TextGuideColors
  /** The box's stroke, in points; defaults to 2. */
  strokeWidth?: number
  /** The box's corner radius, in points; defaults to 8. */
  radius?: number
  labels?: TextGuideLabels
  labelStyle?: TextGuideLabelStyle
  /** Defaults to `bottom`. */
  labelPlacement?: TextGuideLabelPlacement
  /** The gap between the box and the label, in points; defaults to 12. */
  labelOffset?: number
}

/** A rectangle in view points, origin top left. */
export interface TextGuideFrame {
  x: number
  y: number
  width: number
  height: number
}

/** The guide as drawn: the box after clamping, to its stroke's outer edge, and the label while one shows. */
export interface TextGuideLayout {
  box: TextGuideFrame
  label?: TextGuideFrame
}

/**
 * Back-camera preview with a box marking the region text is picked from, in two steps. Live, the camera focuses on the
 * region and keeps its sharpest recent frames; under each new focus the sharpest of its first frames is read in the
 * background with the platform recognizer (ML Kit on Android). Nothing shows over the preview but the guide. The
 * session's capture then takes the best frame with its words for the user to pick from. Each callback fires on change
 * only, starting with the state at the time it is set.
 */
export interface TextCameraProps extends HybridViewProps {
  /**
   * The scan this view shows, which keeps the capture and the guide state. One view shows a session at a time: a
   * second reports `input/invalid` on `onError` and captures nothing until the first lets go.
   */
  session: TextSession
  /** Whether the camera runs; defaults to true. False releases the camera and the kept frames. */
  active?: boolean
  /** The area read, as fractions of the view; clamped to it. Changes apply on the next frame without restarting. */
  textRegion: TextRegion
  /** The box marking the region and its label; the TextCamera wrapper takes color strings. */
  guide?: TextGuide
  /** Tapping the preview focuses there for 5 s, then focus returns to the region; defaults to true. */
  tapToFocus?: boolean
  /**
   * Enables managed focus recovery on Android. Continuous autofocus follows the region and can supply readable
   * frames. With single-shot autofocus and motion sensors, a steady blurred region can trigger a locking scan.
   * The native view defaults to false and the `TextCamera` wrapper enables it by default.
   * See the Android text-camera reference for recovery and capture selection.
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
  /**
   * Where the guide is drawn, in this view's points, also while it is hidden, so a guide drawn by the host can be placed
   * by it; the session's `status` tells what it shows.
   */
  onGuideLayout?: (layout: TextGuideLayout) => void
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

export interface TextCameraMethods extends HybridViewMethods {}

export type TextCameraView = HybridView<
  TextCameraProps,
  TextCameraMethods,
  { ios: 'swift'; android: 'kotlin' }
>
