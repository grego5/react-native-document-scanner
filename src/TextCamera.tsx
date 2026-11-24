import { callback, getHostComponent } from 'react-native-nitro-modules';
import { useLayoutEffect, useMemo, useRef } from 'react';
import { processColor, type ViewProps } from 'react-native';
import type {
  TextCameraMethods,
  TextCameraProps as NativeTextCameraProps,
  TextGuide as NativeTextGuide,
  TextGuideColors as NativeTextGuideColors,
  TextGuideLabelStyle as NativeTextGuideLabelStyle,
  TextGuideLayout,
  TextRegion,
} from './TextCamera.nitro';
import TextCameraViewConfig from '../nitrogen/generated/shared/json/TextCameraViewConfig.json';
import { ScannerError } from './ScannerError';
import type { ScannerFailure } from './ScannerFailure';
import { getNativeTextSession, type TextSession } from './TextSession';

const NativeTextCameraHost = getHostComponent<NativeTextCameraProps, TextCameraMethods>(
  'TextCameraView',
  () => TextCameraViewConfig,
);

/** A centered strip for one line, such as a serial number. */
export const DEFAULT_TEXT_REGION: TextRegion = Object.freeze({ x: 0.1, y: 0.44, width: 0.8, height: 0.12 });
const DEFAULT_GUIDE_COLOR = processColor('#61E1CF') as number;
const noop = callback(() => {});

/** `TextGuideLabelStyle` with React Native color strings. */
export interface TextGuideLabelStyle extends Omit<NativeTextGuideLabelStyle, 'color' | 'backgroundColor'> {
  color?: string;
  backgroundColor?: string;
}

/** The native guide, `TextGuide`, with React Native color strings; `ready` defaults to teal. */
export interface TextGuide extends Omit<NativeTextGuide, 'hidden' | 'colors' | 'labelStyle'> {
  colors?: { [state in keyof NativeTextGuideColors]?: string };
  labelStyle?: TextGuideLabelStyle;
}

export interface TextCameraProps
  extends Omit<NativeTextCameraProps, 'hybridRef' | 'session' | 'textRegion' | 'guide' | 'onError'>, ViewProps {
  /** The scan this camera shows, from `DocumentScanner.createTextSession()`; it takes the captures. */
  session: TextSession;
  /** Defaults to `DEFAULT_TEXT_REGION`. Keep the object stable between renders. */
  textRegion?: TextRegion;
  /**
   * The box marking the region and its label per state. `false` draws nothing natively, for a guide drawn by the host
   * from `onGuideLayout` and the session's `status`. Compared by value, so a new object with the same content changes
   * nothing.
   */
  guide?: TextGuide | false;
  /** @deprecated Use `guide.colors.ready`. */
  guideColor?: string;
  onError?: (error: ScannerError) => void;
}

/** [color] as a native ARGB number; undefined when unset or unsupported. */
function nativeColor(name: string, color: string | undefined): number | undefined {
  if (color == null) return undefined;
  const value = processColor(color);
  if (typeof value === 'number') return value;
  if (__DEV__) console.warn(`TextCamera: unsupported ${name} ${JSON.stringify(color)}; using the default.`);
  return undefined;
}

function nativeGuide(guide: TextGuide | false | undefined, guideColor: string | undefined): NativeTextGuide {
  if (guide === false) return { hidden: true };
  const { colors, labelStyle, ...rest } = guide ?? {};
  return {
    ...rest,
    colors: {
      ready: nativeColor('guide.colors.ready', colors?.ready ?? guideColor) ?? DEFAULT_GUIDE_COLOR,
      focusing: nativeColor('guide.colors.focusing', colors?.focusing),
      blurred: nativeColor('guide.colors.blurred', colors?.blurred),
      capturing: nativeColor('guide.colors.capturing', colors?.capturing),
    },
    labelStyle: labelStyle && {
      ...labelStyle,
      color: nativeColor('guide.labelStyle.color', labelStyle.color),
      backgroundColor: nativeColor('guide.labelStyle.backgroundColor', labelStyle.backgroundColor),
    },
  };
}

/** Back-camera preview with a box marking `textRegion`; its session's `capture()` takes the best frame and words to pick
 * from. */
export function TextCamera({
  session,
  active = true,
  textRegion = DEFAULT_TEXT_REGION,
  guide,
  guideColor,
  tapToFocus = true,
  focusLock = true,
  zoom = 1,
  torch = 'off',
  analysisFrameWidth = 1280,
  onGuideLayout,
  onError,
  ...props
}: TextCameraProps) {
  const handlers = useRef({ onGuideLayout, onError });
  useLayoutEffect(() => {
    handlers.current = { onGuideLayout, onError };
  });
  const hasGuideLayout = onGuideLayout != null;
  const hasError = onError != null;
  const nativeGuideLayout = useMemo(
    () => (hasGuideLayout ? callback((layout: TextGuideLayout) => handlers.current.onGuideLayout?.(layout)) : noop),
    [hasGuideLayout],
  );
  const nativeError = useMemo(
    () => (hasError ? callback((error: ScannerFailure) => handlers.current.onError?.(new ScannerError(error.code))) : noop),
    [hasError],
  );
  // By value, so an inline guide object does not resend the struct on every render.
  const guideKey = guide === undefined ? '' : JSON.stringify(guide);
  const nativeGuideConfig = useMemo(
    () => nativeGuide(guideKey === '' ? undefined : (JSON.parse(guideKey) as TextGuide | false), guideColor),
    [guideKey, guideColor],
  );
  const nativeSession = useMemo(() => getNativeTextSession(session), [session]);

  return (
    <NativeTextCameraHost
      {...props}
      session={nativeSession}
      active={active}
      textRegion={textRegion}
      guide={nativeGuideConfig}
      tapToFocus={tapToFocus}
      focusLock={focusLock}
      zoom={zoom}
      torch={torch}
      analysisFrameWidth={analysisFrameWidth}
      onGuideLayout={nativeGuideLayout}
      onError={nativeError}
    />
  );
}
