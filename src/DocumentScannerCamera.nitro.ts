import type { ScannerFailure } from './ScannerFailure'
import type { DocumentCandidate } from './DocumentScannerProcessor.nitro'
import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'

/**
 * `suspended`: no camera is bound (inactive, no permission, hidden, detached or failed); `starting`: binding or waiting for
 * the first preview frame; `ready`: streaming; `capturing`: a `takePhoto` call is in flight, from its focus and
 * steady-photo waits until its shot has settled.
 */
export type CameraState = 'suspended' | 'starting' | 'ready' | 'capturing'

/**
 * Camera2 autofocus state: `scan` covers passive and active scans, `unfocused` covers a passive scan that failed and
 * a lock that did not focus, and `locked` is a tap-to-focus lock that focused.
 */
export type AutofocusState = 'inactive' | 'scan' | 'focused' | 'unfocused' | 'locked'

/**
 * Live guidance: `off` when `guide` is false or no camera is bound, `held` while the last guide is dimmed for
 * up to 400 ms, and `unavailable` after a runtime failure.
 */
export type DocumentState = 'off' | 'searching' | 'tracking' | 'held' | 'unavailable'

/**
 * CameraX still-capture mode: `quality` favors image quality, `latency` the time to deliver each photo, and `zsl`
 * (zero shutter lag) saves a buffered frame from the moment of the request, falling back to `latency` on cameras or
 * configurations without it.
 */
export type CaptureMode = 'quality' | 'latency' | 'zsl'

/**
 * Back-camera torch: `auto` lights it after about a second of dark frames, judged from the exposure time and ISO
 * auto-exposure chooses, and keeps it lit until the camera stops or the mode changes, because a lit torch hides the
 * room's own light. Cameras without a flash unit ignore it.
 */
export type TorchMode = 'off' | 'on' | 'auto'

/**
 * The camera's behaviour profile.
 *
 * `scanner` tunes the camera for documents. While `guide` is on, the lens parks at a fixed distance, about 30 cm at
 * first and then wherever the last focus lock was, so the live guide finds a page without waiting for autofocus. A
 * valid detected page gets one autofocus scan that locks on it; detection runs independently of lens movement. Losing
 * the page, a tap's five seconds, or a failed scan park the lens again instead of resuming continuous
 * autofocus. A photo waits for that document lock rather than focusing itself. Cameras without manual focus control,
 * and photos that do not wait for focus (`qualityGate` off or a `focusTimeout` of 0), keep `camera` focusing, since a
 * photo on a parked lens must wait for a lock.
 *
 * `camera` is a general-purpose camera: continuous autofocus, with document-driven scans while `guide` is on.
 *
 * `off` releases preview, capture and analysis use cases and sensors while keeping the view mounted. An existing
 * capture finishes before release; new capture requests reject with camera/unavailable.
 * Any other mode resumes when visible, in the foreground and permitted.
 */
export type CameraMode = 'scanner' | 'camera' | 'off'

/**
 * A `takePhoto` call's progress, for telling the user when to hold still: `waiting` for a page, focus, exposure, a
 * steadier camera or a torch change; `shooting` from the shot's request until it is exposed; then `captured` once the user may
 * move, or `failed` when the call rejects, which can follow `captured` if saving fails. A call that rejects before it
 * starts waiting reports nothing.
 */
export type CaptureStage = 'waiting' | 'shooting' | 'captured' | 'failed'

/**
 * Focus and motion settings; motion limits are a percent of frame width, and each field merges with its default. Blur is
 * measured from the preview's own image motion (optical flow) over the exposure time, so sliding sideways, moving
 * toward or away from the page and part of the page moving all count, and shake the stabilizer cancels does not.
 * Unknown flow falls back to a gyroscope rotation estimate over the same frame's exposure; cameras whose timestamps
 * do not share the gyroscope clock have no fallback. Capture judges the newest frame result, whatever its age. Unknown
 * motion holds capture until a new estimate arrives or the motion wait times out. A limit of 0 turns that
 * capture motion check off; guidance still uses flow to pace inference and track the page.
 * Focus gating stays on while `qualityGate` is true.
 */
export interface QualityGateConfig {
  /**
   * After focus, `takePhoto` waits up to 1.5 s for stable framing whose frames each have blur at or below this over
   * their own exposure (see `qualityGate`), and rejects if the camera keeps moving. Every region of the page must stay tracked, so
   * a moving or smeared part of the page holds the shot; the gyroscope fallback checks that frame's exposure. Defaults to 0.2;
   * 0 turns off both the blur and the stability check. With `captureMode` `zsl` the saved frame is a buffered one
   * CameraX picks, close to but not necessarily among the frames checked.
   */
  captureBlurPercent?: number
  /**
   * Milliseconds `takePhoto` waits for autofocus to settle, and in `scanner` mode for a confirmed page with it; 0
   * checks immediately. Defaults to 2000.
   * On timeout the shot rejects unless `requireFocus` is explicitly false.
   */
  focusTimeout?: number
  /**
   * `takePhoto` rejects when autofocus has not settled once `focusTimeout` passes, instead of shooting; with a timeout of
   * 0 it rejects unless focus has settled at the press. Defaults to true. False permits shooting after timeout.
   */
  requireFocus?: boolean
}

