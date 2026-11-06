package com.margelo.nitro.documentscanner

import android.util.Log
import com.margelo.nitro.core.Promise
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Internal failure identity; diagnostic causes are logged, never used as the public error contract. */
internal class ScannerException(val code: ScannerErrorCode, cause: Throwable? = null) : RuntimeException(code.name, cause)

/**
 * A caller-supplied value an operation cannot accept, reported as `input/invalid`. Other argument exceptions, such as
 * ones thrown inside Android or OpenCV, report the operation's own code.
 */
internal class InvalidInputException(message: String) : IllegalArgumentException(message)

/** [require] for caller input: throws [InvalidInputException] with [message] unless [value] holds. */
@OptIn(ExperimentalContracts::class)
internal inline fun requireInput(value: Boolean, message: () -> String) {
  contract { returns() implies value }
  if (!value) throw InvalidInputException(message())
}

internal fun scannerFailure(error: Throwable, fallback: ScannerErrorCode): ScannerFailure {
  val code = when (error) {
    is ScannerException -> error.code
    is InvalidInputException -> ScannerErrorCode.INPUT_INVALID
    else -> fallback
  }
  Log.w("RNDocumentScanner", code.name, error)
  return ScannerFailure(code)
}

/** The native engine keeps its normal promise lifecycle; this boundary carries a typed success or failure to JS. */
internal fun <T, R> Promise<T>.scannerResult(
  fallback: ScannerErrorCode,
  success: (T) -> R,
  failure: (ScannerFailure) -> R,
): Promise<R> {
  val result = Promise<R>()
  then { result.resolve(success(it)) }
  catch { result.resolve(failure(scannerFailure(it, fallback))) }
  return result
}
