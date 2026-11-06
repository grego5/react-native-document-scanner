import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { ActivityIndicator, Alert, BackHandler, Linking, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  Camera,
  DocumentScanner,
  useCameraPermission,
  type ScanSession as NativeScanSession,
  type CameraRef,
  type CameraCaptureState,
  type CaptureWaitReason,
  type TorchMode,
} from 'react-native-document-scanner';
import { DEFAULT_TUNING, OptionsPanel } from '../components/OptionsPanel';
import { PrimaryButton } from '../components/PrimaryButton';
import { ReviewPanel } from '../components/ReviewPanel';
import { SheetModal } from '../components/SheetModal';
import { ShutterButton } from '../components/ShutterButton';
import { ActionButton } from '../components/ActionButton';
import { useScanReview } from '../hooks/useScanReview';
import { errorMessage } from '../lib/errorMessage';
import { removeOldPdfExports } from '../lib/pdfExports';
import { loadSettings, saveSettings } from '../lib/settings';
import { CAPTURE_PAPER, MAX_PAGES } from '../lib/scanSession';
import { styles } from '../styles';
import { captureWaitLabels } from '../lib/labels';

/** One headless owner. Attached camera and review views borrow it. */
export default function Scanner() {
  const [native, setNative] = useState<NativeScanSession | null>(null);
  const [scanKey, setScanKey] = useState(0);
  const owner = useRef<NativeScanSession | null>(null);
  // Options persist across launches; a per-scan torch override does not.
  const [saved] = useState(() => loadSettings({ ...DEFAULT_TUNING, removeShadows: true }));
  const [tuning, setTuning] = useState<typeof DEFAULT_TUNING>(() => {
    const { removeShadows: _, ...rest } = saved;
    return rest;
  });
  const [removeShadows, setRemoveShadows] = useState(saved.removeShadows);
  const close = (session: NativeScanSession | null) => {
    if (session)
      void session.close().catch((error) => console.warn('Could not close scan.', error));
  };
  const newScan = () => {
    close(owner.current);
    owner.current = DocumentScanner.createSession();
    setNative(owner.current);
    setScanKey((key) => key + 1);
  };
  useEffect(() => {
    newScan();
    return () => {
      close(owner.current);
      owner.current = null;
    };
  }, []);
  useEffect(() => {
    try {
      removeOldPdfExports();
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') return;
      console.warn('Could not delete expired exports.', error);
    }
  }, []);
  useEffect(() => saveSettings({ ...tuning, removeShadows }), [tuning, removeShadows]);
  if (!native) return <View style={styles.screen} />;
  return (
    <ScanScreen
      key={scanKey}
      native={native}
      onReset={newScan}
      tuning={tuning}
      setTuning={setTuning}
      removeShadows={removeShadows}
      setRemoveShadows={setRemoveShadows}
    />
  );
}