/** The unmodified JPEG written by CameraX, including its EXIF orientation. */
export interface CapturedPhoto {
  /** Original captured JPEG; the source is never re-encoded during capture. */
  originalUri: string
  /** Encoded pixel width before applying EXIF orientation. */
  width: number
  /** Encoded pixel height before applying EXIF orientation. */
  height: number
  /** Raw EXIF orientation tag (1–8). */
  orientation: number
  /** Clockwise rotation from the EXIF orientation tag. */
  rotationDegrees: number
  /**
   * The live guide's page when the shutter fired: its outline in normalized upright photo coordinates and the score of
   * the latest guide inference. Absent unless the guide was tracking a page with every corner inside the frame.
   * It can seed an immediate crop preview while the saved photo is analyzed independently.
   */
  liveCandidate?: DocumentCandidate
  /**
   * A JPEG of the latest live analysis frame at the shutter, at that frame's own resolution: the `liveCandidate`
   * page straightened when there is one, otherwise the whole frame upright. It can be shown at once while the photo
   * is analyzed and rendered, and is the page's thumbnail until a render replaces it; an `ImageViewer` already holds it.
   * Absent when no analysis frame arrived. A cached file like `originalUri`.
   */
  frameUri?: string
}

/**
 * The native CameraX session always uses the default back camera. Each callback fires on change only, starting with
 * the state at the time it is set.
 */
