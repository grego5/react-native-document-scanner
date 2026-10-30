import { useCallback, useState } from 'react'
import { Alert, Image, Pressable, StyleSheet, Text, View } from 'react-native'
import {
  CropEditor,
  DocumentScanner,
  ImagePager,
  type DocumentFilter,
  type DocumentQuad,
} from 'react-native-document-scanner'
import { SafeAreaView } from 'react-native-safe-area-context'
import type { ScanSession } from '../hooks/useScanSession'
import { usePhotoActions } from '../hooks/usePhotoActions'
import { clampQuadToFrame, isValidEditableQuad, sameQuad } from '../lib/documentReview'
import { errorMessage } from '../lib/errorMessage'
import { canSwitchPage, FULL_FRAME, isPreviewCurrent, MAX_PAGES, type ReviewDraft } from '../lib/scanSession'
import { styles } from '../styles'
import { ExportSheet } from './ExportSheet'
import { PageStrip } from './PageStrip'
import { SheetModal } from './SheetModal'
import { PrimaryButton } from './PrimaryButton'
import { TuningSwitch } from './TuningSwitch'

const FILTERS: { value: DocumentFilter; label: string }[] = [
  { value: 'none', label: 'Original' },
  { value: 'enhance', label: 'Enhance' },
  { value: 'grayscale', label: 'Gray' },
  { value: 'blackWhite', label: 'B&W' },
]

interface ReviewProps {
  visible: boolean
  draft: ReviewDraft | null
  session: ScanSession
  awaitingImage: boolean
  /** A sample folder is picked, so a new photo can be saved as a sample. */
  sampling: boolean
  onPreviewReady: () => void
  onOptions: () => void
  onNewScan: () => void
}

/** Retained above the camera: hiding preview or opening crop never unmounts its pager. */
export function ReviewPanel(props: ReviewProps) {
  const cropQuad = props.draft?.cropQuad
  return (
    <View pointerEvents={props.visible ? 'auto' : 'none'}
      accessibilityElementsHidden={!props.visible}
      importantForAccessibility={props.visible ? 'auto' : 'no-hide-descendants'}
      style={[StyleSheet.absoluteFill, !props.visible && styles.hiddenOverlay]}>
      <PreviewMode {...props} visible={props.visible && cropQuad == null} />
      {props.visible && props.draft && cropQuad && (
        <CropMode draft={props.draft} cropQuad={cropQuad} session={props.session} />
      )}
    </View>
  )
}

