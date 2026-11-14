package com.margelo.nitro.documentscanner

/** Mutable only under ScanSessionState's lock. No presentation order or saved-edit transaction. */
internal class ScanPage(
  val id: String,
  val original: SessionResources.Resource,
  val frame: SessionResources.Resource?,
  var options: DocumentRenderOptions,
  val liveCorners: DocumentCorners? = null,
) {
  data class RenderKey(val corners: DocumentCorners, val options: DocumentRenderOptions)
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
    val corners: DocumentCorners,
    val options: DocumentRenderOptions,
    val analysisFailure: ScannerFailure?,
    val renderFailure: ScannerFailure?,
  )

  var corners = fullFrame()
  var explicitGeometry = false
  var requestedRevision = 1L
  var readyRevision: Long? = null
  var exportAnalysisPins = 0
  val analysisOutcome = java.util.concurrent.CompletableFuture<DocumentAnalysis>()
  /** The selection its submitted analysis uses, kept for sample metadata. */
  var model: com.margelo.nitro.documentscanner.processing.CornerModelStore.Selection? = null
  /** An analysis job is submitted and unsettled. */
  var analyzing = false
  /** An import waiting for the shared model; no analysis is submitted yet. */
  var awaitingModel = false
  /** The first render follows analysis. False for an import that displays its full frame while waiting. */
  var analysisGatesRender = true
  var analysis: DocumentAnalysis? = null
  var analysisFailure: ScannerFailure? = null
  var renderFailure: ScannerFailure? = null
  val cache = LinkedHashMap<RenderKey, Artifact>()
  var displayed: Artifact? = null

  fun key() = RenderKey(corners, options)
  val renderWaitsForAnalysis get() = analyzing && analysisGatesRender
  fun metadata() = Metadata(id, requestedRevision, readyRevision, analyzing || awaitingModel,
    !renderWaitsForAnalysis && readyRevision != requestedRevision && renderFailure == null,
    analysis, corners, options, analysisFailure, renderFailure)

  companion object {
    fun fullFrame() = DocumentCorners(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 0.0),
      DocumentPoint(1.0, 1.0), DocumentPoint(0.0, 1.0))
  }
}

/** A sample admission retains original bytes and the page's own analysis/model provenance. */
internal data class SessionSampleSource(
  val original: SessionResources.Lease,
  val analysis: DocumentAnalysis?,
  val model: com.margelo.nitro.documentscanner.processing.CornerModelStore.Selection?,
  val liveCorners: DocumentCorners?,
)
