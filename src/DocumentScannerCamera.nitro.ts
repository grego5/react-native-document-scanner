import type { ScanSession, ScanSessionSnapshot } from './ScanSession.nitro'
import type { ScannerFailure } from './ScannerFailure'
import type { DocumentFilter, DocumentImageFormat } from './DocumentScannerProcessor.nitro'
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
 * Document scanning: `loading` while the guide or `autoCrop` waits for its corner model, and the camera stays off;
 * `unavailable` when that model failed (`model/load-failed` or, without any model, `input/invalid` on `onError`) or
 * the live detector failed. Otherwise `off` when `guideLiveCorners` is false or no camera is bound, and `held` while
 * the last guide is dimmed for up to 400 ms.
 */
export type DocumentState = 'off' | 'loading' | 'searching' | 'tracking' | 'held' | 'unavailable'

/**
 * Still-capture mode: `quality` favors image quality, `latency` the time to deliver each photo, and `zsl`
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
 * A `takePhoto` call's progress, for telling the user when to hold still: `waiting` for a page, focus, exposure, a
 * steadier camera or a torch change; `shooting` from the shot's request until it is exposed; then `captured` once the user may
 * move, or `failed` when the call rejects, which can follow `captured` if saving fails. A call that rejects before it
 * starts waiting reports nothing.
 */
export type CaptureStage = 'waiting' | 'shooting' | 'captured' | 'failed'
export type CaptureWaitReason = 'document' | 'focus' | 'light' | 'steady'

/** Native readiness and capture progress; inactive cameras report unavailable. */
export type CameraCaptureState = 'unavailable' | 'ready' | 'waiting' | 'shooting' | 'saving'

/**
 * Capture readiness checks. Motion blur is measured as a percentage of frame width over the exposure; on Android
 * optical flow falls back to gyroscope motion when available, while iOS treats low-texture motion as unknown. iOS
 * judges focus from whether the lens is still, since AVFoundation reports no focus result.
 */
export interface LowQualityControl {
  /** Enables capture quality checks; defaults to true. */
  enabled?: boolean
  /**
   * Maximum exposure blur as a percentage of frame width; defaults to 0.2. Zero disables motion and stability checks.
   * After focus, capture waits up to one second for stable framing. A ZSL capture may use a different buffered frame.
   */
  captureBlurPercent?: number
  /**
   * Wait for autofocus and require focus; defaults to true. A scan in progress can extend the four-second wait.
   * With live guidance, capture also waits for a page. When false, focus is not required and capture may proceed
   * without a page after the wait. ZSL can still wait for an acceptable buffered frame.
   */
  requireFocus?: boolean
}

export interface ImageCorrectionOptions {
  removeShadows?: boolean
  filter?: DocumentFilter
  simpleDewarp?: boolean
}

export interface ImageOutputOptions {
  jpegQuality?: number
  format?: DocumentImageFormat
  /** Paper width in centimeters; provide both dimensions. */
  width?: number
  /** Paper height in centimeters; provide both dimensions. */
  height?: number
  /** Output density in dots per inch; requires both paper dimensions. */
  dpi?: number
}

/**
 * The native CameraX session always uses the default back camera. Each callback fires on change only, starting with
 * the state at the time it is set.
 */
