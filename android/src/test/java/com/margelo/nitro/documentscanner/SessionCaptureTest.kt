package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.CornerModelStore
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Pure capture admission/resource tests. No camera or takePhoto calls. */
class SessionCaptureTest {
  private class Worker : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
  }
  private class Files : SessionFiles {
    val directories = HashSet<File>()
    val existing = HashSet<File>()
    var failFrame = false
    var failOriginal = false
    override fun directory(cache: File, sessionId: String) = File(File(cache, "sessions"), "session-$sessionId")
    override fun createDirectory(directory: File) { directories.add(directory) }
    override fun sessions(cache: File) = emptyList<File>()
    override fun output(directory: File, id: String, kind: SessionResources.Kind, extension: String): SessionFiles.Output {
      if (failFrame && kind == SessionResources.Kind.FRAME) throw IOException("frame reservation")
      if (failOriginal && kind == SessionResources.Kind.ORIGINAL) throw IOException("original reservation")
      return SessionFiles.Output(directory, File(directory, ".$id.tmp.$extension"), File(directory, "$id.$extension"))
    }
    override fun publish(output: SessionFiles.Output) {
      check(existing.remove(output.temporary))
      check(existing.add(output.destination))
    }
    override fun remove(output: SessionFiles.Output) { existing.remove(output.temporary); existing.remove(output.destination) }
    override fun removeDirectory(cache: File, directory: File) { directories.remove(directory) }
  }

  private val previousModel = CornerModelStore.selected
  private val worker = Worker()
  private val cleanup = Worker()
  private val files = Files()
  private val errors = mutableListOf<Throwable>()
  private val queue = ScannerWorkQueue(worker) { errors.add(it) }
  private val registry = ScanSessionRegistry(files, queue, cleanup, {}, { errors.add(it) })
  private val resources = registry.open(File("capture-state-test").absoluteFile)
  private var preparedSelection: CornerModelStore.Selection? = null
  private val preparedSelections = mutableListOf<CornerModelStore.Selection?>()
  private var preparedCorners: DocumentCorners? = null
  private var candidate: DocumentCandidate? = null
  private val renders = mutableListOf<ScanPage.RenderKey>()
  private val actions = object : ScanSessionState.Actions {
    override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?): () -> DocumentAnalysis {
      preparedSelection = selection
      preparedSelections += selection
      preparedCorners = liveCorners
      val found = candidate
      return { DocumentAnalysis(320.0, 240.0, 1.0, found) }
    }
    override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact {
      assertEquals(SessionResources.Kind.ORIGINAL, original.kind)
      renders.add(key)
      fun output(kind: SessionResources.Kind) = resources.output(kind).use { files.existing.add(it.file); it.commit() }
      return ScanPage.Artifact(output(SessionResources.Kind.RENDER), output(SessionResources.Kind.THUMBNAIL),
        320.0, 240.0, emptyList())
    }
    override fun close() {}
  }
  private val models = ModelStatusStub()
  private val state = ScanSessionState(resources, actions, observeModel = models::observe)
  @After fun restoreModel() { CornerModelStore.activate(previousModel) }

  private fun save(output: SessionResources.Output): SessionResources.Resource {
    files.existing.add(output.file)
    return output.commit()
  }
  private fun finish() { worker.drain(); cleanup.drain(); assertTrue(errors.toString(), errors.isEmpty()) }

  @Test fun reservesSourcePinsThenPublishesOnlySavedSourcesWithCapturedDefaultsAndModel() {
    val model = CornerModelStore.Selection(byteArrayOf(1))
    CornerModelStore.activate(model)
    val options = DocumentRenderOptions(false, null, DocumentFilter.GRAYSCALE, null, null, null, false)
    val capture = SessionCapture.reserve(state, options)
    CornerModelStore.activate(CornerModelStore.Selection(byteArrayOf(2)))
    assertTrue(state.snapshot().pages.isEmpty())
    val original = save(capture.original)
    val frame = save(capture.frame)
    val id = capture.publish(frame, ScanPage.fullFrame())
    capture.close()
    assertEquals(capture.pageId, id)
    assertSame(model, preparedSelection)
    assertEquals(ScanPage.fullFrame(), preparedCorners)
    assertTrue(requireNotNull(state.snapshot().pages[id]).analyzing)
    assertTrue(renders.isEmpty())
    finish()
    assertEquals(DocumentFilter.GRAYSCALE, renders.single().options.filter)
    assertEquals(false, renders.single().options.removeShadows)
    assertTrue(requireNotNull(state.snapshot().pages[id]).ready)
    resources.lease(frame).close()
    resources.lease(original).close()
  }

  @Test fun closeDuringPhysicalSaveRejectsPublicationAndRetainsDirectoryUntilLastWriterSettles() {
    val capture = SessionCapture.reserve(state, null)
    files.existing.add(capture.original.file) // Simulated CameraX writer remains active.
    val closing = state.close()
    finish()
    assertFalse(closing.isDone)
    assertTrue(resources.directory in files.directories)
    assertTrue(capture.original.file in files.existing)
    val error = assertThrows(ScannerException::class.java) { capture.original.commit() }
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, error.code)
    capture.close() // The physical callback, not promise rejection, releases writer pins.
    capture.close()
    finish()
    assertTrue(closing.isDone)
    assertFalse(resources.directory in files.directories)
    assertTrue(files.existing.isEmpty())
    assertTrue(state.snapshot().pages.isEmpty())
  }

  private val shared = CornerModelStore.Selection(byteArrayOf(9))

  private val importOptions = DocumentRenderer.effectiveOptions(DocumentRenderOptions(null, null, DocumentFilter.ENHANCE, null, null, null, null))

  /** Admits through the image worker; [write] stands in for copying and normalizing the picked bytes. */
  private fun admitImport(autoCrop: Boolean = true, write: (File) -> Unit = { files.existing.add(it) }, done: (Result<String>) -> Unit) =
    ImageImport.admit(state, importOptions, autoCrop, { file, effective ->
      assertEquals(DocumentFilter.ENHANCE, effective.filter)
      write(file)
    }, { done(Result.success(it)) }, { done(Result.failure(it)) })

  private fun import(autoCrop: Boolean = true, write: (File) -> Unit = { files.existing.add(it) }): Result<String> {
    var result: Result<String>? = null
    val before = state.snapshot().pageIds.size
    admitImport(autoCrop, write) { result = it }
    assertEquals("Admission waits for the image worker.", before, state.snapshot().pageIds.size)
    finish()
    return requireNotNull(result)
  }

  /** Copies [bytes] through the production copy, as a pick whose reads or destination writes can fail. */
  private fun copy(bytes: ByteArray = byteArrayOf(1, 2, 3), unreadable: Boolean = false, full: Boolean = false): (File) -> Unit = { file ->
    ImageImport.copyOriginal({
      if (!unreadable) java.io.ByteArrayInputStream(bytes)
      else object : java.io.InputStream() { override fun read(): Int = throw IOException("provider read") }
    }) {
      files.existing.add(file)
      if (!full) java.io.ByteArrayOutputStream()
      else object : java.io.OutputStream() { override fun write(b: Int) = throw IOException("disk full") }
    }
  }

  @Test fun importFollowsTheSharedModelInsteadOfTheCameraSelection() {
    CornerModelStore.activate(CornerModelStore.Selection(byteArrayOf(1)))
    models.ready(shared)
    val id = import().getOrThrow()
    val page = requireNotNull(state.snapshot().pages[id])
    assertSame(shared, preparedSelection)
    assertNull(preparedCorners)
    assertTrue(page.ready)
    assertEquals(ScanPage.fullFrame(), page.corners)
    assertEquals(DocumentFilter.ENHANCE, renders.single().options.filter)
  }

  @Test fun importWithoutAModelRendersTheFullImage() {
    val id = import().getOrThrow()
    val page = requireNotNull(state.snapshot().pages[id])
    assertEquals(listOf(null), preparedSelections)
    assertTrue(page.ready)
    assertEquals(ScanPage.fullFrame(), page.corners)
    assertEquals(1, renders.size)
  }

  @Test fun anImportResolvesAndDisplaysItsFullFrameWhileTheModelLoadsThenCropsOnceItIsReady() {
    val corners = DocumentCorners(DocumentPoint(0.1, 0.1), DocumentPoint(0.9, 0.12), DocumentPoint(0.88, 0.9), DocumentPoint(0.12, 0.88))
    candidate = DocumentCandidate(corners, 1.0)
    models.loading()
    val id = import().getOrThrow()
    val waiting = requireNotNull(state.snapshot().pages[id])
    assertTrue("Waiting for the model reads as analysis.", waiting.analyzing)
    assertTrue(waiting.ready)
    assertEquals(listOf(ScanPage.fullFrame()), renders.map { it.corners })
    assertTrue(preparedSelections.isEmpty())
    models.ready(shared)
    finish()
    val cropped = requireNotNull(state.snapshot().pages[id])
    assertEquals(listOf(shared), preparedSelections)
    assertEquals(corners, cropped.corners)
    assertEquals(2.0, cropped.revision, 0.0)
    assertTrue(cropped.ready)
    assertFalse(cropped.analyzing)
    assertEquals(listOf(ScanPage.fullFrame(), corners), renders.map { it.corners })
  }

  @Test fun aGalleryBatchSkipsUnreadablePicksAndReturnsTheAdmittedIdsInPickedOrder() {
    var result: Result<List<String>>? = null
    LibraryImport<String>(0, { it(); true }, { pick, done ->
      admitImport(write = copy(unreadable = pick == "bad"), done = done)
    }) { result = it }.start(state::registerCloseCancellation) { it(Result.success(listOf("a", "bad", "b"))); Released() }
    finish()
    val ids = requireNotNull(result).getOrThrow()
    assertEquals(state.snapshot().pageIds.toList(), ids)
    assertEquals(2, ids.size)
  }

  @Test fun aGalleryDestinationFailureRejectsTheBatchAndKeepsItsAdmittedPages() {
    var result: Result<List<String>>? = null
    LibraryImport<String>(0, { it(); true }, { pick, done ->
      admitImport(write = copy(full = pick == "full"), done = done)
    }) { result = it }.start(state::registerCloseCancellation) { it(Result.success(listOf("a", "full", "b"))); Released() }
    finish()
    assertEquals(ScannerErrorCode.STORAGE_WRITE_FAILED, (requireNotNull(result).exceptionOrNull() as ScannerException).code)
    assertEquals(2, state.snapshot().pageIds.size)
  }

  /** A picker request that has already released its slot, as one that reported its result has. */
  private class Released : PickerRequest {
    override val pickerReleased: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    var ended = 0
    override fun end() { ended++ }
  }

  private fun done() = CompletableFuture.completedFuture(Unit)

  /** Runs [late] from a close callback that runs after close took the gallery call's registration. */
  private fun inTheCloseGap(post: (() -> Unit) -> Boolean, late: () -> Unit, present: ((Result<List<String>>) -> Unit) -> Unit): List<Result<List<String>>> {
    val results = mutableListOf<Result<List<String>>>()
    state.registerCloseCancellation { late(); done() }
    LibraryImport<String>(0, post, { _, _ -> fail("Nothing is admitted after close.") }) { results += it }
      .start(state::registerCloseCancellation) { present(it); Released() }
    state.close()
    finish()
    return results
  }

  @Test fun aPresentationQueuedWhenCloseTookTheRegistrationOpensNoPicker() {
    val main = ArrayDeque<() -> Unit>()
    var presented = 0
    val results = inTheCloseGap({ main.add(it) }, { while (main.isNotEmpty()) main.removeFirst()() }) { presented++ }
    assertEquals(0, presented)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (results.single().exceptionOrNull() as ScannerException).code)
  }

  @Test fun pickerResultsAfterCloseTookTheRegistrationAdmitNothing() {
    var onPicked: ((Result<List<String>>) -> Unit)? = null
    val results = inTheCloseGap({ it(); true }, { requireNotNull(onPicked)(Result.success(listOf("a"))) }) { onPicked = it }
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (results.single().exceptionOrNull() as ScannerException).code)
    assertTrue(state.snapshot().pages.isEmpty())
  }

  @Test fun importAppliesTheFoundPageUnlessAutoCropIsOff() {
    models.ready(shared)
    val corners = DocumentCorners(DocumentPoint(0.1, 0.1), DocumentPoint(0.9, 0.12), DocumentPoint(0.88, 0.9), DocumentPoint(0.12, 0.88))
    candidate = DocumentCandidate(corners, 1.0)
    val cropped = import().getOrThrow()
    val full = import(autoCrop = false).getOrThrow()
    assertEquals(corners, requireNotNull(state.snapshot().pages[cropped]).corners)
    assertEquals(listOf(corners), renders.take(1).map { it.corners })
    assertEquals(ScanPage.fullFrame(), requireNotNull(state.snapshot().pages[full]).corners)
    assertEquals(corners, requireNotNull(state.snapshot().pages[full]).analysis?.candidate?.corners)
  }

  @Test fun importReservesNoFrameOutput() {
    files.failFrame = true
    val id = import().getOrThrow()
    assertTrue(requireNotNull(state.snapshot().pages[id]).ready)
  }

  @Test fun closeRunsPendingCancellationsOnceAndRejectsLateRegistration() {
    var cancelled = 0
    val cleanup = CompletableFuture<Unit>()
    val pending = state.registerCloseCancellation { cancelled++; cleanup }
    val unregistered = state.registerCloseCancellation { fail("Unregistered work is not cancelled."); done() }
    assertTrue(unregistered.tryUnregister())
    assertFalse(unregistered.tryUnregister())
    val closing = state.close()
    assertSame(closing, state.close())
    assertEquals(1, cancelled)
    assertFalse("Close took the registration.", pending.isRegistered)
    assertFalse(pending.tryUnregister())
    val error = assertThrows(ScannerException::class.java) { state.registerCloseCancellation { done() } }
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, error.code)
    assertEquals("Close stops observing the shared model.", 0, models.observers)
    finish()
    assertFalse("Close waits for the cancellation's cleanup.", closing.isDone)
    cleanup.complete(Unit)
    assertTrue(closing.isDone)
  }

  @Test fun closingTheSessionSettlesAnOpenLibraryImportAndEndsItsPicker() {
    var result: Result<List<String>>? = null
    var onPicked: ((Result<List<String>>) -> Unit)? = null
    val request = object : PickerRequest {
      override val pickerReleased = CompletableFuture<Unit>()
      var ended = 0
      override fun end() { ended++ }
    }
    LibraryImport<String>(0, { it(); true }, { _, _ -> fail("Nothing is admitted after close.") }) { result = it }
      .start(state::registerCloseCancellation) { onPicked = it; request }
    val closing = state.close()
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (result?.exceptionOrNull() as ScannerException).code)
    assertEquals(1, request.ended)
    requireNotNull(onPicked)(Result.success(listOf("late")))
    finish()
    assertFalse("Close waits for the picker to release its slot.", closing.isDone)
    request.pickerReleased.complete(Unit)
    assertTrue(closing.isDone)
    assertTrue(state.snapshot().pages.isEmpty())
  }

  @Test fun aFailedDestinationReservationReportsStorageThroughTheCallbackAndRejectsTheBatch() {
    files.failOriginal = true
    val direct = import().exceptionOrNull()
    assertEquals(ScannerErrorCode.STORAGE_WRITE_FAILED, (direct as ScannerException).code)
    var result: Result<List<String>>? = null
    LibraryImport<String>(0, { it(); true }, { _, done -> admitImport(done = done) }) { result = it }
      .start(state::registerCloseCancellation) { it(Result.success(listOf("a"))); Released() }
    finish()
    assertEquals(ScannerErrorCode.STORAGE_WRITE_FAILED, (requireNotNull(result).exceptionOrNull() as ScannerException).code)
    assertTrue(state.snapshot().pages.isEmpty())
  }

  @Test fun failedImportsReportTheSourceOrTheDestinationAndLeaveNoPageOrFiles() {
    val unreadable = import(write = copy(unreadable = true)).exceptionOrNull()
    assertEquals(ScannerErrorCode.IMAGE_LOAD_FAILED, (unreadable as ScannerException).code)
    val undecodable = import { files.existing.add(it); throw IOException("not an image") }.exceptionOrNull()
    assertEquals(ScannerErrorCode.IMAGE_LOAD_FAILED, (undecodable as ScannerException).code)
    val full = import(write = copy(full = true)).exceptionOrNull()
    assertEquals(ScannerErrorCode.STORAGE_WRITE_FAILED, (full as ScannerException).code)
    assertTrue(state.snapshot().pages.isEmpty())
    assertTrue(files.existing.isEmpty())
  }

  @Test fun replacingSessionInvalidatesPublicationWithoutClosingTheOldPhysicalWriter() {
    val capture = SessionCapture.reserve(state, null)
    save(capture.original)
    capture.invalidate()
    val error = assertThrows(ScannerException::class.java) { capture.publish(null, null) }
    assertEquals(ScannerErrorCode.PROCESSOR_SUPERSEDED, error.code)
    cleanup.drain()
    assertTrue(capture.original.destination in files.existing)
    assertTrue(state.snapshot().pages.isEmpty())
    capture.close()
    finish()
    assertTrue(files.existing.isEmpty())
  }

  @Test fun failedSavingAndMissingFrameDoNotLeaveReservationsOrPublishAPartialPage() {
    val failed = SessionCapture.reserve(state, null)
    files.existing.add(failed.original.file)
    failed.invalidate()
    failed.close()
    finish()
    assertTrue(state.snapshot().pages.isEmpty())
    assertTrue(files.existing.isEmpty())

    val noFrame = SessionCapture.reserve(state, null)
    save(noFrame.original)
    noFrame.publish(null, null)
    noFrame.close()
    finish()
    assertTrue(noFrame.frame.resource.deleted.isDone)
    assertFalse(noFrame.original.resource.deleted.isDone)
  }

  @Test fun admissionRejectsOverlapUntilPhysicalCompletionAndFactoryFailureRestoresSlot() {
    val slot = CaptureAdmission<SessionCapture>(Any())
    val capture = slot.admit { SessionCapture.reserve(state, null) }
    capture.invalidate() // Promise is rejected, but physical work still owns admission.
    val error = assertThrows(ScannerException::class.java) { slot.admit { error("Must not allocate") } }
    assertEquals(ScannerErrorCode.CAPTURE_BUSY, error.code)
    assertSame(capture, slot.current)
    assertTrue(slot.finish(capture))
    capture.close()
    assertFalse(slot.finish(capture))
    files.failFrame = true
    assertThrows(IOException::class.java) { slot.admit { SessionCapture.reserve(state, null) } }
    assertNull(slot.current)
    finish()
    assertTrue(files.existing.isEmpty())
  }

  @Test fun simultaneousAdmissionCreatesOnlyOneOwner() {
    val slot = CaptureAdmission<Any>(Any())
    val start = java.util.concurrent.CountDownLatch(1)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    val accepted = java.util.concurrent.atomic.AtomicInteger()
    val busy = java.util.concurrent.atomic.AtomicInteger()
    try {
      val jobs = (1..64).map {
        pool.submit {
          start.await()
          try { slot.admit { accepted.incrementAndGet(); Any() } }
          catch (error: ScannerException) { assertEquals(ScannerErrorCode.CAPTURE_BUSY, error.code); busy.incrementAndGet() }
        }
      }
      start.countDown()
      jobs.forEach { it.get(5, TimeUnit.SECONDS) }
      assertEquals(1, accepted.get())
      assertEquals(63, busy.get())
    } finally { pool.shutdownNow() }
  }

  @Test fun captureStateDescribesProgressAndInactiveModeOverridesEveryPhase() {
    assertEquals(CameraCaptureState.READY, cameraCaptureState(true, null, false))
    assertEquals(CameraCaptureState.WAITING, cameraCaptureState(true, null, true))
    assertEquals(CameraCaptureState.WAITING, cameraCaptureState(true, CaptureStage.WAITING, true))
    assertEquals(CameraCaptureState.SHOOTING, cameraCaptureState(true, CaptureStage.SHOOTING, true))
    assertEquals(CameraCaptureState.SAVING, cameraCaptureState(true, CaptureStage.CAPTURED, true))
    assertEquals(CameraCaptureState.READY, cameraCaptureState(true, CaptureStage.FAILED, false))
    for (stage in listOf(null, CaptureStage.WAITING, CaptureStage.SHOOTING, CaptureStage.CAPTURED, CaptureStage.FAILED)) {
      assertEquals(CameraCaptureState.UNAVAILABLE, cameraCaptureState(false, stage, true))
    }
  }

  @Test fun closeCancelsQueuedSourceSavingButWaitsForItsMainOwnerToReleaseCapturePins() {
    val slot = CaptureAdmission<SessionCapture>(Any())
    val capture = slot.admit { SessionCapture.reserve(state, null) }
    val main = Worker()
    var saves = 0
    var failures = 0
    files.existing.add(capture.original.file) // CameraX has saved; description/frame work is waiting.
    queue.submit(resources.workOwner, ScannerWorkQueue.Kind.CAPTURE, capture.pageId,
      operation = { saves++; save(capture.original) },
      onSuccess = { error("Closed source saving must not publish") },
      onFailure = {
        assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (it as ScannerException).code)
        failures++
        main.execute { slot.finish(capture); capture.invalidate(); capture.close() }
      })
    val closing = state.close()
    cleanup.drain()
    assertFalse(closing.isDone)
    assertSame(capture, slot.current)
    main.drain()
    finish()
    assertTrue(closing.isDone)
    assertNull(slot.current)
    assertEquals(0, saves)
    assertEquals(1, failures)
    assertTrue(state.snapshot().pages.isEmpty())
  }}
