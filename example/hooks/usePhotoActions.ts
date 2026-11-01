import { Alert } from 'react-native'
import * as MediaLibrary from 'expo-media-library'
import * as Sharing from 'expo-sharing'
import { type ScanSession } from 'react-native-document-scanner'
import type { PdfSettings } from '../components/ExportSheet'
import { errorMessage } from '../lib/errorMessage'
import { newImageExport, newPdfExport } from '../lib/pdfExports'

/** Gallery save and PDF share use independent exports and hold the app UX lock until completion. */
export function usePhotoActions(session: ScanSession, run: (operation: () => Promise<void>) => Promise<void>) {
  const [libraryPermission, requestLibraryPermission] = MediaLibrary.usePermissions({
    writeOnly: true,
    granularPermissions: ['photo'],
  })

  /** Exports the session and saves its independent copies in order, so the gallery lists them in page order. */
  const saveAll = () => run(async () => {
    try {
      let permission = libraryPermission
      if (!permission?.granted) permission = await requestLibraryPermission()
      if (!permission.granted) {
        Alert.alert('Photo library access needed', 'Allow photo library access to save the pages.')
        return
      }
      const uris = await session.exportImages(newImageExport().uri)
      for (const uri of uris) {
        await MediaLibrary.Asset.create(uri)
      }
      Alert.alert(`Saved ${uris.length} page${uris.length === 1 ? '' : 's'} to the gallery.`)
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') return;
      Alert.alert('Could not save the pages.', errorMessage(error, ''))
    }
  })

  /**
   * Writes a fresh PDF and opens the share sheet. The file stays available after the sheet closes because its
   * recipient may still be reading; old exports are cleaned on a later startup.
   */
  const sharePdf = (settings: PdfSettings) => run(async () => {
    try {
      const pdf = await session.createPdf({
        ...settings,
        outputPath: newPdfExport().uri,
      })
      await Sharing.shareAsync(pdf, { mimeType: 'application/pdf', UTI: 'com.adobe.pdf' })
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') return;
      Alert.alert('Could not export the PDF.', errorMessage(error, ''))
    }
  })

  return { saveAll, sharePdf }
}
