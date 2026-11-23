package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.AccelerationHistory
import com.margelo.nitro.documentscanner.processing.GyroHistory
import kotlin.math.acos

/** The motion sensors' view of the hand, judged by [FocusRules] for both cameras. */
internal class HandMotion(private val gyro: GyroHistory, private val acceleration: AccelerationHistory) {
  /** The gyroscope's peak turn rate, rad/s, and the accelerometer's peak linear acceleration, m/s². */
  class Reading(val turn: Double, val acceleration: Double) {
    val quiet: Boolean get() = turn <= FocusRules.QUIET_TURN_RATE && acceleration <= FocusRules.QUIET_ACCELERATION
  }

  /** Both peaks over the [FocusRules.QUIET_HAND_NS] before [sensorNs]; null unless the samples cover it, since a quiet
   * gyroscope alone cannot show the phone kept its distance. */
  fun read(sensorNs: Long): Reading? {
    val from = sensorNs - FocusRules.QUIET_HAND_NS
    if (!gyro.covers(from, sensorNs)) return null
    val turn = gyro.peakRate(from, sensorNs) ?: return null
    return Reading(turn, acceleration.peak(from, sensorNs) ?: return null)
  }

  /** How far the phone turned over the [FocusRules.TURN_NS] before [sensorNs], in radians; null without samples. */
  fun turnAngle(sensorNs: Long): Double? = gyro.rotation(sensorNs - FocusRules.TURN_NS, sensorNs)
    ?.let { r -> acos(((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0)) }

  /** The phone turned past [FocusRules.TURN_RADIANS] over the [FocusRules.TURN_NS] before [sensorNs]. */
  fun turned(sensorNs: Long): Boolean = (turnAngle(sensorNs) ?: 0.0) > FocusRules.TURN_RADIANS
}
