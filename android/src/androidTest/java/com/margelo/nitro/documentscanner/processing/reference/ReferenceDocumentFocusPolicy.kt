package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad
import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.sqrt

/** Main-thread policy; the host owns CameraX, tap priority, timeouts and camera lifetime. Times are uptime
 * milliseconds. Provisional geometry can start a scan; only trusted geometry establishes the locked scale reference.
 */
internal class ReferenceDocumentFocusPolicy {
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
