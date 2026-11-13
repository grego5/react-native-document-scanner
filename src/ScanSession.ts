import type { DocumentFilter, DocumentCorners } from './DocumentScannerProcessor.nitro'
import type {
  ImportImageOptions,
  LibraryImportResult,
  SessionPdfOptions,
  ScanSessionChange,
  ScanPageMetadata as NativePageMetadata,
  ScanSession as NativeScanSession,
  ScanSessionSnapshot as NativeSnapshot,
} from './ScanSession.nitro'
import { ScannerError, scannerOperation, scannerValue, unwrapScannerResult } from './ScannerError'
import type { ScannerFailure } from './ScannerFailure'

type Immutable<T> = T extends object ? { readonly [Key in keyof T]: Immutable<T[Key]> } : T
export type ScanPageMetadata = Immutable<NativePageMetadata>

export interface ScanSessionSnapshot {
  readonly version: number
  readonly closed: boolean
  readonly pageIds: readonly string[]
  readonly pages: Readonly<Record<string, ScanPageMetadata>>
}

/** One picked image's import: its admitted page, or why it was not admitted. */
export type ImportImageOutcome =
  | { readonly uri: string; readonly pageId: string; readonly error?: undefined }
  | { readonly uri: string; readonly pageId?: undefined; readonly error: Error }

export interface LibraryImportOptions extends ImportImageOptions {
  maxCount?: number
}

// Pending state can reuse the frozen order from the last publication.
type PendingSnapshot = Omit<NativeSnapshot, 'pageIds'> & { readonly pageIds: readonly string[] }

/**
 * useSyncExternalStore(session.subscribe, session.getSession) reads immutable publications. Awaited mutations publish accepted settings before resolving.
 * Plain stores read getSession(), then subscribe and read again to reconcile a publication between
 * their first read and registration. Subscribe never delivers synchronously.
 */
export interface ScanSession {
  getSession(): ScanSessionSnapshot
  subscribe(listener: () => void): () => void
  setDocumentCrop(pageId: string, corners: DocumentCorners): Promise<void>
  setFilter(pageId: string, filter: DocumentFilter): Promise<void>
  setShadowRemoval(pageId: string, enabled: boolean): Promise<void>
  removePage(pageId: string): Promise<void>
  setPageOrder(pageIds: readonly string[]): Promise<void>
  createPdf(options: SessionPdfOptions): Promise<string>
  createPdf(pageIds: readonly string[], options: SessionPdfOptions): Promise<string>
  exportImages(outputDirectory: string): Promise<string[]>
  exportImages(pageIds: readonly string[], outputDirectory: string): Promise<string[]>
  saveCaptureSample(pageId: string): Promise<string>
  /**
   * Appends a picked image as a page that is analyzed, cropped and filtered like a capture; resolves with its page ID
   * once admitted. Rendering continues in the background, as for a capture.
   */
  importImage(uri: string, options?: ImportImageOptions): Promise<string>
  /** Imports images one at a time in order; each succeeds or fails on its own. */
  importImages(uris: readonly string[], options?: ImportImageOptions): Promise<ImportImageOutcome[]>
  /**
   * Presents the system photo picker and imports the picked images in order, like `importImage`. Cancelling resolves
   * with no pages. `maxCount` limits the selection; omitted, the system maximum applies.
   */
  importFromLibrary(options?: LibraryImportOptions): Promise<LibraryImportResult>
  close(): Promise<void>
}

/** Internal scheduler boundary for deterministic tests; production uses the platform frame owner. */
interface Frames {
  request(callback: () => void): number
  cancel(id: number): void
}

const platformFrames: Frames = {
  request: (callback) => requestAnimationFrame(callback),
  cancel: (id) => cancelAnimationFrame(id),
}

function report(error: unknown): void {
  // Diagnostics must not interrupt other listeners or turn a handled cleanup rejection unhandled.
  try { console.warn('Document scanner session failed.', error) } catch { /* Logging is best effort. */ }
}

function equalValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length &&
      a.every((value: unknown, index: number) => equalValue(value, b[index]))
  }
  // Transport objects are plain value records, with absent optional fields equivalent to undefined.
  const left = a as Record<string, unknown>
  const right = b as Record<string, unknown>
  let leftCount = 0
  for (const key in left) {
    if (!Object.prototype.hasOwnProperty.call(left, key) || left[key] === undefined) continue
    if (!Object.prototype.hasOwnProperty.call(right, key) || !equalValue(left[key], right[key])) return false
    leftCount++
  }
  let rightCount = 0
  for (const key in right) {
    if (Object.prototype.hasOwnProperty.call(right, key) && right[key] !== undefined) rightCount++
  }
  return leftCount === rightCount
}

function freezeInDev<T extends object>(value: T): Readonly<T> {
  return __DEV__ ? Object.freeze(value) : value
}

function immutableCopy<T>(value: T): Immutable<T> {
  // These casts retain the relationship between a plain transport value and its recursively copied type.
  if (Array.isArray(value)) return freezeInDev(value.map((item: unknown) => immutableCopy(item))) as Immutable<T>
  if (value !== null && typeof value === 'object') {
    const source = value as Record<string, unknown>
    const copy: Record<string, unknown> = {}
    for (const key in source) {
      if (!Object.prototype.hasOwnProperty.call(source, key) || source[key] === undefined) continue
      const item = immutableCopy(source[key])
      if (key === '__proto__') {
        Object.defineProperty(copy, key, { value: item, enumerable: true, writable: true, configurable: true })
      } else copy[key] = item
    }
    return freezeInDev(copy) as Immutable<T>
  }
  return value as Immutable<T>
}

class SessionStore implements ScanSession {
  private published: ScanSessionSnapshot
  private pending: PendingSnapshot | undefined
  private sourceVersion: number
  private readonly incoming: ScanSessionChange[] = []
  private initializing = true
  private receiving = false
  private released = false
  private frame: number | undefined
  private closePromise: Promise<void> | undefined
  private readonly unsubscribeNative: () => void
  private readonly listeners = new Set<{ listener: () => void; version: number }>()

  constructor(readonly native: NativeScanSession, private readonly frames: Frames) {
    const registration = scannerValue(
      () => unwrapScannerResult(native.observeResult(this.receive)),
      'processor/unavailable',
    )
    this.unsubscribeNative = registration.unsubscribe
    this.sourceVersion = registration.snapshot.sourceVersion
    this.published = this.snapshot(registration.snapshot, 0)
    this.initializing = false
    if (this.published.closed) this.release()
    else this.drainIncoming()
  }

  getSession = (): ScanSessionSnapshot => this.published

  subscribe = (listener: () => void): (() => void) => {
    const version = this.published.version
    const entry = { listener, version }
    if (!this.released) this.listeners.add(entry)
    return () => { this.listeners.delete(entry) }
  }

  private receive = (snapshot: ScanSessionChange): void => {
    if (this.released) return
    this.incoming.push(snapshot)
    if (!this.initializing) this.drainIncoming()
  }

