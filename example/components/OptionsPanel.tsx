import { Alert, ScrollView, Text, View } from 'react-native'
import {
  DocumentScanner,
  type CaptureMode,
  type CameraMode,
  type SampleFolder,
  type TorchMode,
} from 'react-native-document-scanner'
import { errorMessage } from '../lib/errorMessage'
import { styles } from '../styles'
import { PrimaryButton } from './PrimaryButton'
import { TuningSwitch } from './TuningSwitch'

export interface LiveTuning {
  guideTransitionDuration: number
  squareFrame: boolean
  qualityGate: boolean
  /** The camera's profile while it is shown. */
  mode: Exclude<CameraMode, 'off'>
  adaptiveDetection: boolean
  exposureCompensation: number
  minFrameRate: number
  torch: TorchMode
  captureFeedback: boolean
  captureMode: CaptureMode
  jpegQuality: number | undefined
}

// Each switch cycles through its values; the first is the library default.
const GUIDE_TRANSITIONS_MS = [50, 16, 0]
const EXPOSURE_EV = [0, -0.3, -0.7, -1]
const MIN_FRAME_RATES = [0, 24, 30]
const TORCH_MODES: TorchMode[] = ['off', 'auto', 'on']
const CAMERA_MODES: Exclude<CameraMode, 'off'>[] = ['scanner', 'camera']
const CAPTURE_MODES: CaptureMode[] = ['zsl', 'latency', 'quality']
const JPEG_QUALITIES = [undefined, 0.95, 0.85]

export const DEFAULT_TUNING: LiveTuning = {
  guideTransitionDuration: GUIDE_TRANSITIONS_MS[0],
  squareFrame: false,
  qualityGate: true,
  mode: CAMERA_MODES[0],
  adaptiveDetection: true,
  exposureCompensation: EXPOSURE_EV[0],
  minFrameRate: MIN_FRAME_RATES[0],
  torch: TORCH_MODES[0],
  captureFeedback: true,
  captureMode: CAPTURE_MODES[0],
  jpegQuality: JPEG_QUALITIES[0],
}

function nextValue<T>(values: readonly T[], current: T) {
  return values[(values.indexOf(current) + 1) % values.length]
}

const onOff = (on: boolean) => (on ? 'on' : 'off')

const OPTIONS: { label: (tuning: LiveTuning) => string; next: (tuning: LiveTuning) => Partial<LiveTuning> }[] = [
  {
    label: (t) => `Guide ${t.guideTransitionDuration} ms`,
    next: (t) => ({ guideTransitionDuration: nextValue(GUIDE_TRANSITIONS_MS, t.guideTransitionDuration) }),
  },
  { label: (t) => `Square ${onOff(t.squareFrame)}`, next: (t) => ({ squareFrame: !t.squareFrame }) },
  { label: (t) => `Quality gate ${onOff(t.qualityGate)}`, next: (t) => ({ qualityGate: !t.qualityGate }) },
  { label: (t) => `Mode ${t.mode}`, next: (t) => ({ mode: nextValue(CAMERA_MODES, t.mode) }) },
  { label: (t) => `Adaptive detection ${onOff(t.adaptiveDetection)}`, next: (t) => ({ adaptiveDetection: !t.adaptiveDetection }) },
  {
    label: (t) => `Exposure ${t.exposureCompensation} EV`,
    next: (t) => ({ exposureCompensation: nextValue(EXPOSURE_EV, t.exposureCompensation) }),
  },
  {
    label: (t) => `Min fps ${t.minFrameRate || 'auto'}`,
    next: (t) => ({ minFrameRate: nextValue(MIN_FRAME_RATES, t.minFrameRate) }),
  },
  { label: (t) => `Torch ${t.torch}`, next: (t) => ({ torch: nextValue(TORCH_MODES, t.torch) }) },
  { label: (t) => `Shutter feedback ${onOff(t.captureFeedback)}`, next: (t) => ({ captureFeedback: !t.captureFeedback }) },
  {
    label: (t) => `Capture ${t.captureMode}`,
    next: (t) => ({ captureMode: nextValue(CAPTURE_MODES, t.captureMode) }),
  },
  {
    label: (t) => `JPEG ${t.jpegQuality ?? 'auto'}`,
    next: (t) => ({ jpegQuality: nextValue(JPEG_QUALITIES, t.jpegQuality) }),
  },
]

export function OptionsPanel({
  tuning,
  disabled,
  onChange,
  onClose,
  removeShadows,
  onRemoveShadowsChange,
  sampleFolder,
  onSampleFolderChange,
}: {
  tuning: LiveTuning
  /** Set while a photo is in flight, so a setting that rebinds the camera cannot fail it. */
  disabled: boolean
  onChange: (update: (tuning: LiveTuning) => LiveTuning) => void
  onClose: () => void
  removeShadows: boolean
  onRemoveShadowsChange: (value: boolean) => void
  sampleFolder: SampleFolder
  onSampleFolderChange: (folder: SampleFolder) => void
}) {
  return (
    <View style={styles.optionsPanel}>
      <ScrollView>
        <Text style={styles.diagnostics}>Shadow is the default for new pages.</Text>
        <View style={styles.tuningRow}>
          <TuningSwitch label={`Shadow · ${onOff(removeShadows)}`} disabled={disabled}
            onPress={() => onRemoveShadowsChange(!removeShadows)} />
          {OPTIONS.map(({ label, next }, index) => (
            <TuningSwitch
              key={index}
              label={label(tuning)}
              disabled={disabled}
              onPress={() => onChange((t) => ({ ...t, ...next(t) }))}
            />
          ))}
          <SampleFolderSwitches folder={sampleFolder} onChange={onSampleFolderChange} />
        </View>
      </ScrollView>
      <PrimaryButton label="Close" onPress={onClose} />
    </View>
  )
}

/**
 * Where collected samples go: a folder the library keeps across launches. While collection is on, a Sample button shows
 * in live and photo review; stopping keeps the folder, and forgetting it releases the folder too.
 */
function SampleFolderSwitches({ folder, onChange }: {
  folder: SampleFolder
  onChange: (folder: SampleFolder) => void
}) {
  const pick = () => {
    DocumentScanner.pickSampleFolder().then(onChange, (error: unknown) =>
      Alert.alert('Could not open the folder picker.', errorMessage(error, '')))
  }
  const update = (change: () => void) => {
    change()
    onChange(DocumentScanner.getSampleFolder())
  }
  if (folder.uri == null) return <TuningSwitch label="Sample collection off" onPress={pick} />
  return (
    <>
      <TuningSwitch label={`Sample collection ${folder.collecting ? 'on' : 'off'}`}
        onPress={() => update(() => DocumentScanner.setSampleCollection(!folder.collecting))} />
      <TuningSwitch label={`Sample folder ${folder.name ?? 'picked'}`} onPress={pick} />
      <TuningSwitch label="Forget sample folder" onPress={() => update(DocumentScanner.clearSampleFolder)} />
    </>
  )
}
