import { callback, getHostComponent } from 'react-native-nitro-modules';
import { forwardRef, useImperativeHandle, useLayoutEffect, useMemo, useRef } from 'react';
import { processColor, type ViewProps } from 'react-native';
import type { CameraState } from './DocumentScannerCamera.nitro';
import type { TextCameraMethods, TextCameraProps as NativeTextCameraProps, TextReading, TextRegion } from './TextCamera.nitro';
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
   * The agreed reading now, or the first one within `timeoutMs` (defaults to 1500) when there is none yet; undefined
   * when nothing was read. Rejects with `camera/unavailable` while the camera is not running.
   */
  readText(options?: { timeoutMs?: number }): Promise<TextReading | undefined>;
}

export interface TextCameraProps
  extends Omit<NativeTextCameraProps, 'hybridRef' | 'textRegion' | 'guideColor' | 'onError' | 'onTextChange'>, ViewProps {
  /** Defaults to `DEFAULT_TEXT_REGION`. Keep the object stable between renders. */
  textRegion?: TextRegion;
  /** Frame color as a React Native color string; defaults to teal. */
  guideColor?: string;
  /** The agreed reading, or undefined when the region holds no accepted text. */
  onTextChange?: (reading: TextReading | undefined) => void;
  onError?: (error: ScannerError) => void;
}

/** Back-camera preview that reads one line of text inside `textRegion`. */
export const TextCamera = forwardRef<TextCameraRef, TextCameraProps>(function TextCamera(
  {
    active = true,
    textRegion = DEFAULT_TEXT_REGION,
    textGuide = 'native',
    guideColor,
    allowedCharacters = '',
    textPattern = '',
    minLength = 1,
    tapToFocus = true,
    torch = 'off',
    recognitionRate = 5,
    analysisFrameWidth = 1280,
    onCameraStateChange,
    onTextChange,
    onError,
    ...props
  },
  forwardedRef,
) {
  const handlers = useRef({ onCameraStateChange, onTextChange, onError });
  useLayoutEffect(() => {
    handlers.current = { onCameraStateChange, onTextChange, onError };
  });
  const hasCameraState = onCameraStateChange != null;
  const hasText = onTextChange != null;
  const hasError = onError != null;
  const nativeCameraState = useMemo(
    () => (hasCameraState ? callback((state: CameraState) => handlers.current.onCameraStateChange?.(state)) : noop),
    [hasCameraState],
  );
  const nativeText = useMemo(
    () =>
      hasText
        ? callback((reading: TextReading) => handlers.current.onTextChange?.(reading.text === '' ? undefined : reading))
        : noop,
    [hasText],
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
    readText: async (options?: { timeoutMs?: number }) => {
      const reading = await scannerOperation(() => {
        const view = nativeRef.current;
        if (view == null) throw new ScannerError('camera/unavailable');
        return view.readTextResult(options?.timeoutMs ?? 1500);
      }, 'camera/unavailable');
      return reading.text === '' ? undefined : reading;
    },
  }), []);

  return (
    <NativeTextCameraHost
      {...props}
      active={active}
      textRegion={textRegion}
      textGuide={textGuide}
      guideColor={nativeGuideColor}
      allowedCharacters={allowedCharacters}
      textPattern={textPattern}
      minLength={minLength}
      tapToFocus={tapToFocus}
      torch={torch}
      recognitionRate={recognitionRate}
      analysisFrameWidth={analysisFrameWidth}
      onCameraStateChange={nativeCameraState}
      onTextChange={nativeText}
      onError={nativeError}
      hybridRef={setNativeRef}
    />
  );
});
