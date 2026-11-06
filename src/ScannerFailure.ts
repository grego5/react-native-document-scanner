/** Stable identifiers for programmatic handling. Display copy belongs to the host app. */
export type ScannerErrorCode =
  | 'feature/unsupported'
  | 'camera/unavailable'
  | 'camera/permission-denied'
  | 'camera/not-ready'
  | 'capture/focus-timeout'
  | 'capture/motion-timeout'
  | 'capture/busy'
  | 'capture/not-ready'
  | 'capture/failed'
  | 'capture/metadata-failed'
  | 'storage/write-failed'
  | 'image/load-failed'
  | 'editor/filter-failed'
  | 'processor/superseded'
  | 'processor/disposed'
  | 'processor/unavailable'
  | 'analysis/failed'
  | 'render/failed'
  | 'pdf/write-failed'
  | 'cache/delete-failed'
  | 'permission/request-failed'
  | 'permission/unavailable'
  | 'model/load-failed'
  | 'model/superseded'
  | 'input/invalid'

/** Native failure payload. Platform diagnostics remain in native logs. */
export interface ScannerFailure {
  code: ScannerErrorCode
}
