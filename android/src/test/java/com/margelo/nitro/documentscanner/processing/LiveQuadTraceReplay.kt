package com.margelo.nitro.documentscanner.processing

import kotlin.math.ceil

/** Deterministic JVM replay of recorded raw live candidates, using recorded times only. */
internal object LiveQuadTraceReplay {
  /** Feeds a fresh stabilizer, resetting where the recorded generation or frame geometry changes, as the detector does.
   */
  fun replay(
    trace: LiveQuadTrace,
    config: LiveQuadStabilizerConfig = LiveQuadStabilizerConfig(),
  ): List<LiveQuadOutput> {
    val stabilizer = LiveQuadStabilizer(config)
    var previous: LiveQuadObservation? = null
    return trace.observations.map { o ->
      if (previous != null && !sameHistory(previous!!, o)) stabilizer.reset()
      previous = o
      val input = LiveQuadInput(o.rawQuad.takeIf { o.guideEligible || o.trackingOnly }, o.width, o.height,
        o.sensorTimestampNs, o.arrivalNs)
      if (o.trackingOnly) stabilizer.retainTracked(input) ?: stabilizer.update(input.copy(quad = null))
      else stabilizer.update(input)
    }
  }

  /** Row differences against the recording. Ids are compared after the recording's starting id offset,
   * because the detector keeps ids monotonic from before the trace began.
   */
  fun mismatches(trace: LiveQuadTrace, outputs: List<LiveQuadOutput>): List<String> {
    if (outputs.isEmpty()) return emptyList()
    val idOffset = trace.observations.first().acceptanceId - outputs.first().acceptanceId
    return trace.observations.zip(outputs).withIndex().mapNotNull { (index, pair) ->
      val (recorded, replayed) = pair
      val differences = listOfNotNull(
        "status ${recorded.status}/${replayed.status}".takeIf { recorded.status != replayed.status },
        "decision ${recorded.decision}/${replayed.decision}".takeIf { recorded.decision != replayed.decision },
        "accepted".takeIf { recorded.accepted != replayed.accepted },
        "acceptanceId ${recorded.acceptanceId}/${replayed.acceptanceId + idOffset}"
          .takeIf { recorded.acceptanceId != replayed.acceptanceId + idOffset },
      )
      if (differences.isEmpty()) null else "row ${index + 1}: ${differences.joinToString(", ")}"
    }
  }

