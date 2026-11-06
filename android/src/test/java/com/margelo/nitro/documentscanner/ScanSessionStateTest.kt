package com.margelo.nitro.documentscanner

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ScanSessionStateTest {
  private class ManualExecutor : Executor {
    val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
  }

  private class Files : SessionFiles {
    val existing = HashSet<File>()
    override fun directory(cache: File, sessionId: String) = File(File(cache, "sessions"), "session-$sessionId")
    override fun createDirectory(directory: File) {}
    override fun sessions(cache: File) = emptyList<File>()
    override fun output(directory: File, id: String, kind: SessionResources.Kind, extension: String) =
      SessionFiles.Output(directory, File(directory, ".$id.tmp.$extension"), File(directory, "$id.$extension"))
    override fun publish(output: SessionFiles.Output) {
      check(existing.remove(output.temporary))
      check(existing.add(output.destination))
    }
    override fun remove(output: SessionFiles.Output) {
      existing.remove(output.temporary)
      existing.remove(output.destination)
    }
    override fun removeDirectory(cache: File, directory: File) {
      existing.removeAll { it.parentFile == directory }
    }
  }

  private val worker = ManualExecutor()
  private val cleanup = ManualExecutor()
  private val files = Files()
  private val callbackErrors = mutableListOf<Throwable>()
  private val queue = ScannerWorkQueue(worker) { callbackErrors.add(it) }
  private val registry = ScanSessionRegistry(files, queue, cleanup, {}, { callbackErrors.add(it) })
  private val resources = registry.open(File("session-state-test").absoluteFile)
  private fun source(kind: SessionResources.Kind = SessionResources.Kind.ORIGINAL) =
    resources.output(kind).use { files.existing.add(it.file); it.commit() }

  private val rendered = mutableListOf<Pair<ScanPage.RenderKey, ScanPage.Artifact>>()
  private var analysisCalls = 0
  private var selectedModel = "capture model"
  private val usedModels = mutableListOf<String>()
  private var analysisResult = DocumentAnalysis(320.0, 240.0, 1.0, null)
  private var duringAnalysis: () -> Unit = {}
  private var duringRender: (ScanPage.RenderKey) -> Unit = {}
  private var analysisError: Throwable? = null
  private var renderError: Throwable? = null
  private var actionsClosed = 0
  private val actions = object : ScanSessionState.Actions {
    override fun prepareAnalysis(original: SessionResources.Resource, liveQuad: DocumentQuad?, selection: com.margelo.nitro.documentscanner.processing.QuadModelStore.Selection?): () -> DocumentAnalysis {
      val model = selectedModel
      return {
        resources.lease(original).use {
          analysisCalls++
          usedModels.add(model)
          duringAnalysis()
          analysisError?.let { throw it }
          analysisResult
        }
      }
    }
    override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact =
      resources.lease(original).use {
        assertEquals(SessionResources.Kind.ORIGINAL, original.kind)
        renderError?.let { throw it }
        val image = source(SessionResources.Kind.RENDER)
        val thumbnail = source(SessionResources.Kind.THUMBNAIL)
        try {
          duringRender(key)
          ScanPage.Artifact(image, thumbnail, 320.0, 240.0, emptyList()).also { rendered.add(key to it) }
        } catch (error: Throwable) {
          resources.remove(image)
          resources.remove(thumbnail)
          throw error
        }
      }
    override fun close() { actionsClosed++ }
  }
  private val main = ManualExecutor()
  private val state = ScanSessionState(resources, actions, dispatcher = main) { error, fallback ->
    ScannerFailure((error as? ScannerException)?.code ?: fallback)
  }
  private val full = ScanPage.fullFrame()
  private val crop = DocumentQuad(DocumentPoint(.1, .1), DocumentPoint(.9, .1),
    DocumentPoint(.9, .9), DocumentPoint(.1, .9))
  private fun meta(id: String = "page") = requireNotNull(state.metadata()[id])
  private fun admit(options: DocumentRenderOptions? = null, id: String = "page") {
    state.admit(id, source(), source(SessionResources.Kind.FRAME), null, options)
  }
  private fun drain() { worker.drain(); cleanup.drain(); assertTrue(callbackErrors.toString(), callbackErrors.isEmpty()) }
  private fun options(filter: DocumentFilter, shadows: Boolean = false) =
    DocumentRenderOptions(shadows, null, filter, null, null, null, false)

  @Test fun orderPublicationIsImmutableIncrementalAndRejectsPartialOrders() {
    admit(id = "a"); admit(id = "b")
    val before = state.snapshot()
    val changes = mutableListOf<ScanSessionChange>()
    val registration = state.observe { changes.add(it) }
    state.setPageOrder(listOf("b", "a")); main.drain()
    assertEquals(listOf("b", "a"), state.snapshot().pageIds.toList())
    assertEquals(listOf("a", "b"), before.pageIds.toList())
    assertTrue(changes.single().updatedPages.isEmpty())
    assertEquals(listOf("b", "a"), changes.single().pageIds!!.toList())
    state.setPageOrder(listOf("b", "a")); main.drain()
    assertEquals(1, changes.size)
    assertThrows(InvalidInputException::class.java) { state.setPageOrder(listOf("a")) }
    state.removePage("b"); admit(id = "c")
    assertEquals(listOf("a", "c"), state.snapshot().pageIds.toList())
    registration.unsubscribe()
  }

  @Test fun claimedPublicationDoesNotLockMetadataAndCloseWaitsForItsCompletion() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val publishing = java.util.concurrent.CompletableFuture.runAsync {
      state.publishExport {
        entered.countDown()
        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
      }
    }
    try {
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
      val read = java.util.concurrent.CompletableFuture.supplyAsync { state.snapshot() }
      assertFalse(read.get(2, java.util.concurrent.TimeUnit.SECONDS).closed)
      val closing = state.close()
      cleanup.drain()
      assertTrue(state.snapshot().closed)
      assertFalse(closing.isDone)
      release.countDown()
      publishing.get(5, java.util.concurrent.TimeUnit.SECONDS)
      closing.get(5, java.util.concurrent.TimeUnit.SECONDS)
      try { state.publishExport { fail("Closed state cannot admit publication") }; fail() }
      catch (error: ScannerException) { assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, error.code) }
    } finally {
      release.countDown()
      publishing.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }
  }

  @Test fun failedClaimedPublicationAlsoReleasesTheCloseBarrier() {
    var closing: java.util.concurrent.CompletableFuture<Unit>? = null
    try {
      state.publishExport {
        closing = state.close()
        cleanup.drain()
        assertFalse(requireNotNull(closing).isDone)
        throw IOException("publication failed")
      }
      fail("Expected publication failure")
    } catch (error: IOException) { assertEquals("publication failed", error.message) }
    assertTrue(requireNotNull(closing).isDone)
  }

  @Test fun sampleAnalysisIsTheAdmissionSnapshotRatherThanALaterGlobalMirror() {
    admit()
    val early = state.sampleSource("page")
    try {
      assertNull(early.analysis)
      drain()
      assertNull(early.analysis)
      val ready = state.sampleSource("page")
      try { assertEquals(analysisResult, ready.analysis) } finally { ready.original.close() }
    } finally { early.original.close() }
    state.close(); drain()
  }

  @Test fun firstRenderWaitsForAuthoritativeAnalysisAndUsesCapturedDefaultsAndModel() {
    admit(options(DocumentFilter.ENHANCE))
    selectedModel = "later model"
    assertTrue(meta().analyzing)
    assertFalse(meta().rendering)
    assertNull(meta().readyRevision)
    assertTrue(rendered.isEmpty())
    duringAnalysis = { assertTrue(rendered.isEmpty()) }
    drain()
    assertEquals(listOf("capture model"), usedModels)
    assertEquals(1, analysisCalls)
    assertEquals(1, rendered.size)
    assertEquals(DocumentFilter.ENHANCE, rendered.single().first.options.filter)
    assertEquals(false, rendered.single().first.options.removeShadows)
    assertEquals(full, rendered.single().first.quad)
    assertEquals(meta().requestedRevision, meta().readyRevision)
  }

  private fun candidate(quad: DocumentQuad): DocumentCandidate = DocumentCandidate(quad, .99)

  @Test fun manualGeometryDuringAnalysisWinsButFilterChangesKeepUsefulCandidate() {
    analysisResult = analysisResult.copy(candidate = candidate(crop))
    admit()
    duringAnalysis = {
      state.setDocumentCrop("page", full) // Equal full frame is still an explicit user choice.
      state.setFilter("page", DocumentFilter.GRAYSCALE)
    }
    drain()
    assertEquals(full, meta().quad)
    assertEquals(crop, meta().analysis?.candidate?.quad)
    assertEquals(DocumentFilter.GRAYSCALE, rendered.single().first.options.filter)

    duringAnalysis = { state.setFilter("other", DocumentFilter.ENHANCE) }
    admit(id = "other")
    drain()
    assertEquals(crop, meta("other").quad)
    assertEquals(DocumentFilter.ENHANCE, rendered.last().first.options.filter)
    assertEquals(2, analysisCalls)
  }

  @Test fun aPageAdmittedWithoutAutoCropKeepsTheFullFrameButStillHasItsCandidate() {
    analysisResult = analysisResult.copy(candidate = candidate(crop))
    state.admit("photo", source(), source(SessionResources.Kind.FRAME), null, null, autoCrop = false)
    drain()
    assertEquals(full, meta("photo").quad)
    assertEquals(full, rendered.single().first.quad)
    assertEquals(crop, meta("photo").analysis?.candidate?.quad)
    state.setDocumentCrop("photo", crop)
    drain()
    assertEquals(crop, rendered.last().first.quad)
  }

  @Test fun analysisFailureAndNoCandidateBothLeaveFullFrameAndManualCroppingUsable() {
    analysisError = IOException("analysis")
    admit()
    drain()
    assertEquals(ScannerErrorCode.ANALYSIS_FAILED, meta().analysisFailure?.code)
    assertNull(meta().renderFailure)
    assertEquals(full, rendered.single().first.quad)
    state.setDocumentCrop("page", crop)
    drain()
    assertEquals(crop, rendered.last().first.quad)
    assertEquals(1, analysisCalls)
    analysisError = null
    admit(id = "no-candidate")
    drain()
    assertNull(meta("no-candidate").analysisFailure)
    assertEquals(full, meta("no-candidate").quad)
  }

  @Test fun equalSettingsAreNoOpsAndInvalidCommandsDoNotChangeAcceptedState() {
    admit()
    drain()
    val before = meta()
    state.setDocumentCrop("page", full.copy())
    state.setFilter("page", DocumentFilter.NONE)
    state.setShadowRemoval("page", true)
    assertEquals(before, meta())
    assertThrows(InvalidInputException::class.java) { state.setDocumentCrop("page", full.copy(topLeft = DocumentPoint(Double.NaN, 0.0))) }
    assertThrows(InvalidInputException::class.java) { state.setFilter("missing", DocumentFilter.NONE) }
    assertEquals(before, meta())
    drain()
    assertEquals(1, rendered.size)
  }

  @Test fun aBAReusesBothFilterAndShadowSlotsWithoutRenderingFramePlaceholder() {
    admit()
    drain()
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    drain()
    state.setShadowRemoval("page", false)
    drain()
    assertEquals(3, rendered.size)
    state.setShadowRemoval("page", true)
    assertEquals(meta().requestedRevision, meta().readyRevision)
    state.setFilter("page", DocumentFilter.NONE)
    assertEquals(meta().requestedRevision, meta().readyRevision)
    drain()
    assertEquals(3, rendered.size)
  }

  @Test fun queuedEditsCoalesceAndRunningCompatibleStaleSuccessNeverPublishesOldRevision() {
    admit()
    duringRender = {
      duringRender = {}
      state.setFilter("page", DocumentFilter.GRAYSCALE)
      state.setFilter("page", DocumentFilter.NONE)
      assertNull(meta().readyRevision)
    }
    drain()
    // The active A is cached, queued B is superseded, and latest A consumes that cache.
    assertEquals(1, rendered.size)
    assertEquals(3L, meta().readyRevision)
    assertNull(meta().renderFailure)
  }

  @Test fun compatibleStaleResultIsCachedWithoutBecomingDisplayed() {
    admit()
    duringRender = { key ->
      if (key.options.filter == DocumentFilter.NONE) {
        state.setFilter("page", DocumentFilter.GRAYSCALE)
      } else {
        assertNull(meta().readyRevision)
        assertNull(meta().renderFailure)
      }
    }
    drain()
    assertEquals(2, rendered.size)
    assertEquals(2L, meta().readyRevision)
    duringRender = {}
    state.setFilter("page", DocumentFilter.NONE)
    assertEquals(3L, meta().readyRevision)
    drain()
    assertEquals(2, rendered.size)
  }

  @Test fun admissionAndFilterValidationRejectBeforeChangingEffectiveSettings() {
    val original = source()
    val invalid = DocumentRenderOptions(null, null, DocumentFilter.NONE, DocumentImageFormat.PNG, null, null, null)
    assertThrows(InvalidInputException::class.java) { state.admit("page", original, null, null, invalid) }
    assertTrue(state.metadata().isEmpty())
    resources.lease(original).close() // Rejection did not consume the source.
    val valid = invalid.copy(filter = DocumentFilter.GRAYSCALE)
    state.admit("page", original, null, null, valid)
    assertThrows(InvalidInputException::class.java) { state.admit("other", original, null, null, valid) }
    val before = meta()
    assertThrows(InvalidInputException::class.java) { state.setFilter("page", DocumentFilter.NONE) }
    assertEquals(before, meta())
    drain()
    assertEquals(DocumentImageFormat.PNG, rendered.single().first.options.format)
  }
  @Test fun staleSuccessWithIncompatibleGeometryIsRetiredAndNewGeometryRendersOriginal() {
    admit()
    duringRender = {
      duringRender = {}
      state.setDocumentCrop("page", crop)
      assertNull(meta().readyRevision)
    }
    drain()
    assertEquals(2, rendered.size)
    assertTrue(rendered.first().second.image.deleted.isDone)
    assertFalse(rendered.last().second.image.deleted.isDone)
    assertEquals(crop, rendered.last().first.quad)
    assertEquals(meta().requestedRevision, meta().readyRevision)
  }

  @Test fun failureKeepsPreviousDisplayAndEqualFailedSettingsRetry() {
    admit()
    drain()
    val previous = rendered.single().second
    val previousRevision = meta().readyRevision
    renderError = IOException("render")
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    drain()
    assertEquals(previousRevision, meta().readyRevision)
    assertEquals(ScannerErrorCode.RENDER_FAILED, meta().renderFailure?.code)
    assertFalse(previous.image.deleted.isDone)
    renderError = null
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    assertNull(meta().renderFailure)
    drain()
    assertEquals(meta().requestedRevision, meta().readyRevision)
    assertEquals(2, rendered.size)
  }

  @Test fun staleFailureCannotFailLatestRevision() {
    admit()
    duringRender = {
      duringRender = {}
      state.setFilter("page", DocumentFilter.GRAYSCALE)
      throw IOException("old render")
    }
    drain()
    assertEquals(1, rendered.size)
    assertEquals(DocumentFilter.GRAYSCALE, rendered.single().first.options.filter)
    assertNull(meta().renderFailure)
    assertEquals(meta().requestedRevision, meta().readyRevision)
  }

  @Test fun cropInvalidatesCachesButPreservesDisplayedFileUntilReplacementAndReaderRelease() {
    admit()
    drain()
    val originalDisplay = rendered.single().second
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    drain()
    val display = rendered.last().second
    val reader = resources.lease(display.image)
    state.setDocumentCrop("page", crop)
    cleanup.drain()
    assertTrue(originalDisplay.image.deleted.isDone)
    assertFalse(display.image.deleted.isDone)
    duringRender = { assertFalse(display.image.deleted.isDone) }
    drain()
    duringRender = {} // The lifetime assertion applies only to the replacement render.
    assertFalse(display.image.deleted.isDone)
    reader.close()
    cleanup.drain()
    assertTrue(display.image.deleted.isDone)
    state.setFilter("page", DocumentFilter.NONE)
    drain()
    assertEquals(4, rendered.size)
  }

  @Test fun deleteDuringRenderRejectsStaleResultAndDoesNotAffectOtherPage() {
    admit()
    admit(id = "other")
    duringRender = {
      duringRender = {}
      state.removePage("page")
      assertFalse(state.metadata().containsKey("page"))
    }
    drain()
    assertFalse(state.metadata().containsKey("page"))
    assertTrue(rendered.first().second.image.deleted.isDone)
    assertEquals(meta("other").requestedRevision, meta("other").readyRevision)
  }

  @Test fun deleteDuringAnalysisAndIdReuseCannotPublishIntoReplacementPage() {
    admit()
    duringAnalysis = {
      duringAnalysis = {}
      state.removePage("page")
      admit()
    }
    drain()
    assertEquals(2, analysisCalls)
    assertEquals(1, rendered.size)
    assertEquals(1L, meta().readyRevision)
  }

  @Test fun closeDuringRenderDiscardsResultDrainsModelAndRejectsFurtherAdmission() {
    admit()
    var closed: java.util.concurrent.CompletableFuture<Unit>? = null
    duringRender = {
      closed = state.close()
      assertFalse(requireNotNull(closed).isDone)
      assertSame(closed, state.close())
      assertEquals(0, actionsClosed)
    }
    drain()
    assertTrue(requireNotNull(closed).isDone)
    assertTrue(state.metadata().isEmpty())
    assertTrue(files.existing.isEmpty())
    assertTrue(rendered.single().second.image.deleted.isDone)
    assertEquals(1, actionsClosed)
    val error = assertThrows(ScannerException::class.java) { state.setFilter("page", DocumentFilter.NONE) }
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, error.code)
  }

  @Test fun headlessSessionOwnsMembershipUntilExplicitClose() {
    // No view or JS observer is attached; admission and processing still publish membership.
    admit(id = "b")
    admit(id = "a")
    val captured = state.snapshot()
    assertArrayEquals(arrayOf("b", "a"), captured.pageIds)
    drain()
    assertTrue(state.snapshot().pages.values.all { it.ready })
    state.removePage("a")
    assertArrayEquals(arrayOf("b"), state.snapshot().pageIds)
    state.close()
    assertTrue(state.snapshot().closed)
    assertArrayEquals(emptyArray<String>(), state.snapshot().pageIds)
  }

  @Test fun changesContainOnlyAffectedPagesAndRemovalWhileSnapshotsStayImmutable() {
    admit(id = "a")
    admit(id = "b")
    val changes = mutableListOf<ScanSessionChange>()
    val registration = state.observe { changes.add(it) }
    val before = registration.snapshot
    state.setFilter("a", DocumentFilter.GRAYSCALE)
    state.setFilter("a", DocumentFilter.GRAYSCALE)
    main.drain()
    assertEquals(1, changes.size)
    assertEquals(setOf("a"), changes.single().updatedPages.keys)
    assertTrue(changes.single().removedPageIds.isEmpty())
    assertEquals(DocumentFilter.NONE, before.pages["a"]?.options?.filter)
    state.removePage("b")
    main.drain()
    assertArrayEquals(arrayOf("b"), changes.last().removedPageIds)
    assertTrue(changes.last().updatedPages.isEmpty())
    assertTrue(before.pages.containsKey("b"))
    registration.unsubscribe()
  }

  @Test fun bindingClearsSeenPagesSilentlyAndRejectsOnlyUnseenOnes() {
    // Pages without a frame or render yet resolve to no thumbnail source, so the binding's classification is
    // exercised without constructing file URIs, which are unavailable on the JVM.
    listOf("a", "b", "c").forEach { state.admit(it, source(), null, null, null) }
    main.drain()
    val cleared = mutableListOf<String>()
    val errors = mutableListOf<ScannerErrorCode>()
    fun binding(id: String) = SessionPageBinding(state, id, thumbnailOnly = true,
      changed = { check(it == null); cleared.add(id) }, failed = { errors.add(it.code) })

    // An update still queued when the page is removed.
    val a = binding("a")
    a.start()
    state.setFilter("a", DocumentFilter.GRAYSCALE)
    state.removePage("a")
    main.drain()
    assertEquals(listOf("a"), cleared)
    // A restart (a view attaching again) that finds its seen page gone.
    a.start()
    assertEquals(listOf("a", "a"), cleared)
    // Removal between registration and the first read.
    binding("b").start(afterRegistration = { state.removePage("b") })
    assertEquals(listOf("a", "a", "b"), cleared)
    assertTrue(errors.isEmpty())
    // A page the binding never saw.
    binding("missing").start()
    assertEquals(listOf(ScannerErrorCode.INPUT_INVALID), errors)

    // Detach, close the session, attach again.
    val c = binding("c")
    c.start()
    c.close()
    state.close()
    drain(); main.drain()
    c.start()
    assertEquals(listOf(ScannerErrorCode.INPUT_INVALID), errors)
    assertEquals("c", cleared.last())
    // A closed session is still invalid input for a binding that never saw its page.
    binding("c").start()
    assertEquals(listOf(ScannerErrorCode.INPUT_INVALID, ScannerErrorCode.INPUT_INVALID), errors)
  }

  @Test fun sourceVersionsTrackMetadataTransitionsAndSnapshotsKeepTheirOldValues() {
    assertEquals(0.0, state.snapshot().sourceVersion, 0.0)
    admit()
    val initial = state.snapshot()
    assertEquals(1.0, initial.sourceVersion, 0.0)
    assertTrue(requireNotNull(initial.pages["page"]).analyzing)
    state.setFilter("page", DocumentFilter.NONE)
    assertSame(initial, state.snapshot())
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    val edited = state.snapshot()
    assertEquals(2.0, edited.sourceVersion, 0.0)
    assertEquals(DocumentFilter.NONE, initial.pages["page"]?.options?.filter)
    assertEquals(DocumentFilter.GRAYSCALE, edited.pages["page"]?.options?.filter)
    @Suppress("UNCHECKED_CAST")
    val map = edited.pages as MutableMap<String, ScanPageMetadata>
    assertThrows(UnsupportedOperationException::class.java) { map.clear() }
    drain()
    assertTrue(requireNotNull(state.snapshot().pages["page"]).ready)
    assertTrue(state.snapshot().sourceVersion > edited.sourceVersion)
    assertTrue(requireNotNull(initial.pages["page"]).analyzing)
  }

  @Test fun atomicRegistrationBracketsConcurrentMutationsWithoutLossOrEagerCallback() {
    admit()
    val start = java.util.concurrent.CountDownLatch(1)
    val writer = Thread {
      start.await()
      repeat(100) { state.setFilter("page", if (it % 2 == 0) DocumentFilter.GRAYSCALE else DocumentFilter.NONE) }
    }
    val versions = mutableListOf<Double>()
    writer.start()
    start.countDown()
    val registration = state.observe { versions.add(it.sourceVersion) }
    writer.join(5000)
    assertFalse(writer.isAlive)
    assertTrue(versions.isEmpty())
    main.drain()
    val expected = ((registration.snapshot.sourceVersion.toInt() + 1)..101).map { it.toDouble() }
    assertEquals(expected, versions)
    assertEquals(101.0, state.snapshot().sourceVersion, 0.0)
    registration.unsubscribe()
  }

  @Test fun callbacksRunUnlockedCanReadAndMutateAndUnsubscribeAnotherObserver() {
    admit()
    var secondCalls = 0
    var firstCalls = 0
    lateinit var second: ScanSessionEvents.Registration
    val first = state.observe {
      firstCalls++
      // A separate thread must acquire the state lock while the callback is still executing.
      val copied = java.util.concurrent.CompletableFuture.supplyAsync { state.snapshot() }
        .get(1, java.util.concurrent.TimeUnit.SECONDS)
      assertTrue(copied.sourceVersion >= it.sourceVersion)
      second.unsubscribe()
      if (firstCalls == 1) state.setShadowRemoval("page", false)
    }
    second = state.observe { secondCalls++ }
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    main.drain()
    assertEquals(2, firstCalls)
    assertEquals(0, secondCalls)
    first.unsubscribe()
    first.unsubscribe()
    state.setFilter("page", DocumentFilter.NONE)
    main.drain()
    assertEquals(2, firstCalls)
  }

  @Test fun closeDropsQueuedOldGenerationAndDeliversExactlyOneTerminalSnapshot() {
    admit()
    val snapshots = mutableListOf<ScanSessionChange>()
    val registration = state.observe { snapshots.add(it) }
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    val closing = state.close()
    state.close()
    assertTrue(state.snapshot().closed)
    assertTrue(snapshots.isEmpty())
    main.drain()
    assertEquals(1, snapshots.size)
    assertTrue(snapshots.single().closed)
    assertTrue(snapshots.single().updatedPages.isEmpty())
    assertThrows(ScannerException::class.java) { state.observe {} }
    registration.unsubscribe()
    drain()
    assertTrue(closing.isDone)
    main.drain()
    assertEquals(1, snapshots.size)
  }
  @Test fun throwingObserverCannotStallOtherObserversOrLaterVersions() {
    admit()
    val gate = Any()
    val reported = mutableListOf<Throwable>()
    val events = ScanSessionEvents(gate, main) { reported.add(it) }
    val versions = mutableListOf<Double>()
    synchronized(gate) {
      events.observe { throw IllegalStateException("listener") }
      events.observe { versions.add(it.sourceVersion) }
      state.snapshot().pages.values.forEach(events::updatePage)
    }
    events.flush()
    main.drain()
    synchronized(gate) { events.close() }
    events.flush()
    main.drain()
    assertEquals(listOf(1.0, 2.0), versions)
    assertEquals(2, reported.size)
  }

  @Test fun dispatcherRejectionReleasesObserversWithoutLosingReadableState() {
    admit()
    val gate = Any()
    val reported = mutableListOf<Throwable>()
    val events = ScanSessionEvents(gate, Executor { throw java.util.concurrent.RejectedExecutionException() }) {
      reported.add(it)
    }
    var calls = 0
    val registration = synchronized(gate) {
      val registration = events.observe { calls++ }
      state.snapshot().pages.values.forEach(events::updatePage)
      registration
    }
    events.flush()
    assertEquals(1, reported.size)
    assertEquals(0, calls)
    assertEquals(1.0, synchronized(gate) { events.latest.sourceVersion }, 0.0)
    registration.unsubscribe()
    synchronized(gate) { events.close() }
    events.flush()
    assertEquals(1, reported.size)
  }}
