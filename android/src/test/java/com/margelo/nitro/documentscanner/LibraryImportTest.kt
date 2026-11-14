package com.margelo.nitro.documentscanner

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class LibraryImportTest {
  /** A session's close registration, taken by hand. */
  private class Registration : ScanSessionState.CloseRegistration {
    private var cancel: (() -> CompletableFuture<Unit>)? = null
    private var registered = true
    override val isRegistered get() = registered
    override fun tryUnregister() = registered.also { registered = false }

    fun register(cancel: () -> CompletableFuture<Unit>) = also { this.cancel = cancel }

    /** Takes the registration as session close does, then runs its cancellation. */
    fun close(): CompletableFuture<Unit> {
      registered = false
      return requireNotNull(cancel)()
    }
  }

  /** A picker request whose release the test controls. */
  private class Request(released: Boolean = true) : PickerRequest {
    override val pickerReleased = CompletableFuture<Unit>().also { if (released) it.complete(Unit) }
    var ended = 0
    override fun end() { ended++ }
  }

  private fun operation(
    maxCount: Int = 0,
    post: (() -> Unit) -> Boolean = { it(); true },
    admit: AdmitPickedImage<String> = { _, _ -> fail("Nothing to admit.") },
    onResult: (Result<List<String>>) -> Unit,
  ) = LibraryImport(maxCount, post, admit, onResult)

  private fun run(picked: Result<List<String>>, maxCount: Int = 0, admit: AdmitPickedImage<String>): Result<List<String>>? {
    var result: Result<List<String>>? = null
    val registration = Registration()
    operation(maxCount, admit = admit) { assertNull("Settles once.", result); result = it }
      .start(registration::register) { it(picked); Request() }
    assertFalse("A normal outcome claims the registration.", registration.isRegistered)
    return result
  }

  private fun unreadable() = Result.failure<String>(ScannerException(ScannerErrorCode.IMAGE_LOAD_FAILED))
  private fun code(result: Result<*>?) = (requireNotNull(result).exceptionOrNull() as ScannerException).code

  @Test fun returnsAdmittedIdsInPickedOrderWhenAdmissionsSettleOutOfOrderAndSkipsUnreadablePicks() {
    val pending = mutableMapOf<String, (Result<String>) -> Unit>()
    var result: Result<List<String>>? = null
    operation(admit = { uri, done -> pending[uri] = done }) { result = it }
      .start(Registration()::register) { it(Result.success(listOf("a", "bad", "c"))); Request() }
    assertEquals(listOf("a", "bad", "c"), pending.keys.toList())
    pending.getValue("c")(Result.success("page-c"))
    pending.getValue("bad")(unreadable())
    assertNull(result)
    pending.getValue("a")(Result.success("page-a"))
    assertEquals(listOf("page-a", "page-c"), requireNotNull(result).getOrThrow())
  }

  @Test fun aDestinationFailureRejectsTheCallOnceEveryPickSettles() {
    val pending = mutableListOf<(Result<String>) -> Unit>()
    var result: Result<List<String>>? = null
    operation(admit = { _, done -> pending += done }) { result = it }
      .start(Registration()::register) { it(Result.success(listOf("a", "b", "c"))); Request() }
    val storage = ScannerException(ScannerErrorCode.STORAGE_WRITE_FAILED)
    pending[0](Result.success("page-a"))
    pending[1](Result.failure(storage))
    assertNull("Later picks still settle first.", result)
    pending[2](Result.success("page-c"))
    assertSame(storage, requireNotNull(result).exceptionOrNull())
  }

  @Test fun cancellingOrAnEntirelyUnreadableSelectionReturnsNoIdsAndPickerFailureRejects() {
    assertEquals(emptyList<String>(), requireNotNull(run(Result.success(emptyList())) { _, _ -> fail("Nothing to admit.") }).getOrThrow())
    assertEquals(emptyList<String>(), requireNotNull(run(Result.success(listOf("x", "y"))) { _, done -> done(unreadable()) }).getOrThrow())
    val busy = ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    assertSame(busy, requireNotNull(run(Result.failure(busy)) { _, _ -> fail("Nothing to admit.") }).exceptionOrNull())
  }

  @Test fun synchronousAndThrowingAdmissionsSettleOnce() {
    val failure = IllegalStateException("admission")
    val result = requireNotNull(run(Result.success(listOf("a", "b"))) { uri, done ->
      if (uri == "b") throw failure else done(Result.success("page-$uri"))
    })
    assertSame("An untyped admission failure is no unreadable pick.", failure, result.exceptionOrNull())
  }

  @Test fun admitsOnlyTheFirstPicksUpToTheLimitWhenTheFallbackPickerIgnoresIt() {
    val admitted = mutableListOf<String>()
    val value = requireNotNull(run(Result.success(listOf("a", "b", "c")), maxCount = 2) { uri, done ->
      admitted += uri
      done(Result.success("page-$uri"))
    }).getOrThrow()
    assertEquals(listOf("a", "b"), admitted)
    assertEquals(listOf("page-a", "page-b"), value)
  }

  @Test fun aFailedRegistrationSettlesDirectlyAndALaunchFailureReleasesAndUnregisters() {
    val disposed = ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
    var result: Result<List<String>>? = null
    operation { result = it }.start({ throw disposed }) { fail("Not presented."); Request() }
    assertSame(disposed, result?.exceptionOrNull())
    val registration = Registration()
    val busy = ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    operation { result = it }.start(registration::register) { throw busy }
    assertSame(busy, result?.exceptionOrNull())
    assertFalse(registration.isRegistered)
  }

  @Test fun aNormalOutcomeWaitsForThePickerToReleaseItsSlot() {
    val registration = Registration()
    val request = Request(released = false)
    var result: Result<List<String>>? = null
    operation { result = it }.start(registration::register) { it(Result.success(emptyList())); request }
    assertNull(result)
    assertTrue("The registration stays until the slot is free.", registration.isRegistered)
    request.pickerReleased.complete(Unit)
    assertEquals(emptyList<String>(), requireNotNull(result).getOrThrow())
    assertFalse(registration.isRegistered)
  }

  @Test fun aNormalOutcomeLeavesTheDismissalToThePicker() {
    val main = ArrayDeque<() -> Unit>()
    val registration = Registration()
    val request = Request(released = false)
    var onPicked: ((Result<List<String>>) -> Unit)? = null
    var result: Result<List<String>>? = null
    operation(post = { main.add(it) }) { result = it }.start(registration::register) { onPicked = it; request }
    main.removeFirst()()
    // The picker frees its slot before it reports.
    request.pickerReleased.complete(Unit)
    requireNotNull(onPicked)(Result.success(emptyList()))
    assertTrue("Nothing is posted to end the picker.", main.isEmpty())
    assertEquals(0, request.ended)
    assertEquals(emptyList<String>(), requireNotNull(result).getOrThrow())
    assertFalse(registration.isRegistered)
  }

  @Test fun aRejectedPresentationPostReportsUnavailableAndUnregisters() {
    val registration = Registration()
    val results = mutableListOf<Result<List<String>>>()
    operation(post = { false }) { results += it }.start(registration::register) { fail("Not presented."); Request() }
    assertEquals(ScannerErrorCode.PROCESSOR_UNAVAILABLE, code(results.single()))
    assertFalse(registration.isRegistered)
  }

  @Test fun closeBeforeTheQueuedPresentationPresentsNothingAndCompletesAtOnce() {
    val main = ArrayDeque<() -> Unit>()
    var presented = 0
    val results = mutableListOf<Result<List<String>>>()
    val registration = Registration()
    operation(post = { main.add(it) }) { results += it }.start(registration::register) { presented++; Request() }
    val closing = registration.close()
    while (main.isNotEmpty()) main.removeFirst()()
    assertEquals(0, presented)
    assertTrue("No presentation was authorized, so nothing holds the slot.", closing.isDone)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(results.single()))
  }

  @Test fun closeInsideThePresenterEndsTheRequestOnceItReturnsAndWaitsForItsRelease() {
    val registration = Registration()
    val request = Request(released = false)
    val results = mutableListOf<Result<List<String>>>()
    var closing: CompletableFuture<Unit>? = null
    operation { results += it }.start(registration::register) { closing = registration.close(); request }
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(results.single()))
    assertEquals(1, request.ended)
    assertFalse(requireNotNull(closing).isDone)
    request.pickerReleased.complete(Unit)
    assertTrue(requireNotNull(closing).isDone)
  }

  @Test fun closeDuringPresentationKeepsThePickerBusyUntilItsSlotIsReleased() {
    var current: Request? = null
    fun present(): Request {
      if (current != null) throw ScannerException(ScannerErrorCode.CAPTURE_BUSY)
      return Request(released = false).also { current = it }
    }
    fun release(request: Request) {
      assertSame(request, current)
      current = null
      request.pickerReleased.complete(Unit)
    }
    fun reopen(): Result<List<String>> {
      val registration = Registration()
      val results = mutableListOf<Result<List<String>>>()
      operation { results += it }.start(registration::register) { onPicked ->
        val request = present()
        release(request)
        onPicked(Result.success(emptyList()))
        request
      }
      assertFalse(registration.isRegistered)
      return results.single()
    }

    val registration = Registration()
    val cancelled = mutableListOf<Result<List<String>>>()
    var closing: CompletableFuture<Unit>? = null
    operation { cancelled += it }.start(registration::register) {
      val request = present()
      closing = registration.close()
      request
    }
    val first = requireNotNull(current)
    val close = requireNotNull(closing)
    assertEquals(1, first.ended)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(cancelled.single()))
    assertFalse(close.isDone)
    assertEquals(ScannerErrorCode.CAPTURE_BUSY, code(reopen()))

    release(first)
    assertTrue(close.isDone)
    close.join()
    assertEquals(Result.success(emptyList<String>()), reopen())
  }

  @Test fun closeWhileThePickerIsOpenEndsItOnPostDropsItsLateResultAndWaitsForItsRelease() {
    val main = ArrayDeque<() -> Unit>()
    val registration = Registration()
    val request = Request(released = false)
    var onPicked: ((Result<List<String>>) -> Unit)? = null
    val results = mutableListOf<Result<List<String>>>()
    operation(post = { main.add(it) }) { results += it }.start(registration::register) { onPicked = it; request }
    main.removeFirst()()
    val closing = registration.close()
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(results.single()))
    assertEquals("The request is ended on the picker's thread.", 0, request.ended)
    main.removeFirst()()
    assertEquals(1, request.ended)
    requireNotNull(onPicked)(Result.success(listOf("late")))
    assertFalse(closing.isDone)
    request.pickerReleased.complete(Unit)
    assertTrue(closing.isDone)
    assertEquals(1, results.size)
  }

  @Test fun aRejectedCleanupPostFailsCloseAndStillCancelsTheImport() {
    val main = ArrayDeque<() -> Unit>()
    val registration = Registration()
    val request = Request(released = false)
    val results = mutableListOf<Result<List<String>>>()
    var acceptsPosts = true
    operation(post = { acceptsPosts && main.add(it) }) { results += it }.start(registration::register) { request }
    main.removeFirst()()
    acceptsPosts = false

    val closing = registration.close()

    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(results.single()))
    assertEquals(0, request.ended)
    assertTrue(closing.isCompletedExceptionally)
    val error = assertThrows(java.util.concurrent.CompletionException::class.java) { closing.join() }
    assertEquals(ScannerErrorCode.PROCESSOR_UNAVAILABLE, (error.cause as ScannerException).code)
  }

  @Test fun aLaunchFailureWhileCloseWaitsCompletesTheReleaseWithoutASecondResult() {
    val registration = Registration()
    val results = mutableListOf<Result<List<String>>>()
    var closing: CompletableFuture<Unit>? = null
    operation { results += it }.start(registration::register) {
      closing = registration.close()
      throw ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    }
    assertTrue(requireNotNull(closing).isDone)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(results.single()))
  }

  @Test fun cancellationDuringAdmissionSettlesOnceAndIgnoresLateAdmissions() {
    val pending = mutableListOf<(Result<String>) -> Unit>()
    val results = mutableListOf<Result<List<String>>>()
    val registration = Registration()
    operation(admit = { _, done -> pending += done }) { results += it }
      .start(registration::register) { it(Result.success(listOf("a", "b"))); Request() }
    assertTrue(registration.close().isDone)
    pending.forEach { it(Result.success("late")) }
    assertEquals(1, results.size)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, code(results.single()))
  }
}