function PreviewMode({ visible, draft, session, awaitingImage, sampling, onPreviewReady, onOptions, onNewScan }: ReviewProps) {
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const closeExport = useCallback(() => setExportOpen(false), [])
  const actions = usePhotoActions(session.exportPages)
  const { pages, rendering, exporting } = session
  const newPhoto = draft != null && draft.pageId == null
  const selectedId = draft?.pageId ?? session.selectedId
  const pageIndex = pages.findIndex((page) => page.id === selectedId)
  const page = pages[pageIndex]
  const index = newPhoto ? pages.length : Math.max(0, pageIndex)
  const processing = (draft?.analyzing ?? false) || rendering || exporting
  const current = draft == null || isPreviewCurrent(draft)
  const blocked = processing || !current || awaitingImage
  const canNavigate = visible && !blocked && (draft == null || canSwitchPage(session))
  const shownUri = draft?.preview?.uri ?? draft?.frameUri ?? draft?.originalUri ?? page?.outputUri
  const filter = draft?.filter ?? page?.filter ?? 'none'
  const shadow = draft?.removeShadows ?? page?.removeShadows ?? true
  const thumbnails = pages.map((item) => ({
    id: item.id,
    uri: draft?.pageId === item.id ? draft.preview?.thumbnailUri ?? item.thumbnailUri : item.thumbnailUri,
  }))
  const uris = pages.map((item) => draft?.pageId === item.id ? shownUri! : item.outputUri)
  if (newPhoto) {
    thumbnails.push({ id: draft.id, uri: draft.preview?.thumbnailUri ?? shownUri! })
    uris.push(shownUri!)
  }

  const selectPage = (position: number) => {
    if (!canNavigate || position === index) return
    const target = pages[position]
    if (!target) return
    if (draft) session.switchPage(target.id)
    else session.select(target.id)
  }

  // The outline the user accepted is the photo's label; without one the photo is saved unlabeled.
  const saveSample = () => {
    if (!draft) return
    DocumentScanner.saveCaptureSample(draft.originalUri, draft.quad ?? undefined).then(
      (name) => Alert.alert('Sample saved', name),
      (error: unknown) => Alert.alert('Sample', errorMessage(error, 'Could not save the sample.')),
    )
  }

  const confirmDelete = () => {
    Alert.alert(newPhoto ? 'Delete this photo?' : `Delete page ${index + 1}?`, undefined, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => {
        if (newPhoto) session.discardDraft()
        else if (page) session.deletePage(page.id)
      } },
    ])
  }

  return (
    <SafeAreaView pointerEvents={!visible || awaitingImage ? 'none' : 'auto'}
      accessibilityElementsHidden={!visible}
      importantForAccessibility={visible ? 'auto' : 'no-hide-descendants'}
      style={[StyleSheet.absoluteFill, styles.reviewScreen, (!visible || awaitingImage) && styles.hiddenOverlay]}>
      <View style={styles.reviewHeader}>
        <TuningSwitch label="New scan" disabled={processing} onPress={onNewScan} />
        <Text style={styles.pageCounter}>{index + 1} / {uris.length}</Text>
        <TuningSwitch label="Options" disabled={processing} onPress={onOptions} />
      </View>
      <View style={styles.imageEditor}>
        <ImagePager uris={uris} index={index} swipeEnabled={canNavigate}
          onIndexChange={selectPage}
          onLoad={(uri) => { if (uri === shownUri) onPreviewReady() }}
          onError={(error) => {
            onPreviewReady()
            Alert.alert('Page preview', errorMessage(error, 'The page could not be shown.'))
          }}
          style={StyleSheet.absoluteFill} />
        {draft?.analyzing && <Text style={styles.imageHint}>Finding the page…</Text>}
        {rendering && <Text style={styles.imageHint}>Updating…</Text>}
      </View>
      <PageStrip pages={thumbnails} index={index} disabled={!canNavigate} onSelect={selectPage} />
      <View style={styles.actions}>
        <View style={styles.reviewTools}>
          <Toggle label="Shadow" checked={shadow} disabled={processing}
            onPress={() => session.setRemoveShadows(!shadow)} />
          <TuningSwitch label="Crop" style={styles.reviewTool} disabled={processing || (draft != null && draft.quad == null)}
            onPress={session.startCrop} />
          <TuningSwitch label={`Filter · ${FILTERS.find((item) => item.value === filter)!.label}`} style={styles.reviewTool}
            disabled={processing} onPress={() => setFiltersOpen(true)} />
          {sampling && newPhoto && (
            <TuningSwitch label="Sample" style={styles.reviewTool} disabled={draft.analyzing} onPress={saveSample} />
          )}
        </View>
        <View style={styles.actionRow}>
          <PrimaryButton label="Delete" variant="secondary" style={styles.actionButton}
            disabled={exporting} onPress={confirmDelete} />
          <PrimaryButton label="Add page" variant="secondary" style={styles.actionButton}
            disabled={blocked || pages.length + (newPhoto ? 1 : 0) >= MAX_PAGES}
            onPress={() => draft ? session.commitDraft('camera') : session.showSummary(false)} />
          {draft && (newPhoto || !canSwitchPage(session)) ? (
            <PrimaryButton label={newPhoto ? 'Done' : 'Save'} style={styles.actionButton}
              disabled={blocked} onPress={() => session.commitDraft('summary')} />
          ) : (
            <PrimaryButton label="Export" style={styles.actionButton} disabled={blocked}
              onPress={() => setExportOpen(true)} />
          )}
        </View>
      </View>
      <SheetModal visible={filtersOpen} onClose={() => setFiltersOpen(false)}>
        <View style={styles.sheet}>
          <Text style={styles.sessionTitle}>Filter</Text>
          {FILTERS.map((item) => (
            <Pressable key={item.value} accessibilityRole="radio"
              accessibilityState={{ checked: filter === item.value, disabled: processing }}
              disabled={processing} style={[styles.filterChoice, filter === item.value && styles.activeTool]}
              onPress={() => { session.setFilter(item.value); setFiltersOpen(false) }}>
              <Text style={styles.tuningLabel}>{item.label}</Text>
            </Pressable>
          ))}
          <PrimaryButton label="Close" variant="secondary" onPress={() => setFiltersOpen(false)} />
        </View>
      </SheetModal>
      <SheetModal visible={exportOpen} onClose={closeExport}>
        <ExportSheet pageCount={pages.length} onCancel={closeExport}
          onSharePdf={(settings) => { closeExport(); void actions.sharePdf(pages.map((item) => item.outputUri), settings) }}
          onSaveImages={() => { closeExport(); void actions.saveAll(pages.map((item) => item.outputUri)) }} />
      </SheetModal>
    </SafeAreaView>
  )
}

