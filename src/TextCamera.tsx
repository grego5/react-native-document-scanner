import { callback, getHostComponent } from 'react-native-nitro-modules';
import { forwardRef, useImperativeHandle, useLayoutEffect, useMemo, useRef } from 'react';
import { processColor, type ViewProps } from 'react-native';
import type { AutofocusState, CameraState } from './DocumentScannerCamera.nitro';
import type {
  TextCameraMethods,
  TextCameraProps as NativeTextCameraProps,
  TextCapture,
  TextRegion,
} from './TextCamera.nitro';
import TextCameraViewConfig from '../nitrogen/generated/shared/json/TextCameraViewConfig.json';
import { ScannerError, scannerOperation } from './ScannerError';
import type { ScannerFailure } from './ScannerFailure';

const NativeTextCameraHost = getHostComponent<NativeTextCameraProps, TextCameraMethods>(
  'TextCameraView',
  () => TextCameraViewConfig,
);

/** A centered strip for one line, such as a serial number. */
export const DEFAULT_TEXT_REGION: TextRegion = Object.freeze({ x: 0.1, y: 0.44, width: 0.8, height: 0.12 });
const DEFAULT_GUIDE_COLOR = processColor('#61E1CF') as number;
const noop = callback(() => {});

export interface TextCameraRef {
  /**
   * A still of the region with every word in it, for picking text with `TextSelection`: the sharpest recent frame, in
   * focus where the camera could focus. A recent background read in focus that found text returns at once, unless a
   * kept frame is clearly sharper, which is read instead; when neither finds text the region locks again and the
   * capture waits for that lock's read. Past `timeoutMs` (defaults to 2000) the sharpest frame
   * kept is read then. Rejects with `capture/not-ready` when no frame came, `camera/unavailable` while the camera is
   * not running, and `feature/unsupported` on iOS.
   */
  captureText(options?: { timeoutMs?: number }): Promise<TextCapture>;
}

export interface TextCameraProps
  extends Omit<NativeTextCameraProps, 'hybridRef' | 'textRegion' | 'guideColor' | 'onError'>, ViewProps {
  /** Defaults to `DEFAULT_TEXT_REGION`. Keep the object stable between renders. */
  textRegion?: TextRegion;
  /** Box color as a React Native color string; defaults to teal. */
  guideColor?: string;
  onError?: (error: ScannerError) => void;
}

/** Back-camera preview with a box marking `textRegion`; `captureText()` returns its best frame and words to pick from. */
export const TextCamera = forwardRef<TextCameraRef, TextCameraProps>(function TextCamera(
  {
    active = true,
    textRegion = DEFAULT_TEXT_REGION,
    guideColor,
    tapToFocus = true,
    focusLock = true,
    zoom = 1,
    torch = 'off',
    analysisFrameWidth = 1280,
    onCameraStateChange,
    onAutofocusChange,
    onBlurChange,
    onError,
    ...props
  },
  forwardedRef,
) {
  const handlers = useRef({ onCameraStateChange, onAutofocusChange, onBlurChange, onError });
  useLayoutEffect(() => {
    handlers.current = { onCameraStateChange, onAutofocusChange, onBlurChange, onError };
  });
  const hasCameraState = onCameraStateChange != null;
  const hasAutofocus = onAutofocusChange != null;
  const hasBlur = onBlurChange != null;
  const hasError = onError != null;
  const nativeCameraState = useMemo(
    () => (hasCameraState ? callback((state: CameraState) => handlers.current.onCameraStateChange?.(state)) : noop),
    [hasCameraState],
  );
  const nativeAutofocus = useMemo(
    () => (hasAutofocus ? callback((state: AutofocusState) => handlers.current.onAutofocusChange?.(state)) : noop),
    [hasAutofocus],
  );
  const nativeBlur = useMemo(
    () => (hasBlur ? callback((blurred: boolean) => handlers.current.onBlurChange?.(blurred)) : noop),
    [hasBlur],
  );
  const nativeError = useMemo(
    () => (hasError ? callback((error: ScannerFailure) => handlers.current.onError?.(new ScannerError(error.code))) : noop),
    [hasError],
  );
  const nativeGuideColor = useMemo(() => {
    if (guideColor == null) return DEFAULT_GUIDE_COLOR;
    const color = processColor(guideColor);
    if (typeof color === 'number') return color;
    if (__DEV__) console.warn(`TextCamera: unsupported guideColor ${JSON.stringify(guideColor)}; using the default.`);
    return DEFAULT_GUIDE_COLOR;
  }, [guideColor]);
  const nativeRef = useRef<TextCameraMethods | null>(null);
  const setNativeRef = useMemo(
    () =>
      callback((view: TextCameraMethods | null) => {
        nativeRef.current = view;
      }),
    [],
  );

  useImperativeHandle(forwardedRef, () => ({
    captureText: (options?: { timeoutMs?: number }) =>
      scannerOperation(() => {
        const view = nativeRef.current;
        if (view == null) throw new ScannerError('camera/unavailable');
        return view.captureTextResult(options?.timeoutMs ?? 2000);
      }, 'capture/failed'),
  }), []);

  return (
    <NativeTextCameraHost
      {...props}
      active={active}
      textRegion={textRegion}
      guideColor={nativeGuideColor}
      tapToFocus={tapToFocus}
      focusLock={focusLock}
      zoom={zoom}
      torch={torch}
      analysisFrameWidth={analysisFrameWidth}
      onCameraStateChange={nativeCameraState}
      onAutofocusChange={nativeAutofocus}
      onBlurChange={nativeBlur}
      onError={nativeError}
      hybridRef={setNativeRef}
    />
  );
});
