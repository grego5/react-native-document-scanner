import { useRef, useState } from 'react';
import { Platform, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  type AutofocusState,
  DocumentScanner,
  TextCamera,
  type TextCameraRef,
  type TextGuide,
  type TextReading,
  type TextRegion,
} from 'react-native-document-scanner';
import { ActionButton } from './ActionButton';
import { PrimaryButton } from './PrimaryButton';
import { SheetModal } from './SheetModal';
import { ShutterButton } from './ShutterButton';
import { diagnosticsAvailable, shareDiagnostics } from '../lib/diagnostics';
import { errorMessage } from '../lib/errorMessage';
import { styles } from '../styles';

const SERIAL_CHARACTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-/';
const REGIONS: Record<'line' | 'code', TextRegion> = {
  line: { x: 0.08, y: 0.42, width: 0.84, height: 0.12 },
  code: { x: 0.25, y: 0.4, width: 0.5, height: 0.16 },
};
/** Zoom steps: more lets close-up text fill the frame from farther away, inside the lens's focus range. */
const ZOOMS = [1, 1.5, 2];
/** The built-in guides, in the order the guide button steps through them. */
const GUIDES: TextGuide[] = ['crosshair', 'dot', 'line', 'none'];
/** Zoom and the focus report are Android only. */
const ANDROID = Platform.OS === 'android';

/** Reads one serial-like line; the user edits the result and applies it. */
export function TextScanPanel({ granted, onClose }: { granted: boolean; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const camera = useRef<TextCameraRef>(null);
  const [region, setRegion] = useState<keyof typeof REGIONS>('line');
  const [serialOnly, setSerialOnly] = useState(true);
  const [torch, setTorch] = useState<'off' | 'on'>('off');
  const [zoom, setZoom] = useState(1);
  const [guide, setGuide] = useState<TextGuide>('crosshair');
  const [highlight, setHighlight] = useState(true);
  const [focus, setFocus] = useState<AutofocusState>('inactive');
  const [live, setLive] = useState<TextReading>();
  const [value, setValue] = useState<string>();
  const [message, setMessage] = useState('');

  const read = async () => {
    setMessage('');
    try {
      const reading = await camera.current?.readText();
      if (reading) setValue(reading.text);
      else setMessage('No text found in the frame. Hold the line inside it.');
    } catch (error) {
      setMessage(errorMessage(error, 'Could not read text.'));
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
          allowedCharacters={serialOnly ? SERIAL_CHARACTERS : ''}
          minLength={serialOnly ? 4 : 1}
          torch={torch}
          zoom={zoom}
          textGuide={guide}
          textHighlight={highlight}
          onAutofocusChange={setFocus}
          onTextChange={setLive}
          onError={(error) => setMessage(errorMessage(error, 'Could not start the text camera.'))}
        />
        <View style={styles.cameraNotices} pointerEvents="none">
          {!granted && <Text style={styles.notice}>Allow camera access on the scanner screen first.</Text>}
          {value === undefined && (
            <Text style={styles.notice}>
              {live ? `${live.text} · ${live.votes}×` : 'Point the frame at one line of text.'}
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
            <ShutterButton disabled={!granted} onPress={read} />
          </View>
          <View style={styles.shutterSide}>
            <ActionButton
              label={region === 'line' ? 'Wide line' : 'Short code'}
              onPress={() => setRegion(region === 'line' ? 'code' : 'line')}
            />
            <ActionButton
              label={serialOnly ? 'Serial A-Z 0-9' : 'Any text'}
              onPress={() => setSerialOnly(!serialOnly)}
            />
            <ActionButton
              label={`Guide ${guide}`}
              onPress={() => setGuide(GUIDES[(GUIDES.indexOf(guide) + 1) % GUIDES.length])}
            />
            <ActionButton label={`Highlight ${highlight ? 'on' : 'off'}`} onPress={() => setHighlight(!highlight)} />
          </View>
        </View>
      </View>
      {/* A modal sheet rises above the keyboard; the edge-to-edge main window does not resize for it. */}
      <SheetModal visible={value !== undefined} onClose={() => setValue(undefined)}>
        <View style={styles.optionsPanel}>
          <View style={panel.field}>
            <Text style={styles.tuningLabel}>Read text</Text>
            <TextInput
              style={[styles.numberInput, panel.input]}
              value={value ?? ''}
              onChangeText={setValue}
              autoCapitalize="characters"
              autoCorrect={false}
              autoFocus
              selectTextOnFocus
            />
          </View>
          <View style={styles.actionRow}>
            <PrimaryButton label="Scan again" variant="secondary" onPress={() => setValue(undefined)} style={panel.button} />
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
                setValue(undefined);
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
});
