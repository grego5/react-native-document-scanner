package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad
import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.sqrt

internal enum class DocumentFocusAction { NONE, START, RELEASE }

/** OFF and EDOF have no adjustable autofocus scan; the minimum focus distance may be missing. */
internal fun hasAdjustableAutofocus(modes: IntArray?): Boolean = modes?.any {
  it == CameraMetadata.CONTROL_AF_MODE_AUTO || it == CameraMetadata.CONTROL_AF_MODE_MACRO ||
    it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE || it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
} == true

/** Where `scanner` mode parks the lens before any lock, in diopters: about 30 cm, a typical scan distance. At
 * the model's 224-pixel input a lens parked there blurred pages from 15 cm to infinity by under 0.7% of the frame in
 * defocus estimates, below the 1% at which simulated blur first moved detections.
 */
internal const val DOCUMENT_PARK_DIOPTERS = 1 / 0.3f

/** The lens can be held at a set distance: autofocus can be turned off, focus distance is a manual control, and the
 * lens reports its focus range.
 */
internal fun canParkFocus(afModes: IntArray?, capabilities: IntArray?, minimumFocusDiopters: Float?): Boolean =
  afModes?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true &&
    capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
    minimumFocusDiopters != null && minimumFocusDiopters > 0f && hasAdjustableAutofocus(afModes)

/** The parked distance: the last focus lock's, which follows how far the user holds the phone, else
 * [DOCUMENT_PARK_DIOPTERS]; within the lens's range. An uncalibrated lens reports distances in its own monotonic units,
 * which only the learned distance matches.
 */
internal fun parkDiopters(learned: Float?, minimumFocusDiopters: Float): Float =
  (learned?.takeIf { it.isFinite() } ?: DOCUMENT_PARK_DIOPTERS).coerceIn(0f, minimumFocusDiopters)

/** Main-thread policy; the host owns CameraX, tap priority, timeouts and camera lifetime. Times are uptime
 * milliseconds. Provisional geometry can start a scan; only trusted geometry establishes the locked scale reference.
 */
internal class DocumentFocusPolicy {
  private enum class Phase { SEEKING, SCANNING, LOCKED }
  private var phase = Phase.SEEKING
  private var retryAfter = 0L
  private var lastSeen = 0L
  private var referenceArea: Double? = null
  private var scaleDirection = 0
  private var scaleVotes = 0
  private var failedSince: Long? = null

  fun reset() {
    phase = Phase.SEEKING
    retryAfter = 0L
    referenceArea = null
    scaleVotes = 0
    scaleDirection = 0
    failedSince = null
  }

  fun settled(success: Boolean, now: Long) {
    if (phase != Phase.SCANNING) return
    phase = if (success) Phase.LOCKED else Phase.SEEKING
    retryAfter = now + COOLDOWN
    referenceArea = null
    scaleVotes = 0
    failedSince = null
  }

  /** [acquireTrusted] starts the first scan only on a confirmed guide, as on a parked lens, whose frames already show
   * the page; otherwise provisional geometry starts it.
   */
  fun observe(
    quad: DocumentQuad?, trusted: Boolean, focusFailed: Boolean, now: Long, blocked: Boolean,
    acquireTrusted: Boolean = false,
  ): DocumentFocusAction {
    if (blocked) return DocumentFocusAction.NONE
    val area = quad?.let(::area)?.takeIf { it.isFinite() && it >= .03 }
    if (area == null) {
      if (phase == Phase.LOCKED && now - lastSeen >= LOST_HOLD) {
        reset()
        return DocumentFocusAction.RELEASE
      }
      return DocumentFocusAction.NONE
    }
    lastSeen = now
    if (phase == Phase.SCANNING) return DocumentFocusAction.NONE
    if (phase == Phase.SEEKING) return if (now >= retryAfter && (trusted || !acquireTrusted)) start() else DocumentFocusAction.NONE
    if (focusFailed) {
      if (failedSince == null) failedSince = now
      if (now >= retryAfter && now - failedSince!! >= FAILED_HOLD) return start()
    } else failedSince = null
    if (!trusted) return DocumentFocusAction.NONE
    val reference = referenceArea
    if (reference == null) {
      referenceArea = area // Replace approximate acquisition geometry without another scan.
      return DocumentFocusAction.NONE
    }
    val ratio = sqrt(area / reference)
    val direction = when { ratio > 1.08 -> 1; ratio < 1 / 1.08 -> -1; else -> 0 }
    scaleVotes = if (direction != 0 && direction == scaleDirection) scaleVotes + 1 else if (direction != 0) 1 else 0
    scaleDirection = direction
    // Confirmed distance change invalidates the old lock immediately; a failure cooldown must not keep stale focus.
    return if (scaleVotes >= 2) start() else DocumentFocusAction.NONE
  }

  private fun start(): DocumentFocusAction {
    phase = Phase.SCANNING
    referenceArea = null
    scaleVotes = 0
    failedSince = null
    return DocumentFocusAction.START
  }

  private fun area(q: DocumentQuad): Double {
    val p = arrayOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)
    return abs(p.indices.sumOf { i -> p[i].x * p[(i + 1) % 4].y - p[(i + 1) % 4].x * p[i].y }) / 2
  }

  private companion object {
    const val COOLDOWN = 1000L
    const val LOST_HOLD = 1000L
    const val FAILED_HOLD = 500L
  }
}
