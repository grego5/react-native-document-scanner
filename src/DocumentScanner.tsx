import { callback, getHostComponent } from 'react-native-nitro-modules';
import { forwardRef, useImperativeHandle, useLayoutEffect, useMemo, useRef } from 'react';
import { processColor, type ViewProps } from 'react-native';
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
  LowQualityControl,
  ImageCorrectionOptions,
  ImageOutputOptions,
} from './DocumentScannerCamera.nitro';
import DocumentScannerCameraViewConfig from '../nitrogen/generated/shared/json/DocumentScannerCameraViewConfig.json';
import { ScannerError, ScannerCancellation, scannerOperation } from './ScannerError';
import type { ScannerFailure } from './ScannerFailure';
import { getNativeScanSession, synchronizeSession, type ScanSession } from './ScanSession';

const NativeCameraHost = getHostComponent<DocumentScannerCameraProps, DocumentScannerCameraMethods>(
  'DocumentScannerCameraView',
  () => DocumentScannerCameraViewConfig,
);

/** The camera permission is read and requested through `CameraPermission` or `useCameraPermission`. */
export interface CameraRef {
  /** Capture into the required session. The returned page is in its published snapshot; rendering may continue. */
  takePhoto(options?: TakePhotoOptions): Promise<string>;
}

// React sends null for a prop that a render removes, and Nitro rejects null for optional props, so every native
// prop always gets a concrete value: the native default, an empty gate config, or a no-op callback.
const DEFAULT_GUIDE_COLOR = processColor('#61E1CF') as number; // LiveGuideOverlay.DEFAULT_COLOR.
const DEFAULT_CAPTURE_COLOR = processColor('#FFFFFF') as number; // CaptureFeedbackView.DEFAULT_COLOR.
const DEFAULT_LOW_QUALITY_CONTROL = {};
const noop = callback(() => {});

export type CameraMode = 'document' | 'photo';

export interface CameraProps
  extends
    Omit<
      DocumentScannerCameraProps,
      'hybridRef' | 'guideColor' | 'captureColor' | 'onError' | 'session' | 'imageCorrection' | 'outputOptions' | 'lowQualityControl'
    >,
    ViewProps {
  /** Document scanning by default; photo disables document guidance and processing. */
  mode?: CameraMode;
  lowQualityControl?: LowQualityControl;
  /** Native session that owns new captures. */
  session: ScanSession;
  imageCorrection?: ImageCorrectionOptions;
  outputOptions?: ImageOutputOptions;
  /** Required local model source. Camera stays off until validation succeeds; failures emit onError. */
  cornerDetectionModel: string;
  onError?: (error: ScannerError) => void;
  /**
   * Guide color for every guide state, as a React Native color string such as '#ff3b30', 'rgba(255, 59, 48, 0.8)' or
   * 'red'; defaults to teal. PlatformColor and DynamicColorIOS values are not supported.
   */
  guideColor?: string;
  /** Capture feedback color, as a color string like `guideColor`; failure always blinks red. Defaults to white. */
  captureColor?: string;
}

/** A color string as the native ARGB number, or [fallback] for none or an unsupported value. */
function nativeColor(prop: string, value: string | undefined, fallback: number): number {
  if (value == null) return fallback;
  const color = processColor(value);
  if (typeof color === 'number') return color;
  if (__DEV__)
    console.warn(`Camera: unsupported ${prop} ${JSON.stringify(value)}; using the default.`);
  return fallback;
}

const DEFAULT_IMAGE_CORRECTION = {};
const DEFAULT_OUTPUT_OPTIONS = {};
const PHOTO_CORRECTION = { filter: 'none', removeShadows: false, simpleDewarp: false } as const;

