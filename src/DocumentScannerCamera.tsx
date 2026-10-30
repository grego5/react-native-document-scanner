import {
  callback,
  getHostComponent,
} from 'react-native-nitro-modules'
import {
  forwardRef,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
} from 'react'
import { processColor, type ViewProps } from 'react-native'
import type {
  AutofocusState,
  CameraState,
  CaptureStage,
  DocumentState,
  CapturedPhoto,
  TakePhotoOptions,
  DocumentScannerCameraMethods,
  DocumentScannerCameraProps,
} from './DocumentScannerCamera.nitro'
import DocumentScannerCameraViewConfig from '../nitrogen/generated/shared/json/DocumentScannerCameraViewConfig.json'
import { ScannerError, scannerOperation } from './ScannerError'
import type { ScannerFailure } from './ScannerFailure'

const NativeCamera = getHostComponent<
  DocumentScannerCameraProps,
  DocumentScannerCameraMethods
>('DocumentScannerCameraView', () => DocumentScannerCameraViewConfig)

/** The camera permission is read and requested through `CameraPermission` or `useCameraPermission`. */
export interface CameraRef {
  /** Capture a full resolution JPEG and return its original file URI and EXIF orientation. */
  takePhoto(options?: TakePhotoOptions): Promise<CapturedPhoto>
  /** See `DocumentScannerCameraMethods.saveLiveSampleResult`; resolves with the saved image's file name. */
  saveLiveSample(): Promise<string>
}

// React sends null for a prop that a render removes, and Nitro rejects null for optional props, so every native
// prop always gets a concrete value: the native default, an empty gate config, or a no-op callback.
const DEFAULT_GUIDE_COLOR = processColor('#61E1CF') as number // LiveQuadOverlay.DEFAULT_COLOR.
const DEFAULT_CAPTURE_COLOR = processColor('#FFFFFF') as number // CaptureFeedbackView.DEFAULT_COLOR.
const DEFAULT_QUALITY_GATE_CONFIG = {}
const noop = callback(() => {})

export interface CameraProps
  extends Omit<DocumentScannerCameraProps, 'hybridRef' | 'guideColor' | 'captureColor' | 'onError'>,
    ViewProps {
  /** Required local model source. Camera stays off until validation succeeds; failures emit onError. */
  quadTensorModel: string
  onError?: (error: ScannerError) => void
  /**
   * Guide color for every guide state, as a React Native color string such as '#ff3b30', 'rgba(255, 59, 48, 0.8)' or
   * 'red'; defaults to teal. PlatformColor and DynamicColorIOS values are not supported.
   */
  guideColor?: string
  /** Capture feedback color, as a color string like `guideColor`; failure always blinks red. Defaults to white. */
  captureColor?: string
}

/** A color string as the native ARGB number, or [fallback] for none or an unsupported value. */
function nativeColor(prop: string, value: string | undefined, fallback: number): number {
  if (value == null) return fallback
  const color = processColor(value)
  if (typeof color === 'number') return color
  if (__DEV__) console.warn(`Camera: unsupported ${prop} ${JSON.stringify(value)}; using the default.`)
  return fallback
}

