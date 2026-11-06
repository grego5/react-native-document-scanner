import { Alert, Pressable, ScrollView, Text, View } from 'react-native'
import {
  DocumentScanner,
  type CaptureMode,
  type SampleFolder,
  type TorchMode,
} from 'react-native-document-scanner'
import { errorMessage } from '../lib/errorMessage'
import { styles } from '../styles'
import { PrimaryButton } from './PrimaryButton'

export interface LiveTuning {
  squareFrame: boolean
  qualityGate: boolean
  /** Which component the camera screen shows, `Scanner` or `Camera`; its mode button switches it. */
  mode: 'scanner' | 'camera'
  /** `camera` mode's photo: the scanner's full size instead of the standard 1440x1080 one. */
  highResolutionPhoto: boolean
  adaptiveDetection: boolean
  exposureCompensation: number
  minFrameRate: number
  /** The torch for each new scan, `off` or `auto`; the camera screen's torch button sets it with a long press. */
  torch: Extract<TorchMode, 'off' | 'auto'>
  lowLightBoost: boolean
  captureFeedback: boolean
  captureMode: CaptureMode
  jpegQuality: number | undefined
}

// Each switch cycles through its values; the first is the library default.
const EXPOSURE_EV = [0, -0.3, -0.7, -1]
const MIN_FRAME_RATES = [0, 24, 30]
const CAPTURE_MODES: CaptureMode[] = ['zsl', 'latency', 'quality']
const JPEG_QUALITIES = [undefined, 0.95, 0.85]

export const DEFAULT_TUNING: LiveTuning = {
  squareFrame: false,
  qualityGate: true,
  mode: 'scanner',
  highResolutionPhoto: false,
  adaptiveDetection: true,
  exposureCompensation: EXPOSURE_EV[0],
  minFrameRate: MIN_FRAME_RATES[0],
  torch: 'off',
  lowLightBoost: true,
  captureFeedback: true,
  captureMode: CAPTURE_MODES[0],
  jpegQuality: JPEG_QUALITIES[0],
}

function nextValue<T>(values: readonly T[], current: T) {
  return values[(values.indexOf(current) + 1) % values.length]
}

type Option = { label: string; wide?: boolean } & (
  | { on: boolean; toggle: () => void }
  | { value: string; next: () => void; changed: boolean }
)

/** Same-size cells, two per row and a wide option alone: a label, highlighted while a flag is on or a value is not its
 * default, with the value a tap cycles to its next. Rows are fixed pairs, so a cell's size never depends on its text or on the cells around it. */
function OptionGrid({ options, disabled }: { options: Option[]; disabled?: boolean }) {
  const rows: Option[][] = []
  for (const option of options) {
    const last = rows[rows.length - 1]
    if (option.wide || !last || last.length === 2 || last[0].wide) rows.push([option])
    else last.push(option)
  }
  return (
    <View style={styles.optionGrid}>
      {rows.map((row) => (
        <View key={row[0].label} style={styles.optionRow}>
          {row.map((option) => <OptionCell key={option.label} option={option} disabled={disabled} />)}
          {row.length === 1 && !row[0].wide && <View style={styles.optionSpacer} />}
        </View>
      ))}
    </View>
  )
}

function OptionCell({ option, disabled }: { option: Option; disabled?: boolean }) {
  return (
        <Pressable
          key={option.label}
          accessibilityRole={'on' in option ? 'switch' : 'button'}
          accessibilityState={{ disabled, checked: 'on' in option ? option.on : undefined }}
          accessibilityLabel={'on' in option ? option.label : `${option.label} ${option.value}`}
          disabled={disabled}
          onPress={'on' in option ? option.toggle : option.next}
          style={({ pressed }) => [styles.optionCell, ('on' in option ? option.on : option.changed) && styles.activeTool,
            pressed && styles.buttonPressed, disabled && styles.buttonDisabled]}>
          <Text style={styles.optionLabel} numberOfLines={1}>{option.label}</Text>
          {'value' in option && <Text style={styles.optionValue} numberOfLines={1}>{option.value}</Text>}
        </Pressable>
  )
}

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
  const set = (update: Partial<LiveTuning>) => onChange((t) => ({ ...t, ...update }))
  const flag = (label: string, key: keyof LiveTuning & ('squareFrame' | 'qualityGate' | 'adaptiveDetection' |
    'lowLightBoost' | 'captureFeedback' | 'highResolutionPhoto')): Option =>
    ({ label, on: tuning[key], toggle: () => set({ [key]: !tuning[key] }) })
  const cycle = <T,>(label: string, values: readonly T[], current: T, key: keyof LiveTuning, show: (value: T) => string): Option =>
    ({ label, value: show(current), changed: current !== values[0], next: () => set({ [key]: nextValue(values, current) }) })
  const options: Option[] = [
    { label: 'Shadow removal', on: removeShadows, toggle: () => onRemoveShadowsChange(!removeShadows) },
    flag('Quality check', 'qualityGate'),
    cycle('Capture', CAPTURE_MODES, tuning.captureMode, 'captureMode', String),
    cycle('JPEG', JPEG_QUALITIES, tuning.jpegQuality, 'jpegQuality', (q) => (q == null ? 'auto' : String(q))),
    flag('Square', 'squareFrame'),
    flag('Throttle detection', 'adaptiveDetection'),
    flag('Feedback', 'captureFeedback'),
    cycle('Exposure', EXPOSURE_EV, tuning.exposureCompensation, 'exposureCompensation', (ev) => `${ev} EV`),
    cycle('Min fps', MIN_FRAME_RATES, tuning.minFrameRate, 'minFrameRate', (fps) => (fps ? String(fps) : 'auto')),
    flag('Low light boost', 'lowLightBoost'),
    flag('Hi-res camera', 'highResolutionPhoto'),
  ]
  return (
    <View style={styles.optionsPanel}>
      <ScrollView>
        <OptionGrid options={options} disabled={disabled} />
        <SampleFolderSwitches folder={sampleFolder} onChange={onSampleFolderChange} />
      </ScrollView>
      <PrimaryButton label="Close" onPress={onClose} />
    </View>
  )
}

/**
 * Where collected samples go: a folder the library keeps across launches. While collection is on, a Sample button shows
 * in photo review; turning collection off releases the folder, so turning it on again asks for one.
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
  // Turning samples off releases the folder; turning them on picks one, which turns collection on.
  const toggle = () => {
    if (folder.collecting) update(DocumentScanner.clearSampleFolder)
    else if (folder.uri != null) update(() => DocumentScanner.setSampleCollection(true))
    else pick()
  }
  const options: Option[] = [{ label: 'Samples', on: folder.collecting, toggle }]
  if (folder.collecting) options.push({ label: 'Folder', value: folder.name ?? 'picked', next: pick, changed: false })
  return <OptionGrid options={options} />
}
