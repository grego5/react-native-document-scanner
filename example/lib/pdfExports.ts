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

/** Each gallery action owns a unique directory outside native sessions. */
export function newImageExport(): Directory {
  EXPORTS.create({ intermediates: true, idempotent: true })
  let directory: Directory
  do {
    directory = new Directory(EXPORTS, `images-${Date.now()}-${++sequence}`)
  } while (directory.exists)
  directory.create()
  return directory
}

/** Best-effort startup cleanup of this app's exports, retained for at least seven days. */
export function removeOldPdfExports() {
  if (!EXPORTS.exists) return
  const now = Date.now()
  for (const entry of EXPORTS.list()) {
    const match = entry instanceof File ? /^scan-(\d+)-\d+\.pdf$/.exec(entry.name) :
      /^images-(\d+)-\d+$/.exec(entry.name)
    if (match && now - Number(match[1]) > RETENTION) entry.delete()
  }
}