/** Resolves the profile in React while retaining one native camera view. */
export const Camera = forwardRef<CameraRef, CameraProps>(function Camera(
  {
    mode = 'document',
    cornerDetectionModel,
    session,
    imageCorrection = DEFAULT_IMAGE_CORRECTION,
    outputOptions = DEFAULT_OUTPUT_OPTIONS,
    active = true,
    guideLiveCorners = true,
    autoCrop = true,
    highResolutionPhoto,
    inferenceThrottling = false,
    minCornerScore = 0.95,
    lowQualityControl = DEFAULT_LOW_QUALITY_CONTROL,
    tapToFocus = true,
    exposureCompensation = 0,
    minFrameRate = 0,
    torch = 'off',
    lowLightBoost = true,
    captureMode = 'zsl',
    guideTransitionDuration = 80,
    analysisFrameWidth = 640,
    squareAspectRatio = false,
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
  const photo = mode === 'photo';
  const effectiveDocumentState = mode === 'photo' ? undefined : onDocumentStateChange;
  // Native re-sends the current state whenever a callback prop changes, so pass a stable wrapper that calls the
  // latest handler, and change it only when a handler is added or removed; without a handler it gets a no-op.
  const handlers = useRef({
    onCaptureStateChange,
    onCameraStateChange,
    onAutofocusChange,
    onDocumentStateChange: effectiveDocumentState,
    onCaptureStage,
    onError,
  });
  useLayoutEffect(() => {
    handlers.current = {
      onCaptureStateChange,
      onCameraStateChange,
      onAutofocusChange,
      onDocumentStateChange: effectiveDocumentState,
      onCaptureStage,
      onError,
    };
  });
  const hasCameraState = onCameraStateChange != null;
  const hasCaptureState = onCaptureStateChange != null;
  const hasAutofocus = onAutofocusChange != null;
  const hasDocumentState = effectiveDocumentState != null;
  const hasCaptureStage = onCaptureStage != null;
  // Separate memos, so adding or removing one handler does not make native resend the others' states.
  const nativeCameraState = useMemo(
    () =>
      hasCameraState
        ? callback((state: CameraState) => handlers.current.onCameraStateChange?.(state))
        : noop,
    [hasCameraState],
  );
  const nativeCaptureState = useMemo(
    () =>
      hasCaptureState
        ? callback((state: CameraCaptureState) => handlers.current.onCaptureStateChange?.(state))
        : noop,
    [hasCaptureState],
  );
  const nativeAutofocus = useMemo(
    () =>
      hasAutofocus
        ? callback((state: AutofocusState) => handlers.current.onAutofocusChange?.(state))
        : noop,
    [hasAutofocus],
  );
  const nativeDocumentState = useMemo(
    () =>
      hasDocumentState
        ? callback((state: DocumentState) => handlers.current.onDocumentStateChange?.(state))
        : noop,
    [hasDocumentState],
  );
  const nativeCaptureStage = useMemo(
    () =>
      hasCaptureStage
        ? callback((stage: CaptureStage, waitReason?: CaptureWaitReason) =>
            handlers.current.onCaptureStage?.(stage, waitReason),
          )
        : noop,
    [hasCaptureStage],
  );
  const hasError = onError != null;
  const nativeError = useMemo(
    () =>
      hasError
        ? callback((error: ScannerFailure) =>
            handlers.current.onError?.(new ScannerError(error.code)),
          )
        : noop,
    [hasError],
  );
  const effectiveGuideColor = photo ? undefined : guideColor;
  const nativeGuideColor = useMemo(
    () => nativeColor('guideColor', effectiveGuideColor, DEFAULT_GUIDE_COLOR),
    [effectiveGuideColor],
  );
  const nativeCaptureColor = useMemo(
    () => nativeColor('captureColor', captureColor, DEFAULT_CAPTURE_COLOR),
    [captureColor],
  );
  const nativeRef = useRef<DocumentScannerCameraMethods | null>(null);
  const setNativeRef = useMemo(
    () =>
      callback((view: DocumentScannerCameraMethods | null) => {
        nativeRef.current = view;
      }),
    [],
  );

  useImperativeHandle(forwardedRef, () => {
    const requireNativeView = () => {
      if (nativeRef.current == null) {
        throw new ScannerError('camera/unavailable');
      }
      return nativeRef.current;
    };

    return {
      takePhoto: async (options?: TakePhotoOptions) => {
        const result = await scannerOperation(
          () => requireNativeView().takePhotoResult(options),
          'capture/failed',
        );
        synchronizeSession(session, result.snapshot);
        if (session.getSession().pages[result.pageId] === undefined)
          throw new ScannerCancellation('processor/superseded');
        return result.pageId;
      },
    };
  }, [session]);

  return (
    <NativeCameraHost
      {...props}
      session={getNativeScanSession(session)}
      active={active}
      cornerDetectionModel={cornerDetectionModel ?? ''}
      onError={nativeError}
      guideLiveCorners={photo ? false : guideLiveCorners}
      autoCrop={photo ? false : autoCrop}
      highResolutionPhoto={highResolutionPhoto ?? !photo}
      inferenceThrottling={photo ? false : inferenceThrottling}
      minCornerScore={photo ? 0.95 : minCornerScore}
      lowQualityControl={lowQualityControl}
      imageCorrection={photo ? PHOTO_CORRECTION : imageCorrection}
      outputOptions={outputOptions}
      tapToFocus={tapToFocus}
      exposureCompensation={exposureCompensation}
      minFrameRate={minFrameRate}
      torch={torch}
      lowLightBoost={lowLightBoost}
      captureMode={captureMode}
      guideTransitionDuration={photo ? 80 : guideTransitionDuration}
      analysisFrameWidth={analysisFrameWidth}
      squareAspectRatio={squareAspectRatio}
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
  );
});