function Toggle({ label, checked, disabled, onPress }: {
  label: string; checked: boolean; disabled: boolean; onPress: () => void
}) {
  return (
    <Pressable accessibilityRole="switch" accessibilityLabel={label}
      accessibilityState={{ checked, disabled }} disabled={disabled} onPress={onPress}
      style={[styles.tuningSwitch, styles.reviewTool, checked && styles.activeTool, disabled && styles.buttonDisabled]}>
      <Text style={styles.tuningLabel}>{label} {checked ? '●' : '○'}</Text>
    </Pressable>
  )
}

/** Moves the outline over the whole original photo; Submit re-renders the page with it, Cancel keeps the old one. */
function CropMode({ draft, cropQuad, session }: { draft: ReviewDraft; cropQuad: DocumentQuad; session: ScanSession }) {
  const autoQuad = draft.analysis?.candidate?.quad ?? draft.croppedQuad
  const autoCrop = autoQuad ? clampQuadToFrame(autoQuad) : null
  return (
    <SafeAreaView style={[StyleSheet.absoluteFill, styles.reviewScreen]}>
      <View style={styles.imageEditor}>
        <Image source={{ uri: draft.originalUri }} resizeMode="contain" style={styles.photo} />
        {/* Draws the same contain-fitted photo over the Image once it has loaded, so there is no blank frame. */}
        <CropEditor
          originalUri={draft.originalUri}
          quad={cropQuad}
          onQuadChange={session.editQuad}
          onError={(error) => Alert.alert('Crop editor', errorMessage(error, 'The editor could not update the photo.'))}
          style={StyleSheet.absoluteFill}
        />
      </View>
      <Text style={styles.diagnostics}>Drag the corners onto the page's edges.</Text>
      <View style={styles.actions}>
        <View style={styles.reviewTools}>
          <Toggle label="No crop" checked={sameQuad(cropQuad, FULL_FRAME)} disabled={false}
            onPress={() => session.editQuad(FULL_FRAME)} />
          <Toggle label="Auto crop" checked={autoCrop != null && sameQuad(cropQuad, autoCrop)} disabled={autoCrop == null}
            onPress={() => { if (autoCrop) session.editQuad(autoCrop) }} />
        </View>
        <View style={styles.actionRow}>
          <PrimaryButton
            label="Cancel"
            variant="secondary"
            style={styles.actionButton}
            onPress={() => session.endCrop(false)}
          />
          <PrimaryButton
            label="Submit"
            style={styles.actionButton}
            onPress={() => session.endCrop(true)}
            disabled={!isValidEditableQuad(cropQuad)}
          />
        </View>
      </View>
    </SafeAreaView>
  )
}
