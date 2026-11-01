import { useCallback, useRef, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import {
  CropEditor,
  ImagePager,
  ScannerError,
  type DocumentFilter,
  type CropEditorRef,
} from 'react-native-document-scanner';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { ScanReview } from '../hooks/useScanReview';
import { usePhotoActions } from '../hooks/usePhotoActions';
import { errorMessage } from '../lib/errorMessage';
import { MAX_PAGES } from '../lib/scanSession';
import { styles } from '../styles';
import { ExportSheet } from './ExportSheet';
import { PageStrip } from './PageStrip';
import { SheetModal } from './SheetModal';
import { PrimaryButton } from './PrimaryButton';
import { ActionButton } from './ActionButton';

const FILTERS: { value: DocumentFilter; label: string }[] = [
  { value: 'none', label: 'Original' },
  { value: 'enhance', label: 'Enhance' },
  { value: 'grayscale', label: 'Gray' },
  { value: 'blackWhite', label: 'B&W' },
];

interface ReviewProps {
  visible: boolean;
  session: ScanReview;
  /** A sample folder is picked, so a new photo can be saved as a sample. */
  sampling: boolean;
  onOptions: () => void;
  onNewScan: () => void;
}

/** Retained above the camera: hiding preview or opening crop never unmounts its pager. */
export function ReviewPanel(props: ReviewProps) {
  const review = props.session.screen.type === 'review' ? props.session.screen : null;
  const cropping = review?.cropping;
  if (!props.session.native) return null;
  return (
    <View
      pointerEvents={props.visible ? 'auto' : 'none'}
      accessibilityElementsHidden={!props.visible}
      importantForAccessibility={props.visible ? 'auto' : 'no-hide-descendants'}
      style={[StyleSheet.absoluteFill, !props.visible && styles.hiddenOverlay]}>
      <PreviewMode {...props} visible={props.visible && !cropping} />
      {props.visible && review && cropping && (
        <CropMode pageId={review.pageId} session={props.session} />
      )}
    </View>
  );
}

function PreviewMode({
  visible,
  session,
  sampling,
  onOptions,
  onNewScan,
}: ReviewProps) {
  const review = session.screen.type === 'review' ? session.screen : null;
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const closeExport = useCallback(() => setExportOpen(false), []);
  const actions = usePhotoActions(session.native, session.run);
  const { pageIds, page, working } = session;
  const newPhoto = review?.origin === 'capture';
  const selectedId = session.selectedId;
  const index = pageIds.indexOf(selectedId ?? '');
  const editBlocked = working || !page;
  const blocked = working || !page?.ready;
  const canNavigate = visible && !working && !newPhoto && !review?.cropping;
  const filter = page?.options.filter ?? 'none';
  const shadow = page?.options.removeShadows ?? true;
  const selectPage = useCallback(
    (position: number) => {
      if (canNavigate && pageIds[position]) session.select(pageIds[position]);
    },
    [canNavigate, pageIds, session.select],
  );
  const saveSample = () => {
    if (!page || !session.native) return;
    void session.run(async () => {
      try {
        const name = await session.native.saveCaptureSample(page.id);
        Alert.alert('Sample saved', name);
      } catch (error) {
        Alert.alert('Sample', errorMessage(error, 'Could not save the sample.'));
      }
    });
  };

  const confirmDelete = () => {
    Alert.alert(newPhoto ? 'Delete this photo?' : `Delete page ${index + 1}?`, undefined, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: () => {
          if (newPhoto) session.dismissReview();
          else if (page) session.deletePage(page.id);
        },
      },
    ]);
  };

  return (
    <SafeAreaView
      pointerEvents={!visible ? 'none' : 'auto'}
      accessibilityElementsHidden={!visible}
      importantForAccessibility={visible ? 'auto' : 'no-hide-descendants'}
      style={[
        StyleSheet.absoluteFill,
        styles.reviewScreen,
        (!visible) && styles.hiddenOverlay,
      ]}>
      <View style={styles.reviewHeader}>
        <ActionButton label="New scan" disabled={working} onPress={onNewScan} />
        <Text style={styles.pageCounter}>
          {index + 1} / {pageIds.length}
        </Text>
        <ActionButton label="Options" disabled={working} onPress={onOptions} />
      </View>
      <View style={styles.imageEditor}>
        <ImagePager
          session={session.native}
          selectedPageId={selectedId ?? ''}
          swipeEnabled={canNavigate}
          onPageChange={session.select}
          onError={(error) => {
            Alert.alert('Page preview', errorMessage(error, 'The page could not be shown.'));
          }}
          style={StyleSheet.absoluteFill}
        />
        {page?.analyzing && <Text style={styles.imageHint}>Finding the page…</Text>}
        {page?.rendering && <Text style={styles.imageHint}>Updating…</Text>}
        {(page?.renderFailure ?? page?.analysisFailure) && (
          <Text style={styles.imageHint}>
            {errorMessage(
              new ScannerError((page!.renderFailure ?? page!.analysisFailure)!.code),
              '',
            )}
          </Text>
        )}
      </View>
      <PageStrip
        session={session.native}
        pageIds={pageIds}
        index={index}
        disabled={!canNavigate}
        onSelect={selectPage}
      />
      <View style={styles.actions}>
        <View style={styles.reviewTools}>
          <Toggle
            label="Shadow"
            checked={shadow}
            disabled={editBlocked}
            onPress={() => session.setShadowRemoval(!shadow)}
          />
          <ActionButton
            label="Crop"
            style={styles.reviewTool}
            disabled={editBlocked}
            onPress={session.startCrop}
          />
          <ActionButton
            label={`Filter · ${FILTERS.find((item) => item.value === filter)!.label}`}
            style={styles.reviewTool}
            disabled={editBlocked}
            onPress={() => setFiltersOpen(true)}
          />
          {sampling && newPhoto && (
            <ActionButton
              label="Sample"
              style={styles.reviewTool}
              disabled={editBlocked || page?.analyzing}
              onPress={saveSample}
            />
          )}
        </View>
        <View style={styles.actionRow}>
          <PrimaryButton
            label="Delete"
            variant="secondary"
            style={styles.actionButton}
            disabled={working}
            onPress={confirmDelete}
          />
          <PrimaryButton
            label="Add page"
            variant="secondary"
            style={styles.actionButton}
            disabled={blocked || pageIds.length >= MAX_PAGES}
            onPress={() => session.navigate('camera')}
          />
          {review != null ? (
            <PrimaryButton
              label={newPhoto ? 'Done' : 'Save'}
              style={styles.actionButton}
              disabled={blocked}
              onPress={() => session.navigate('summary')}
            />
          ) : (
            <PrimaryButton
              label="Export"
              style={styles.actionButton}
              disabled={blocked}
              onPress={() => setExportOpen(true)}
            />
          )}
        </View>
      </View>
      <SheetModal visible={filtersOpen} onClose={() => setFiltersOpen(false)}>
        <View style={styles.sheet}>
          <Text style={styles.sessionTitle}>Filter</Text>
          {FILTERS.map((item) => (
            <Pressable
              key={item.value}
              accessibilityRole="radio"
              accessibilityState={{ checked: filter === item.value, disabled: editBlocked }}
              disabled={editBlocked}
              style={[styles.filterChoice, filter === item.value && styles.activeTool]}
              onPress={() => {
                session.setFilter(item.value);
                setFiltersOpen(false);
              }}>
              <Text style={styles.tuningLabel}>{item.label}</Text>
            </Pressable>
          ))}
          <PrimaryButton label="Close" variant="secondary" onPress={() => setFiltersOpen(false)} />
        </View>
      </SheetModal>
      <SheetModal visible={exportOpen} onClose={closeExport}>
        <ExportSheet
          pageCount={pageIds.length}
          onCancel={closeExport}
          onSharePdf={(settings) => {
            closeExport();
            void actions.sharePdf(settings);
          }}
          onSaveImages={() => {
            closeExport();
            void actions.saveAll();
          }}
        />
      </SheetModal>
    </SafeAreaView>
  );
}

