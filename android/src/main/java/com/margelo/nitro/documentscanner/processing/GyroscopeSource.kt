package com.margelo.nitro.documentscanner.processing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** Feeds [history] at 200 Hz on its own thread while started. Call start and stop on main. */
internal class GyroscopeSource(context: Context, val history: GyroHistory) {
  private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
  private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
  private var thread: HandlerThread? = null
  private val listener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      history.add(event.timestamp, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
    }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
  }

  val isRunning: Boolean get() = thread != null

  fun start() {
    if (thread != null || gyroscope == null) return
    history.clear()
    val started = HandlerThread("DocumentScannerGyro").also { it.start() }
    thread = started
    sensors?.registerListener(listener, gyroscope, SAMPLING_PERIOD_US, Handler(started.looper))
  }

  fun stop() {
    val started = thread ?: return
    thread = null
    sensors?.unregisterListener(listener)
    started.quitSafely()
    history.clear()
  }

  private companion object {
    const val SAMPLING_PERIOD_US = 5_000
  }
}
