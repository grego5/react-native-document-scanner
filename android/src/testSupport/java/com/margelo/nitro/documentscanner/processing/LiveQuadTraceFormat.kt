package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad

/** Shutter tap arrival time plus the authoritative post-capture JPEG analysis, in upright JPEG dimensions. Tap and
 * `takePicture` times are on the gyroscope clock; [firedSensorNs] is null when a gate rejected the shot, and then
 * [saved] is false. [exposureNs] comes from the JPEG's EXIF.
 * [gateSource] is the estimate the gate judged; [flow] is the image-motion estimate it read, [flowAgeNs] the time
 * since its publication and [flowSourceAgeNs] its source frame's age.
 */
internal data class LiveQuadCaptureEvent(
  val arrivalNs: Long,
  val quad: DocumentQuad?,
  val score: Double?,
  val width: Int,
  val height: Int,
  val tapSensorNs: Long? = null,
  val firedSensorNs: Long? = null,
  val exposureNs: Long? = null,
  val saved: Boolean = true,
  val gateEvaluationNs: Long? = null,
  val gateExposureNs: Long? = null,
  val gateBlur: Double? = null,
  val gateSource: MotionSource? = null,
  val flow: FlowMotionEstimate? = null,
  val flowAgeNs: Long? = null,
  val flowSourceAgeNs: Long? = null,
)

/** Calibrated gyroscope rates in rad/s on the device's natural axes, timed on the sensor clock. */
internal data class LiveQuadGyroSample(val sensorNs: Long, val x: Double, val y: Double, val z: Double)

/** The recording back camera: the optics compensation uses, whether its timestamps share the gyroscope clock, the
 * raw HAL intrinsics (fx, fy, cx, cy, s) with the pre-correction array (left, top, right, bottom) they refer to, and
 * its available LENS_OPTICAL_STABILIZATION modes.
 */
internal data class LiveQuadCameraRecord(
  val optics: LiveCameraOptics,
  val realtimeTimestamps: Boolean,
  val intrinsics: List<Double>? = null,
  val preCorrection: List<Int>? = null,
  val opticalStabilizationModes: List<Int>? = null,
)

internal data class LiveQuadTrace(
  val config: LiveQuadStabilizerConfig,
  val observations: List<LiveQuadObservation>,
  val captures: List<LiveQuadCaptureEvent> = emptyList(),
  val gyro: List<LiveQuadGyroSample> = emptyList(),
  val camera: LiveQuadCameraRecord? = null,
  val frames: List<LiveFrameSample> = emptyList(),
)

/** UTF-8 TSV using only the current schema, with no versioning or compatibility parsing. Doubles round-trip exactly.
 * Frame rows follow observations; gyro rows are last because their clock differs from arrivalNs.
 * A capture's sensorNs is its tap and `analyzed` whether a photo was saved.
 */
internal object LiveQuadTraceFormat {
  const val HEADER_LINE = "# live-quad-trace"
  private const val CONFIG_PREFIX = "# config "
  private const val CAMERA_PREFIX = "# camera "
  private const val MISSING = "-"
  private val COLUMNS = listOf(
    "kind", "generation", "sensorNs", "arrivalNs", "width", "height", "cropLeft", "cropTop", "cropRight", "cropBottom",
    "rotation", "raw", "score", "status", "decision", "chain", "expired", "accepted", "acceptanceId", "deadlineNs",
    "preprocessingMs", "inferenceMs", "stabilizerNs", "rates",
    "exposureNs", "skewNs", "afState", "ois", "focusDiopters", "analyzed", "firedNs",
    "gateEvaluationNs", "gateExposureNs", "gateBlur", "gateSource",
    "flowStatus", "flowSpeed", "flowConfidence", "flowTracks", "flowLostRegions", "flowFromNs", "flowToNs", "flowSourceWidth", "flowAgeNs",
    "flowSourceAgeNs", "flowProcessingNs",
    "flowTransform", "flowTransformProcessingNs",
    "guideEligible",
    "trackingOnly",
  )

