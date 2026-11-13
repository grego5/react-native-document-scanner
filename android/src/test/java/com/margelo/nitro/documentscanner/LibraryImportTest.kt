package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class LibraryImportTest {
  private val open: (() -> Unit) -> () -> Unit = { { } }

  private fun operation(maxCount: Int = 0, admit: (String, (Result<String>) -> Unit) -> Unit, onResult: (Result<LibraryImportResult>) -> Unit) =
    LibraryImport(maxCount, { it() }, admit) { onResult(it) }

  private fun run(picked: Result<List<String>>, maxCount: Int = 0, admit: (String, (Result<String>) -> Unit) -> Unit): Result<LibraryImportResult>? {
    var result: Result<LibraryImportResult>? = null
    operation(maxCount, admit) { assertNull("Settles once.", result); result = it }.start(open) { it(picked); { } }
    return result
  }

  @Test fun keepsPickedOrderWhenAdmissionsSettleOutOfOrderAndCountsFailures() {
    val pending = mutableMapOf<String, (Result<String>) -> Unit>()
    var result: Result<LibraryImportResult>? = null
    operation(admit = { uri, done -> pending[uri] = done }) { result = it }
      .start(open) { it(Result.success(listOf("a", "bad", "c"))); { } }
    assertEquals(listOf("a", "bad", "c"), pending.keys.toList())
    pending.getValue("c")(Result.success("page-c"))
    pending.getValue("bad")(Result.failure(ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED)))
    assertNull(result)
    pending.getValue("a")(Result.success("page-a"))
    val value = requireNotNull(result).getOrThrow()
    assertArrayEquals(arrayOf("page-a", "page-c"), value.pageIds)
    assertEquals(1.0, value.failedCount, 0.0)
  }

  @Test fun cancellingAdmitsNothingAndPickerFailureRejects() {
    val empty = requireNotNull(run(Result.success(emptyList())) { _, _ -> fail("Nothing to admit.") }).getOrThrow()
    assertEquals(0, empty.pageIds.size)
    assertEquals(0.0, empty.failedCount, 0.0)
    val busy = ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    assertSame(busy, requireNotNull(run(Result.failure(busy)) { _, _ -> fail("Nothing to admit.") }).exceptionOrNull())
  }

  @Test fun synchronousAndThrowingAdmissionsSettleOnce() {
    val value = requireNotNull(run(Result.success(listOf("a", "b"))) { uri, done ->
      if (uri == "b") throw IllegalStateException("admission") else done(Result.success("page-$uri"))
    }).getOrThrow()
    assertArrayEquals(arrayOf("page-a"), value.pageIds)
    assertEquals(1.0, value.failedCount, 0.0)
  }

  @Test fun admitsOnlyTheFirstPicksUpToTheLimitWhenTheFallbackPickerIgnoresIt() {
    val admitted = mutableListOf<String>()
    val value = requireNotNull(run(Result.success(listOf("a", "b", "c")), maxCount = 2) { uri, done ->
      admitted += uri
      done(Result.success("page-$uri"))
    }).getOrThrow()
    assertEquals(listOf("a", "b"), admitted)
    assertArrayEquals(arrayOf("page-a", "page-b"), value.pageIds)
    assertEquals(0.0, value.failedCount, 0.0)
  }

  @Test fun aClosedSessionPresentsNothingAndAPresentationFailureUnregisters() {
    val disposed = ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
    var result: Result<LibraryImportResult>? = null
    operation(admit = { _, _ -> fail() }) { result = it }.start({ throw disposed }) { fail("Not presented."); { } }
    assertSame(disposed, result?.exceptionOrNull())
    var unregistered = 0
    val busy = ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    operation(admit = { _, _ -> fail() }) { result = it }.start({ { unregistered++ } }) { throw busy }
    assertSame(busy, result?.exceptionOrNull())
    assertEquals(1, unregistered)
  }

  @Test fun cancellationDuringAdmissionSettlesOnceAndIgnoresLateAdmissions() {
    val pending = mutableListOf<(Result<String>) -> Unit>()
    val results = mutableListOf<Result<LibraryImportResult>>()
    val operation = operation(admit = { _, done -> pending += done }) { results += it }
    operation.start(open) { it(Result.success(listOf("a", "b"))); { } }
    operation.cancel(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED))
    pending.forEach { it(Result.success("late")) }
    assertEquals(1, results.size)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (results.single().exceptionOrNull() as ScannerException).code)
  }
}