function Toggle({
  label,
  checked,
  disabled,
  onPress,
}: {
  label: string;
  checked: boolean;
  disabled: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.tuningSwitch,
        styles.reviewTool,
        checked && styles.activeTool,
        disabled && styles.buttonDisabled,
      ]}>
      <Text style={styles.tuningLabel}>
        {label} {checked ? '●' : '○'}
      </Text>
    </Pressable>
  );
}

/** Moves the outline over the whole original photo; Submit re-renders the page with it, Cancel keeps the old one. */
function CropMode({
  pageId,
  session,
}: {
  pageId: string;
  session: ScanReview;
}) {
  const editor = useRef<CropEditorRef>(null);
  const autoCrop = session.page?.analysis?.candidate != null;
  const setPreset = (preset: 'none' | 'auto') => {
    void session.run(async () => {
      try { await editor.current?.setPreset(preset) }
      catch (error) { Alert.alert('Crop editor', errorMessage(error, 'Could not update the crop.')) }
    });
  };
  return (
    <SafeAreaView style={[StyleSheet.absoluteFill, styles.reviewScreen]}>
      <View style={styles.imageEditor}>
        <CropEditor
          session={session.native}
          pageId={pageId}
          ref={editor}
          onError={(error) =>
            Alert.alert(
              'Crop editor',
              errorMessage(error, 'The editor could not update the photo.'),
            )
          }
          style={StyleSheet.absoluteFill}
        />
      </View>
      <Text style={styles.diagnostics}>Drag the corners onto the page's edges.</Text>
      <View style={styles.actions}>
        <View style={styles.reviewTools}>
          <ActionButton
            label="No crop"
            style={styles.reviewTool}
            onPress={() => setPreset('none')}
          />
          <ActionButton
            label="Auto crop"
            style={styles.reviewTool}
            disabled={!autoCrop || session.working}
            onPress={() => {
              setPreset('auto');
            }}
          />
        </View>
        <View style={styles.actionRow}>
          <PrimaryButton
            disabled={session.working}
            label="Cancel"
            variant="secondary"
            style={styles.actionButton}
            onPress={() => session.cancelCrop()}
          />
          <PrimaryButton
            disabled={session.working}
            label="Submit"
            style={styles.actionButton}
            onPress={() => editor.current && session.submitCrop(editor.current)}
          />
        </View>
      </View>
    </SafeAreaView>
  );
}

