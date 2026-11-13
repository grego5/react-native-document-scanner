import type { CaptureWaitReason } from 'react-native-document-scanner'

/** App-owned capture copy; hosts can translate or replace these messages. */
export const captureWaitLabels: Record<CaptureWaitReason, string> = {
  document: 'Point the camera at the page.',
  focus: 'Hold still. Focusing…',
  light: 'Hold still. Adjusting to the light…',
  steady: 'Hold steady to capture.',
}
