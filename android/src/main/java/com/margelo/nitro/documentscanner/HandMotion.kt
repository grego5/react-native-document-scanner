package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.AccelerationHistory
import com.margelo.nitro.documentscanner.processing.GyroHistory

/** The motion sensors' view of the hand, judged by [FocusRules] for both cameras. */
internal class HandMotion(private val gyro: GyroHistory, private val acceleration: AccelerationHistory) {
  /** The gyroscope's net turn, radians, and the accelerometer's peak linear acceleration, m/s². */
  class Reading(val turn: Double, val acceleration: Double) {
    val quiet: Boolean get() = turn <= FocusRules.QUIET_TURN && acceleration <= FocusRules.QUIET_ACCELERATION
  }

  /** The net turn and the acceleration's peak over the [FocusRules.QUIET_HAND_NS] before [sensorNs]; null unless the
   * samples cover it, since a quiet gyroscope alone cannot show the phone kept its distance. The turn is net, not a
   * peak rate: a hand's tremor peaks at a few tenths of a radian a second but turns back within the span, and moves
   * the view a fraction of a percent. */
  fun read(sensorNs: Long): Reading? {
    val from = sensorNs - FocusRules.QUIET_HAND_NS
    if (!gyro.covers(from, sensorNs)) return null
    val turn = gyro.turn(from, sensorNs) ?: return null
    return Reading(turn, acceleration.peak(from, sensorNs) ?: return null)
  }

  /** How far the phone turned over the [FocusRules.TURN_NS] before [sensorNs], in radians; null without samples. */
  fun turnAngle(sensorNs: Long): Double? = gyro.turn(sensorNs - FocusRules.TURN_NS, sensorNs)

  /** The phone turned past [FocusRules.TURN_RADIANS] over the [FocusRules.TURN_NS] before [sensorNs]. */
  fun turned(sensorNs: Long): Boolean = (turnAngle(sensorNs) ?: 0.0) > FocusRules.TURN_RADIANS
}
