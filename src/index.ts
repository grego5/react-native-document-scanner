import { Platform } from 'react-native';
import { NitroModules } from 'react-native-nitro-modules';
import type { DocumentScannerProcessor } from './DocumentScannerProcessor.nitro';
import { ScannerError, scannerOperation, scannerValue, unwrapScannerResult } from './ScannerError';
import { createScanSession } from './ScanSession';
export type { ImportImageOptions, LibraryImportResult, SessionPdfOptions } from './ScanSession.nitro';
export type { ImportImageOutcome, LibraryImportOptions, ScanSession, ScanSessionSnapshot, ScanPageMetadata } from './ScanSession';

export { ScannerError, ScannerCancellation } from './ScannerError';
export type { ScannerErrorCode } from './ScannerFailure';

export { Camera } from './DocumentScanner';
export { CameraPermission, useCameraPermission } from './CameraPermission';
export type { CameraPermissionStatus } from './CameraPermission.nitro';
export { CropEditor } from './DocumentCropEditor';
export type { CropEditorProps, CropEditorRef } from './DocumentCropEditor';
export type { CropPreset, CropDraftPreset } from './DocumentCropEditor.nitro';
export { ImageViewer } from './ImageViewer';
export type { ImageViewerProps, ImageViewerRef } from './ImageViewer';
export { PageThumbnail } from './PageThumbnail';
export type { PageThumbnailProps } from './PageThumbnail';
export type { PageThumbnailResizeMode } from './PageThumbnail.nitro';
export { ImagePager } from './ImagePager';
export type { ImagePagerProps } from './ImagePager';
export type { ImagePagerDirection } from './ImagePager.nitro';
export type { CameraMode, CameraProps, CameraRef } from './DocumentScanner';
export type { CameraFeatureSupport } from './DocumentScannerCamera.nitro';
export type { ImageCorrectionOptions, ImageOutputOptions } from './DocumentScannerCamera.nitro';
export type {
  AutofocusState,
  CameraState,
  CaptureMode,
  DocumentState,
  CameraCaptureState,
  TakePhotoOptions,
  LowQualityControl,
  TorchMode,
  CaptureStage,
  CaptureWaitReason,
} from './DocumentScannerCamera.nitro';
export type {
  DocumentAnalysis,
  DocumentCandidate,
  DocumentFilter,
  DocumentImageFormat,
  DocumentPoint,
  DocumentCorners,
  DocumentRenderOptions,
  PaperSize,
  ImageFit,
  SampleFolder,
} from './DocumentScannerProcessor.nitro';

const nativeProcessor = NitroModules.createHybridObject<DocumentScannerProcessor>(
  'DocumentScannerProcessor',
);

/** Creates an explicitly owned session with a stable frame-batched metadata store. */
function createSession() {
  return scannerValue(
    () => createScanSession(unwrapScannerResult(nativeProcessor.createSessionResult())),
    'processor/unavailable',
  );
}
/** Public operations reject with ScannerError or ScannerCancellation; native failure payloads stay behind this facade. */
export const DocumentScanner = {
  createSession,
  /** See `DocumentScannerProcessor.pickSampleFolderResult`. */
  pickSampleFolder: () =>
    scannerOperation(() => nativeProcessor.pickSampleFolderResult(), 'permission/request-failed'),
  getSampleFolder: () =>
    scannerValue(() => nativeProcessor.getSampleFolder(), 'processor/unavailable'),
  clearSampleFolder: () =>
    scannerValue(() => nativeProcessor.clearSampleFolder(), 'processor/unavailable'),
  setSampleFolder: (uri: string) =>
    scannerValue(() => {
      requireSampleCollection();
      nativeProcessor.setSampleFolder(uri);
    }, 'input/invalid'),
  setSampleCollection: (on: boolean) =>
    scannerValue(() => {
      if (on) requireSampleCollection();
      nativeProcessor.setSampleCollection(on);
    }, 'processor/unavailable'),
};

/** iOS defers sample collection; void native setters cannot carry its typed failure. */
function requireSampleCollection() {
  if (Platform.OS === 'ios') throw new ScannerError('feature/unsupported');
}
