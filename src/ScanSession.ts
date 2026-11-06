import type { DocumentFilter, DocumentQuad } from './DocumentScannerProcessor.nitro'
import type {
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

/**
 * useSyncExternalStore(session.subscribe, session.getSession) reads immutable publications. Awaited mutations publish accepted settings before resolving.
 * Plain stores read getSession(), then subscribe and read again to reconcile a publication between
 * their first read and registration. Subscribe never delivers synchronously.
 */
export interface ScanSession {
  getSession(): ScanSessionSnapshot
  subscribe(listener: () => void): () => void
  setDocumentCrop(pageId: string, quad: DocumentQuad): Promise<void>
  setFilter(pageId: string, filter: DocumentFilter): Promise<void>
  setShadowRemoval(pageId: string, enabled: boolean): Promise<void>
  removePage(pageId: string): Promise<void>
  setPageOrder(pageIds: readonly string[]): Promise<void>
  createPdf(options: SessionPdfOptions): Promise<string>
  createPdf(pageIds: readonly string[], options: SessionPdfOptions): Promise<string>
  exportImages(outputDirectory: string): Promise<string[]>
  exportImages(pageIds: readonly string[], outputDirectory: string): Promise<string[]>
  saveCaptureSample(pageId: string): Promise<string>
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
  const keys = Object.keys(left).filter((key) => left[key] !== undefined)
  return keys.length === Object.keys(right).filter((key) => right[key] !== undefined).length &&
    keys.every((key) => Object.prototype.hasOwnProperty.call(right, key) && equalValue(left[key], right[key]))
}

function immutableCopy<T>(value: T): Immutable<T> {
  // These casts retain the relationship between a plain transport value and its recursively copied type.
  if (Array.isArray(value)) return Object.freeze(value.map((item: unknown) => immutableCopy(item))) as Immutable<T>
  if (value !== null && typeof value === 'object') {
    return Object.freeze(Object.fromEntries(
      Object.entries(value).filter(([, item]) => item !== undefined)
        .map(([key, item]: [string, unknown]) => [key, immutableCopy(item)]),
    )) as Immutable<T>
  }
  return value as Immutable<T>
}

class SessionStore implements ScanSession {
  private published: ScanSessionSnapshot
  private pending: NativeSnapshot | undefined
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
          const base = this.pending ?? { ...this.published, sourceVersion: this.sourceVersion }
          const pages = Object.assign(Object.create(null), base.pages)
          for (const id of next.removedPageIds) delete pages[id]
          for (const [id, page] of Object.entries(next.updatedPages)) pages[id] = page
          this.pending = { sourceVersion: next.sourceVersion, closed: false, pages,
            pageIds: next.pageIds ?? [...base.pageIds.filter(id => Object.prototype.hasOwnProperty.call(pages, id)),
              ...Object.keys(next.updatedPages).filter(id => !base.pageIds.includes(id))] }
        }
        this.sourceVersion = next.sourceVersion
        this.schedule()
      }
    } finally { this.receiving = false }
  }

  private schedule(): void {
    if (!this.released && this.frame === undefined) this.frame = this.frames.request(this.flush)
  }

  private snapshot(source: NativeSnapshot, version: number): ScanSessionSnapshot {
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
    return Object.freeze({
      version,
      closed: source.closed,
      pageIds,
      pages: !changed && previous !== undefined ? previous.pages : Object.freeze(pages),
    })
  }

  private flush = (): void => {
    this.frame = undefined
    if (this.released) return
    const next = this.pending
    this.pending = undefined
    if (next !== undefined) this.published = this.snapshot(next, this.published.version + 1)
    // Install first. New listeners during notification already read this version and are not replayed.
    for (const entry of [...this.listeners]) {
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
  synchronize(): void {
    if (this.released) return
    const full = scannerValue(() => this.native.getSession(), 'processor/unavailable')
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

  setDocumentCrop = (pageId: string, quad: DocumentQuad): Promise<void> =>
    this.runCommand(() => this.native.setDocumentCropResult(pageId, quad))

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
export function synchronizeSession(session: ScanSession): void {
  if (!(session instanceof SessionStore)) throw new ScannerError('input/invalid')
  session.synchronize()
}