  fun write(trace: LiveQuadTrace, out: Appendable) {
    val config = trace.config
    out.append(HEADER_LINE).append('\n')
    out.append(CONFIG_PREFIX).append("algorithmVersion=${config.algorithmVersion} minFrameAreaFraction=${config.minFrameAreaFraction} " +
      "holdNs=${config.holdNs}").append('\n')
    trace.camera?.let {
      val optics = it.optics
      out.append(CAMERA_PREFIX).append("focalLengthPx=${optics.focalLengthPx} activeWidth=${optics.activeWidth} " +
        "activeHeight=${optics.activeHeight} sensorOrientation=${optics.sensorOrientation} " +
        "focalSource=${if (optics.calibrated) "intrinsic" else "lens"} " +
        "timestampSource=${if (it.realtimeTimestamps) "REALTIME" else "UNKNOWN"} " +
        "intrinsics=${it.intrinsics?.joinToString(",") ?: MISSING} " +
        "preCorrection=${it.preCorrection?.joinToString(",") ?: MISSING} " +
        "ois=${it.opticalStabilizationModes?.joinToString(",") ?: MISSING}").append('\n')
    }
    out.append(COLUMNS.joinToString("\t")).append('\n')
    var captureIndex = 0
    fun writeCapturesUntil(arrivalNs: Long) {
      while (captureIndex < trace.captures.size && trace.captures[captureIndex].arrivalNs <= arrivalNs) {
        out.append(captureRow(trace.captures[captureIndex++])).append('\n')
      }
    }
    for (observation in trace.observations) {
      writeCapturesUntil(observation.arrivalNs)
      out.append(observationRow(observation)).append('\n')
    }
    writeCapturesUntil(Long.MAX_VALUE)
    for (frame in trace.frames) out.append(frameRow(frame)).append('\n')
    for (sample in trace.gyro) out.append(gyroRow(sample)).append('\n')
  }

