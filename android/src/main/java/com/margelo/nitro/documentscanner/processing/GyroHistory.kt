package com.margelo.nitro.documentscanner.processing

/** Recent calibrated gyroscope rates (rad/s) on the device's natural axes, timed on the sensor clock that camera
 * timestamps share (elapsedRealtimeNanos). Written by the sensor thread; read by the analysis executor and main.
 */
internal class GyroHistory(private val capacity: Int = 512) {
  private val times = LongArray(capacity)
  private val rates = DoubleArray(capacity * 3)
  private var count = 0
  private var next = 0

  @Synchronized fun add(timestampNs: Long, x: Double, y: Double, z: Double) {
    if (count > 0 && timestampNs <= times[index(count - 1)]) return
    times[next] = timestampNs
    rates[next * 3] = x
    rates[next * 3 + 1] = y
    rates[next * 3 + 2] = z
    next = (next + 1) % capacity
    if (count < capacity) count++
  }

  @Synchronized fun clear() {
    count = 0
    next = 0
  }

  /** Device rotation from [fromNs] to [toNs] in its own frame, row-major 3x3; reversed spans give the inverse.
   * Each rate holds until the next sample and the newest for at most [MAX_EXTRAPOLATION_NS]. Null when samples
   * do not cover the span.
   */
  @Synchronized fun rotation(fromNs: Long, toNs: Long): DoubleArray? = query(fromNs, toNs, 0)

  @Synchronized fun peakRate(fromNs: Long, toNs: Long): Double? = query(fromNs, toNs, 1)?.first()

  /** How far the device turned from [fromNs] to [toNs], in radians: the angle of [rotation], so a tremor that turns
   * back and forth within the span nets little. Null when samples do not cover the span. */
  fun turn(fromNs: Long, toNs: Long): Double? = rotation(fromNs, toNs)
    ?.let { r -> kotlin.math.acos(((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0)) }

  /** Whether samples reach both ends of [fromNs]..[toNs] within [MAX_EXTRAPOLATION_NS], so a peak over it saw it all. */
  @Synchronized fun covers(fromNs: Long, toNs: Long): Boolean =
    count > 0 && times[index(0)] <= fromNs + MAX_EXTRAPOLATION_NS && times[index(count - 1)] >= toNs - MAX_EXTRAPOLATION_NS

  @Synchronized fun blur(fromNs: Long, toNs: Long, exposureNs: Long, optics: LiveCameraOptics): Double? =
    query(fromNs, toNs, 2, optics.focalLengthPx, optics.activeWidth, exposureNs)?.first()

  /**
   * Only the samples a span reads cross to native: the last at or before its start, which holds its rate into it, through
   * the first after its end, which bounds the last held rate; without either end the oldest or newest sample stands in,
   * so coverage checks see what the whole history would show them.
   */
  private fun query(fromNs: Long, toNs: Long, operation: Int, focal: Double = 1.0, width: Int = 1,
    exposureNs: Long = 0): DoubleArray? {
    val first = (lastAtOrBefore(minOf(fromNs, toNs))).coerceAtLeast(0)
    val last = (lastAtOrBefore(maxOf(fromNs, toNs)) + 1).coerceAtMost(count - 1)
    val size = (last - first + 1).coerceAtLeast(0)
    val timestamps = LongArray(size) { times[index(first + it)] }
    val snapshot = DoubleArray(size * 3)
    for (i in 0 until size) {
      val at = index(first + i) * 3
      snapshot[i * 3] = rates[at]
      snapshot[i * 3 + 1] = rates[at + 1]
      snapshot[i * 3 + 2] = rates[at + 2]
    }
    // These methods take the history's sensor clock. Camera callers are enabled only for REALTIME timestamps.
    return NativeMotion.gyroNative(timestamps, snapshot, fromNs, toNs, longArrayOf(0L), operation, focal, width, exposureNs)
  }

  private fun index(i: Int): Int = (next - count + i + capacity) % capacity

  /** The logical index of the newest sample at or before [timeNs], or -1; samples are in rising time order. */
  private fun lastAtOrBefore(timeNs: Long): Int {
    var low = 0
    var high = count - 1
    var found = -1
    while (low <= high) {
      val middle = (low + high) ushr 1
      if (times[index(middle)] <= timeNs) { found = middle; low = middle + 1 } else high = middle - 1
    }
    return found
  }

  companion object {
    const val MAX_EXTRAPOLATION_NS = 50_000_000L
  }
}
