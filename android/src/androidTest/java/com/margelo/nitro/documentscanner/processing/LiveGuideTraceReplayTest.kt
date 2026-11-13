package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad

class LiveGuideTraceReplayTest {
  @Before fun initializeNative() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
  }
  @Test
  fun trackedHeartbeatsBridgeTheModelIntervalWithoutCountingAsInference() {
    val page = rect(.2, .2, .8, .8)
    val source = trace(listOf(page to 0L, page to 33L, page to 66L, page to 300L, page to 600L, page to 900L, page to 1066L))
    val trace = source.copy(observations = source.observations.mapIndexed { index, row ->
      if (index in 3..5) row.copy(guideEligible = false, trackingOnly = true) else row
    })
    val output = LiveGuideTraceReplay.replay(trace)
    assertTrue(output.drop(2).all { it.accepted != null && !it.expiredBeforeInput })
    assertEquals(GuideDecision.REPLACED, output.last().decision)
    val summary = LiveGuideTraceReplay.summarize("adaptive", trace, output, "n/a")
    assertTrue(summary.contains("trackingHeartbeats=3"))
    assertTrue(summary.contains("cadenceMs n=3"))
  }
  @Test
  fun legacyTraceIsMarkedAsADifferentAlgorithm() {
    val trace = syntheticTrace()
    assertEquals(1, trace.config.algorithmVersion)
    assertNotEquals(GuideStabilizerConfig(), trace.config)
    val outputs = LiveGuideTraceReplay.replay(trace)
    assertNotEquals(emptyList<String>(), LiveGuideTraceReplay.mismatches(trace, outputs))
    assertEquals(outputs, LiveGuideTraceReplay.replay(trace))
  }

  @Test
  fun provisionalFocusRowsNeverBecomeGuideConfirmationSamples() {
    val page = rect(.2, .2, .8, .8)
    val raw = trace(listOf(page to 0L, page to 66L, page to 132L, page to 198L, page to 264L, page to 330L))
    val recording = raw.copy(observations = raw.observations.mapIndexed { i, row -> row.copy(guideEligible = i >= 3) })
    val replayed = LiveGuideTraceReplay.replay(recording)
    assertTrue(replayed.take(3).all { it.accepted == null && it.chainLength == 0 })
    assertTrue(replayed.drop(3).all { it.accepted != null })
    assertEquals(GuideDecision.REPLACED, replayed.last().decision)
    assertEquals(3L, replayed.last().acceptanceId)
  }

  @Test
  fun expiryFollowedByImmediateReacquisition() {
    val page = rect(.2,.2,.8,.8)
    val other = rect(.05,.1,.45,.6)
    val outputs = LiveGuideTraceReplay.replay(trace(listOf(page to 0L, other to 500L)))
    assertEquals(GuideDecision.ACQUIRED,outputs.last().decision)
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
    val off = LiveGuideTrace(GuideStabilizerConfig(), observations, frames = frames)
    val offSummary = LiveGuideTraceReplay.summarize("flow off", off, LiveGuideTraceReplay.replay(off), "n/a")
    assertTrue(offSummary, offSummary.contains("flow n/a (estimation off)"))
    assertTrue(offSummary, offSummary.contains("deliveredFrameMs n=4 median=33.00 p95=34.00 max=34.00 fps=30.30"))
    assertTrue(offSummary, offSummary.contains("inferredAnalysisMs(flow+preprocessing+inference+stabilizer) n=3 median=23.00 p95=23.00"))
    // The same rows with flow processing on the middle inference add only that frame's estimator time.
    val estimate = FlowMotionEstimate(FlowMotionStatus.MEASURED, 10.0, 320, 33_000_000, 66_000_000, 1.0, 20)
    val on = off.copy(frames = frames.map {
      if (it.sensorTimestampNs == 66_000_000L) it.copy(flow = estimate, flowProcessingNs = 2_000_000) else it
    })
    val onSummary = LiveGuideTraceReplay.summarize("flow on", on, LiveGuideTraceReplay.replay(on), "n/a")
    assertTrue(onSummary, onSummary.contains("deliveredFrameMs n=4 median=33.00"))
    assertTrue(onSummary, onSummary.contains("inferredAnalysisMs(flow+preprocessing+inference+stabilizer) n=3 median=23.00 p95=25.00"))
  }

  /** Optional current-format device trace replay, selected with instrumentation argument liveGuideTrace. */
  @Test
  fun recordedTracesReplay() {
    val path = InstrumentationRegistry.getArguments().getString("liveGuideTrace")
    assumeTrue("Pass instrumentation liveGuideTrace=<device file or directory> to replay recorded traces", path != null)
    val root = File(path!!)
    val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.extension == "tsv" }.sortedBy { it.path }.toList()
    else listOf(root)
    assertTrue("No .tsv traces under $root", files.isNotEmpty())
    val current = GuideStabilizerConfig()
    val failures = mutableListOf<String>()
    val summary = buildString {
      appendLine("currentConfig=$current")
      for (file in files) {
        val trace = LiveGuideTraceFormat.read(file.readText(Charsets.UTF_8))
        val outputs = LiveGuideTraceReplay.replay(trace, current)
        val strict = when {
          trace.config != current -> "skipped: recorded config differs"
          else -> {
            val mismatches = LiveGuideTraceReplay.mismatches(trace, outputs)
            if (mismatches.isNotEmpty()) failures.add("${file.name}: ${mismatches.take(5).joinToString("; ")}")
            if (mismatches.isEmpty()) "passed" else "failed (${mismatches.size} rows)"
          }
        }
        append(LiveGuideTraceReplay.summarize(file.name, trace, outputs, strict))

      }
    }
    val reportDirectory = File(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "live-guide-trace-reports").apply { mkdirs() }
    File(reportDirectory, "summary.txt").writeText(summary, Charsets.UTF_8)
    println(summary)
    assertEquals(emptyList<String>(), failures)
  }

  /** Raw candidates at sensor = arrival milliseconds; recorded output fields are placeholders. */
  private fun trace(frames: List<Pair<DocumentCorners, Long>>) = LiveGuideTrace(GuideStabilizerConfig(), frames.map { (corners, ms) ->
    LiveDocumentObservation(1, ms * 1_000_000, ms * 1_000_000, 1000, 1000, 0, 0, 1000, 1000, 0, corners, .9,
      GuideTrackingStatus.SEARCHING, GuideDecision.ACQUIRING, 0, false, null, 0, 0, 0.0, 0.0, 0)
  })

  private fun rect(left: Double, top: Double, right: Double, bottom: Double) = DocumentCorners(
    DocumentPoint(left, top), DocumentPoint(right, top), DocumentPoint(right, bottom), DocumentPoint(left, bottom),
  )

  private fun syntheticTrace(): LiveGuideTrace {
    val text = InstrumentationRegistry.getInstrumentation().context.assets.open("live-guide-trace/synthetic.tsv").bufferedReader().use { it.readText() }
    return LiveGuideTraceFormat.read(text)
  }
}
