import { Directory, File, Paths } from 'expo-file-system'

const EXPORTS = new Directory(Paths.cache, 'document-scanner-exports')
const RETENTION = 7 * 24 * 60 * 60 * 1000 // Milliseconds; share recipients can outlive the share sheet.
let sequence = 0

/** Allocates a fresh name; neither a previous share nor an export from an earlier app launch is overwritten. */
export function newPdfExport(): File {
  EXPORTS.create({ intermediates: true, idempotent: true })
  let file: File
  do {
    file = new File(EXPORTS, `scan-${Date.now()}-${++sequence}.pdf`)
  } while (file.exists)
  return file
}

/** Best-effort startup cleanup of this app's exports, retained for at least seven days. */
export function removeOldPdfExports() {
  if (!EXPORTS.exists) return
  const now = Date.now()
  for (const entry of EXPORTS.list()) {
    if (!(entry instanceof File)) continue
    const match = /^scan-(\d+)-\d+\.pdf$/.exec(entry.name)
    if (match && now - Number(match[1]) > RETENTION) entry.delete()
  }
}
