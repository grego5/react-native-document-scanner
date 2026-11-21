package com.margelo.nitro.documentscanner.processing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** Feeds [history] at 200 Hz, and [acceleration] from the linear-acceleration sensor where there is one, on its own
 * thread while started. Call start and stop on main. */
internal class GyroscopeSource(
  context: Context, val history: GyroHistory, val acceleration: AccelerationHistory = AccelerationHistory(),
) {
  private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
  private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
  private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
  private var thread: HandlerThread? = null
  private val listener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      val x = event.values[0].toDouble()
      val y = event.values[1].toDouble()
      val z = event.values[2].toDouble()
      if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) acceleration.add(event.timestamp, x, y, z)
      else history.add(event.timestamp, x, y, z)
    }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
  }

  val isRunning: Boolean get() = thread != null

  fun start() {
    if (thread != null || gyroscope == null) return
    history.clear()
    acceleration.clear()
    val started = HandlerThread("DocumentScannerGyro").also { it.start() }
    thread = started
    val handler = Handler(started.looper)
    sensors?.registerListener(listener, gyroscope, SAMPLING_PERIOD_US, handler)
    accelerometer?.let { sensors?.registerListener(listener, it, ACCELERATION_PERIOD_US, handler) }
  }

  fun stop() {
    val started = thread ?: return
    thread = null
    sensors?.unregisterListener(listener)
    started.quitSafely()
    history.clear()
    acceleration.clear()
  }

  private companion object {
    const val SAMPLING_PERIOD_US = 5_000
    const val ACCELERATION_PERIOD_US = 10_000
  }
}