  private drainIncoming(): void {
    if (this.receiving) return
    this.receiving = true
    try {
      let next: ScanSessionChange | undefined
      while ((next = this.incoming.shift()) !== undefined) {
        if (this.pending?.closed || next.sourceVersion <= this.sourceVersion) continue
        if (next.closed) {
          this.pending = { sourceVersion: next.sourceVersion, closed: true, pageIds: [], pages: {} }
        } else if (next.sourceVersion !== this.sourceVersion + 1) {
          try {
            const full = scannerValue(() => this.native.getSession(), 'processor/unavailable')
            if (full.sourceVersion < next.sourceVersion) continue
            this.pending = full
            this.sourceVersion = full.sourceVersion
            this.schedule()
            continue
          } catch (error) { report(error); continue }
        } else {
          const base = this.pending ?? this.published
          const updatedPages = next.updatedPages
          let pageIds: readonly string[] = next.pageIds ?? base.pageIds
          if (next.pageIds === undefined) {
            let changedIds: string[] | undefined
            if (next.removedPageIds.length !== 0) {
              const removedIds = new Set(next.removedPageIds)
              const retainedIds = base.pageIds.filter(id =>
                !removedIds.has(id) || Object.prototype.hasOwnProperty.call(updatedPages, id))
              if (retainedIds.length !== base.pageIds.length) changedIds = retainedIds
            }
            for (const id of Object.keys(updatedPages)) {
              if (!Object.prototype.hasOwnProperty.call(base.pages, id)) (changedIds ??= [...base.pageIds]).push(id)
            }
            pageIds = changedIds ?? base.pageIds
          }
          const pages: Record<string, NativePageMetadata> = Object.create(null)
          for (const id of pageIds) {
            pages[id] = Object.prototype.hasOwnProperty.call(updatedPages, id)
              ? updatedPages[id] : base.pages[id]
          }
          this.pending = { sourceVersion: next.sourceVersion, closed: false, pages, pageIds }
        }
        this.sourceVersion = next.sourceVersion
        this.schedule()
      }
    } finally { this.receiving = false }
  }

  private schedule(): void {
    if (!this.released && this.frame === undefined) this.frame = this.frames.request(this.flush)
  }

  private snapshot(source: PendingSnapshot, version: number): ScanSessionSnapshot {
    const previous = this.initializing ? undefined : this.published
    const pages: Record<string, ScanPageMetadata> = Object.create(null)
    let changed = previous === undefined || Object.keys(previous.pages).length !== Object.keys(source.pages).length
    for (const [id, value] of Object.entries(source.pages)) {
      const old = previous?.pages[id]
      pages[id] = old !== undefined && equalValue(old, value) ? old : immutableCopy(value)
      if (pages[id] !== old) changed = true
    }
    const pageIds = previous && equalValue(previous.pageIds, source.pageIds)
      ? previous.pageIds : immutableCopy(source.pageIds)
    if (previous !== undefined && !changed && previous.closed === source.closed && pageIds === previous.pageIds) return previous
    return freezeInDev({
      version,
      closed: source.closed,
      pageIds,
      pages: !changed && previous !== undefined ? previous.pages : freezeInDev(pages),
    })
  }

  private flush = (): void => {
    this.frame = undefined
    if (this.released) return
    const next = this.pending
    this.pending = undefined
    if (next !== undefined) this.published = this.snapshot(next, this.published.version + 1)
    // Install first. New listeners during notification already read this version and are not replayed.
    for (const entry of this.listeners) {
      if (!this.listeners.has(entry) || entry.version >= this.published.version) continue
      entry.version = this.published.version
      try { entry.listener() } catch (error) { report(error) }
    }
    if (this.published.closed) this.release()
  }

  private release(): void {
    if (this.released) return
    this.released = true
    if (this.frame !== undefined) this.frames.cancel(this.frame)
    this.frame = undefined
    this.pending = undefined
    this.incoming.length = 0
    this.listeners.clear()
    try { this.unsubscribeNative() } catch (error) { report(error) }
  }

  /** Command boundary: publish accepted native state before returning control to its caller. */
  synchronize(snapshot?: NativeSnapshot): void {
    if (this.released) return
    const full = snapshot ?? scannerValue(() => this.native.getSession(), 'processor/unavailable')
    if (full.sourceVersion >= this.sourceVersion) {
      this.sourceVersion = full.sourceVersion
      this.pending = full
    }
    if (this.frame !== undefined) this.frames.cancel(this.frame)
    this.frame = undefined
    this.flush()
  }

  private runCommand(operation: () => Promise<boolean | ScannerFailure>): Promise<void> {
    return scannerOperation(operation, 'processor/unavailable').then(() => { this.synchronize() })
  }

  setDocumentCrop = (pageId: string, corners: DocumentCorners): Promise<void> =>
    this.runCommand(() => this.native.setDocumentCropResult(pageId, corners))

  setFilter = (pageId: string, filter: DocumentFilter): Promise<void> =>
    this.runCommand(() => this.native.setFilterResult(pageId, filter))