export interface DocumentScannerCameraProps extends HybridViewProps {
  /** Explicit owner of all captures; replacing or closing it invalidates pending publication. */
  session: ScanSession
  /** Correction and output defaults for new pages, frozen when capture is admitted. */
  imageCorrection: ImageCorrectionOptions
  outputOptions: ImageOutputOptions
  /**
   * Local model path or URI. When `guideLiveCorners` or `autoCrop` is enabled, an omitted or empty source uses the
   * Expo plugin's model. If that model is absent, the camera stays off and reports `input/invalid`.
   */
  cornerDetectionModel?: string
  /** Native camera failure. A model preparation failure keeps the camera suspended until the source changes. */
  /** Reports camera failures and the non-blocking `camera/analysis-slow` warning. */
  onError?: (error: ScannerFailure) => void
  /**
   * Whether the camera runs; defaults to true. False releases preview, capture and analysis use cases and sensors while
   * keeping the view mounted: a capture in progress finishes first, and new ones reject with `camera/unavailable`. True
   * resumes once visible, in the foreground and permitted.
   */
  active?: boolean
  /**
   * Enable live corner detection and the guide; defaults to true. Android also aims focus at the guide and can wait for
   * a detected page during capture. Toggling the guide keeps the current model request while `autoCrop` stays enabled
   * or the same explicit source is set. With an omitted or empty source, disabling both lets the camera run without
   * waiting for a model.
   */
  guideLiveCorners?: boolean
  /**
   * Crops each new photo to the page its analysis finds; defaults to true. False keeps the whole frame: the page is still
   * analyzed, for the crop editor's `auto` preset and samples, but its outline is never applied on its own.
   */
  autoCrop?: boolean
  /** Pause inference while optical flow tracks a trusted outline; defaults to false. Tracking loss or sufficient scene
   * change resumes detection.
   */
  inferenceThrottling?: boolean
  /** Minimum live corner confidence, from 0 to 1; defaults to 0.95. 0 disables score rejection.
   * Lower scores clear the tracked outline. See `inferenceThrottling` for adaptive retry timing.
   */
  minCornerScore?: number
  /**
   * Capture readiness checks, enabled by default. See `LowQualityControl` for focus and motion options.
   * Detection and tracking run independently of focus.
   */
  lowQualityControl: LowQualityControl
  /**
   * Tapping the preview focuses and meters there for 5 s, after which continuous autofocus resumes; defaults to true.
   * Taps are ignored while a photo is in flight.
   */
  tapToFocus?: boolean
  /**
   * Auto-exposure bias in EV stops, rounded to the camera's step and clamped to its range; negative values keep white
   * paper from clipping. Defaults to 0, and cameras without compensation ignore it. A change made while a capture
   * is pending waits for it to end, so the shot keeps its exposure; only the latest value then applies.
   */
  exposureCompensation?: number
  /**
   * Lowest frame rate auto-exposure may fall to in dim light, which caps each exposure near 1/minFrameRate s: shorter
   * exposures blur less but are noisier. Picks the range the camera supports for its bound use cases with the lowest
   * minimum at or above it (the highest minimum when none reaches it), its maximum nearest 30. Defaults to 0, the
   * camera's own range. Changing it rebinds the camera, after any photo in flight settles.
   */
  minFrameRate?: number
  /**
   * Torch mode; defaults to `off`. It applies without rebinding the camera, after any pending photo settles.
   */
  torch?: TorchMode
  /**
   * Allow automatic low-light boost on supported cameras. Defaults to true. Android requires Android 15+.
   * The camera manages boost activity. Changes wait for a pending capture.
   */
  lowLightBoost?: boolean
  /**
   * Still-capture mode; defaults to `zsl`. Changing it rebinds after an in-flight photo settles. Android may bind
   * `latency` instead when ZSL is unavailable or lighting conditions make its buffered frames unsuitable.
   */
  captureMode?: CaptureMode
  /**
   * The photo size for text, preferring 4:3 within 4096 px and 12.6 MP, widest short side first; other aspects
   * are binding fallbacks. Defaults to true. On Android that
   * includes the sizes the camera lists as high-resolution (below 20 fps, generally without zero shutter lag) when no
   * normal 4:3 size has a 2400 px short side, as on limited phones that list their 12 MP size only there. False takes a
   * standard photo, the smallest 4:3 with a 1080 px short side, as 1440x1080. Changing it rebinds the camera.
   */
  highResolutionPhoto?: boolean
  /**
   * Time constant in milliseconds of the guide's easing toward each new detection when it moved little, mostly detection
   * jitter; larger moves ease faster, down to 20 ms by a tenth of the preview's diagonal, as the One Euro filter adapts
   * to speed, and a corner moving over a fifth of it snaps. Defaults to 80; 0 snaps every move.
   */
  guideTransitionDuration?: number
  /**
   * Requested width in pixels, before rotation, of the 4:3 frames live detection analyzes; the camera picks the
   * closest size it supports, and the visible preview keeps its own resolution. Defaults to 640. Changing it rebinds
   * the camera.
   */
  analysisFrameWidth?: number
  /**
   * Crops the camera to the centered square of its frame: the preview, live analysis and every photo show only that
   * square, so the model's square input is not stretched and review shows what the model saw. Defaults to false, the
   * full 4:3 frame. Android rebinds after an in-flight photo settles; iOS changes framing without rebinding.
   */
  squareAspectRatio?: boolean
  /** Shows native camera diagnostics in fixed rows. Defaults to false. */
  showStats?: boolean
  /** ARGB color of the guide and the searching viewfinder; the Camera wrapper takes a color string. Defaults to teal. */
  guideColor?: number
  /**
   * Animates each `takePhoto` over the preview, around the guide outline or, without one, the viewfinder area: a pulse
   * while waiting, a line that runs around it while shooting, then a flash and a haptic tick when the user may move, or
   * a red blink on failure. Defaults to true.
   */
  captureFeedback?: boolean
  /** ARGB color of the capture feedback; the Camera wrapper takes a color string. Defaults to white. */
  captureColor?: number
  onCameraStateChange?: (state: CameraState) => void
  onCaptureStateChange?: (state: CameraCaptureState) => void
  onAutofocusChange?: (state: AutofocusState) => void
  /** iOS diagnostic for actual low-light boost engagement. */
  onLowLightBoostActiveChange?: (active: boolean) => void
  onDocumentStateChange?: (state: DocumentState) => void
  /** Each capture stage and changes to its waiting reason, independent of captureFeedback. Reason is absent outside waiting (and before the first gate decision). Not re-sent when the handler is set. */
  onCaptureStage?: (stage: CaptureStage, waitReason?: CaptureWaitReason) => void
}

/** Overrides for one capture; camera settings and later shots are unchanged. */
export interface TakePhotoOptions {
  /** Skip document, focus, lighting and motion waits for this shot. Defaults to false.
   * Camera readiness and permission checks still apply. A tap's scan still in progress is waited for until it settles,
   * unless `requireFocus` is false; the shot then uses continuous autofocus as it stands, or the tap's lock.
   */
  skipLowQualityControl?: boolean
}

export interface CameraCaptureResult {
  pageId: string
  snapshot: ScanSessionSnapshot
}

export interface DocumentScannerCameraMethods extends HybridViewMethods {
  /**
   * Native result transport for public `takePhoto`: the new page ID and a versioned session snapshot, or a failure code. The view shows a frozen
   * preview frame while the shot saves. Originals remain private session resources: Android captures JPEG;
   * the iOS source layer uses provisional JPEG backing and can reopen TIFF/JPEG/PNG.
   */
  getCameraFeatures(): CameraFeatureSupport | undefined
  takePhotoResult(options?: TakePhotoOptions): Promise<CameraCaptureResult | ScannerFailure>
}

export interface CameraFeatureSupport {
  lowLightBoostSupported: boolean
  zeroShutterLagSupported: boolean
}

export type DocumentScannerCameraView = HybridView<
  DocumentScannerCameraProps,
  DocumentScannerCameraMethods,
  { ios: 'swift'; android: 'kotlin' }
>
