import { File, Paths } from 'expo-file-system'
import * as Sharing from 'expo-sharing'
import { Alert, Platform } from 'react-native'
import { DocumentScanner } from 'react-native-document-scanner'
import { errorMessage } from './errorMessage'

/** Diagnostics are Android only for now. */
export const diagnosticsAvailable = Platform.OS === 'android'

/**
 * This test app keeps the library's diagnostics on from launch, before any camera mounts, so a tester on any device can
 * send what the camera did without ADB or a debug build.
 */
export function enableDiagnostics() {
  if (!diagnosticsAvailable) return
  try {
    DocumentScanner.setDiagnosticsEnabled(true)
  } catch (error) {
    console.warn('Could not turn on scanner diagnostics', error)
  }
}

/** How the tester judged a captured photo by eye, for calibrating the photo's own soft flag. */
export type PhotoMark = 'blurry' | 'sharp'

// For this run of the app: marks by page ID, matched in the log against each photo's `capture photo page=` line.
const marks = new Map<string, PhotoMark>()

export function photoMark(pageId: string): PhotoMark | undefined {
  return marks.get(pageId)
}

/** Cycles the page's mark: none, blurry, sharp, none. Returns the new mark. */
export function cyclePhotoMark(pageId: string): PhotoMark | undefined {
  const next = { none: 'blurry', blurry: 'sharp', sharp: undefined }[marks.get(pageId) ?? 'none'] as PhotoMark | undefined
  if (next) marks.set(pageId, next)
  else marks.delete(pageId)
  return next
}

/**
 * Exports the scanner's diagnostics and offers them to the share sheet: email, a chat app, Drive, or Files. Photo
 * marks follow the log's own lines, one `photo mark page=<id> <mark>` line each.
 */
export async function shareDiagnostics() {
  try {
    let uri = await DocumentScanner.exportDiagnostics()
    if (marks.size > 0) {
      const log = new File(uri)
      const lines = [...marks].map(([id, mark]) => `photo mark page=${id} ${mark}`)
      const marked = new File(Paths.cache, log.name.replace(/(\.txt)?$/, '-marked.txt'))
      if (marked.exists) marked.delete()
      marked.write(`${log.textSync()}\n${lines.join('\n')}\n`)
      uri = marked.uri
    }
    await Sharing.shareAsync(uri, { mimeType: 'text/plain', dialogTitle: 'Share scanner log' })
  } catch (error) {
    Alert.alert('Could not share the log.', errorMessage(error, ''))
  }
}
