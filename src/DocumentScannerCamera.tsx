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
  CaptureWaitReason,
  DocumentState,
  CameraCaptureState,
  TakePhotoOptions,
  DocumentScannerCameraMethods,
  DocumentScannerCameraProps,
} from './DocumentScannerCamera.nitro'
import DocumentScannerCameraViewConfig from '../nitrogen/generated/shared/json/DocumentScannerCameraViewConfig.json'
import { ScannerError, ScannerCancellation, scannerOperation } from './ScannerError'
import type { ScannerFailure } from './ScannerFailure'
import { getNativeScanSession, synchronizeSession, type ScanSession } from './ScanSession'

const NativeCamera = getHostComponent<
  DocumentScannerCameraProps,
  DocumentScannerCameraMethods
>('DocumentScannerCameraView', () => DocumentScannerCameraViewConfig)

/** The camera permission is read and requested through `CameraPermission` or `useCameraPermission`. */
export interface CameraRef {
  /** Capture into the required session. The returned page is in its published snapshot; rendering may continue. */
  takePhoto(options?: TakePhotoOptions): Promise<string>
}

// React sends null for a prop that a render removes, and Nitro rejects null for optional props, so every native
// prop always gets a concrete value: the native default, an empty gate config, or a no-op callback.
const DEFAULT_GUIDE_COLOR = processColor('#61E1CF') as number // LiveQuadOverlay.DEFAULT_COLOR.
const DEFAULT_CAPTURE_COLOR = processColor('#FFFFFF') as number // CaptureFeedbackView.DEFAULT_COLOR.
const DEFAULT_QUALITY_GATE_CONFIG = {}
const noop = callback(() => {})

