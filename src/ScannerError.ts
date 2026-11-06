import type { ScannerErrorCode, ScannerFailure } from './ScannerFailure'

/** A failed public operation; cancellation is reported separately. Use `code` for handling and localize the displayed message in the app. */
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

/** An operation intentionally replaced by a later action; handle separately from failures. */
export class ScannerCancellation extends Error {
  readonly code: 'processor/superseded' | 'model/superseded'
  constructor(code: 'processor/superseded' | 'model/superseded') {
    super(code)
    this.name = 'AbortError'
    this.code = code
  }
}

/** Native results use a distinct failure object so error codes survive Nitro without parsing exception text. */
export function unwrapScannerResult<T>(result: T | ScannerFailure): T {
  if (result !== null && typeof result === 'object' && 'code' in result) {
    // Only ScannerFailure has a code in the generated result unions.
    const code = (result as ScannerFailure).code
    if (code === 'processor/superseded' || code === 'model/superseded') throw new ScannerCancellation(code)
    throw new ScannerError(code)
  }
  return result as T
}

/** Unexpected bridge exceptions still reject with the operation's code and retain the original diagnostic cause. */
export async function scannerOperation<T>(operation: () => Promise<T | ScannerFailure>, fallback: ScannerErrorCode): Promise<T> {
  try {
    return unwrapScannerResult(await operation())
  } catch (error) {
    if (error instanceof ScannerError || error instanceof ScannerCancellation) throw error
    throw new ScannerError(fallback, { cause: error })
  }
}

/** Synchronous bridge entry points use the same public failure class. */
export function scannerValue<T>(operation: () => T, fallback: ScannerErrorCode): T {
  try {
    return operation()
  } catch (error) {
    if (error instanceof ScannerError || error instanceof ScannerCancellation) throw error
    throw new ScannerError(fallback, { cause: error })
  }
}
