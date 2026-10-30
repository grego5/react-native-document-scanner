package com.margelo.nitro.documentscanner.processing

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
  @Synchronized fun rotation(fromNs: Long, toNs: Long): DoubleArray? {
    if (toNs < fromNs) return rotation(toNs, fromNs)?.let(::transpose)
    if (count == 0 || times[index(0)] > fromNs || toNs > times[index(count - 1)] + MAX_EXTRAPOLATION_NS) return null
    var result = identity()
    var t = fromNs
    for (i in 0 until count) {
      if (t >= toNs) break
      val at = index(i)
      val end = if (i + 1 < count) minOf(times[index(i + 1)], toNs) else toNs
      if (end <= t) continue
      val dt = (end - t) * 1e-9
      result = multiply(result, exp(rates[at * 3] * dt, rates[at * 3 + 1] * dt, rates[at * 3 + 2] * dt))
      t = end
    }
    return result
  }

  /** Largest angular speed in rad/s among samples timed within [fromNs, toNs]; null when none are. */
  @Synchronized fun peakRate(fromNs: Long, toNs: Long): Double? {
    var peak: Double? = null
    for (i in count - 1 downTo 0) {
      val at = index(i)
      if (times[at] > toNs) continue
      if (times[at] < fromNs) break
      val x = rates[at * 3]
      val y = rates[at * 3 + 1]
      val z = rates[at * 3 + 2]
      val rate = sqrt(x * x + y * y + z * z)
      if (peak == null || rate > peak) peak = rate
    }
    return peak
  }

  private fun index(i: Int): Int = (next - count + i + capacity) % capacity

  companion object {
    const val MAX_EXTRAPOLATION_NS = 50_000_000L

    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun transpose(m: DoubleArray) = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { cell ->
      val row = cell / 3
      val column = cell % 3
      a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
    }

    /** Rodrigues rotation for the rotation vector (x, y, z). */
    private fun exp(x: Double, y: Double, z: Double): DoubleArray {
      val angle = sqrt(x * x + y * y + z * z)
      if (angle == 0.0) return identity()
      val kx = x / angle; val ky = y / angle; val kz = z / angle
      val s = sin(angle)
      val c = 1 - cos(angle)
      return doubleArrayOf(
        1 - c * (ky * ky + kz * kz), -s * kz + c * kx * ky, s * ky + c * kx * kz,
        s * kz + c * kx * ky, 1 - c * (kx * kx + kz * kz), -s * kx + c * ky * kz,
        -s * ky + c * kx * kz, s * kx + c * ky * kz, 1 - c * (kx * kx + ky * ky),
      )
    }
  }
}
