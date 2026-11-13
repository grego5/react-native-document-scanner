import type { HybridObject } from 'react-native-nitro-modules'
import type { DocumentAnalysis, DocumentFilter, DocumentCorners, DocumentRenderOptions, ImageFit } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'
import type { ImageCorrectionOptions, ImageOutputOptions } from './DocumentScannerCamera.nitro'

/** PDF placement/encoding options; source pages are supplied as session IDs separately. */
export interface SessionPdfOptions {
  outputPath: string
  imageFit?: ImageFit
  autoRotateExif?: boolean
  jpegQuality?: number
  targetDpi?: number
  pageWidth?: number
  pageHeight?: number
  pageAspectRatio?: number
}

/**
 * Page defaults for an imported image, frozen at admission like a capture's. The original is downscaled to the smallest
 * size that still covers `outputOptions`' paper at its dpi, or to the 4096 px render limit without a paper size.
 * Analysis uses the model the camera has prepared, or the bundled model when none is; a call freezes it for every
 * image it admits.
 */
export interface ImportImageOptions {
  imageCorrection?: ImageCorrectionOptions
  outputOptions?: ImageOutputOptions
  /** Crop to the page analysis finds; defaults to true. Without a page the whole image is kept for editing. */
  autoCrop?: boolean
}

/** Pages admitted from a library pick, in picked order. Cancelling the picker admits none. */
export interface LibraryImportResult {
  readonly pageIds: string[]
  /** Picked images that could not be admitted. */
  readonly failedCount: number
}

/** Native metadata only. Image files and render caches remain outside transport. */
export interface ScanPageMetadata {
  readonly id: string
  readonly revision: number
  readonly readyRevision?: number
  readonly analyzing: boolean
  readonly rendering: boolean
  readonly ready: boolean
  readonly analysis?: DocumentAnalysis
  readonly corners: DocumentCorners
  readonly options: DocumentRenderOptions
  readonly analysisFailure?: ScannerFailure
  readonly renderFailure?: ScannerFailure
}

/** Full immutable source state; sourceVersion sequences native changes, not JS frame publications. */
export interface ScanSessionSnapshot {
  readonly sourceVersion: number
  readonly closed: boolean
  /** Session-owned page order. */
  readonly pageIds: string[]
  readonly pages: Record<string, ScanPageMetadata>
}

export interface ScanSessionChange {
  readonly sourceVersion: number
  readonly closed: boolean
  readonly updatedPages: Record<string, ScanPageMetadata>
  readonly removedPageIds: string[]
  /** Present only when session order changes. */
  readonly pageIds?: string[]
}

/** Registration and initial snapshot are atomic. No eager callback; unsubscribe is idempotent. */
export interface ScanSessionObservation {
  readonly snapshot: ScanSessionSnapshot
  readonly unsubscribe: () => void
}

/** Raw result transport. Public operations translate ScannerFailure to ScannerError. */
export interface ScanSession extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  createPdfResult(pageIds: string[] | undefined, options: SessionPdfOptions): Promise<string | ScannerFailure>
  exportImagesResult(pageIds: string[] | undefined, outputDirectory: string): Promise<string[] | ScannerFailure>
  /** Admits a local `file://` image, or an Android `content://` image, as the last page; resolves with its page ID. */
  importImageResult(uri: string, options: ImportImageOptions): Promise<string | ScannerFailure>
  /**
   * Presents the system photo picker, without library permission, and admits each picked image in order as by
   * `importImageResult`. `maxCount` 0 allows the system maximum. One picker may be open at a time.
   */
  importFromLibraryResult(options: ImportImageOptions, maxCount: number): Promise<LibraryImportResult | ScannerFailure>
  saveCaptureSampleResult(pageId: string): Promise<string | ScannerFailure>
  setDocumentCropResult(pageId: string, corners: DocumentCorners): Promise<boolean | ScannerFailure>
  setFilterResult(pageId: string, filter: DocumentFilter): Promise<boolean | ScannerFailure>
  setShadowRemovalResult(pageId: string, enabled: boolean): Promise<boolean | ScannerFailure>
  setPageOrderResult(pageIds: string[]): Promise<boolean | ScannerFailure>
  removePageResult(pageId: string): Promise<boolean | ScannerFailure>
  /** Rejects new mutations immediately; resolves after native work and resource cleanup drain. */
  closeResult(): Promise<boolean | ScannerFailure>
  getSession(): ScanSessionSnapshot
  observeResult(onChange: (change: ScanSessionChange) => void): ScanSessionObservation | ScannerFailure
}
