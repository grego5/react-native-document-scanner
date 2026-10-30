import type { ScannerErrorCode, ScannerFailure } from './ScannerFailure'

/** A rejected public operation. Use `code` for handling and localize the displayed message in the app. */
export class ScannerError extends Error {
  readonly code: ScannerErrorCode
  readonly cause: unknown

  constructor(code: ScannerErrorCode, options?: { cause?: unknown }) {
    super(code)
    this.name = 'ScannerError'
    this.code = code
    this.cause = options?.cause
  }
}

/** Native results use a distinct failure object so error codes survive Nitro without parsing exception text. */
export function unwrapScannerResult<T>(result: T | ScannerFailure): T {
  if (result !== null && typeof result === 'object' && 'code' in result) {
    // Only ScannerFailure has a code in the generated result unions.
    throw new ScannerError((result as ScannerFailure).code)
  }
  return result as T
}

/** Unexpected bridge exceptions still reject with the operation's code and retain the original diagnostic cause. */
export async function scannerOperation<T>(operation: () => Promise<T | ScannerFailure>, fallback: ScannerErrorCode): Promise<T> {
  try {
    return unwrapScannerResult(await operation())
  } catch (error) {
    if (error instanceof ScannerError) throw error
    throw new ScannerError(fallback, { cause: error })
  }
}

/** Synchronous bridge entry points use the same public failure class. */
export function scannerValue<T>(operation: () => T, fallback: ScannerErrorCode): T {
  try {
    return operation()
  } catch (error) {
    if (error instanceof ScannerError) throw error
    throw new ScannerError(fallback, { cause: error })
  }
}