  setShadowRemoval = (pageId: string, enabled: boolean): Promise<void> =>
    this.runCommand(() => this.native.setShadowRemovalResult(pageId, enabled))

  removePage = (pageId: string): Promise<void> =>
    this.runCommand(() => this.native.removePageResult(pageId))

  setPageOrder = (pageIds: readonly string[]): Promise<void> =>
    this.runCommand(() => this.native.setPageOrderResult([...pageIds]))

  createPdf = (pagesOrOptions: readonly string[] | SessionPdfOptions, options?: SessionPdfOptions): Promise<string> =>
    scannerOperation(() => this.native.createPdfResult(options === undefined ? undefined : [...pagesOrOptions as readonly string[]], options ?? pagesOrOptions as SessionPdfOptions), 'pdf/write-failed')

  exportImages = (pagesOrDirectory: readonly string[] | string, outputDirectory?: string): Promise<string[]> =>
    scannerOperation(() => this.native.exportImagesResult(typeof pagesOrDirectory === 'string' ? undefined : [...pagesOrDirectory], typeof pagesOrDirectory === 'string' ? pagesOrDirectory : outputDirectory!), 'storage/write-failed')

  saveCaptureSample = (pageId: string): Promise<string> =>
    scannerOperation(() => this.native.saveCaptureSampleResult(pageId), 'storage/write-failed')

  importImage = (uri: string, options: ImportImageOptions = {}): Promise<string> =>
    scannerOperation(() => this.native.importImageResult(uri, options), 'image/load-failed')
      .then((pageId) => { this.synchronize(); return pageId })

  importImages = async (uris: readonly string[], options?: ImportImageOptions): Promise<ImportImageOutcome[]> => {
    const outcomes: ImportImageOutcome[] = []
    for (const uri of uris) {
      try { outcomes.push({ uri, pageId: await this.importImage(uri, options) }) }
      catch (error) { outcomes.push({ uri, error: error instanceof Error ? error : new Error(String(error)) }) }
    }
    return outcomes
  }

  importFromLibrary = ({ maxCount = 0, ...options }: LibraryImportOptions = {}): Promise<LibraryImportResult> =>
    scannerOperation(() => this.native.importFromLibraryResult(options, maxCount), 'image/load-failed')
      .then((result) => { this.synchronize(); return result })

  close = (): Promise<void> => {
    if (this.closePromise !== undefined) return this.closePromise
    // Invoke native immediately, before yielding. Its close marks ownership closed at admission.
    this.closePromise = this.runCommand(() => this.native.closeResult()).then(
      () => { this.reconcileClose() },
      (error: unknown) => { this.reconcileClose(); throw error },
    )
    // Keep the original rejected promise for awaiting callers while handling fire-and-forget cleanup.
    void this.closePromise.catch(report)
    return this.closePromise
  }

  private reconcileClose(): void {
    if (this.released) return
    try {
      const full = scannerValue(() => this.native.getSession(), 'processor/unavailable')
      if (full.sourceVersion > this.sourceVersion) {
        this.pending = full; this.sourceVersion = full.sourceVersion; this.schedule()
      }
    }
    catch (error) { report(error) }
  }
}

/** Internal construction boundary: registration failure must not orphan a newly created native owner. */
export function createScanSession(native: NativeScanSession, frames: Frames = platformFrames): ScanSession {
  try { return new SessionStore(native, frames) }
  catch (error) {
    void scannerOperation(() => native.closeResult(), 'cache/delete-failed').catch(report)
    throw error
  }
}

/** Internal native-view binding. An arbitrary external-store implementation is not a native session. */
export function getNativeScanSession(session: ScanSession): NativeScanSession {
  if (!(session instanceof SessionStore)) throw new ScannerError('input/invalid')
  return session.native
}

/** Internal: capture uses the same publication boundary as session mutations. */
export function synchronizeSession(session: ScanSession, snapshot?: NativeSnapshot): void {
  if (!(session instanceof SessionStore)) throw new ScannerError('input/invalid')
  session.synchronize(snapshot)
}
