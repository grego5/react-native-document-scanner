import { ScannerError, type ScannerErrorCode } from 'react-native-document-scanner'

/** Example app copy. Library error codes stay stable when an app translates or changes these messages. */
const SCANNER_MESSAGES: Record<ScannerErrorCode, string> = {
  'camera/unavailable': 'The camera is closed. Open it and try again.',
  'camera/permission-denied': 'Allow camera access to take a photo.',
  'camera/not-ready': 'Wait for the camera preview, then try again.',
  'capture/focus-timeout': 'The camera could not focus. Adjust the distance and try again.',
  'capture/motion-timeout': 'Hold the camera steady and try again.',
  'capture/failed': 'The photo could not be taken. Please try again.',
  'capture/metadata-failed': 'The captured photo could not be read. Please take another photo.',
  'storage/write-failed': 'The file could not be saved. Check the available storage.',
  'image/load-failed': 'The photo could not be opened.',
  'editor/filter-failed': 'The filter preview could not be prepared. Try another filter.',
  'processor/busy': 'A page is still being processed. Please try again shortly.',
  'processor/disposed': 'The scanner is closed. Open it again to continue.',
  'processor/unavailable': 'The scanner is unavailable. Please reopen it.',
  'analysis/failed': 'The photo could not be analyzed. You can still adjust the crop manually.',
  'render/failed': 'The page could not be prepared. Please try again.',
  'pdf/write-failed': 'The PDF could not be created. Check the available storage and try again.',
  'cache/delete-failed': 'Some temporary files could not be removed.',
  'permission/request-failed': 'Camera access could not be requested. Please try again.',
  'permission/unavailable': 'Camera access could not be checked. Please reopen the scanner.',
  'model/load-failed': 'The requested model could not be loaded. The previous model is still active.',
  'model/superseded': 'A newer model was requested.',
  'input/invalid': 'The scanner settings or selected image are invalid.',
}

export function errorMessage(error: unknown, fallback: string) {
  if (error instanceof ScannerError) return SCANNER_MESSAGES[error.code]
  console.warn('Scanner example operation failed.', error)
  return fallback || 'Something went wrong. Please try again.'
}
