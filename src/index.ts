import { NitroModules } from 'react-native-nitro-modules';
import type {
  CreatePdfOptions,
  DocumentQuad,
  DocumentRenderOptions,
  DocumentScannerProcessor,
} from './DocumentScannerProcessor.nitro';
import { scannerOperation, scannerValue } from './ScannerError';

export { ScannerError } from './ScannerError';
export type { ScannerErrorCode } from './ScannerFailure';

export { Camera } from './DocumentScannerCamera';
export { CameraPermission, useCameraPermission } from './CameraPermission';
export type { CameraPermissionStatus } from './CameraPermission.nitro';
export { CropEditor } from './DocumentCropEditor';
export type { CropEditorProps } from './DocumentCropEditor';
export { ImageViewer } from './ImageViewer';
export type { ImageViewerProps, ImageViewerRef } from './ImageViewer';
export { ImagePager } from './ImagePager';
export type { ImagePagerProps } from './ImagePager';
export type { CameraProps, CameraRef } from './DocumentScannerCamera';
export type {
  AutofocusState,
  CameraState,
  CaptureMode,
  DocumentState,
  CapturedPhoto,
  TakePhotoOptions,
  QualityGateConfig,
  TorchMode,
  CameraMode,
  CaptureStage,
} from './DocumentScannerCamera.nitro';
export type {
  CreatePdfOptions,
  DocumentAnalysis,
  DocumentCandidate,
  DocumentCornerProvenance,
  DocumentFilter,
  DocumentImageFormat,
  DocumentPoint,
  DocumentPointProvenance,
  DocumentQuad,
  DocumentRenderOptions,
  PaperSize,
  ImageFit,
  RenderedDocument,
  SampleFolder,
  DocumentSideProvenance,
} from './DocumentScannerProcessor.nitro';

const nativeProcessor = NitroModules.createHybridObject<DocumentScannerProcessor>(
  'DocumentScannerProcessor',
);

/** Public operations reject with ScannerError; native failure payloads stay behind this facade. */
export const DocumentScanner = {
  analyzePhoto: (originalUri: string, liveQuad?: DocumentQuad) =>
    scannerOperation(
      () => nativeProcessor.analyzePhotoResult(originalUri, liveQuad),
      'analysis/failed',
    ),
  renderDocument: (originalUri: string, quad: DocumentQuad, options?: DocumentRenderOptions) =>
    scannerOperation(
      () => nativeProcessor.renderDocumentResult(originalUri, quad, options),
      'render/failed',
    ),
  createPdf: (options: CreatePdfOptions) =>
    scannerOperation(() => nativeProcessor.createPdfResult(options), 'pdf/write-failed'),
  removeCachedImages: async (uris: string[]): Promise<void> => {
    await scannerOperation(
      () => nativeProcessor.removeCachedImagesResult(uris),
      'cache/delete-failed',
    );
  },
  /** See `DocumentScannerProcessor.pickSampleFolderResult`. */
  pickSampleFolder: () =>
    scannerOperation(() => nativeProcessor.pickSampleFolderResult(), 'permission/request-failed'),
  getSampleFolder: () =>
    scannerValue(() => nativeProcessor.getSampleFolder(), 'processor/unavailable'),
  clearSampleFolder: () =>
    scannerValue(() => nativeProcessor.clearSampleFolder(), 'processor/unavailable'),
  setSampleFolder: (uri: string) =>
    scannerValue(() => nativeProcessor.setSampleFolder(uri), 'input/invalid'),
  setSampleCollection: (on: boolean) =>
    scannerValue(() => nativeProcessor.setSampleCollection(on), 'processor/unavailable'),
  /** See `DocumentScannerProcessor.saveCaptureSampleResult`; resolves with the photo's file name. */
  saveCaptureSample: (originalUri: string, quad?: DocumentQuad) =>
    scannerOperation(
      () => nativeProcessor.saveCaptureSampleResult(originalUri, quad),
      'storage/write-failed',
    ),
  dispose: () => scannerValue(() => nativeProcessor.dispose(), 'processor/unavailable'),
};

