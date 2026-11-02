import type { HybridObject } from 'react-native-nitro-modules'
import type { DocumentAnalysis, DocumentFilter, DocumentQuad, DocumentRenderOptions, ImageFit } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'

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

/** Native metadata only. Image files and render caches remain outside transport. */
export interface ScanPageMetadata {
  readonly id: string
  readonly revision: number
  readonly readyRevision?: number
  readonly analyzing: boolean
  readonly rendering: boolean
  readonly ready: boolean
  readonly analysis?: DocumentAnalysis
  readonly quad: DocumentQuad
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
  saveCaptureSampleResult(pageId: string): Promise<string | ScannerFailure>
  setDocumentCropResult(pageId: string, quad: DocumentQuad): Promise<boolean | ScannerFailure>
  setFilterResult(pageId: string, filter: DocumentFilter): Promise<boolean | ScannerFailure>
  setShadowRemovalResult(pageId: string, enabled: boolean): Promise<boolean | ScannerFailure>
  setPageOrderResult(pageIds: string[]): Promise<boolean | ScannerFailure>
  removePageResult(pageId: string): Promise<boolean | ScannerFailure>
  /** Rejects new mutations immediately; resolves after native work and resource cleanup drain. */
  closeResult(): Promise<boolean | ScannerFailure>
  getSession(): ScanSessionSnapshot
  observeResult(onChange: (change: ScanSessionChange) => void): ScanSessionObservation | ScannerFailure
}
