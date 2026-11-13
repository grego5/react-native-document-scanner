package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import com.margelo.nitro.documentscanner.processing.CornerModelStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Native admission and publication owner. Commands acknowledge settings immediately; work is keyed
 * FIFO metadata, never promise chains. All page transitions use lock; image actions run outside it.
 * Versioned transport is copied under that same lock and delivered in order outside it.
 */
internal class ScanSessionState(
  val resources: SessionResources,
  private val actions: Actions,
  dispatcher: Executor = ScanSessionMainOwner,
  report: (Throwable) -> Unit = ScanSessionMainOwner::report,
  private val failure: (Throwable, ScannerErrorCode) -> ScannerFailure = ::scannerFailure,
) {
  interface Actions : AutoCloseable {
    /** Called at admission: capture model selection, without decoding or acquiring an interpreter. */
    fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?): () -> DocumentAnalysis
    fun preparePinnedAnalysis(source: SessionResources.Lease, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?): () -> DocumentAnalysis =
      prepareAnalysis(source.resource, liveCorners, selection)
    fun renderSnapshot(source: SessionResources.Lease, key: ScanPage.RenderKey): ScanPage.Artifact = render(source.resource, key)
    fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact
    override fun close()
  }

  private val lock = Any()
  private val events = ScanSessionEvents(lock, dispatcher, report)
  private val pages = LinkedHashMap<String, ScanPage>()
  private val queue = resources.workOwner.queue
  private var closed = false
  private var publications = 0
  private val publicationsDrained = CompletableFuture<Unit>()
  private var completion: CompletableFuture<Unit>? = null
  // Work that has not reached the image worker yet, such as an open gallery picker, keyed by registration.
  private val closeCancellations = LinkedHashMap<Any, () -> Unit>()

  // One recent capture placeholder; displayed views retain their own bitmap reference.
  private var capturePlaceholder: Pair<String, android.graphics.Bitmap>? = null
  fun retainCapturePlaceholder(id: String, bitmap: android.graphics.Bitmap) = synchronized(lock) {
    if (!closed && pages.containsKey(id)) capturePlaceholder = id to bitmap
  }
  fun capturePlaceholder(id: String): android.graphics.Bitmap? = synchronized(lock) {
    capturePlaceholder?.takeIf { !closed && it.first == id && pages.containsKey(id) }?.second
  }
  fun releaseCapturePlaceholder(id: String) = synchronized(lock) {
    if (capturePlaceholder?.first == id) capturePlaceholder = null
  }

  fun metadata(): Map<String, ScanPage.Metadata> = synchronized(lock) { pages.mapValues { it.value.metadata() } }

  /** Native file consumers pin the immutable original; file paths never enter metadata. */
  fun leaseOriginal(id: String): SessionResources.Lease = synchronized(lock) { resources.lease(page(id).original) }

  fun sampleSource(id: String): SessionSampleSource = synchronized(lock) {
    val page = page(id)
    SessionSampleSource(resources.lease(page.original), page.analysis, page.model, page.liveCorners)
  }

  /** Atomically pin the currently displayed native artifact, including its thumbnail sidecar. */
  fun imageSource(id: String, original: Boolean = false): SessionPageSource =
    requireNotNull(resolveImageSource(id, original, false))

  fun thumbnailSource(id: String): SessionPageSource? = resolveImageSource(id, false, true)

  private fun resolveImageSource(id: String, original: Boolean, thumbnailOnly: Boolean): SessionPageSource? = synchronized(lock) {
    val page = page(id)
    val artifact = if (original) null else page.displayed
    val source = if (thumbnailOnly) artifact?.thumbnail ?: page.frame ?: return@synchronized null
      else if (original) page.original else artifact?.image ?: page.frame ?: page.original
    val leases = ArrayList<SessionResources.Lease>()
    try {
      leases.add(resources.lease(source))
      if (!thumbnailOnly) artifact?.thumbnail?.let { leases.add(resources.lease(it)) }
      SessionPageSource(source.id, leases, if (thumbnailOnly && artifact != null)
        android.net.Uri.fromFile(artifact.image.paths.destination).toString() else null)
    } catch (error: Throwable) {
      leases.forEach { it.close() }
      throw error
    }
  }

  fun hasPage(id: String): Boolean = synchronized(lock) { !closed && pages.containsKey(id) }

  fun snapshot(): ScanSessionSnapshot = synchronized(lock) { events.latest }

  fun observe(callback: (ScanSessionChange) -> Unit): ScanSessionEvents.Registration = transition {
    requireOpen()
    events.observe(callback)
  }

  /**
   * Runs [cancel] once, on the closing thread, if the session closes before the returned unregistration is called.
   * For operations that are not yet queued work. Throws `processor/disposed` when already closed.
   */
  fun onClose(cancel: () -> Unit): () -> Unit = synchronized(lock) {
    requireOpen()
    val key = Any()
    closeCancellations[key] = cancel
    return@synchronized { synchronized(lock) { closeCancellations.remove(key) } }
  }

  private inline fun <T> transition(action: () -> T): T {
    try { return synchronized(lock) { action() } } finally { events.flush() }
  }

  private fun changed(page: ScanPage) {
    val value = page.metadata()
    events.updatePage(ScanPageMetadata(value.id, value.requestedRevision.toDouble(), value.readyRevision?.toDouble(),
      value.analyzing, value.rendering, value.readyRevision == value.requestedRevision,
      value.analysis, value.corners, value.options, value.analysisFailure, value.renderFailure))
  }

  /** Takes ownership of already published immutable source tokens only on successful admission. Without [autoCrop] the
   * page keeps the full frame: it is still analyzed, so the editor's automatic preset and samples have the candidate,
   * but the candidate is never applied by itself. */
  fun admit(
    id: String,
    original: SessionResources.Resource,
    frame: SessionResources.Resource?,
    liveCorners: DocumentCorners?,
    options: DocumentRenderOptions?,
    selection: CornerModelStore.Selection? = CornerModelStore.selected,
    autoCrop: Boolean = true,
    /** True only when [options] already came from [DocumentRenderer.effectiveOptions], as a reservation's do. */
    normalized: Boolean = false,
  ) = transition {
    requireOpen()
    requireInput(id.isNotBlank() && !pages.containsKey(id)) { "Page ID must be nonempty and unique." }
    val effective = if (normalized) requireNotNull(options) else DocumentRenderer.effectiveOptions(options)
    requireInput(original.owner === resources && original.kind == SessionResources.Kind.ORIGINAL) { "Expected a session original." }
    requireInput(frame == null || (frame.owner === resources && frame.kind == SessionResources.Kind.FRAME)) { "Expected a session frame." }
    requireInput(pages.values.none { it.original === original || (frame != null && it.frame === frame) }) { "Source already belongs to a page." }
    resources.lease(original).close()
    frame?.let { resources.lease(it).close() }
    val analysisSource = resources.lease(original)
    val analyze = try { actions.preparePinnedAnalysis(analysisSource, liveCorners, selection) }
      catch (error: Throwable) { analysisSource.close(); throw error }
    val page = ScanPage(id, original, frame, effective, selection, liveCorners)
    page.explicitGeometry = !autoCrop
    pages[id] = page
    changed(page)
    queue.submit(resources.workOwner, ScannerWorkQueue.Kind.ANALYSIS, id,
      operation = {
        synchronized(lock) { if (page.exportAnalysisPins == 0) requireCurrent(page) }
        analyze()
      },
      onSuccess = { analysis ->
        analysisSource.close()
        page.analysisOutcome.complete(analysis)
        finishAnalysis(page, analysis, null)
      },
      onFailure = { error ->
        analysisSource.close()
        page.analysisOutcome.completeExceptionally(error)
        finishAnalysis(page, null, error)
      })
  }

  fun setDocumentCrop(id: String, corners: DocumentCorners) = transition {
    val page = page(id)
    val issue = NativeCornerGeometry.renderIssue(NativeCornerGeometry.coordinates(corners))
    requireInput(issue == null) { requireNotNull(issue) }
    // Even an explicit full-frame edit during analysis protects that choice from its candidate.
    page.explicitGeometry = true
    edit(page, corners, page.options)
  }

  fun setFilter(id: String, filter: DocumentFilter) = transition {
    val page = page(id)
    val options = DocumentRenderer.effectiveOptions(page.options.copy(filter = filter))
    edit(page, page.corners, options)
  }

  fun setShadowRemoval(id: String, value: Boolean) = transition {
    val page = page(id)
    edit(page, page.corners, page.options.copy(removeShadows = value))
  }

  private fun edit(page: ScanPage, corners: DocumentCorners, options: DocumentRenderOptions) {
    if (page.corners == corners && page.options == options && page.renderFailure == null) return
    page.corners = corners
    page.options = options
    page.requestedRevision++
    page.renderFailure = null
    invalidate(page)
    changed(page)
    if (!page.analyzing) requestRender(page)
  }

  private fun finishAnalysis(page: ScanPage, analysis: DocumentAnalysis?, error: Throwable?) = transition {
    if (!current(page)) return@transition
    page.analyzing = false
    page.analysis = analysis
    page.analysisFailure = error?.let { failure(it, ScannerErrorCode.ANALYSIS_FAILED) }
    val candidate = analysis?.candidate?.corners
    if (!page.explicitGeometry && candidate != null &&
      NativeCornerGeometry.renderIssue(NativeCornerGeometry.coordinates(candidate)) == null && page.corners != candidate) {
      page.corners = candidate
      page.requestedRevision++
      invalidate(page)
    }
    changed(page)
    requestRender(page)
  }

  private fun requestRender(page: ScanPage) {
    val revision = page.requestedRevision
    val key = page.key()
    page.cache[key]?.let {
      publish(page, revision, it)
      return
    }
    queue.submit(resources.workOwner, ScannerWorkQueue.Kind.RENDER, page.id,
      operation = {
        val cached = synchronized(lock) {
          requireCurrent(page)
          if (page.requestedRevision != revision) throw ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)
          page.cache[key]
        }
        cached ?: actions.render(page.original, key)
      },
      onSuccess = { artifact ->
        transition {
          if (!current(page) || !compatible(page.key(), key)) {
            retire(artifact)
          } else {
            val previous = page.cache.put(key, artifact)
            if (previous != null && previous !== artifact && previous !== page.displayed) retire(previous)
            if (page.requestedRevision == revision) publish(page, revision, artifact)
          }
        }
      },
      onFailure = { error ->
        transition {
          if (current(page) && page.requestedRevision == revision) {
            page.renderFailure = failure(error, ScannerErrorCode.RENDER_FAILED)
            changed(page)
          }
        }
      },
      onDiscard = ::retire)
  }

  /** Geometry/output configuration determines compatibility; filters and shadows each have one slot. */
  private fun compatible(a: ScanPage.RenderKey, b: ScanPage.RenderKey) =
    a.corners == b.corners && a.options.copy(filter = b.options.filter, removeShadows = b.options.removeShadows) == b.options

  private fun invalidate(page: ScanPage) {
    val iterator = page.cache.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (!compatible(page.key(), entry.key)) {
        iterator.remove()
        if (entry.value !== page.displayed) retire(entry.value)
      }
    }
  }

  private fun publish(page: ScanPage, revision: Long, artifact: ScanPage.Artifact) {
    val previous = page.displayed
    page.displayed = artifact
    page.readyRevision = revision
    page.renderFailure = null
    changed(page)
    if (previous != null && previous !== artifact && page.cache.values.none { it === previous }) retire(previous)
  }

  private fun retire(artifact: ScanPage.Artifact) {
    resources.remove(artifact.image)
    resources.remove(artifact.thumbnail)
  }

  fun setPageOrder(ids: List<String>) = transition {
    requireOpen()
    events.setPageOrder(ids)
  }

  fun removePage(id: String) = transition {
    val page = page(id)
    pages.remove(id)
    releaseCapturePlaceholder(id)
    events.removePage(id)
    (page.cache.values + listOfNotNull(page.displayed)).distinct().forEach(::retire)
    resources.remove(page.original)
    page.frame?.let { resources.remove(it) }
  }

  /** Snapshot admission is one state-lock operation, including all source/cache pins and input order. */
  fun exportSnapshot(requestedIds: List<String>?): SessionExportSnapshot = synchronized(lock) {
    requireOpen()
    val ids = requestedIds ?: events.latest.pageIds.toList()
    requireInput(ids.isNotEmpty()) { "No pages provided." }
    val selected = ids.toList().map(::page) // Validate every ID before pinning or creating output.
    val entries = ArrayList<SessionExportSnapshot.Page>()
    try {
      for (page in selected) {
        val source = resources.lease(page.original)
        try {
          page.renderFailure?.let { throw ScannerException(it.code) }
          if (!page.explicitGeometry && page.analysisFailure != null) throw ScannerException(ScannerErrorCode.ANALYSIS_FAILED)
          val artifact = if (page.analyzing) null else page.cache[page.key()]
          val image = artifact?.let { resources.lease(it.image) }
          val automatic = !page.explicitGeometry && page.analyzing
          val entry = SessionExportSnapshot.Page(page.id, page.requestedRevision, page.key(),
            automatic, page.analysisOutcome, source, image,
            releaseAnalysis = { if (automatic) synchronized(lock) { page.exportAnalysisPins-- } })
          if (automatic) page.exportAnalysisPins++
          entries.add(entry)
        } catch (error: Throwable) { source.close(); throw error }
      }
      SessionExportSnapshot(entries, resources, actions)
    } catch (error: Throwable) { entries.forEach { it.close() }; throw error }
  }

  fun checkExportOpen() = synchronized(lock) { requireOpen() }

  /** Claim before close; filesystem work runs unlocked. Close awaits every claimed transaction. */
  fun <T> publishExport(action: () -> T): T {
    synchronized(lock) { requireOpen(); publications++ }
    try { return action() } finally {
      val drained = synchronized(lock) { publications--; closed && publications == 0 }
      if (drained) publicationsDrained.complete(Unit)
    }
  }

  fun close(): CompletableFuture<Unit> {
    val cancellations = transition {
      completion?.let { return it }
      closed = true
      val pending = closeCancellations.values.toList()
      closeCancellations.clear()
      pages.clear()
      capturePlaceholder = null
      events.close()
      // Install before cancellation callbacks can reenter this state.
      completion = CompletableFuture()
      pending
    }
    // Unlocked: a cancellation may settle its caller's promise or reenter this state.
    cancellations.forEach { runCatching(it) }
    val result = requireNotNull(completion)
    if (synchronized(lock) { publications == 0 }) publicationsDrained.complete(Unit)
    val modelClosed = queue.close(resources.workOwner).thenRun { actions.close() }
    resources.close().thenCombine(modelClosed) { _, _ -> Unit }
      .thenCombine(publicationsDrained) { _, _ -> Unit }.whenComplete { _, error ->
      if (error == null) result.complete(Unit) else result.completeExceptionally(error)
    }
    return result
  }

  private fun page(id: String): ScanPage {
    requireOpen()
    return pages[id] ?: throw InvalidInputException("Unknown page ID.")
  }

  private fun current(page: ScanPage) = !closed && pages[page.id] === page
  private fun requireCurrent(page: ScanPage) {
    if (!current(page)) throw ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)
  }
  private fun requireOpen() {
    if (closed) throw ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
  }
}
