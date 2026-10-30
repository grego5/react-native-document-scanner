package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import kotlin.math.abs

internal data class LiveQuadStabilizerConfig(
  val algorithmVersion: Int = 2,
  val minFrameAreaFraction: Double = 0.03,
  val holdNs: Long = 400_000_000,

)

/** [width] and [height] are upright frame dimensions; [arrivalNs] is monotonic processing time. */
internal data class LiveQuadInput(
  val quad: DocumentQuad?,
  val width: Int,
  val height: Int,
  val sensorTimestampNs: Long,
  val arrivalNs: Long,
)

internal enum class LiveQuadStatus { SEARCHING, TRACKING, HELD }

internal enum class LiveQuadDecision {
  ACQUIRING, ACQUIRED, AGREED, CHALLENGING, REPLACED, MISSING, TOO_SMALL, INVALID, NOT_RECTANGLE, TOO_TILTED, GAP_RESTART,
  DUPLICATE_TIMESTAMP, TIMESTAMP_BACKWARD,
}

/** [accepted] is non-null only for tracking and held outlines. */
internal data class LiveQuadOutput(
  val status: LiveQuadStatus,
  val decision: LiveQuadDecision,
  val accepted: DocumentQuad?,
  val acceptanceId: Long,
  val holdDeadlineNs: Long,
  val chainLength: Int,
  val expiredBeforeInput: Boolean,
)

/** Decides which live candidates the guide may display. Displays valid model coordinates unchanged.
 * Single-threaded and unsynchronized; confined to the live analysis executor.
 */
internal class LiveQuadStabilizer(config: LiveQuadStabilizerConfig = LiveQuadStabilizerConfig()) {
  /** Changing it starts fresh history; acceptance ids keep rising, since the main thread drops ids it already expired. */
  var config = config
    set(value) {
      if (value == field) return
      field = value
      reset()
    }
  private var accepted: DocumentQuad? = null
  private var acceptedSensorNs = 0L
  private var acceptanceId = 0L
  private var holdDeadlineNs = 0L
  private var lastTimestampNs: Long? = null

  /** Called only on reliable still frames. A frame gap cannot resurrect an already expired guide. */
  fun retainTracked(input: LiveQuadInput): LiveQuadOutput? {
    if (accepted == null) return null
    if (input.arrivalNs >= holdDeadlineNs) return null
    val predicted = input.quad ?: return null
    if (shapeDecision(predicted, input) != null) return null
    accepted = predicted
    acceptedSensorNs = input.sensorTimestampNs
    acceptanceId++
    holdDeadlineNs = input.arrivalNs + config.holdNs
    return output(LiveQuadDecision.AGREED, tracking = true, expired = false)
  }

  fun update(input: LiveQuadInput): LiveQuadOutput {
    val previousTimestampNs = lastTimestampNs
    if (previousTimestampNs != null && input.sensorTimestampNs == previousTimestampNs) {
      return output(LiveQuadDecision.DUPLICATE_TIMESTAMP, tracking = false, expired = false)
    }
    val backward = previousTimestampNs != null && input.sensorTimestampNs < previousTimestampNs
    if (backward) reset()
    lastTimestampNs = input.sensorTimestampNs

    val expired = accepted != null && input.arrivalNs >= holdDeadlineNs
    if (expired) {
      accepted = null
    }

    val decision = evaluate(input)
    val tracking = decision == LiveQuadDecision.AGREED || decision == LiveQuadDecision.ACQUIRED ||
      decision == LiveQuadDecision.REPLACED
    return output(if (backward) LiveQuadDecision.TIMESTAMP_BACKWARD else decision, tracking, expired)
  }

  fun reset() {
    accepted = null
    holdDeadlineNs = 0L
    lastTimestampNs = null
  }

  private fun evaluate(input: LiveQuadInput): LiveQuadDecision {
    val candidate = input.quad
    val invalid = if (candidate == null) LiveQuadDecision.MISSING else shapeDecision(candidate, input)
    if (invalid != null) {
      // Only a successful retainTracked on this very frame can keep a missing/invalid model result alive.
      if (acceptedSensorNs != input.sensorTimestampNs) accepted = null
      return invalid
    }
    val hadOutline = accepted != null
    accept(candidate!!, input)
    return if (!hadOutline) LiveQuadDecision.ACQUIRED else LiveQuadDecision.REPLACED
  }

  private fun accept(quad: DocumentQuad, input: LiveQuadInput) {
    accepted = quad
    acceptedSensorNs = input.sensorTimestampNs
    acceptanceId++
    holdDeadlineNs = input.arrivalNs + config.holdNs
  }

  private fun output(decision: LiveQuadDecision, tracking: Boolean, expired: Boolean): LiveQuadOutput {
    val quad = accepted
    val status = when {
      quad == null -> LiveQuadStatus.SEARCHING
      tracking -> LiveQuadStatus.TRACKING
      else -> LiveQuadStatus.HELD
    }
    return LiveQuadOutput(
      status, decision, quad, acceptanceId, holdDeadlineNs, if (tracking) 1 else 0, expired,
    )
  }

  /** Basic area and normalized convex-corner validity. */
  private fun shapeDecision(quad: DocumentQuad, frame: LiveQuadInput): LiveQuadDecision? {
    if (area(quad, frame) < config.minFrameAreaFraction * frame.width * frame.height) return LiveQuadDecision.TOO_SMALL
    val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
    return if (DocCornerSession.validNormalizedQuad(points)) null else LiveQuadDecision.INVALID
  }

  /** Shoelace area in upright pixels. */
  private fun area(quad: DocumentQuad, frame: LiveQuadInput): Double {
    val twiceArea = cross(quad.topLeft, quad.topRight) + cross(quad.topRight, quad.bottomRight) +
      cross(quad.bottomRight, quad.bottomLeft) + cross(quad.bottomLeft, quad.topLeft)
    return abs(twiceArea) / 2 * frame.width * frame.height
  }

  private fun cross(a: DocumentPoint, b: DocumentPoint): Double = a.x * b.y - b.x * a.y
}
