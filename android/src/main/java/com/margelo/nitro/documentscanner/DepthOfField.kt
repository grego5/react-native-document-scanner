package com.margelo.nitro.documentscanner

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.hypot

/**
 * The bound camera's depth of field near focus. Inside the hyperfocal distance, a lens focused at D diopters renders
 * sharp what lies between D - δ and D + δ diopters, with δ = N·c / f² for aperture N, focal length f and circle of
 * confusion c: the same in diopters at every distance, so the sharp range in metres narrows as the lens focuses nearer.
 * [toleranceDiopters] is δ. The camera's own depth-of-field report, `LENS_FOCUS_RANGE`, is a fixed range on the
 * SM-G998U, so it is worked out from the lens and sensor instead.
 */
internal class DepthOfField(val toleranceDiopters: Double) {
  /**
   * How far a page or line may grow or shrink, as size ratios, before it leaves the depth of field of a lens focused at
   * [focusDiopters], the frame's `LENS_FOCUS_DISTANCE`; [zoom] crops the image and so enlarges its blur. Apparent size
   * is inversely proportional to distance, so a size ratio s is the subject at D·s diopters: growing past 1 + δ/D passes
   * the near limit, shrinking past D / (D - δ) the far one, and nothing does beyond the hyperfocal distance. Null without
   * a focus distance, as at infinity.
   */
  fun limit(focusDiopters: Float?, zoom: Double = 1.0): SizeLimit? {
    val d = focusDiopters?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return null
    val delta = toleranceDiopters / zoom.coerceAtLeast(1.0)
    return SizeLimit(1 + delta / d, if (d > delta) d / (d - delta) else Double.POSITIVE_INFINITY)
  }

  override fun toString() = "DepthOfField(±%.3f D)".format(toleranceDiopters)

  companion object {
    /** The circle of confusion as the sensor's diagonal over this: the usual print sharpness, a few photo pixels. */
    const val CONFUSION_DIVISOR = 1500.0

    /** From the camera's first focal length and aperture and its sensor size; null without them, or when its focus
     * distances are uncalibrated, and so not diopters. */
    fun read(characteristics: CameraCharacteristics): DepthOfField? {
      val calibration = characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
      if (calibration == null || calibration == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED) {
        return null
      }
      val focalMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
      val aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
      val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
      return of(focalMm?.toDouble() ?: return null, aperture?.toDouble() ?: return null,
        hypot(sensor.width.toDouble(), sensor.height.toDouble()) / CONFUSION_DIVISOR)
    }

    /** From [focalMm], [aperture] and the circle of confusion [confusionMm]; null for a value that is not positive. */
    fun of(focalMm: Double, aperture: Double, confusionMm: Double): DepthOfField? {
      if (focalMm <= 0 || aperture <= 0 || confusionMm <= 0) return null
      // δ in diopters, 1/m: N·c / f² with millimetres is per millimetre.
      return DepthOfField(1000 * aperture * confusionMm / (focalMm * focalMm))
    }
  }
}

/** How far a subject may grow, as [grow], or shrink, as [shrink], each a size ratio above 1, before leaving focus. */
internal data class SizeLimit(val grow: Double, val shrink: Double) {
  /** Each limit kept within [floor], past the outline's jitter, and [cap], since the reported distance is approximate. */
  fun bounded(floor: Double, cap: Double) = SizeLimit(grow.coerceIn(floor, cap), shrink.coerceIn(floor, cap))

  /** The size ratio [scale] since the baseline passes either limit. */
  fun exceeded(scale: Double): Boolean = scale >= grow || scale * shrink <= 1.0

  override fun toString() = "grow %.3fx, shrink %.3fx".format(grow, shrink)

  companion object {
    fun fixed(ratio: Double) = SizeLimit(ratio, ratio)
  }
}

/** The size change past which a subject leaves [this] depth of field when focused at [focusDiopters] under [zoom], kept
 * within [FocusRules.SIZE_FLOOR] and [FocusRules.SIZE_CAP]; [FocusRules.SIZE_FALLBACK] without a depth of field. */
internal fun DepthOfField?.sizeLimit(focusDiopters: Float?, zoom: Double = 1.0): SizeLimit =
  this?.limit(focusDiopters, zoom)?.bounded(FocusRules.SIZE_FLOOR, FocusRules.SIZE_CAP)
    ?: SizeLimit.fixed(FocusRules.SIZE_FALLBACK)

/**
 * Whether a locked subject has changed size past its limit since it was first measured, as moving the phone nearer or
 * farther does. Area scales by the square of distance whatever the view's aspect, so its root is the size ratio. A
 * subject is its [key]: a new key measures afresh, so a new lock or another subject is a new baseline. The change must
 * hold for [FocusRules.SIZE_FRAMES] observations in a row, and is reported once per key.
 */
internal class SizeWatch {
  private var key: Any? = null
  private var baseArea = 0.0
  private var limit = SizeLimit.fixed(FocusRules.SIZE_FALLBACK)
  private var passed = 0
  private var reported = false

  fun reset() {
    key = null
  }

  /** The size ratio once the subject [key] of [area] has passed its limit, which [limit] gives when it is first
   * measured; null otherwise, and after the key's change was reported. */
  fun observe(key: Any, area: Double, limit: () -> SizeLimit): Double? {
    if (area <= 0.0) return null
    if (key != this.key) {
      this.key = key
      baseArea = area
      this.limit = limit()
      passed = 0
      reported = false
      return null
    }
    if (reported) return null
    val scale = kotlin.math.sqrt(area / baseArea)
    passed = if (this.limit.exceeded(scale)) passed + 1 else 0
    if (passed < FocusRules.SIZE_FRAMES) return null
    reported = true
    return scale
  }

  /** The limit measured with the current key's baseline. */
  val currentLimit: SizeLimit get() = limit
}