  fun read(text: String): LiveQuadTrace {
    val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
    require(lines.size >= 3 && lines[0] == HEADER_LINE) { "Not a live quad trace." }
    require(lines[1].startsWith(CONFIG_PREFIX)) { "Missing trace config line." }
    val values = settings(lines[1].removePrefix(CONFIG_PREFIX))
    val config = LiveQuadStabilizerConfig(
      algorithmVersion = values["algorithmVersion"]?.toInt() ?: 1,
      minFrameAreaFraction = values.getValue("minFrameAreaFraction").toDouble(),
      holdNs = values.getValue("holdNs").toLong(),
    )
    val camera = lines[2].takeIf { it.startsWith(CAMERA_PREFIX) }?.let {
      val geometry = settings(it.removePrefix(CAMERA_PREFIX))
      LiveQuadCameraRecord(
        LiveCameraOptics(
          focalLengthPx = geometry.getValue("focalLengthPx").toDouble(),
          activeWidth = geometry.getValue("activeWidth").toInt(),
          activeHeight = geometry.getValue("activeHeight").toInt(),
          sensorOrientation = geometry.getValue("sensorOrientation").toInt(),
          calibrated = geometry.getValue("focalSource") == "intrinsic",
        ),
        realtimeTimestamps = geometry.getValue("timestampSource") == "REALTIME",
        intrinsics = geometry.getValue("intrinsics").takeIf { value -> value != MISSING }
          ?.split(',')?.map { value -> value.toDouble() },
        preCorrection = geometry.getValue("preCorrection").takeIf { value -> value != MISSING }
          ?.split(',')?.map { value -> value.toInt() },
        opticalStabilizationModes = geometry.getValue("ois").takeIf { value -> value != MISSING }
          ?.split(',')?.map { value -> value.toInt() },
      )
    }
    val header = if (camera == null) 2 else 3
    require(lines.getOrNull(header)?.split('\t') == COLUMNS) { "Unexpected trace columns." }
    val observations = mutableListOf<LiveQuadObservation>()
    val captures = mutableListOf<LiveQuadCaptureEvent>()
    val gyro = mutableListOf<LiveQuadGyroSample>()
    val frames = mutableListOf<LiveFrameSample>()
    for ((index, line) in lines.drop(header + 1).withIndex()) {
      val fields = line.split('\t')
      require(fields.size == COLUMNS.size) { "Trace row ${index + 1} has ${fields.size} fields." }
      val row = COLUMNS.zip(fields).toMap()
      when (row.getValue("kind")) {
        "obs" -> observations.add(LiveQuadObservation(
          generation = row.getValue("generation").toLong(),
          sensorTimestampNs = row.getValue("sensorNs").toLong(),
          arrivalNs = row.getValue("arrivalNs").toLong(),
          width = row.getValue("width").toInt(),
          height = row.getValue("height").toInt(),
          cropLeft = row.getValue("cropLeft").toInt(),
          cropTop = row.getValue("cropTop").toInt(),
          cropRight = row.getValue("cropRight").toInt(),
          cropBottom = row.getValue("cropBottom").toInt(),
          rotationDegrees = row.getValue("rotation").toInt(),
          rawQuad = parseQuad(row.getValue("raw")),
          rawScore = parseOptionalDouble(row.getValue("score")),
          status = LiveQuadStatus.valueOf(row.getValue("status")),
          decision = LiveQuadDecision.valueOf(row.getValue("decision")),
          chainLength = row.getValue("chain").toInt(),
          expiredBeforeInput = row.getValue("expired").toBooleanStrict(),
          accepted = parseQuad(row.getValue("accepted")),
          acceptanceId = row.getValue("acceptanceId").toLong(),
          holdDeadlineNs = row.getValue("deadlineNs").toLong(),
          preprocessingMs = row.getValue("preprocessingMs").toDouble(),
          inferenceMs = row.getValue("inferenceMs").toDouble(),
          stabilizerNs = row.getValue("stabilizerNs").toLong(),
          guideEligible = row.getValue("guideEligible").toBooleanStrict(),
          trackingOnly = row.getValue("trackingOnly").toBooleanStrict(),
        ))
        "capture" -> captures.add(LiveQuadCaptureEvent(
          arrivalNs = row.getValue("arrivalNs").toLong(),
          quad = parseQuad(row.getValue("raw")),
          score = parseOptionalDouble(row.getValue("score")),
          width = row.getValue("width").toInt(),
          height = row.getValue("height").toInt(),
          tapSensorNs = parseOptionalLong(row.getValue("sensorNs")),
          firedSensorNs = parseOptionalLong(row.getValue("firedNs")),
          exposureNs = parseOptionalLong(row.getValue("exposureNs")),
          saved = row.getValue("analyzed").toBooleanStrict(),
          gateEvaluationNs = parseOptionalLong(row.getValue("gateEvaluationNs")),
          gateExposureNs = parseOptionalLong(row.getValue("gateExposureNs")),
          gateBlur = parseOptionalDouble(row.getValue("gateBlur")),
          gateSource = row.getValue("gateSource").takeIf { it != MISSING }?.let(MotionSource::valueOf),
          flow = parseFlow(row),
          flowAgeNs = parseOptionalLong(row.getValue("flowAgeNs")),
          flowSourceAgeNs = parseOptionalLong(row.getValue("flowSourceAgeNs")),
        ))
        "frame" -> frames.add(LiveFrameSample(
          sensorTimestampNs = row.getValue("sensorNs").toLong(),
          arrivalNs = row.getValue("arrivalNs").toLong(),
          exposureNs = parseOptionalLong(row.getValue("exposureNs")),
          rollingShutterSkewNs = parseOptionalLong(row.getValue("skewNs")),
          afState = parseOptionalLong(row.getValue("afState"))?.toInt(),
          opticalStabilization = row.getValue("ois").takeIf { it != MISSING }?.toBooleanStrict(),
          focusDiopters = parseOptionalDouble(row.getValue("focusDiopters")),
          analyzed = row.getValue("analyzed").toBooleanStrict(),
          flow = parseFlow(row),
          flowAgeNs = parseOptionalLong(row.getValue("flowAgeNs")),
          flowProcessingNs = parseOptionalLong(row.getValue("flowProcessingNs")),
        ))
        "gyro" -> {
          val rates = row.getValue("rates").split(',').map { it.toDouble() }
          require(rates.size == 3) { "A gyro row needs three rates." }
          gyro.add(LiveQuadGyroSample(row.getValue("sensorNs").toLong(), rates[0], rates[1], rates[2]))
        }
        else -> throw IllegalArgumentException("Unknown trace row kind ${row["kind"]}.")
      }
    }
    return LiveQuadTrace(config, observations, captures, gyro, camera, frames)
  }

