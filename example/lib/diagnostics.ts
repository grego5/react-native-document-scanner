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

/** Exports the scanner's diagnostics and offers them to the share sheet: email, a chat app, Drive, or Files. */
export async function shareDiagnostics() {
  try {
    const uri = await DocumentScanner.exportDiagnostics()
    await Sharing.shareAsync(uri, { mimeType: 'text/plain', dialogTitle: 'Share scanner log' })
  } catch (error) {
    Alert.alert('Could not share the log.', errorMessage(error, ''))
  }
}
