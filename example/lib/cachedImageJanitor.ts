import { Directory, File, Paths } from 'expo-file-system'
import { DocumentScanner } from 'react-native-document-scanner'

/** The library's cache folder; nothing from earlier launches is referenced, since sessions are not restored. */
const CACHE_FOLDER = new Directory(Paths.cache, 'react-native-document-scanner')

/**
 * Deletes cached photos and renders once the session stops referencing them. Deletion waits while any tracked
 * processor call runs, so a file is never removed under an analysis or render reading it.
 */
export class CachedImageJanitor {
  private readonly owned = new Set<string>()
  private readonly unreferenced = new Set<string>()
  private referenced = new Set<string>()
  private running = 0

  /** Deletes what earlier launches left behind. */
  removeLeftovers(): Promise<void> {
    if (!CACHE_FOLDER.exists) return Promise.resolve()
    const uris = CACHE_FOLDER.list().flatMap((entry) => (entry instanceof File ? [entry.uri] : []))
    return uris.length > 0 ? this.track(DocumentScanner.removeCachedImages(uris)) : Promise.resolve()
  }

  /** Marks a file this session created; it is deleted after it stops being referenced. */
  own(uri: string) {
    this.owned.add(uri)
  }

  /** Deletes a file this session created but never referenced. */
  release(uri: string) {
    this.unreferenced.add(uri)
    this.flush()
  }

  /** Holds deletion back until `operation` settles. */
  track<T>(operation: Promise<T>): Promise<T> {
    this.running++
    return operation.finally(() => {
      this.running--
      this.flush()
    })
  }

  /** Takes the session's current references; owned files outside them are deleted. */
  collect(referenced: Set<string>) {
    this.referenced = referenced
    for (const uri of this.owned) {
      if (referenced.has(uri)) continue
      this.owned.delete(uri)
      this.unreferenced.add(uri)
    }
    this.flush()
  }

  private flush() {
    if (this.running > 0 || this.unreferenced.size === 0) return
    const uris: string[] = []
    for (const uri of this.unreferenced) {
      if (this.referenced.has(uri)) this.owned.add(uri)
      else uris.push(uri)
    }
    this.unreferenced.clear()
    if (uris.length === 0) return
    DocumentScanner.removeCachedImages(uris).catch((error: unknown) => {
      console.warn('Could not delete cached scan images.', error)
    })
  }
}
