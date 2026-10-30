package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveQuadTraceReplayTest {
  @Test
  fun trackedHeartbeatsBridgeTheModelIntervalWithoutCountingAsInference() {
    val page = rect(.2, .2, .8, .8)
    val source = trace(listOf(page to 0L, page to 33L, page to 66L, page to 300L, page to 600L, page to 900L, page to 1066L))
    val trace = source.copy(observations = source.observations.mapIndexed { index, row ->
      if (index in 3..5) row.copy(guideEligible = false, trackingOnly = true) else row
    })
    val output = LiveQuadTraceReplay.replay(trace)
    assertTrue(output.drop(2).all { it.accepted != null && !it.expiredBeforeInput })
    assertEquals(LiveQuadDecision.REPLACED, output.last().decision)
    val summary = LiveQuadTraceReplay.summarize("adaptive", trace, output, "n/a")
    assertTrue(summary.contains("trackingHeartbeats=3"))
    assertTrue(summary.contains("cadenceMs n=3"))
  }
  @Test
  fun legacyTraceIsMarkedAsADifferentAlgorithm() {
    val trace = syntheticTrace()
    assertEquals(1, trace.config.algorithmVersion)
    assertNotEquals(LiveQuadStabilizerConfig(), trace.config)
    val outputs = LiveQuadTraceReplay.replay(trace)
    assertNotEquals(emptyList<String>(), LiveQuadTraceReplay.mismatches(trace, outputs))
    assertEquals(outputs, LiveQuadTraceReplay.replay(trace))
  }

  @Test
  fun provisionalFocusRowsNeverBecomeGuideConfirmationSamples() {
    val page = rect(.2, .2, .8, .8)
    val raw = trace(listOf(page to 0L, page to 66L, page to 132L, page to 198L, page to 264L, page to 330L))
    val recording = raw.copy(observations = raw.observations.mapIndexed { i, row -> row.copy(guideEligible = i >= 3) })
    val replayed = LiveQuadTraceReplay.replay(recording)
    assertTrue(replayed.take(3).all { it.accepted == null && it.chainLength == 0 })
    assertTrue(replayed.drop(3).all { it.accepted != null })
    assertEquals(LiveQuadDecision.REPLACED, replayed.last().decision)
    assertEquals(3L, replayed.last().acceptanceId)
  }

  @Test
  fun expiryFollowedByImmediateReacquisition() {
    val page = rect(.2,.2,.8,.8)
    val other = rect(.05,.1,.45,.6)
    val outputs = LiveQuadTraceReplay.replay(trace(listOf(page to 0L, other to 500L)))
    assertEquals(LiveQuadDecision.ACQUIRED,outputs.last().decision)
    assertTrue(outputs.last().expiredBeforeInput)
    assertEquals(other,outputs.last().accepted)
  }

  @Test
  fun flowOffTraceStillReportsDeliveredCadenceAndAnalysisTime() {
    val page = rect(.2, .2, .8, .8)
    val observations = trace(listOf(page to 0L, page to 66L, page to 133L)).observations.map {
      it.copy(preprocessingMs = 2.0, inferenceMs = 20.0, stabilizerNs = 1_000_000)
    }
    val frames = listOf(0L, 33L, 66L, 100L, 133L).map {
      LiveFrameSample(it * 1_000_000, it * 1_000_000, null, null, null, null, null, analyzed = it % 66 == 0L)
    }
    val off = LiveQuadTrace(LiveQuadStabilizerConfig(), observations, frames = frames)
    val offSummary = LiveQuadTraceReplay.summarize("flow off", off, LiveQuadTraceReplay.replay(off), "n/a")
    assertTrue(offSummary, offSummary.contains("flow n/a (estimation off)"))
    assertTrue(offSummary, offSummary.contains("deliveredFrameMs n=4 median=33.00 p95=34.00 max=34.00 fps=30.30"))
    assertTrue(offSummary, offSummary.contains("inferredAnalysisMs(flow+preprocessing+inference+stabilizer) n=3 median=23.00 p95=23.00"))
    // The same rows with flow processing on the middle inference add only that frame's estimator time.
    val estimate = FlowMotionEstimate(FlowMotionStatus.MEASURED, 10.0, 320, 33_000_000, 66_000_000, 1.0, 20)
    val on = off.copy(frames = frames.map {
      if (it.sensorTimestampNs == 66_000_000L) it.copy(flow = estimate, flowProcessingNs = 2_000_000) else it
    })
    val onSummary = LiveQuadTraceReplay.summarize("flow on", on, LiveQuadTraceReplay.replay(on), "n/a")
    assertTrue(onSummary, onSummary.contains("deliveredFrameMs n=4 median=33.00"))
    assertTrue(onSummary, onSummary.contains("inferredAnalysisMs(flow+preprocessing+inference+stabilizer) n=3 median=23.00 p95=25.00"))
  }

  /** Replays `-PliveQuadTrace=<file or directory>` and writes `build/reports/live-quad-trace/summary.txt`. */
  @Test
  fun recordedTracesReplay() {
    val path = System.getProperty("liveQuadTrace")
    assumeTrue("Pass -PliveQuadTrace=<file or directory> to replay recorded traces", path != null)
    val root = File(path!!)
    val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.extension == "tsv" }.sortedBy { it.path }.toList()
    else listOf(root)
    assertTrue("No .tsv traces under $root", files.isNotEmpty())
    val current = LiveQuadStabilizerConfig()
    val failures = mutableListOf<String>()
    val summary = buildString {
      appendLine("currentConfig=$current")
      for (file in files) {
        val trace = LiveQuadTraceFormat.read(file.readText(Charsets.UTF_8))
        val outputs = LiveQuadTraceReplay.replay(trace, current)
        val strict = when {
          trace.config != current -> "skipped: recorded config differs"
          else -> {
            val mismatches = LiveQuadTraceReplay.mismatches(trace, outputs)
            if (mismatches.isNotEmpty()) failures.add("${file.name}: ${mismatches.take(5).joinToString("; ")}")
            if (mismatches.isEmpty()) "passed" else "failed (${mismatches.size} rows)"
          }
        }
        append(LiveQuadTraceReplay.summarize(file.name, trace, outputs, strict))

      }
    }
    val reportDirectory = File(System.getProperty("liveQuadTraceReport") ?: "build/reports/live-quad-trace").apply { mkdirs() }
    File(reportDirectory, "summary.txt").writeText(summary, Charsets.UTF_8)
    println(summary)
    assertEquals(emptyList<String>(), failures)
  }

  /** Raw candidates at sensor = arrival milliseconds; recorded output fields are placeholders. */
  private fun trace(frames: List<Pair<DocumentQuad, Long>>) = LiveQuadTrace(LiveQuadStabilizerConfig(), frames.map { (quad, ms) ->
    LiveQuadObservation(1, ms * 1_000_000, ms * 1_000_000, 1000, 1000, 0, 0, 1000, 1000, 0, quad, .9,
      LiveQuadStatus.SEARCHING, LiveQuadDecision.ACQUIRING, 0, false, null, 0, 0, 0.0, 0.0, 0)
  })

  private fun rect(left: Double, top: Double, right: Double, bottom: Double) = DocumentQuad(
    DocumentPoint(left, top), DocumentPoint(right, top), DocumentPoint(right, bottom), DocumentPoint(left, bottom),
  )

  private fun syntheticTrace(): LiveQuadTrace {
    val text = requireNotNull(javaClass.classLoader!!.getResource("live-quad-trace/synthetic.tsv")).readText(Charsets.UTF_8)
    return LiveQuadTraceFormat.read(text)
  }
}
