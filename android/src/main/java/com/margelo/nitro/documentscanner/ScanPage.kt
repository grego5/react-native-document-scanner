package com.margelo.nitro.documentscanner

/** Mutable only under ScanSessionState's lock. No presentation order or saved-edit transaction. */
internal class ScanPage(
  val id: String,
  val original: SessionResources.Resource,
  val frame: SessionResources.Resource?,
  var options: DocumentRenderOptions,
  val model: com.margelo.nitro.documentscanner.processing.QuadModelStore.Selection?,
  val liveQuad: DocumentQuad? = null,
) {
  data class RenderKey(val quad: DocumentQuad, val options: DocumentRenderOptions)
  data class Artifact(
    val image: SessionResources.Resource,
    val thumbnail: SessionResources.Resource,
    val width: Double,
    val height: Double,
    val validContentPolygon: List<DocumentPoint>,
  )

  /** File tokens and render caches stay internal; metadata contains no paths or artifact objects. */
  data class Metadata(
    val id: String,
    val requestedRevision: Long,
    val readyRevision: Long?,
    val analyzing: Boolean,
    val rendering: Boolean,
    val analysis: DocumentAnalysis?,
    val quad: DocumentQuad,
    val options: DocumentRenderOptions,
    val analysisFailure: ScannerFailure?,
    val renderFailure: ScannerFailure?,
  )

  var quad = fullFrame()
  var explicitGeometry = false
  var requestedRevision = 1L
  var readyRevision: Long? = null
  var exportAnalysisPins = 0
  val analysisOutcome = java.util.concurrent.CompletableFuture<DocumentAnalysis>()
  var analyzing = true
  var analysis: DocumentAnalysis? = null
  var analysisFailure: ScannerFailure? = null
  var renderFailure: ScannerFailure? = null
  val cache = LinkedHashMap<RenderKey, Artifact>()
  var displayed: Artifact? = null

  fun key() = RenderKey(quad, options)
  fun metadata() = Metadata(id, requestedRevision, readyRevision, analyzing,
    !analyzing && readyRevision != requestedRevision && renderFailure == null,
    analysis, quad, options, analysisFailure, renderFailure)

  companion object {
    fun fullFrame() = DocumentQuad(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 0.0),
      DocumentPoint(1.0, 1.0), DocumentPoint(0.0, 1.0))
  }
}

/** A sample admission retains original bytes and the page's own analysis/model provenance. */
internal data class SessionSampleSource(
  val original: SessionResources.Lease,
  val analysis: DocumentAnalysis?,
  val model: com.margelo.nitro.documentscanner.processing.QuadModelStore.Selection?,
  val liveQuad: DocumentQuad?,
)