/** CameraX preview and still capture, hosted by a Nitro native view on Android. */
export const Camera = forwardRef<CameraRef, CameraProps>(function Camera(
  {
    quadTensorModel,
    mode = 'scanner',
    guide = true,
    adaptiveDetection = false,
    minCornerScore = 0.95,
    qualityGate = true,
    qualityGateConfig = DEFAULT_QUALITY_GATE_CONFIG,
    tapToFocus = true,
    exposureCompensation = 0,
    minFrameRate = 0,
    torch = 'off',
    captureMode = 'zsl',
    jpegQuality,
    guideTransitionDuration = 50,
    analysisWidth = 640,
    squareFrame = false,
    showStats = false,
    guideColor,
    captureFeedback = true,
    retainCapturePreview = false,
    captureColor,
    onCameraStateChange,
    onAutofocusChange,
    onDocumentStateChange,
    onCaptureStage,
    onError,
    ...props
  },
  forwardedRef,
) {
  // Native re-sends the current state whenever a callback prop changes, so pass a stable wrapper that calls the
  // latest handler, and change it only when a handler is added or removed; without a handler it gets a no-op.
  const handlers = useRef({ onCameraStateChange, onAutofocusChange, onDocumentStateChange, onCaptureStage, onError })
  useLayoutEffect(() => {
    handlers.current = { onCameraStateChange, onAutofocusChange, onDocumentStateChange, onCaptureStage, onError }
  })
  const hasCameraState = onCameraStateChange != null
  const hasAutofocus = onAutofocusChange != null
  const hasDocumentState = onDocumentStateChange != null
  const hasCaptureStage = onCaptureStage != null
  // Separate memos, so adding or removing one handler does not make native resend the others' states.
  const nativeCameraState = useMemo(
    () => hasCameraState
      ? callback((state: CameraState) => handlers.current.onCameraStateChange?.(state))
      : noop,
    [hasCameraState],
  )
  const nativeAutofocus = useMemo(
    () => hasAutofocus
      ? callback((state: AutofocusState) => handlers.current.onAutofocusChange?.(state))
      : noop,
    [hasAutofocus],
  )
  const nativeDocumentState = useMemo(
    () => hasDocumentState
      ? callback((state: DocumentState) => handlers.current.onDocumentStateChange?.(state))
      : noop,
    [hasDocumentState],
  )
  const nativeCaptureStage = useMemo(
    () => hasCaptureStage
      ? callback((stage: CaptureStage) => handlers.current.onCaptureStage?.(stage))
      : noop,
    [hasCaptureStage],
  )
  const hasError = onError != null
  const nativeError = useMemo(
    () => hasError ? callback((error: ScannerFailure) => handlers.current.onError?.(new ScannerError(error.code))) : noop,
    [hasError],
  )
  const nativeGuideColor = useMemo(() => nativeColor('guideColor', guideColor, DEFAULT_GUIDE_COLOR), [guideColor])
  const nativeCaptureColor = useMemo(
    () => nativeColor('captureColor', captureColor, DEFAULT_CAPTURE_COLOR),
    [captureColor],
  )
  const nativeRef = useRef<DocumentScannerCameraMethods | null>(null)
  const setNativeRef = useMemo(
    () =>
      callback((view: DocumentScannerCameraMethods | null) => {
        nativeRef.current = view
      }),
    [],
  )

  useImperativeHandle(
    forwardedRef,
    () => {
      const requireNativeView = () => {
        if (nativeRef.current == null) {
          throw new ScannerError('camera/unavailable')
        }
        return nativeRef.current
      }

      return {
        takePhoto: (options?: TakePhotoOptions) => scannerOperation(() => requireNativeView().takePhotoResult(options), 'capture/failed'),
        saveLiveSample: () => scannerOperation(() => requireNativeView().saveLiveSampleResult(), 'storage/write-failed'),
      }
    },
    [],
  )

  return (
    <NativeCamera
      {...props}
      mode={mode}
      quadTensorModel={quadTensorModel ?? ''}
      onError={nativeError}
      guide={guide}
      adaptiveDetection={adaptiveDetection}
      minCornerScore={minCornerScore}
      qualityGate={qualityGate}
      qualityGateConfig={qualityGateConfig}
      tapToFocus={tapToFocus}
      exposureCompensation={exposureCompensation}
      minFrameRate={minFrameRate}
      torch={torch}
      captureMode={captureMode}
      // Native reads a negative or non-finite value as omitted, since a prop removed in React keeps its last value.
      jpegQuality={jpegQuality ?? -1}
      guideTransitionDuration={guideTransitionDuration}
      analysisWidth={analysisWidth}
      squareFrame={squareFrame}
      showStats={showStats}
      guideColor={nativeGuideColor}
      captureFeedback={captureFeedback}
      retainCapturePreview={retainCapturePreview}
      captureColor={nativeCaptureColor}
      onCameraStateChange={nativeCameraState}
      onAutofocusChange={nativeAutofocus}
      onDocumentStateChange={nativeDocumentState}
      onCaptureStage={nativeCaptureStage}
      hybridRef={setNativeRef}
    />
  )
})