export interface DocumentScannerCameraProps extends HybridViewProps {
  /** Required local model path/URI. Native prepares it before binding; dimensions come from the tensor. */
  quadTensorModel?: string
  /** Native camera failure. A model preparation failure keeps the camera suspended until the source changes. */
  onError?: (error: ScannerFailure) => void
  /** Behaviour profile, or `off` to release the camera; defaults to `scanner`. */
  mode?: CameraMode
  /**
   * Document-driven autofocus is always enabled with guidance; a provisional page can select its focus region.
   * A successful scan holds focus until distance/target changes or focus failure; taps retain priority.
   * Live DocCornerNet guidance: the guide and its inference; defaults to true. False stops both without rebinding the
   * camera; preview, `takePhoto` and the quality gate continue.
   * Finite, convex, in-frame candidates are accepted immediately, then optical flow carries the outline while inference is paused.
   * Camera-derived tilt is diagnostic and may show a hint to hold the phone parallel; it never rejects an outline.
   */
  guide?: boolean
  /** Pause inference while optical flow tracks a trusted page outline; defaults to false for live
   * comparison. Optical flow still runs every analysis frame and carries the guide through movement. Lost tracking,
   * page-region loss, accumulated scene change restores detection. There are no timed
   * settling phases. Reliable tracking keeps the guide alive between model confirmations.
   */
  adaptiveDetection?: boolean
  /** Minimum live corner confidence, from 0 to 1; defaults to 0.95. 0 disables score rejection.
   * Lower scores clear tracking and pause inference until image movement is measured.
   */
  minCornerScore?: number
  /**
   * Capture focus and motion gating; defaults to true. Detection and tracking run independently of focus state.
   * `takePhoto` starts no focus scan of its own. In
   * `scanner` mode with `guide` on, document detection owns focus: the call waits for a confirmed page and the lock of
   * its document scan, or of a tap, while detection keeps acquiring, refocusing and parking. Otherwise it waits for a
   * tap scan to lock or continuous autofocus to report focused. Either waits up to `qualityGateConfig.focusTimeout`,
   * then rejects unless `requireFocus` is explicitly false. Supported autofocus ZSL captures then wait for focused,
   * converged exposure and converged white balance, the states CameraX requires of a buffered frame. With capture
   * motion checking on, framing must also be stable: four consecutive analyzed frames, each moving under 5% of the
   * frame width per second with its own smear within `captureBlurPercent`, while the page holds its position, scale and
   * shape. A focus change restarts that run. Each frame is judged from its own metadata and motion, in order. The shot
   * fires as soon as everything aligns, with no settling delay; that wait times out after 1500 ms following focus.
   * CameraX still selects the buffered frame, so the photo is the closest available frame, not necessarily one judged.
   * Motion limits come from `qualityGateConfig`. False turns off all of it, whatever the config.
   */
  qualityGate?: boolean
  /** Focus and motion settings for `qualityGate`. */
  qualityGateConfig?: QualityGateConfig
  /**
   * Tapping the preview focuses and meters there for 5 s, after which continuous autofocus resumes, or with
   * in `scanner` mode the lens stays parked where the tap focused; defaults to true.
   */
  tapToFocus?: boolean
  /**
   * Auto-exposure bias in EV stops, rounded to the camera's step and clamped to its range; negative values keep white
   * paper from clipping. Defaults to 0, and cameras without compensation ignore it.
   */
  exposureCompensation?: number
  /**
   * Lowest frame rate auto-exposure may fall to in dim light, which caps each exposure near 1/minFrameRate s: shorter
   * exposures blur less but are noisier. Picks the advertised range with the lowest minimum at or above it (the
   * highest minimum when none reaches it), its maximum nearest 30. Defaults to 0, the camera's own range.
   */
  minFrameRate?: number
  /** Torch mode; defaults to `off`. It applies without rebinding the camera, after any photo in flight settles. */
  torch?: TorchMode
  /** Still-capture mode; defaults to `zsl`. Changing it rebinds the camera, after any photo in flight settles. */
  captureMode?: CaptureMode
  /**
   * JPEG quality from 0 to 1; when omitted the capture mode chooses (1 for `quality`, 0.95 for `latency`). Changing
   * it rebinds the camera. Values above 1 count as 1, and negative or non-finite ones as omitted; the lowest value uses
   * CameraX's minimum quality of 1/100.
   */
  jpegQuality?: number
  /** Duration in milliseconds of the guide's easing toward each new detection; defaults to 50, and 0 snaps. */
  guideTransitionDuration?: number
  /**
   * Requested width in pixels, before rotation, of the 4:3 frames live detection analyzes; the camera picks the
   * closest size it supports, and the visible preview keeps its own resolution. Defaults to 640. Changing it rebinds
   * the camera.
   */
  analysisWidth?: number
  /**
   * Crops the camera to the centered square of its frame: the preview, live analysis and every photo show only that
   * square, so the model's square input is not stretched and review shows what the model saw. Defaults to false, the
   * full 4:3 frame. Changing it rebinds the camera, after any photo in flight settles.
   */
  squareFrame?: boolean
  /** Reserves a fixed strip above the preview for status, model score, timing and blur figures; defaults to false. */
  showStats?: boolean
  /** ARGB color of the guide and the searching viewfinder; the Camera wrapper takes a color string. Defaults to teal. */
  guideColor?: number
  /**
   * Animates each `takePhoto` over the preview, around the guide outline or, without one, the viewfinder area: a pulse
   * while waiting, a line that runs around it while shooting, then a flash and a haptic tick when the user may move, or
   * a red blink on failure. Defaults to true.
   */
  captureFeedback?: boolean
  /** Retain the frozen shutter frame after capture until this becomes false. Defaults to false. */
  retainCapturePreview?: boolean
  /** ARGB color of the capture feedback; the Camera wrapper takes a color string. Defaults to white. */
  captureColor?: number
  onCameraStateChange?: (state: CameraState) => void
  onAutofocusChange?: (state: AutofocusState) => void
  onDocumentStateChange?: (state: DocumentState) => void
  /** Each stage of each `takePhoto` call as it happens; unlike the state callbacks, it is not re-sent when set. */
  onCaptureStage?: (stage: CaptureStage) => void
}

/** Overrides for one capture; camera settings and later shots are unchanged. */
export interface TakePhotoOptions {
  /** Skip document, focus, lighting and motion waits for this shot. Defaults to false.
   * Camera readiness and permission checks still apply.
   */
  skipQualityGate?: boolean
}

export interface DocumentScannerCameraMethods extends HybridViewMethods {
  /**
   * Native result transport for public `takePhoto`: one saved JPEG or a failure code. The view shows a frozen
   * preview frame while the shot saves.
   */
  takePhotoResult(options?: TakePhotoOptions): Promise<CapturedPhoto | ScannerFailure>
  /**
   * Native result transport for public `saveLiveSample`: saves the latest live inference frame, as the model saw it,
   * into the sample folder as `<ms>-<long edge>.png` beside `<ms>-<long edge>.json`, a `tools/label-samples`
   * entry with the model's outline as its unconfirmed starting quad and the model's raw prediction, never the guide
   * after stabilizing or optical flow. `<ms>` is the save time. Resolves
   * with the image's file name; fails with `input/invalid` while sample collection is off and `camera/not-ready` before the
   * first inference.
   */
  saveLiveSampleResult(): Promise<string | ScannerFailure>
}

export type DocumentScannerCameraView = HybridView<
  DocumentScannerCameraProps,
  DocumentScannerCameraMethods,
  { android: 'kotlin' }
>
