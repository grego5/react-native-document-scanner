package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CaptureResult

/** What one analysis frame's print edges say about focus; see [FocusEvidence]. */
internal enum class FocusVerdict {
  /** The edges are as narrow as this camera's sharp frames. */
  SHARP,
  /** Wider both ways: the lens is off the print's distance. */
  DEFOCUSED,
  /** The phone moved during the exposure, or one direction alone widened: motion, which says nothing about focus. */
  SMEARED,
  /** Too few edges, or the lens moving: no evidence either way. */
  UNKNOWN,
}

/** A frame's [verdict], with the sharp [floor] it was judged against, in analysis pixels, and the print [edges] it was
 * judged by. */
internal data class FocusReading(val verdict: FocusVerdict, val floor: Double, val edges: Int = 0) {
  override fun toString() = "${verdict.name.lowercase()} (floor %.2f px)".format(floor)
}

/**
 * Analysis executor, one per camera binding: judges each frame's focus by its print's edge width ([EdgeWidth]) against
 * this camera's sharp floor, the width its in-focus frames measure. Phones sharpen and denoise differently, and noise
 * reduction in low light widens a sharp edge, so the floor is learned from the binding's own frames: a low percentile
 * of the widths of frames the camera reports focused, while still and the lens at rest, under [FLOOR_CAP]. A frame the
 * camera wrongly reports focused, as continuous autofocus settling short of the page, measures past the cap and does not
 * teach it. Until enough frames have, the floor is [FLOOR_DEFAULT], high enough that only gross defocus reads defocused.
 * Widths are in analysis pixels and not scaled by resolution: sharpening keeps a sharp edge's turn to a pixel or two at
 * any width, as the text camera's frames show at 1440 wide, while defocus widens with resolution, so a wider frame only
 * separates the two further.
 *
 * Defocus widens edges both ways and leaves fewer of them past the step a sharp edge makes; motion widens them along
 * its direction only. So a frame reads [FocusVerdict.DEFOCUSED] when its median width and both directions' pass their
 * multiples of the floor, and [FocusVerdict.SMEARED] when one direction is [smear] wider than the other while the narrow
 * one stays sharp, or when the gyroscope's blur over its exposure reaches [smear] and the edges do not read sharp: edges
 * that measure sharp were not blurred, whatever the gyroscope says a hand's turn could have done.
 */
internal class FocusEvidence {
  private val widths = DoubleArray(FLOOR_SAMPLES)
  private var learned = 0
  private var nextSample = 0

  /** Forgets the learned floor, for a new binding, whose camera or light may differ. */
  fun reset() {
    learned = 0
    nextSample = 0
  }

  /** The sharp floor, in analysis pixels. */
  fun floor(): Double {
    if (learned < FLOOR_LEARNED) return FLOOR_DEFAULT
    val sorted = widths.copyOf(minOf(learned, FLOOR_SAMPLES)).also { it.sort() }
    return sorted[(sorted.size * FLOOR_PERCENTILE).toInt()].coerceIn(FLOOR_MIN, FLOOR_CAP)
  }

  /**
   * One analysis frame [width] pixels wide: its [edges] over the page or guide, the camera's [frame] state, and its
   * gyroscope [blur] as a fraction of the frame's width, null when unknown; [width] turns that blur into pixels.
   */
  fun judge(edges: EdgeWidth?, width: Int, frame: CameraFrameState?, blur: Double?): FocusReading {
    val floor = floor()
    val verdict = verdict(edges, width, frame, blur, floor)
    if (edges != null && verdict != FocusVerdict.SMEARED && frame != null && !frame.lensMoving &&
      (frame.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
        frame.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)) learn(edges.median)
    return FocusReading(verdict, floor, edges?.edges ?: 0)
  }

  private fun verdict(edges: EdgeWidth?, width: Int, frame: CameraFrameState?, blur: Double?, floor: Double): FocusVerdict {
    if (edges == null || frame?.lensMoving == true) return FocusVerdict.UNKNOWN
    val smear = smear(floor)
    val rows = edges.rows
    val columns = edges.columns
    if (rows != null && columns != null) {
      val narrow = minOf(rows, columns)
      if (narrow <= SHARP * floor && maxOf(rows, columns) - narrow >= smear) return FocusVerdict.SMEARED
    }
    if (edges.median <= SHARP * floor) return FocusVerdict.SHARP
    if (blur != null && blur * width >= smear) return FocusVerdict.SMEARED
    val narrow = listOfNotNull(rows, columns).minOrNull() ?: edges.median
    return if (edges.median >= DEFOCUSED * floor && narrow >= DEFOCUSED_EACH * floor) FocusVerdict.DEFOCUSED
      else FocusVerdict.UNKNOWN
  }

  /** The widening that reads as motion over a [floor]: a floor's width, and at least [SMEAR_MIN], since a floor as
   * narrow as a single pixel's turn would read a hand's tremor as smear. */
  private fun smear(floor: Double) = maxOf(SMEAR * floor, SMEAR_MIN)

  private fun learn(width: Double) {
    if (width > FLOOR_CAP) return
    widths[nextSample] = width
    nextSample = (nextSample + 1) % FLOOR_SAMPLES
    if (learned < FLOOR_SAMPLES) learned++
  }

  companion object {
    /** The floor before enough frames taught one, in pixels: a dim frame's sharp width on the SM-G998U, so a camera
     * whose sharp frames measure wider than most still reads sharp. */
    const val FLOOR_DEFAULT = 1.6
    /** The learned floor's bounds, in pixels: a single-pixel turn measures 0.8 between its 10% and 90% points, and a
     * focused frame wider than [FLOOR_CAP] is a report of focus that was not reached. */
    const val FLOOR_MIN = 0.8
    const val FLOOR_CAP = 2.0
    /** Focused frames kept, how many before the floor is learned, and the percentile taken. */
    const val FLOOR_SAMPLES = 48
    const val FLOOR_LEARNED = 8
    const val FLOOR_PERCENTILE = .25
    /** Multiples of the floor: the widest median that reads sharp; the median and narrower direction defocus passes;
     * and the smear, by the gyroscope or one direction past the other, that reads motion. On the SM-G998U, defocus
     * measured 2.3 to 3 floors and in-focus frames up to 1.4. */
    const val SHARP = 1.5
    const val DEFOCUSED = 2.2
    const val DEFOCUSED_EACH = 1.6
    const val SMEAR = 1.0
    /** The least widening that reads as motion, in pixels. */
    const val SMEAR_MIN = 1.0
  }
}
