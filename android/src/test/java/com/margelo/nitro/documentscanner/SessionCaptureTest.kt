package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.QuadModelStore
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

  private val previousModel = QuadModelStore.selected
  private val worker = Worker()
  private val cleanup = Worker()
  private val files = Files()
  private val errors = mutableListOf<Throwable>()
  private val queue = ScannerWorkQueue(worker) { errors.add(it) }
  private val registry = ScanSessionRegistry(files, queue, cleanup, {}, { errors.add(it) })
  private val resources = registry.open(File("capture-state-test").absoluteFile)
  private var preparedSelection: QuadModelStore.Selection? = null
  private var preparedQuad: DocumentQuad? = null
  private val renders = mutableListOf<ScanPage.RenderKey>()
  private val actions = object : ScanSessionState.Actions {
    override fun prepareAnalysis(original: SessionResources.Resource, liveQuad: DocumentQuad?, selection: QuadModelStore.Selection?): () -> DocumentAnalysis {
      preparedSelection = selection
      preparedQuad = liveQuad
      return { DocumentAnalysis(320.0, 240.0, 1.0, null) }
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
  @After fun restoreModel() { QuadModelStore.activate(previousModel) }

  private fun save(output: SessionResources.Output): SessionResources.Resource {
    files.existing.add(output.file)
    return output.commit()
  }
  private fun finish() { worker.drain(); cleanup.drain(); assertTrue(errors.toString(), errors.isEmpty()) }

  @Test fun reservesSourcePinsThenPublishesOnlySavedSourcesWithCapturedDefaultsAndModel() {
    val model = QuadModelStore.Selection(byteArrayOf(1))
    QuadModelStore.activate(model)
    val options = DocumentRenderOptions(false, null, DocumentFilter.GRAYSCALE, null, null, null, false)
    val capture = SessionCapture.reserve(state, options)
    QuadModelStore.activate(QuadModelStore.Selection(byteArrayOf(2)))
    assertTrue(state.snapshot().pages.isEmpty())
    val original = save(capture.original)
    val frame = save(capture.frame)
    val id = capture.publish(frame, ScanPage.fullFrame())
    capture.close()
    assertEquals(capture.pageId, id)
    assertSame(model, preparedSelection)
    assertEquals(ScanPage.fullFrame(), preparedQuad)
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
