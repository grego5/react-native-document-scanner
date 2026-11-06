package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import kotlin.math.abs

/** Decides which live candidates the guide may display. Displays valid model coordinates unchanged.
 * Single-threaded and unsynchronized; confined to the live analysis executor.
 */
internal class ReferenceGuideStabilizer(config: GuideStabilizerConfig = GuideStabilizerConfig()) {
  /** Changing it starts fresh history; acceptance ids keep rising, since the main thread drops ids it already expired. */
  var config = config
    set(value) {
      if (value == field) return
      field = value
      reset()
    }
  private var accepted: DocumentCorners? = null
  private var acceptedSensorNs = 0L
  private var acceptanceId = 0L
  private var holdDeadlineNs = 0L
  private var lastTimestampNs: Long? = null

  /** Called only on reliable still frames. A frame gap cannot resurrect an already expired guide. */
  fun retainTracked(input: GuideInput): GuideOutput? {
    if (accepted == null) return null
    if (input.arrivalNs >= holdDeadlineNs) return null
    val predicted = input.corners ?: return null
    if (shapeDecision(predicted, input) != null) return null
    accepted = predicted
    acceptedSensorNs = input.sensorTimestampNs
    acceptanceId++
    holdDeadlineNs = input.arrivalNs + config.holdNs
    return output(GuideDecision.AGREED, tracking = true, expired = false)
  }

  fun update(input: GuideInput): GuideOutput {
    val previousTimestampNs = lastTimestampNs
    if (previousTimestampNs != null && input.sensorTimestampNs == previousTimestampNs) {
      return output(GuideDecision.DUPLICATE_TIMESTAMP, tracking = false, expired = false)
    }
    val backward = previousTimestampNs != null && input.sensorTimestampNs < previousTimestampNs
    if (backward) reset()
    lastTimestampNs = input.sensorTimestampNs

    val expired = accepted != null && input.arrivalNs >= holdDeadlineNs
    if (expired) {
      accepted = null
    }

    val decision = evaluate(input)
    val tracking = decision == GuideDecision.AGREED || decision == GuideDecision.ACQUIRED ||
      decision == GuideDecision.REPLACED
    return output(if (backward) GuideDecision.TIMESTAMP_BACKWARD else decision, tracking, expired)
  }

  fun reset() {
    accepted = null
    holdDeadlineNs = 0L
    lastTimestampNs = null
  }

  private fun evaluate(input: GuideInput): GuideDecision {
    val candidate = input.corners
    val invalid = if (candidate == null) GuideDecision.MISSING else shapeDecision(candidate, input)
    if (invalid != null) {
      // Only a successful retainTracked on this very frame can keep a missing/invalid model result alive.
      if (acceptedSensorNs != input.sensorTimestampNs) accepted = null
      return invalid
    }
    val hadOutline = accepted != null
    accept(candidate!!, input)
    return if (!hadOutline) GuideDecision.ACQUIRED else GuideDecision.REPLACED
  }

  private fun accept(corners: DocumentCorners, input: GuideInput) {
    accepted = corners
    acceptedSensorNs = input.sensorTimestampNs
    acceptanceId++
    holdDeadlineNs = input.arrivalNs + config.holdNs
  }

  private fun output(decision: GuideDecision, tracking: Boolean, expired: Boolean): GuideOutput {
    val corners = accepted
    val status = when {
      corners == null -> GuideTrackingStatus.SEARCHING
      tracking -> GuideTrackingStatus.TRACKING
      else -> GuideTrackingStatus.HELD
    }
    return GuideOutput(
      status, decision, corners, acceptanceId, holdDeadlineNs, if (tracking) 1 else 0, expired,
    )
  }

  /** Basic area and normalized convex-corner validity. */
  private fun shapeDecision(corners: DocumentCorners, frame: GuideInput): GuideDecision? {
    if (area(corners, frame) < config.minFrameAreaFraction * frame.width * frame.height) return GuideDecision.TOO_SMALL
    val points = listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
    return if (com.margelo.nitro.documentscanner.NativeCornerGeometry.prediction(points.flatMap { listOf(it.x, it.y) }.toDoubleArray())) null else GuideDecision.INVALID
  }

  /** Shoelace area in upright pixels. */
  private fun area(corners: DocumentCorners, frame: GuideInput): Double {
    val twiceArea = cross(corners.topLeft, corners.topRight) + cross(corners.topRight, corners.bottomRight) +
      cross(corners.bottomRight, corners.bottomLeft) + cross(corners.bottomLeft, corners.topLeft)
    return abs(twiceArea) / 2 * frame.width * frame.height
  }

  private fun cross(a: DocumentPoint, b: DocumentPoint): Double = a.x * b.y - b.x * a.y
}
