import { Alert } from 'react-native'
import * as MediaLibrary from 'expo-media-library'
import * as Sharing from 'expo-sharing'
import { DocumentScanner } from 'react-native-document-scanner'
import type { PdfSettings } from '../components/ExportSheet'
import { errorMessage } from '../lib/errorMessage'
import { newPdfExport } from '../lib/pdfExports'

/** Gallery save and PDF share for finished pages, run through `run` so the session keeps the files until they end. */
export function usePhotoActions(run: (operation: () => Promise<void>) => Promise<void>) {
  const [libraryPermission, requestLibraryPermission] = MediaLibrary.usePermissions({
    writeOnly: true,
    granularPermissions: ['photo'],
  })

  /** Saves `uris` in order, so the gallery lists them in page order. */
  const saveAll = (uris: string[]) => run(async () => {
    try {
      let permission = libraryPermission
      if (!permission?.granted) permission = await requestLibraryPermission()
      if (!permission.granted) {
        Alert.alert('Photo library access needed', 'Allow photo library access to save the pages.')
        return
      }
      for (const uri of uris) await MediaLibrary.Asset.create(uri)
      Alert.alert(`Saved ${uris.length} page${uris.length === 1 ? '' : 's'} to the gallery.`)
    } catch (error) {
      Alert.alert('Could not save the pages.', errorMessage(error, ''))
    }
  })

  /**
   * Writes a fresh PDF and opens the share sheet. The file stays available after the sheet closes because its
   * recipient may still be reading; old exports are cleaned on a later startup.
   */
  const sharePdf = (uris: string[], settings: PdfSettings) => run(async () => {
    try {
      const pdf = await DocumentScanner.createPdf({
        ...settings,
        outputPath: newPdfExport().uri,
        pages: uris,
      })
      await Sharing.shareAsync(pdf, { mimeType: 'application/pdf', UTI: 'com.adobe.pdf' })
    } catch (error) {
      Alert.alert('Could not export the PDF.', errorMessage(error, ''))
    }
  })

  return { saveAll, sharePdf }
}