export interface ScannerProps
  extends Omit<DocumentScannerCameraProps, 'hybridRef' | 'guideColor' | 'captureColor' | 'onError' | 'session' | 'renderOptions'>,
    ViewProps {
  /** Native session that owns new captures. */
  session: ScanSession
  renderOptions?: DocumentScannerCameraProps['renderOptions']
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

const DEFAULT_RENDER_OPTIONS = {}

/** The props `Camera` enforces, so the settings that make a scanner never reach it. */
type ScannerOnlyProps = 'documentDetection' | 'autoCrop' | 'adaptiveDetection' | 'minCornerScore' | 'guideColor' |
  'guideTransitionDuration' | 'onDocumentStateChange'

/**
 * A general-purpose camera over the same native view: no live detection or guide, plain continuous autofocus, captures
 * that never wait for a page, photos that keep the whole frame and their own colours (no filter, no shadow removal),
 * and a standard 1440x1080-class photo unless `highResolutionPhoto`. Those settings are enforced: scanner-only props
 * passed anyway, as from shared app settings, are ignored. Every other prop works as on `Scanner`.
 */
export interface CameraProps extends Omit<ScannerProps, ScannerOnlyProps | 'renderOptions'> {
  /** Render defaults for new pages; `filter` and `removeShadows` are always `none` and off here. */
  renderOptions?: Omit<NonNullable<ScannerProps['renderOptions']>, 'filter' | 'removeShadows'>
}

/**
 * The document scanner: live detection with the guide, document focus, captures that wait for a page, photos cropped
 * to it and the photo size for text. Every prop can override these defaults.
 */
export const Scanner = forwardRef<CameraRef, ScannerProps>(function Scanner(props, ref) {
  return <NativeCameraView {...props} ref={ref} />
})

export const Camera = forwardRef<CameraRef, CameraProps>(function Camera(
  { renderOptions = DEFAULT_RENDER_OPTIONS, highResolutionPhoto = false, ...props },
  ref,
) {
  const shared: Partial<ScannerProps> = { ...props }
  for (const key of SCANNER_ONLY_PROPS) delete shared[key]
  const photoOptions = useMemo(
    () => ({ ...renderOptions, filter: 'none' as const, removeShadows: false }),
    [renderOptions],
  )
  return (
    <NativeCameraView
      {...(shared as ScannerProps)}
      documentDetection={false}
      autoCrop={false}
      highResolutionPhoto={highResolutionPhoto}
      renderOptions={photoOptions}
      ref={ref}
    />
  )
})

const SCANNER_ONLY_PROPS: readonly ScannerOnlyProps[] = ['documentDetection', 'autoCrop', 'adaptiveDetection',
  'minCornerScore', 'guideColor', 'guideTransitionDuration', 'onDocumentStateChange']

/** The native view with every prop explicit; `Scanner` and `Camera` choose its defaults. */
const NativeCameraView = forwardRef<CameraRef, ScannerProps>(function NativeCameraView(
  {
    quadTensorModel,
    session,
    renderOptions = DEFAULT_RENDER_OPTIONS,
    active = true,
    documentDetection = true,
    autoCrop = true,
    highResolutionPhoto = true,
    adaptiveDetection = false,
    minCornerScore = 0.95,
    qualityGate = true,
    qualityGateConfig = DEFAULT_QUALITY_GATE_CONFIG,
    tapToFocus = true,
    exposureCompensation = 0,
    minFrameRate = 0,
    torch = 'off',
    lowLightBoost = true,
    captureMode = 'zsl',
    jpegQuality,
    guideTransitionDuration = 80,
    analysisWidth = 640,
    squareFrame = false,
    showStats = false,
    guideColor,
    captureFeedback = true,
    captureColor,
    onCameraStateChange,
    onCaptureStateChange,
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
  const captureSession = useRef(session)
  useLayoutEffect(() => { captureSession.current = session }, [session])
  const handlers = useRef({ onCaptureStateChange, onCameraStateChange, onAutofocusChange, onDocumentStateChange, onCaptureStage, onError })
  useLayoutEffect(() => {
    handlers.current = { onCaptureStateChange, onCameraStateChange, onAutofocusChange, onDocumentStateChange, onCaptureStage, onError }
  })
  const hasCameraState = onCameraStateChange != null
  const hasCaptureState = onCaptureStateChange != null
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
  const nativeCaptureState = useMemo(
    () => hasCaptureState
      ? callback((state: CameraCaptureState) => handlers.current.onCaptureStateChange?.(state))
      : noop,
    [hasCaptureState],
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
      ? callback((stage: CaptureStage, waitReason?: CaptureWaitReason) => handlers.current.onCaptureStage?.(stage, waitReason))
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
        takePhoto: async (options?: TakePhotoOptions) => {
          const owner = captureSession.current
          const id = await scannerOperation(() => requireNativeView().takePhotoResult(options), 'capture/failed')
          synchronizeSession(owner)
          if (owner.getSession().pages[id] === undefined) throw new ScannerCancellation('processor/superseded')
          return id
        },
      }
    },
    [],
  )

  return (
    <NativeCamera
      {...props}
      session={getNativeScanSession(session)}
      active={active}
      quadTensorModel={quadTensorModel ?? ''}
      onError={nativeError}
      documentDetection={documentDetection}
      autoCrop={autoCrop}
      highResolutionPhoto={highResolutionPhoto}
      adaptiveDetection={adaptiveDetection}
      minCornerScore={minCornerScore}
      qualityGate={qualityGate}
      renderOptions={renderOptions}
      qualityGateConfig={qualityGateConfig}
      tapToFocus={tapToFocus}
      exposureCompensation={exposureCompensation}
      minFrameRate={minFrameRate}
      torch={torch}
      lowLightBoost={lowLightBoost}
      captureMode={captureMode}
      // Native reads a negative or non-finite value as omitted, since a prop removed in React keeps its last value.
      jpegQuality={jpegQuality ?? -1}
      guideTransitionDuration={guideTransitionDuration}
      analysisWidth={analysisWidth}
      squareFrame={squareFrame}
      showStats={showStats}
      guideColor={nativeGuideColor}
      captureFeedback={captureFeedback}
      captureColor={nativeCaptureColor}
      onCameraStateChange={nativeCameraState}
      onCaptureStateChange={nativeCaptureState}
      onAutofocusChange={nativeAutofocus}
      onDocumentStateChange={nativeDocumentState}
      onCaptureStage={nativeCaptureStage}
      hybridRef={setNativeRef}
    />
  )
})