export function ScanScreen({
  native,
  onReset,
  tuning,
  setTuning,
  removeShadows,
  setRemoveShadows,
}: {
  native: NativeScanSession;
  onReset: () => void;
  tuning: typeof DEFAULT_TUNING;
  setTuning: Dispatch<SetStateAction<typeof DEFAULT_TUNING>>;
  removeShadows: boolean;
  setRemoveShadows: (value: boolean) => void;
}) {
  const cameraRef = useRef<CameraRef>(null);
  const session = useScanReview(native, onReset);
  const [message, setMessage] = useState('');
  const [captureState, setCaptureState] = useState<CameraCaptureState>('unavailable');
  const [waitReason, setWaitReason] = useState<CaptureWaitReason>();
  const capturing =
    captureState === 'waiting' || captureState === 'shooting' || captureState === 'saving';
  const [optionsOpen, setOptionsOpen] = useState(false);
  // A tap switches between on and the default, `off` or `auto`, for this scan only; a new scan remounts this screen and
  // returns to the default. In `auto` that means on or automatic, which stays dark in good light.
  const [torchOverride, setTorchOverride] = useState<Extract<TorchMode, 'on'>>();
  const torch = torchOverride ?? tuning.torch;
  const [sampleFolder, setSampleFolder] = useState(() => DocumentScanner.getSampleFolder());
  const sampling = sampleFolder.collecting;
  const permission = useCameraPermission();
  const granted = permission.status === 'granted';
  const { screen, pageIds } = session;
  const review = screen.type === 'review' ? screen : null;
  const view = screen.type;
  const full = pageIds.length >= MAX_PAGES;

  const confirmDiscard = (then: () => void) => {
    const count = pageIds.length;
    if (count === 0) {
      then();
      return;
    }
    Alert.alert(`Discard ${count} unsaved page${count === 1 ? '' : 's'}?`, undefined, [
      { text: 'Keep', style: 'cancel' },
      { text: 'Discard', style: 'destructive', onPress: then },
    ]);
  };

  // Back cancels crop drafts; saved page edits already belong to the native session.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      // An export keeps the pager and its pages in place until it ends.
      if (session.working || capturing) return true;
      if (optionsOpen) setOptionsOpen(false);
      else if (view === 'review') {
        // Crop Cancel issues no native command.
        if (review?.cropping) session.cancelCrop();
        else session.dismissReview();
      } else if (view === 'summary') session.navigate('camera');
      else if (pageIds.length > 0) {
        confirmDiscard(() => {
          session.reset();
          BackHandler.exitApp();
        });
      } else return false;
      return true;
    });
    return () => subscription.remove();
  });

  const allowCamera = async () => {
    if (permission.status === 'blocked') {
      Linking.openSettings().catch(() => {
        setMessage(
          'Could not open app settings. Open Android settings and allow camera access for this app.',
        );
      });
      return;
    }
    try {
      const status = await permission.request();
      setMessage(status === 'denied' ? 'Camera access was denied. You can try again.' : '');
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') return;
      setMessage(errorMessage(error, 'Could not request camera access.'));
    }
  };

  const takePhoto = () => session.run(async () => {
    setMessage('');
    const pressedAt = performance.now();
    try {
      const photo = await cameraRef.current?.takePhoto();
      if (!photo) throw new Error('The Camera view is not ready.');
      console.log(`takePhoto resolved in ${Math.round(performance.now() - pressedAt)} ms`);
      session.reviewCapture(photo);
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') return;
      setMessage(errorMessage(error, 'Could not capture a photo.'));
    }
  });

  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.screen}>
        <View style={styles.cameraStage}>
          <Camera
            session={session.native}
            renderOptions={{ ...CAPTURE_PAPER, format: 'auto', filter: 'none', removeShadows }}
            quadTensorModel="asset:///doccornernet/model.tflite"
            ref={cameraRef}
            {...tuning}
            torch={torch}
            mode={view === 'camera' && !optionsOpen ? tuning.mode : 'off'}
            guide={view === 'camera' && !optionsOpen}
            showStats
            onCaptureStateChange={setCaptureState}
            onCaptureStage={(stage, reason) => setWaitReason(stage === 'waiting' ? reason : undefined)}
            onError={(error) => setMessage(errorMessage(error, 'Could not prepare the camera.'))}
            style={StyleSheet.absoluteFill}
          />
          <View style={styles.cameraNotices} pointerEvents="box-none">
            {!granted && (
              <PrimaryButton
                label={
                  permission.status === 'blocked'
                    ? 'Open app settings'
                    : permission.status === 'denied'
                      ? 'Try camera permission again'
                      : 'Allow camera'
                }
                onPress={allowCamera}
              />
            )}
            {full && (
              <Text style={styles.notice}>
                This scan has {MAX_PAGES} pages, the most it can hold. Tap Pages.
              </Text>
            )}
            {captureState === 'waiting' && waitReason && (
              <Text style={styles.notice}>{captureWaitLabels[waitReason]}</Text>
            )}
            {!!message && (
              <Text style={styles.notice} numberOfLines={3}>
                {message}
              </Text>
            )}
          </View>
        </View>
        <View>
          <View style={styles.controls}>
            <View style={styles.shutterRow}>
              <View style={styles.shutterSide}>
                <ActionButton
                  label={tuning.mode === 'scanner' ? 'Scanner' : 'Camera'}
                  accessibilityHint="Switches between document scanning and plain photos."
                  disabled={capturing || session.working || view !== 'camera'}
                  onPress={() =>
                    setTuning((t) => ({ ...t, mode: t.mode === 'scanner' ? 'camera' : 'scanner' }))
                  }
                />
                {pageIds.length > 0 && (
                  <ActionButton
                    label={`Pages · ${pageIds.length}`}
                    disabled={capturing || session.working || view !== 'camera'}
                    onPress={() => session.navigate('summary')}
                  />
                )}
              </View>
              <ShutterButton
                disabled={
                  capturing ||
                  !granted ||
                  captureState !== 'ready' ||
                  full ||
                  view !== 'camera' ||
                  session.working
                }
                onPress={takePhoto}
              />
              <View style={styles.shutterSide}>
                <ActionButton
                  label={`Torch ${torch}`}
                  accessibilityHint="Tap to switch between on and the default for this scan. Long press to make auto or off the default."
                  disabled={capturing || session.working || view !== 'camera'}
                  onPress={() => setTorchOverride(torchOverride ? undefined : 'on')}
                  onLongPress={() => {
                    setTorchOverride(undefined);
                    setTuning((t) => ({ ...t, torch: t.torch === 'auto' ? 'off' : 'auto' }));
                  }}
                />
                <ActionButton
                  label="Options"
                  disabled={capturing || session.working || view !== 'camera'}
                  onPress={() => setOptionsOpen(true)}
                />
              </View>
            </View>
          </View>
        </View>
      </SafeAreaView>
      {/* Capture, saved pages, and export share one pager overlay. Crop is its separate editor screen. */}
      <ReviewPanel
        visible={view !== 'camera'}
        session={session}
        sampling={sampling}
        onNewScan={() => confirmDiscard(session.reset)}
      />
      <SheetModal visible={optionsOpen} onClose={() => setOptionsOpen(false)}>
        <OptionsPanel
          tuning={tuning}
          disabled={capturing || session.working}
          removeShadows={removeShadows}
          onRemoveShadowsChange={setRemoveShadows}
          sampleFolder={sampleFolder}
          onSampleFolderChange={setSampleFolder}
          onChange={setTuning}
          onClose={() => setOptionsOpen(false)}
        />
      </SheetModal>
      {session.working && <View style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center', backgroundColor: '#0006' }]}>
        <ActivityIndicator size="large" color="white" accessibilityLabel="Working" />
      </View>}
    </View>
  );
}