  private fun settings(line: String): Map<String, String> = line.split(' ').associate { entry ->
    val (key, value) = entry.split('=', limit = 2)
    key to value
  }

  private fun observationRow(o: LiveQuadObservation): String {
    val fields = listOf(
      "obs", o.generation, o.sensorTimestampNs, o.arrivalNs, o.width, o.height, o.cropLeft, o.cropTop, o.cropRight,
      o.cropBottom, o.rotationDegrees, formatQuad(o.rawQuad), o.rawScore ?: MISSING, o.status, o.decision, o.chainLength,
      o.expiredBeforeInput, formatQuad(o.accepted), o.acceptanceId, o.holdDeadlineNs, o.preprocessingMs, o.inferenceMs,
      o.stabilizerNs,
    )
    return COLUMNS.mapIndexed { index, column ->
      if (index < fields.size) fields[index] else when (column) {
        "guideEligible" -> o.guideEligible
        "trackingOnly" -> o.trackingOnly
        else -> MISSING
      }
    }.joinToString("\t")
  }

  private fun captureRow(c: LiveQuadCaptureEvent): String = COLUMNS.joinToString("\t") { column ->
    when (column) {
      "kind" -> "capture"
      "arrivalNs" -> c.arrivalNs.toString()
      "width" -> c.width.toString()
      "height" -> c.height.toString()
      "raw" -> formatQuad(c.quad)
      "score" -> c.score?.toString() ?: MISSING
      "sensorNs" -> c.tapSensorNs?.toString() ?: MISSING
      "firedNs" -> c.firedSensorNs?.toString() ?: MISSING
      "exposureNs" -> c.exposureNs?.toString() ?: MISSING
      "analyzed" -> c.saved.toString()
      "gateEvaluationNs" -> c.gateEvaluationNs?.toString() ?: MISSING
      "gateExposureNs" -> c.gateExposureNs?.toString() ?: MISSING
      "gateBlur" -> c.gateBlur?.toString() ?: MISSING
      "gateSource" -> c.gateSource?.name ?: MISSING
      "flowAgeNs" -> c.flowAgeNs?.toString() ?: MISSING
      "flowSourceAgeNs" -> c.flowSourceAgeNs?.toString() ?: MISSING
      else -> flowField(column, c.flow) ?: MISSING
    }
  }

  private fun frameRow(f: LiveFrameSample): String = COLUMNS.joinToString("\t") { column ->
    when (column) {
      "kind" -> "frame"
      "sensorNs" -> f.sensorTimestampNs.toString()
      "arrivalNs" -> f.arrivalNs.toString()
      "exposureNs" -> f.exposureNs?.toString() ?: MISSING
      "skewNs" -> f.rollingShutterSkewNs?.toString() ?: MISSING
      "afState" -> f.afState?.toString() ?: MISSING
      "ois" -> f.opticalStabilization?.toString() ?: MISSING
      "focusDiopters" -> f.focusDiopters?.toString() ?: MISSING
      "analyzed" -> f.analyzed.toString()
      "flowAgeNs" -> f.flowAgeNs?.toString() ?: MISSING
      "flowProcessingNs" -> f.flowProcessingNs?.toString() ?: MISSING
      else -> flowField(column, f.flow) ?: MISSING
    }
  }

