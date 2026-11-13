package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.CornerModelStore
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
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
    override fun directory(cache: File, sessionId: String) = File(File(cache, "sessions"), "session-$sessionId")
    override fun createDirectory(directory: File) { directories.add(directory) }
    override fun sessions(cache: File) = emptyList<File>()
    override fun output(directory: File, id: String, kind: SessionResources.Kind, extension: String): SessionFiles.Output {
      if (failFrame && kind == SessionResources.Kind.FRAME) throw IOException("frame reservation")
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
  private var preparedCorners: DocumentCorners? = null
  private var candidate: DocumentCandidate? = null
  private val renders = mutableListOf<ScanPage.RenderKey>()
  private val actions = object : ScanSessionState.Actions {
    override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?): () -> DocumentAnalysis {
      preparedSelection = selection
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
  private val state = ScanSessionState(resources, actions)
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
    val frame = save(requireNotNull(capture.frame))
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

  private val frozen = CornerModelStore.Selection(byteArrayOf(9))

  private fun import(autoCrop: Boolean = true, write: (File) -> Unit = { files.existing.add(it) }): Result<String> {
    var result: Result<String>? = null
    val before = state.snapshot().pageIds.size
    val options = DocumentRenderOptions(null, null, DocumentFilter.ENHANCE, null, null, null, null)
    ImageImport.admit(state, DocumentRenderer.effectiveOptions(options), autoCrop, frozen, { file, effective ->
      assertEquals(DocumentFilter.ENHANCE, effective.filter)
      write(file)
    }, { result = Result.success(it) }, { result = Result.failure(it) })
    assertEquals("Admission waits for the image worker.", before, state.snapshot().pageIds.size)
    finish()
    return requireNotNull(result)
  }

  @Test fun importPublishesFramelessPageAndKeepsFullImageWithoutAPage() {
    CornerModelStore.activate(CornerModelStore.Selection(byteArrayOf(1)))
    val id = import().getOrThrow()
    val page = requireNotNull(state.snapshot().pages[id])
    // The call's frozen model wins over whatever the camera has active.
    assertSame(frozen, preparedSelection)
    assertNull(preparedCorners)
    assertTrue(page.ready)
    assertEquals(ScanPage.fullFrame(), page.corners)
    assertEquals(DocumentFilter.ENHANCE, renders.single().options.filter)
  }

  @Test fun importAppliesTheFoundPageUnlessAutoCropIsOff() {
    CornerModelStore.activate(CornerModelStore.Selection(byteArrayOf(1)))
    val corners = DocumentCorners(DocumentPoint(0.1, 0.1), DocumentPoint(0.9, 0.12), DocumentPoint(0.88, 0.9), DocumentPoint(0.12, 0.88))
    candidate = DocumentCandidate(corners, 1.0)
    val cropped = import().getOrThrow()
    val full = import(autoCrop = false).getOrThrow()
    assertEquals(corners, requireNotNull(state.snapshot().pages[cropped]).corners)
    assertEquals(ScanPage.fullFrame(), requireNotNull(state.snapshot().pages[full]).corners)
  }

  @Test fun importReservesNoFrameOutput() {
    CornerModelStore.activate(CornerModelStore.Selection(byteArrayOf(1)))
    files.failFrame = true
    val id = import().getOrThrow()
    assertTrue(requireNotNull(state.snapshot().pages[id]).ready)
  }

  @Test fun closeRunsPendingCancellationsOnceAndRejectsLateRegistration() {
    var cancelled = 0
    state.onClose { cancelled++ }
    val unregistered = state.onClose { fail("Unregistered work is not cancelled.") }
    unregistered()
    val closing = state.close()
    state.close()
    assertEquals(1, cancelled)
    val error = assertThrows(ScannerException::class.java) { state.onClose {} }
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, error.code)
    finish()
    assertTrue(closing.isDone)
  }

  @Test fun closingTheSessionSettlesAnOpenLibraryImportAndEndsItsPicker() {
    var result: Result<LibraryImportResult>? = null
    var ended = 0
    var onPicked: ((Result<List<String>>) -> Unit)? = null
    val operation = LibraryImport<String>(0, { it() }, { _, _ -> fail("Nothing is admitted after close.") }) { result = it }
    operation.start(state::onClose) { onPicked = it; { ended++ } }
    state.close()
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (result?.exceptionOrNull() as ScannerException).code)
    assertEquals(1, ended)
    requireNotNull(onPicked)(Result.success(listOf("late")))
    finish()
  }

  @Test fun failedImportReportsImageLoadAndLeavesNoPageOrFiles() {
    CornerModelStore.activate(CornerModelStore.Selection(byteArrayOf(1)))
    val error = import { files.existing.add(it); throw IOException("not an image") }.exceptionOrNull()
    assertEquals(ScannerErrorCode.IMAGE_LOAD_FAILED, (error as ScannerException).code)
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
    assertTrue(requireNotNull(noFrame.frame).resource.deleted.isDone)
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