  fun summarize(name: String, trace: LiveQuadTrace, outputs: List<LiveQuadOutput>, strict: String): String {
    val rows = trace.observations
    val decisions = LiveQuadDecision.entries.associateWith { decision -> outputs.count { it.decision == decision } }
    val statuses = LiveQuadStatus.entries.associateWith { status -> outputs.count { it.status == status } }

    val acquisitionMs = mutableListOf<Double>()
    val reacquisitionMs = mutableListOf<Double>()
    val holdMs = mutableListOf<Double>()
    var holdsRecovered = 0
    var holdsExpired = 0
    var holdsReset = 0
    var rejectedChallenges = 0
    var isolatedRejected = 0
    var confirmedChallenges = 0
    var firstCandidateNs: Long? = null
    var lossNs: Long? = null
    var holdStartNs: Long? = null
    var acceptedDeadlineNs = 0L // Deadline of the quad accepted as of the previous row.
    var challengeLength = 0
    var previousChain = 0
    var previousStatus = LiveQuadStatus.SEARCHING
    for ((row, output) in rows.zip(outputs)) {
      val arrival = row.arrivalNs
      // Expiry happens at the previous deadline, before this row is evaluated, even when this row
      // immediately acquires again; the guide was absent from the deadline until this arrival.
      if (output.expiredBeforeInput) {
        holdStartNs?.let { start ->
          holdMs.add((acceptedDeadlineNs - start) / 1e6)
          holdsExpired++
        }
        holdStartNs = null
        if (challengeLength > 0) {
          rejectedChallenges++
          if (challengeLength == 1) isolatedRejected++
          challengeLength = 0
        }
        lossNs = acceptedDeadlineNs
        firstCandidateNs = null
        previousStatus = LiveQuadStatus.SEARCHING
      }
      // Challenger chains: a chain built while a quad stays accepted, ending in replacement or rejection.
      if (challengeLength > 0) {
        if (output.decision == LiveQuadDecision.REPLACED) {
          confirmedChallenges++
          challengeLength = 0
        } else if (output.chainLength <= previousChain || output.status == LiveQuadStatus.SEARCHING) {
          rejectedChallenges++
          if (challengeLength == 1) isolatedRejected++
          challengeLength = 0
        } else {
          challengeLength = output.chainLength
        }
      }
      if (challengeLength == 0 && output.status == LiveQuadStatus.HELD && output.chainLength > 0) {
        challengeLength = output.chainLength
      }
      // Holds: from the first held row until recovery, expiry (above) or a history reset.
      if (holdStartNs == null && output.status == LiveQuadStatus.HELD) {
        holdStartNs = arrival
      } else if (holdStartNs != null && output.status != LiveQuadStatus.HELD) {
        holdMs.add((arrival - holdStartNs) / 1e6)
        if (output.status == LiveQuadStatus.TRACKING) holdsRecovered++ else holdsReset++
        holdStartNs = null
      }
      if (output.status != LiveQuadStatus.SEARCHING) acceptedDeadlineNs = output.holdDeadlineNs
      // Acquisition from the first usable candidate; reacquisition from the loss of an accepted quad.
      if (previousStatus != LiveQuadStatus.SEARCHING && output.status == LiveQuadStatus.SEARCHING) lossNs = arrival
      if (output.status == LiveQuadStatus.SEARCHING && output.chainLength > 0 && firstCandidateNs == null) firstCandidateNs = arrival
      if (output.status == LiveQuadStatus.SEARCHING && output.chainLength == 0) firstCandidateNs = null
      if (output.decision == LiveQuadDecision.ACQUIRED) {
        acquisitionMs.add((arrival - (firstCandidateNs ?: arrival)) / 1e6)
        lossNs?.let { reacquisitionMs.add((arrival - it) / 1e6) }
        firstCandidateNs = null
        lossNs = null
      }
      previousChain = output.chainLength
      previousStatus = output.status
    }

    val inferredRows = rows.filterNot { it.trackingOnly }
    val cadenceMs = inferredRows.zipWithNext().filter { (a, b) -> a.generation == b.generation }
      .map { (a, b) -> (b.sensorTimestampNs - a.sensorTimestampNs) / 1e6 }.filter { it > 0 }
    val stabilizerNs = rows.map { it.stabilizerNs.toDouble() }
    val accepted = (statuses[LiveQuadStatus.TRACKING] ?: 0) + (statuses[LiveQuadStatus.HELD] ?: 0)
    val start = rows.firstOrNull()?.arrivalNs ?: 0L
    return buildString {
      appendLine("trace $name")
      appendLine("  rows=${rows.size} captures=${trace.captures.size} durationS=${fmt(((rows.lastOrNull()?.arrivalNs ?: start) - start) / 1e9)}")
      appendLine("  provisionalFocusRows=${inferredRows.count { !it.guideEligible }} trackingHeartbeats=${rows.count { it.trackingOnly }}")
      appendLine("  recordedConfig=${trace.config}")
      appendLine("  strict=$strict")
      appendLine("  decisions ${decisions.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
      appendLine("  statuses ${statuses.entries.joinToString(" ") { "${it.key}=${it.value}" }} heldShareOfAccepted=${
        if (accepted == 0) "n/a" else fmt(100.0 * (statuses[LiveQuadStatus.HELD] ?: 0) / accepted) + "%"}")
      appendLine("  acquisitionMs ${stats(acquisitionMs)}")
      appendLine("  reacquisitionMs ${stats(reacquisitionMs)}")
      appendLine("  replacements=${decisions[LiveQuadDecision.REPLACED]}")
      appendLine("  challengers rejected=$rejectedChallenges isolatedSingleFrame=$isolatedRejected confirmed=$confirmedChallenges")
      appendLine("  holds recovered=$holdsRecovered expired=$holdsExpired reset=$holdsReset durationMs ${stats(holdMs)}")
      appendLine("  cadenceMs ${stats(cadenceMs)}${cadenceMs.takeIf { it.isNotEmpty() }?.let { " fps=${fmt(1000 / percentile(it, .5))}" } ?: ""}")
      appendLine("  recordedStabilizerNs ${stats(stabilizerNs)}")
      val fits = trace.frames.mapNotNull { it.flow }.filter { it.transformProcessingNs != null }
      appendLine("  guideMotion attempts=${fits.size} fitted=${fits.count { it.transform != null }} " +
        "fitMs ${stats(fits.map { it.transformProcessingNs!! / 1e6 })}")
      for (capture in trace.captures) {
        appendLine("  capture atS=${fmt((capture.arrivalNs - start) / 1e9)} quad=${capture.quad != null} score=${capture.score} jpeg=${capture.width}x${capture.height}")
      }
      if (trace.frames.isNotEmpty() || trace.captures.isNotEmpty()) append(motionSummary(trace))
    }
  }

  /** How frames spread over autofocus states, gyro-estimated rotation blur and optical-flow blur, and each capture's
   * blur. Flow blur is `speed * exposure / sourceWidth` in analysis-buffer pixels, a fraction of the frame width like
   * the gyro's; tracker and JPEG pixels are never compared directly.
   */
  private fun motionSummary(trace: LiveQuadTrace): String = buildString {
    val frames = trace.frames
    val optics = trace.camera?.takeIf { it.realtimeTimestamps }?.optics
    val gyro = if (optics != null && trace.gyro.isNotEmpty()) GyroHistory(trace.gyro.size).apply {
      trace.gyro.forEach { add(it.sensorNs, it.x, it.y, it.z) }
    } else null
    fun relative(group: List<LiveFrameSample>) = "n=${group.size}"
    appendLine("  frames n=${frames.size} analyzed=${frames.count { it.analyzed }} noResult=${frames.count { it.exposureNs == null }} " +
      "oisOn=${frames.count { it.opticalStabilization == true }} oisOff=${frames.count { it.opticalStabilization == false }} " +
      "cameraOis=${trace.camera?.opticalStabilizationModes}")
    appendLine("  frameExposureMs ${stats(frames.mapNotNull { it.exposureNs?.div(1e6) })}")
    for ((state, group) in frames.groupBy { it.afState }.toSortedMap(nullsFirst<Int>())) {
      appendLine("  af ${state?.let { AF_STATES.getOrNull(it) ?: "$it" } ?: "unknown"} ${relative(group)}")
    }
    if (gyro == null || optics == null) appendLine("  gyroBlur n/a (no gyro rows or no REALTIME camera)")
    val blurPercent = frames.associateWith { f ->
      val exposure = f.exposureNs ?: return@associateWith null
      if (gyro == null || optics == null) return@associateWith null
      motionBlur(gyro, optics, f.sensorTimestampNs - GYRO_MARGIN_NS,
        f.sensorTimestampNs + (f.rollingShutterSkewNs ?: 0L) + exposure + GYRO_MARGIN_NS, exposure)?.times(100)
    }
    for ((low, high) in BLUR_BINS_PERCENT.zipWithNext()) {
      val group = frames.filter { f -> blurPercent[f]?.let { it >= low && it < high } == true }
      appendLine("  gyroBlur ${fmt(low)}-${if (high.isInfinite()) "inf" else fmt(high)}% ${relative(group)}")
    }
    // Common to flow-on and flow-off runs so the two compare: every delivered analysis frame, which the 60 ms inference
    // limiter hides from cadenceMs, and per-inference time with absent flow processing counted as zero.
    val deliveredMs = frames.zipWithNext { a, b -> (b.sensorTimestampNs - a.sensorTimestampNs) / 1e6 }
    appendLine("  deliveredFrameMs ${stats(deliveredMs)}" +
      (deliveredMs.takeIf { it.isNotEmpty() }?.let { " fps=${fmt(1000 / percentile(it, .5))}" } ?: ""))
    val flowNs = frames.associate { it.sensorTimestampNs to (it.flowProcessingNs ?: 0L) }
    appendLine("  inferredAnalysisMs(flow+preprocessing+inference+stabilizer) ${stats(trace.observations.filterNot { it.trackingOnly }.map {
      ((flowNs[it.sensorTimestampNs] ?: 0L) + it.stabilizerNs) / 1e6 + it.preprocessingMs + it.inferenceMs
    })}")
    val flowFrames = frames.filter { it.flow != null }
    if (flowFrames.isEmpty()) appendLine("  flow n/a (estimation off)")
    else {
      val flowPercent = flowFrames.associateWith { f -> f.exposureNs?.let { flowBlurPercent(f.flow, it) } }
      appendLine("  flow n=${flowFrames.size} " + FlowMotionStatus.entries.joinToString(" ") { status ->
        "${status.name.lowercase()}=${flowFrames.count { it.flow!!.status == status }}"
      })
      appendLine("  flowProcessingMs ${stats(flowFrames.mapNotNull { it.flowProcessingNs?.div(1e6) })}")
      appendLine("  flowExposureToPublishMs ${stats(flowFrames.mapNotNull { it.flowAgeNs?.div(1e6) })}")
      val measured = flowFrames.map { it.flow!! }.filter { it.status == FlowMotionStatus.MEASURED }
      appendLine("  flowIntervalMs ${stats(measured.map { (it.toSensorNs - it.fromSensorNs) / 1e6 })}")
      appendLine("  flowTracks ${stats(measured.map { it.validTracks.toDouble() })} " +
        "confidence ${stats(flowFrames.map { it.flow!!.confidence })}")
      for ((low, high) in BLUR_BINS_PERCENT.zipWithNext()) {
        val group = flowFrames.filter { f -> flowPercent[f]?.let { it >= low && it < high } == true }
        appendLine("  flowBlur ${fmt(low)}-${if (high.isInfinite()) "inf" else fmt(high)}% ${relative(group)}")
      }
      // Disagreements across the capture limit: flow alone sees translation and approach, the gyro alone rotation
      // the tracker lost.
      val both = flowFrames.filter { flowPercent[it] != null && blurPercent[it] != null }
      val limit = BLUR_BINS_PERCENT[1]
      val flowOnly = both.filter { flowPercent[it]!! >= limit && blurPercent[it]!! < limit }
      val gyroOnly = both.filter { flowPercent[it]!! < limit && blurPercent[it]!! >= limit }
      appendLine("  flowVsGyro n=${both.size} agree=${both.size - flowOnly.size - gyroOnly.size} " +
        "flowOnly(${relative(flowOnly)}) gyroOnly(${relative(gyroOnly)}) " +
        "gyroBlurWhileFlowUnknown=${flowFrames.count { it.flow!!.speedPxPerSecond == null && (blurPercent[it] ?: 0.0) >= limit }}")
    }
    for (capture in trace.captures) {
      val tap = capture.tapSensorNs ?: continue
      val fired = capture.firedSensorNs
      val exposure = capture.exposureNs
      // A separate retrospective estimate using JPEG exposure; this was not the gate's decision value.
      val blur = if (fired == null || exposure == null || gyro == null || optics == null) null
        else motionBlur(gyro, optics, fired - 50_000_000L, fired, exposure)?.times(100)
      appendLine("  captureBlur saved=${capture.saved} delayMs=${fired?.let { fmt((it - tap) / 1e6) } ?: "-"} " +
        "gateEvaluationNs=${capture.gateEvaluationNs ?: "-"} " +
        "gateExposureMs=${capture.gateExposureNs?.let { fmt(it / 1e6) } ?: "-"} " +
        "gateBlur=${capture.gateBlur?.let { fmt(it * 100) + "%" } ?: "-"} gateSource=${capture.gateSource ?: "-"} " +
        "jpegExposureMs=${exposure?.let { fmt(it / 1e6) } ?: "-"} jpegExposureGyroBlur=${blur?.let { fmt(it) + "%" } ?: "-"} " +
        "flow=${capture.flow?.status ?: "-"} flowPublishedAgoMs=${capture.flowAgeNs?.let { fmt(it / 1e6) } ?: "-"} " +
        "flowSourceAgeMs=${capture.flowSourceAgeNs?.let { fmt(it / 1e6) } ?: "-"} " +
        "flowLostRegions=${capture.flow?.lostRegions ?: "-"} " +
        "jpegExposureFlowBlur=${exposure?.let { flowBlurPercent(capture.flow, it) }?.let { fmt(it) + "%" } ?: "-"}")
    }
  }

  private val AF_STATES = listOf("INACTIVE", "PASSIVE_SCAN", "PASSIVE_FOCUSED", "ACTIVE_SCAN", "FOCUSED_LOCKED",
    "NOT_FOCUSED_LOCKED", "PASSIVE_UNFOCUSED")
  /** Brackets the capture (0.2%) and acquisition (0.6%) gate defaults. */
  private val BLUR_BINS_PERCENT = listOf(0.0, 0.2, 0.6, 1.5, Double.POSITIVE_INFINITY)

  /** Optical-flow blur over [exposureNs] in percent of the analysis frame width; null when the speed is unknown. */
  private fun flowBlurPercent(flow: FlowMotionEstimate?, exposureNs: Long): Double? {
    val speed = flow?.speedPxPerSecond ?: return null
    return 100 * speed * exposureNs * 1e-9 / flow.sourceWidth
  }

  private fun sameHistory(a: LiveQuadObservation, b: LiveQuadObservation): Boolean =
    a.generation == b.generation && a.cropLeft == b.cropLeft && a.cropTop == b.cropTop && a.cropRight == b.cropRight &&
      a.cropBottom == b.cropBottom && a.rotationDegrees == b.rotationDegrees

  private fun stats(values: List<Double>): String = if (values.isEmpty()) "n=0" else
    "n=${values.size} median=${fmt(percentile(values, .5))} p95=${fmt(percentile(values, .95))} max=${fmt(values.max())}"

  private fun percentile(values: List<Double>, quantile: Double): Double {
    val sorted = values.sorted()
    return sorted[(ceil(quantile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
  }

  private fun fmt(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)
}
