package com.margelo.nitro.documentscanner.processing

/** Exposure, first-to-last-row readout skew and autofocus of one frame, as its capture result reports them. The raw
 * CONTROL_AF_STATE, OIS mode and focus distance (diopters) are recorded by live traces only.
 */
internal data class CameraFrameState(
  val exposureNs: Long,
  val rollingShutterSkewNs: Long,
  val outOfFocus: Boolean,
  val afState: Int? = null,
  val opticalStabilization: Boolean? = null,
  val focusDiopters: Float? = null,
  /** Autofocus was off: a parked lens, whose focus is fixed rather than unknown once [outOfFocus] says it stopped. */
  val manualFocus: Boolean = false,
  /** Raw CONTROL_AE_STATE and CONTROL_AWB_STATE, which zero-shutter-lag buffering requires converged. */
  val aeState: Int? = null,
  val awbState: Int? = null,
)

/** Recent capture results keyed by SENSOR_TIMESTAMP, which equals the timestamp of the frame's image buffers. */
internal class CameraFrameHistory(private val capacity: Int = 8) {
  private val timestamps = LongArray(capacity)
  private val states = arrayOfNulls<CameraFrameState>(capacity)
  private var newest = -1

  @Synchronized fun add(timestampNs: Long, state: CameraFrameState) {
    newest = (newest + 1) % capacity
    timestamps[newest] = timestampNs
    states[newest] = state
  }

  /** The result for the frame exposed at [timestampNs]; null until that result arrives. */
  @Synchronized fun at(timestampNs: Long): CameraFrameState? {
    for (i in 0 until capacity) if (states[i] != null && timestamps[i] == timestampNs) return states[i]
    return null
  }

  @Synchronized fun clear() {
    states.fill(null)
    newest = -1
  }
}

/** Rotation blur across one exposure as a fraction of the active-array width: the peak gyroscope rate within
 * [fromNs, toNs] held for [exposureNs]. Translation is not seen. Null without gyroscope samples in the span.
 */
internal fun motionBlur(gyro: GyroHistory, optics: LiveCameraOptics, fromNs: Long, toNs: Long, exposureNs: Long): Double? {
  val rate = gyro.peakRate(fromNs, toNs) ?: return null
  return rate * exposureNs * 1e-9 * optics.focalLengthPx / optics.activeWidth
}

/** One 200 Hz gyroscope period, so a short exposure still spans at least one sample. */
internal const val GYRO_MARGIN_NS = 5_000_000L

/** Which estimate a motion gate judged. */
internal enum class MotionSource { FLOW, GYRO }

/** A gate's blur as a fraction of the frame width and the estimate it came from. Flow adds its [lostRegions], absent
 * for the gyroscope.
 */
internal data class GateMotion(val blur: Double, val source: MotionSource, val lostRegions: Int = 0)

/** Optical-flow blur over [exposureNs] as a fraction of the analysis frame width; null when the speed is unknown. */
internal fun flowBlur(estimate: FlowMotionEstimate, exposureNs: Long): Double? =
  estimate.speedPxPerSecond?.let { it * exposureNs * 1e-9 / estimate.sourceWidth }

/** This frame's blur from its measured [flow], otherwise its [gyroBlur]. Both estimates describe the same exposure;
 * capture checks the completed frame record's age separately. Null when neither source knows.
 */
internal fun gateMotion(
  flow: FlowMotionEstimate?, exposureNs: Long, gyroBlur: Double?,
): GateMotion? {
  val measured = flow?.let { flowBlur(it, exposureNs) }
  return when {
    measured != null -> GateMotion(measured, MotionSource.FLOW, flow.lostRegions)
    gyroBlur != null -> GateMotion(gyroBlur, MotionSource.GYRO)
    else -> null
  }
}
