package com.margelo.nitro.documentscanner

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/** What the capture feedback draws at one moment; alphas are 0–1 over the color's own alpha. */
internal data class CaptureFeedbackFrame(
  /** Opacity of the whole outline. */
  val outlineAlpha: Float,
  /** Fraction of the outline the progress line covers, from its first corner; 0 draws none. */
  val sweep: Float,
  /** Opacity of the progress line. */
  val lineAlpha: Float,
  /** Opacity of the fill inside the outline. */
  val fillAlpha: Float,
  /** The stage's animation has ended and nothing more is drawn. */
  val finished: Boolean,
)

/**
 * The capture feedback's animation over time, without Android types. Waiting has no known end, so it pulses; shooting
 * has a known length of about [SHOOT_ESTIMATE_MS], so its line approaches [SWEEP_CAP] and closes only when the shot
 * is exposed; captured closes the line from where it stopped, flashes the fill and fades; failed blinks and fades.
 */
internal object CaptureFeedbackTimeline {
  const val PULSE_MS = 700.0
  const val SHOOT_ESTIMATE_MS = 350.0
  const val SWEEP_CAP = 0.9f
  const val CLOSE_MS = 90.0
  const val FADE_MS = 260.0
  const val FAIL_BLINK_MS = 110.0
  const val FAIL_BLINKS = 2
  /** Opacity of the full outline under the shooting line, so the target stays visible. */
  const val TRACK_ALPHA = 0.35f
  private const val FLASH_ALPHA = 0.3f
  /** Time constant that puts the line at about 85% of its cap after [SHOOT_ESTIMATE_MS]. */
  private const val SHOOT_TAU_MS = SHOOT_ESTIMATE_MS / 1.9

  /** How far the shooting line has run after [elapsedMs]. */
  fun shootingSweep(elapsedMs: Double): Float =
    if (elapsedMs <= 0) 0f else SWEEP_CAP * (1 - exp(-elapsedMs / SHOOT_TAU_MS)).toFloat()

  /** The waiting pulse after [elapsedMs]: full opacity at the start, dipping to 45% at half a period. */
  fun waiting(elapsedMs: Double): CaptureFeedbackFrame {
    val wave = 0.5 + 0.5 * cos(2 * PI * elapsedMs.coerceAtLeast(0.0) / PULSE_MS)
    return CaptureFeedbackFrame((0.45 + 0.55 * wave).toFloat(), 0f, 0f, 0f, finished = false)
  }

  fun shooting(elapsedMs: Double): CaptureFeedbackFrame =
    CaptureFeedbackFrame(TRACK_ALPHA, shootingSweep(elapsedMs), 1f, 0f, finished = false)

  /** [elapsedMs] after the shot was exposed, with the line at [releasedSweep] when it was. */
  fun captured(elapsedMs: Double, releasedSweep: Float): CaptureFeedbackFrame {
    val t = elapsedMs.coerceAtLeast(0.0)
    if (t < CLOSE_MS) {
      val sweep = releasedSweep + (1 - releasedSweep) * (t / CLOSE_MS).toFloat()
      return CaptureFeedbackFrame(TRACK_ALPHA, sweep, 1f, FLASH_ALPHA * (t / CLOSE_MS).toFloat(), finished = false)
    }
    val fade = 1 - min(1.0, (t - CLOSE_MS) / FADE_MS).toFloat()
    return CaptureFeedbackFrame(TRACK_ALPHA * fade, 1f, fade, FLASH_ALPHA * fade, finished = t >= CLOSE_MS + FADE_MS)
  }

  fun failed(elapsedMs: Double): CaptureFeedbackFrame {
    val t = elapsedMs.coerceAtLeast(0.0)
    val blinksMs = FAIL_BLINK_MS * 2 * FAIL_BLINKS
    if (t < blinksMs) {
      val on = (t / FAIL_BLINK_MS).toInt() % 2 == 0
      return CaptureFeedbackFrame(if (on) 1f else 0.25f, 0f, 0f, 0f, finished = false)
    }
    val fade = 1 - min(1.0, (t - blinksMs) / FADE_MS).toFloat()
    return CaptureFeedbackFrame(fade, 0f, 0f, 0f, finished = t >= blinksMs + FADE_MS)
  }
}
