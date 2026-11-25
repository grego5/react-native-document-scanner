package com.margelo.nitro.documentscanner

import android.os.SystemClock
import com.margelo.nitro.documentscanner.processing.LiveDocumentObservation
import com.margelo.nitro.documentscanner.processing.LiveFrameSample
import com.margelo.nitro.documentscanner.processing.LiveGuideTraceObserver

/** One camera view's bounded, pixel-free diagnostic history, which [ScannerDiagnostics] exports. Producers never
 * perform disk I/O. */
internal class StandaloneCameraTrace : LiveGuideTraceObserver {
  private data class Entry(val elapsedMs: Long, val fields: String)
  private val entries = ArrayDeque<Entry>()

  @Synchronized
  fun record(kind: String, sensorNs: Long?, fields: String) {
    val now = SystemClock.elapsedRealtime()
    prune(now)
    if (entries.size >= 4096) entries.removeFirst()
    entries.addLast(Entry(now, "$kind\t${sensorNs ?: ""}\t$fields"))
  }

  @Synchronized
  fun snapshot(): List<String> {
    prune(SystemClock.elapsedRealtime())
    return entries.map { "${it.elapsedMs}\t${it.fields}" }
  }

  private fun prune(now: Long) {
    while (entries.firstOrNull()?.let { now - it.elapsedMs > 15_000 } == true) entries.removeFirst()
  }

  override fun onFrame(sample: LiveFrameSample) {
    record("analysis", sample.sensorTimestampNs,
      "inference=${sample.analyzed} reason=${sample.inferenceReason} flow=${sample.flow?.status} " +
        "reliable=${sample.flowReliable} lostRegions=${sample.flow?.lostRegions} doubt=${sample.adaptiveDoubt} blur=${sample.blur} " +
        "edges=${sample.edges?.let { "${it.median},${it.rows ?: "-"},${it.columns ?: "-"},${it.edges}" }} " +
        "edgeArea=${if (sample.edgesOnPage) "page" else "guide"}")
  }

  override fun onObservation(observation: LiveDocumentObservation) {
    record("guide", observation.sensorTimestampNs,
      "status=${observation.status} decision=${observation.decision} score=${observation.rawScore} " +
        "eligible=${observation.guideEligible} expired=${observation.expiredBeforeInput} " +
        "trackingOnly=${observation.trackingOnly} retained=${observation.flowRetained} id=${observation.acceptanceId}")
  }

  override fun onGuideExpired(acceptanceId: Long) {
    record("guide-expired", null, "id=$acceptanceId")
  }
}
