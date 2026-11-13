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

  @Synchronized fun blur(fromNs: Long, toNs: Long, exposureNs: Long, optics: LiveCameraOptics): Double? =
    query(fromNs, toNs, 2, optics.focalLengthPx, optics.activeWidth, exposureNs)?.first()

  private fun query(fromNs: Long, toNs: Long, operation: Int, focal: Double = 1.0, width: Int = 1,
    exposureNs: Long = 0): DoubleArray? {
    val timestamps = LongArray(count) { times[index(it)] }
    val snapshot = DoubleArray(count * 3) { rates[index(it / 3) * 3 + it % 3] }
    // These methods take the history's sensor clock. Camera callers are enabled only for REALTIME timestamps.
    return NativeMotion.gyroNative(timestamps, snapshot, fromNs, toNs, longArrayOf(0L), operation, focal, width, exposureNs)
  }

  private fun index(i: Int): Int = (next - count + i + capacity) % capacity

  companion object {
    const val MAX_EXTRAPOLATION_NS = 50_000_000L
  }
}
