package com.margelo.nitro.documentscanner

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
import com.margelo.nitro.documentscanner.processing.LiveDocumentObservation
import com.margelo.nitro.documentscanner.processing.LiveFrameSample
import com.margelo.nitro.documentscanner.processing.LiveGuideTraceObserver
import java.io.IOException

/** One camera view's bounded, pixel-free diagnostic history. Producers never perform disk I/O. */
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
        "reliable=${sample.flowReliable} lostRegions=${sample.flow?.lostRegions} doubt=${sample.adaptiveDoubt} blur=${sample.blur}")
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

  companion object {
    @androidx.annotation.RequiresApi(29)
    fun save(context: Context, rows: List<String>): String {
      val name = "scanner-trace-${System.currentTimeMillis()}.tsv"
      val resolver = context.contentResolver
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "text/tab-separated-values")
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Could not create the trace in Downloads")
      try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the trace")
        stream.bufferedWriter().use { writer ->
          writer.appendLine("elapsed_ms\tevent\tsensor_ns\tdetails")
          rows.forEach { writer.appendLine(it) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1) { "Could not publish the trace" }
        return name
      } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
      }
    }
  }
}
