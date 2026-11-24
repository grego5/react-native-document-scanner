import { useRef, useState } from 'react';
import { Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  type AutofocusState,
  DocumentScanner,
  TextCamera,
  type TextCameraRef,
  type TextCapture,
  type TextRegion,
  TextSelection,
} from 'react-native-document-scanner';
import { ActionButton } from './ActionButton';
import { PrimaryButton } from './PrimaryButton';
import { SheetModal } from './SheetModal';
import { ShutterButton } from './ShutterButton';
import { diagnosticsAvailable, shareDiagnostics } from '../lib/diagnostics';
import { errorMessage } from '../lib/errorMessage';
import { styles } from '../styles';

const REGIONS: Record<'line' | 'code', TextRegion> = {
  line: { x: 0.08, y: 0.42, width: 0.84, height: 0.12 },
  code: { x: 0.25, y: 0.4, width: 0.5, height: 0.16 },
};
/** Zoom steps: more lets close-up text fill the frame from farther away, inside the lens's focus range. */
const ZOOMS = [1, 1.5, 2];
/** Capture, zoom and the focus report are Android only. */
const ANDROID = Platform.OS === 'android';

/** Captures the boxed region and lets the user pick words from it by tap or by dragging a box; the picked text can be
 * edited and copied. */
export function TextScanPanel({ granted, onClose }: { granted: boolean; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const camera = useRef<TextCameraRef>(null);
  const [region, setRegion] = useState<keyof typeof REGIONS>('line');
  const [torch, setTorch] = useState<'off' | 'on'>('off');
  const [zoom, setZoom] = useState(1);
  const [focus, setFocus] = useState<AutofocusState>('inactive');
  const [blurred, setBlurred] = useState(false);
  const [value, setValue] = useState<string>();
  const [capture, setCapture] = useState<TextCapture>();
  const [message, setMessage] = useState('');

  const dismiss = () => {
    setValue(undefined);
    setCapture(undefined);
  };

  const shoot = async () => {
    setMessage('');
    try {
      const shot = await camera.current?.captureText();
      if (!shot) return;
      setCapture(shot);
      setValue('');
    } catch (error) {
      setMessage(errorMessage(error, 'Could not capture the text.'));
    }
  };

  return (
    <View style={[StyleSheet.absoluteFill, styles.screen, { paddingTop: insets.top, paddingBottom: insets.bottom }]}>
      <View style={styles.cameraStage}>
        <TextCamera
          ref={camera}
          style={StyleSheet.absoluteFill}
          active={value === undefined}
          textRegion={REGIONS[region]}
          torch={torch}
          zoom={zoom}
          onAutofocusChange={setFocus}
          onBlurChange={setBlurred}
          onError={(error) => setMessage(errorMessage(error, 'Could not start the text camera.'))}
        />
        <View style={styles.cameraNotices} pointerEvents="none">
          {!granted && <Text style={styles.notice}>Allow camera access on the scanner screen first.</Text>}
          {value === undefined && (
            <Text style={styles.notice}>
              {blurred ? 'Blurred: move the phone back or hold still.' : 'Hold the text inside the box, then capture.'}
              {ANDROID ? ` · focus ${focus}` : ''}
            </Text>
          )}
          {!!message && <Text style={styles.notice}>{message}</Text>}
        </View>
      </View>
      <View style={styles.controls}>
        <View style={styles.shutterRow}>
          <View style={styles.shutterSide}>
            <ActionButton label="Close" onPress={onClose} />
            <ActionButton label={`Torch ${torch}`} onPress={() => setTorch(torch === 'on' ? 'off' : 'on')} />
            {ANDROID && (
              <ActionButton
                label={`Zoom ${zoom}×`}
                onPress={() => setZoom(ZOOMS[(ZOOMS.indexOf(zoom) + 1) % ZOOMS.length])}
              />
            )}
            {diagnosticsAvailable && <ActionButton label="Share log" onPress={shareDiagnostics} />}
          </View>
          <View style={styles.shutterCenter}>
            <ShutterButton disabled={!granted} onPress={shoot} />
          </View>
          <View style={styles.shutterSide}>
            <ActionButton
              label={region === 'line' ? 'Wide line' : 'Short code'}
              onPress={() => setRegion(region === 'line' ? 'code' : 'line')}
            />
          </View>
        </View>
      </View>
      {/* A modal sheet rises above the keyboard; the edge-to-edge main window does not resize for it. */}
      <SheetModal visible={value !== undefined} onClose={dismiss}>
        <View style={styles.optionsPanel}>
          {capture && (
            <View style={panel.field}>
              <Text style={styles.tuningLabel}>
                {`${capture.words.length} words · tap a word or drag a box over several`}
              </Text>
              <TextSelection
                capture={capture}
                style={panel.selection}
                onSelectionChange={(_, text) => setValue(text)}
              />
            </View>
          )}
          <View style={panel.field}>
            <Text style={styles.tuningLabel}>Picked text</Text>
            <TextInput
              style={[styles.numberInput, panel.input]}
              value={value ?? ''}
              onChangeText={setValue}
              autoCapitalize="none"
              autoCorrect={false}
              autoFocus={!capture}
              selectTextOnFocus
            />
          </View>
          <View style={styles.actionRow}>
            <PrimaryButton label="Scan again" variant="secondary" onPress={dismiss} style={panel.button} />
            <PrimaryButton
              label="Copy"
              style={panel.button}
              onPress={() => {
                try {
                  DocumentScanner.copyText(value ?? '');
                  setMessage(`Copied ${value}`);
                } catch (error) {
                  setMessage(errorMessage(error, 'Could not copy the text.'));
                }
                dismiss();
              }}
            />
          </View>
        </View>
      </SheetModal>
    </View>
  );
}

const panel = StyleSheet.create({
  field: { gap: 6 },
  input: { fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }) },
  button: { flex: 1 },
  selection: { height: 180, borderRadius: 8, backgroundColor: '#000' },
});
