import { useEffect, useRef, useState } from 'react'
import { Alert, BackHandler, Linking, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import {
  Camera,
  DocumentScanner,
  useCameraPermission,
  type CameraRef,
  type CameraState,
} from 'react-native-document-scanner'
import { DEFAULT_TUNING, OptionsPanel } from '../components/OptionsPanel'
import { PrimaryButton } from '../components/PrimaryButton'
import { ReviewPanel } from '../components/ReviewPanel'
import { SheetModal } from '../components/SheetModal'
import { ShutterButton } from '../components/ShutterButton'
import { TuningSwitch } from '../components/TuningSwitch'
import { useScanSession } from '../hooks/useScanSession'
import { errorMessage } from '../lib/errorMessage'
import { MAX_PAGES } from '../lib/scanSession'
import { styles } from '../styles'

/**
 * The scan session. The camera stays mounted for the whole session; review and the page list cover it, and live
 * guidance pauses while it is covered. Its size never changes with notices or buttons: a resize rebinds the camera
 * and blanks the preview, so everything that comes and goes overlays it.
 */
export default function ScanScreen() {
  const cameraRef = useRef<CameraRef>(null)
  const [tuning, setTuning] = useState(DEFAULT_TUNING)
  const session = useScanSession()
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [displayedDraft, setDisplayedDraft] = useState<string | null>(null)
  const [cameraState, setCameraState] = useState<CameraState>('suspended')
  const [optionsOpen, setOptionsOpen] = useState(false)
  const [sampleFolder, setSampleFolder] = useState(() => DocumentScanner.getSampleFolder())
  const sampling = sampleFolder.collecting
  const permission = useCameraPermission()
  const granted = permission.status === 'granted'
  const { draft, pages } = session
  const view = draft ? 'review' : session.summary && pages.length > 0 ? 'summary' : 'camera'
  const full = pages.length >= MAX_PAGES
  const awaitingReviewImage = draft != null && draft.pageId == null && displayedDraft !== draft.id

  const confirmDiscard = (then: () => void) => {
    const count = pages.length + (draft != null && draft.pageId == null ? 1 : 0)
    if (count === 0) {
      then()
      return
    }
    Alert.alert(`Discard ${count} unsaved page${count === 1 ? '' : 's'}?`, undefined, [
      { text: 'Keep', style: 'cancel' },
      { text: 'Discard', style: 'destructive', onPress: then },
    ])
  }

  // Back closes options or crop, drops a draft edit, then hides the retained pager.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      // An export keeps the pager and its pages in place until it ends.
      if (session.exporting || busy) return true
      if (optionsOpen) setOptionsOpen(false)
      else if (view === 'review') {
        // Crop mode steps back to the rendered page; a render in flight is dropped with its draft.
        if (draft?.cropQuad) session.endCrop(false)
        else session.discardDraft()
      } else if (view === 'summary') session.showSummary(false)
      else if (pages.length > 0) {
        confirmDiscard(() => {
          session.reset()
          BackHandler.exitApp()
        })
      } else return false
      return true
    })
    return () => subscription.remove()
  })

  const allowCamera = async () => {
    if (permission.status === 'blocked') {
      Linking.openSettings().catch(() => {
        setMessage('Could not open app settings. Open Android settings and allow camera access for this app.')
      })
      return
    }
    try {
      const status = await permission.request()
      setMessage(status === 'denied' ? 'Camera access was denied. You can try again.' : '')
    } catch (error) {
      setMessage(errorMessage(error, 'Could not request camera access.'))
    }
  }

  const takePhoto = async () => {
    setBusy(true)
    setMessage('')
    const pressedAt = performance.now()
    const { generation } = session
    try {
      const photo = await cameraRef.current?.takePhoto()
      if (!photo) throw new Error('The Camera view is not ready.')
      console.log(`takePhoto resolved in ${Math.round(performance.now() - pressedAt)} ms`)
      session.capture(photo, generation)
    } catch (error) {
      setMessage(errorMessage(error, 'Could not capture a photo.'))
    } finally {
      setBusy(false)
    }
  }

  const saveLiveSample = () => {
    cameraRef.current?.saveLiveSample().then(
      (name) => setMessage(`Saved sample ${name}`),
      (error: unknown) => setMessage(errorMessage(error, 'Could not save the sample.')),
    )
  }

  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.screen}>
        <View style={styles.cameraStage}>
          <Camera
            quadTensorModel="asset:///doccornernet/model.tflite"
            ref={cameraRef}
            {...tuning}
            mode={view === 'camera' && !optionsOpen ? tuning.mode : 'off'}
            guide={view === 'camera' && !optionsOpen}
            retainCapturePreview={busy || awaitingReviewImage}
            showStats
            onCameraStateChange={setCameraState}
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
            {full && <Text style={styles.notice}>This scan has {MAX_PAGES} pages, the most it can hold. Tap Done.</Text>}
            {!!message && <Text style={styles.notice} numberOfLines={3}>{message}</Text>}
          </View>
        </View>
        <View>
          <View style={styles.controls}>
            <View style={styles.shutterRow}>
              <View style={styles.shutterSide}>
                {pages.length > 0 && (
                  <TuningSwitch
                    label={`Done · ${pages.length}`}
                    disabled={busy || view !== 'camera'}
                    onPress={() => session.showSummary(true)}
                  />
                )}
                {sampling && (
                  <TuningSwitch label="Sample" disabled={view !== 'camera' || cameraState !== 'ready'}
                    onPress={saveLiveSample} />
                )}
              </View>
              <ShutterButton
                disabled={busy || !granted || cameraState !== 'ready' || !session.ready || full || draft != null}
                onPress={takePhoto}
              />
              <View style={styles.shutterSide}>
                <TuningSwitch label="Options" disabled={busy || view !== 'camera'} onPress={() => setOptionsOpen(true)} />
              </View>
            </View>
          </View>
        </View>
      </SafeAreaView>
      {/* Capture, saved pages, and export share one pager overlay. Crop is its separate editor screen. */}
      <ReviewPanel visible={view !== 'camera'} draft={draft} session={session} awaitingImage={awaitingReviewImage}
        sampling={sampling}
        onPreviewReady={() => { if (draft) setDisplayedDraft(draft.id) }}
        onOptions={() => setOptionsOpen(true)} onNewScan={() => confirmDiscard(session.reset)} />
      <SheetModal visible={optionsOpen} onClose={() => setOptionsOpen(false)}>
        <OptionsPanel tuning={tuning} disabled={busy || session.exporting}
          removeShadows={session.removeShadows} onRemoveShadowsChange={session.setDefaultRemoveShadows}
          sampleFolder={sampleFolder} onSampleFolderChange={setSampleFolder}
          onChange={setTuning} onClose={() => setOptionsOpen(false)} />
      </SheetModal>
    </View>
  )
}