  /** The estimate's own columns; null for any other column or without an estimate. */
  private fun flowField(column: String, flow: FlowMotionEstimate?): String? = flow?.let {
    when (column) {
      "flowStatus" -> it.status.name
      "flowSpeed" -> it.speedPxPerSecond?.toString() ?: MISSING
      "flowConfidence" -> it.confidence.toString()
      "flowTracks" -> it.validTracks.toString()
      "flowLostRegions" -> it.lostRegions.toString()
      "flowFromNs" -> it.fromSensorNs.toString()
      "flowToNs" -> it.toSensorNs.toString()
      "flowSourceWidth" -> it.sourceWidth.toString()
      "flowTransform" -> it.transform?.let { t ->
        // Similarities keep the original six coefficients; perspective terms are appended only when present.
        (listOf(t.xx, t.xy, t.yx, t.yy, t.tx, t.ty) + if (t.px == 0.0 && t.py == 0.0) emptyList() else listOf(t.px, t.py))
          .joinToString(",")
      } ?: MISSING
      "flowTransformProcessingNs" -> it.transformProcessingNs?.toString() ?: MISSING
      else -> null
    }
  }

  private fun parseFlow(row: Map<String, String>): FlowMotionEstimate? {
    val status = row.getValue("flowStatus").takeIf { it != MISSING } ?: return null
    return FlowMotionEstimate(
      FlowMotionStatus.valueOf(status), parseOptionalDouble(row.getValue("flowSpeed")),
      row.getValue("flowSourceWidth").toInt(), row.getValue("flowFromNs").toLong(), row.getValue("flowToNs").toLong(),
      row.getValue("flowConfidence").toDouble(), row.getValue("flowTracks").toInt(),
      row.getValue("flowLostRegions").toInt(),
      row.getValue("flowTransform").takeIf { it != MISSING }?.split(',')?.map(String::toDouble)?.let {
        require(it.size == 6 || it.size == 8) { "A flow transform needs six or eight coefficients." }
        FlowTransform(it[0], it[1], it[2], it[3], it[4], it[5], it.getOrElse(6) { .0 }, it.getOrElse(7) { .0 })
      }, parseOptionalLong(row.getValue("flowTransformProcessingNs")),
    )
  }

  private fun gyroRow(g: LiveQuadGyroSample): String = COLUMNS.joinToString("\t") { column ->
    when (column) {
      "kind" -> "gyro"
      "sensorNs" -> g.sensorNs.toString()
      "rates" -> "${g.x},${g.y},${g.z}"
      else -> MISSING
    }
  }

  private fun formatQuad(quad: DocumentQuad?): String = quad?.let {
    listOf(it.topLeft, it.topRight, it.bottomRight, it.bottomLeft).joinToString(",") { point -> "${point.x},${point.y}" }
  } ?: MISSING

  private fun parseQuad(value: String): DocumentQuad? {
    if (value == MISSING) return null
    val numbers = value.split(',').map { it.toDouble() }
    require(numbers.size == 8) { "A quad needs eight coordinates." }
    return DocumentQuad(
      DocumentPoint(numbers[0], numbers[1]), DocumentPoint(numbers[2], numbers[3]),
      DocumentPoint(numbers[4], numbers[5]), DocumentPoint(numbers[6], numbers[7]),
    )
  }

  private fun parseOptionalDouble(value: String): Double? = if (value == MISSING) null else value.toDouble()

  private fun parseOptionalLong(value: String): Long? = if (value == MISSING) null else value.toLong()
}
