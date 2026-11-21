package com.margelo.nitro.documentscanner.processing

import kotlin.math.sqrt

/** Recent linear acceleration magnitudes (m/s², gravity removed), timed on the sensor clock that camera timestamps
 * share (elapsedRealtimeNanos). Written by the sensor thread; read on main. */
internal class AccelerationHistory(private val capacity: Int = 256) {
  private val times = LongArray(capacity)
  private val magnitudes = DoubleArray(capacity)
  private var count = 0
  private var next = 0

  @Synchronized fun add(timestampNs: Long, x: Double, y: Double, z: Double) {
    if (count > 0 && timestampNs <= times[index(count - 1)]) return
    times[next] = timestampNs
    magnitudes[next] = sqrt(x * x + y * y + z * z)
    next = (next + 1) % capacity
    if (count < capacity) count++
  }

  @Synchronized fun clear() {
    count = 0
    next = 0
  }

  /** The largest magnitude sampled from [fromNs] to [toNs]; null unless samples reach both ends within [GAP_NS]. */
  @Synchronized fun peak(fromNs: Long, toNs: Long): Double? {
    if (count == 0 || times[index(0)] > fromNs + GAP_NS || times[index(count - 1)] < toNs - GAP_NS) return null
    var peak = 0.0
    for (i in 0 until count) {
      val time = times[index(i)]
      if (time in fromNs..toNs) peak = maxOf(peak, magnitudes[index(i)])
    }
    return peak
  }

  private fun index(i: Int): Int = (next - count + i + capacity) % capacity

  companion object {
    /** The widest gap at either end of a span that still counts as sampled. */
    const val GAP_NS = 50_000_000L
  }
}
