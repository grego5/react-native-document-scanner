import type { TextCapture, TextScanStatus } from './TextCamera.nitro'
import type { TextSession as NativeTextSession, TextSessionSnapshot as NativeSnapshot } from './TextSession.nitro'
import { ScannerError, scannerOperation, scannerValue, unwrapScannerResult } from './ScannerError'

export interface TextSessionSnapshot {
  readonly version: number
  readonly closed: boolean
  /** What the scan does, one status for everything the UI shows; `off` while no camera shows it or once closed. */
  readonly status: TextScanStatus
  /** The still being picked from, until `clearCapture` or the next capture. */
  readonly capture?: TextCapture
}

/**
 * One text scan, apart from any camera view: create it when the scan starts, show it in one `TextCamera` at a time,
 * and close it when the scan ends. It keeps the capture being picked from and the camera's status, so a camera that
 * unmounts and comes back finds the capture still there, and hints or a guide drawn by the host read `status`.
 * `useSyncExternalStore(session.subscribe, session.getSnapshot)` reads its immutable snapshots.
 */
export interface TextSession {
  getSnapshot(): TextSessionSnapshot
  /** Registers `listener` for later snapshots; never calls it synchronously. */
  subscribe(listener: () => void): () => void
  /**
   * Captures with the attached camera into the session; resolves with the snapshot holding the capture, already
   * published. Rejects with `camera/unavailable` without a running camera, `capture/not-ready` when the timeout passes
   * before the camera kept any frame, `capture/failed` when the frame cannot be read or encoded, `processor/disposed`
   * once closed. A camera that stops meanwhile, as when its view unmounts, reads the sharpest frame it kept, and
   * rejects with `camera/unavailable` without one. How the frame is picked:
   * `.agents/skills/docs/references/android/text-camera.md#capture-selection` and
   * `.agents/skills/docs/references/ios/camera.md#text-camera`.
   */
  capture(options?: {
    /** How long the capture may wait for a readable frame, in milliseconds; defaults to 2000. */
    timeout?: number
  }): Promise<TextSessionSnapshot>
  /** Drops the capture, for a retake. */
  clearCapture(): void
  /** Rejects pending captures with `processor/disposed`, drops the capture and publishes the closed snapshot last. */
  close(): Promise<void>
}

function report(error: unknown): void {
  try { console.warn('Text scanner session failed.', error) } catch { /* Logging is best effort. */ }
}

/**
 * [snapshot] as published. The bridge builds a new capture object for every snapshot, so the capture [previous]
 * already holds is kept while the native one is the same still, by its image URI: a status change then leaves the
 * capture, and what a selection over it picked, as they were.
 */
function freeze(snapshot: NativeSnapshot, previous: TextSessionSnapshot | undefined): TextSessionSnapshot {
  const capture = snapshot.capture !== undefined && previous?.capture?.uri === snapshot.capture.uri
    ? previous.capture
    : snapshot.capture
  const next: TextSessionSnapshot = {
    version: snapshot.version,
    closed: snapshot.closed,
    status: snapshot.status,
    ...(capture !== undefined && { capture }),
  }
  return __DEV__ ? Object.freeze(next) : next
}

class TextSessionStore implements TextSession {
  private published: TextSessionSnapshot
  private released = false
  private readonly listeners = new Set<() => void>()
  private readonly unsubscribeNative: () => void
  private closePromise: Promise<void> | undefined

  constructor(readonly native: NativeTextSession) {
    const registration = scannerValue(
      () => unwrapScannerResult(native.observeResult(this.accept)),
      'processor/unavailable',
    )
    this.unsubscribeNative = registration.unsubscribe
    this.published = freeze(registration.snapshot, undefined)
    if (this.published.closed) this.release()
  }

  getSnapshot = (): TextSessionSnapshot => this.published

  subscribe = (listener: () => void): (() => void) => {
    if (!this.released) this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  /** Publishes [snapshot] when newer than the published one, and returns it as published; native may deliver
   * versions out of order. */
  private accept = (snapshot: NativeSnapshot): TextSessionSnapshot | undefined => {
    if (this.released || snapshot.version <= this.published.version) return undefined
    const next = freeze(snapshot, this.published)
    this.published = next
    for (const listener of [...this.listeners]) {
      try { listener() } catch (error) { report(error) }
    }
    if (next.closed) this.release()
    return next
  }

  private release(): void {
    if (this.released) return
    this.released = true
    this.listeners.clear()
    try { this.unsubscribeNative() } catch (error) { report(error) }
  }

  capture = (options?: { timeout?: number }): Promise<TextSessionSnapshot> =>
    scannerOperation(() => this.native.captureResult(options?.timeout ?? 2000), 'capture/failed')
      // This capture's own snapshot, which native may have delivered already: a listener may clear it, or a newer
      // snapshot replace it, while it publishes.
      .then((snapshot) => this.accept(snapshot)
        ?? (snapshot.version === this.published.version ? this.published : freeze(snapshot, this.published)))

  clearCapture = (): void => {
    scannerValue(() => this.native.clearCapture(), 'processor/unavailable')
    // Native published the change before returning; take it now rather than on the next delivery.
    this.accept(scannerValue(() => this.native.getSnapshot(), 'processor/unavailable'))
  }

  close = (): Promise<void> => {
    this.closePromise ??= scannerOperation(() => this.native.closeResult(), 'processor/unavailable').then(() => {
      try { this.accept(this.native.getSnapshot()) } catch (error) { report(error) }
    })
    return this.closePromise
  }
}

/** Internal construction boundary. */
export function createTextSession(native: NativeTextSession): TextSession {
  return new TextSessionStore(native)
}

/** Internal native-view binding. An arbitrary store is not a native session. */
export function getNativeTextSession(session: TextSession): NativeTextSession {
  if (!(session instanceof TextSessionStore)) throw new ScannerError('input/invalid')
  return session.native
}
