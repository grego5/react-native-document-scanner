package com.margelo.nitro.documentscanner

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.processing.CameraOpticsReader
import com.margelo.nitro.documentscanner.processing.LiveCornersCameraRecord
import com.margelo.nitro.documentscanner.processing.LiveCornersCaptureEvent
import com.margelo.nitro.documentscanner.processing.LiveCornersGyroSample
import com.margelo.nitro.documentscanner.processing.LiveGuideTraceFormat
import com.margelo.nitro.documentscanner.processing.LiveGuideTraceRecorder
import com.margelo.nitro.documentscanner.processing.GuideStabilizerConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Records one pixel-free live corners trace per run, with 200 Hz gyroscope samples from before the camera starts and
 * the back camera's optics, timestamp source and raw intrinsics.
 * The user follows the on-screen instructions and taps Stop.
 * Arguments: `-e liveGuideScene <label>`, optional `-e liveGuideInstructions <text>`, and `-e liveGuideCapture true`
 * to offer a user-tapped shutter whose post-capture analysis is recorded. `-e liveGuideCaptures <n>` offers n
 * shutters in turn (default 1); `-e liveGuideKeepPhotos true` keeps each JPEG next to the trace as
 * `<scene>-<stamp>-<n>.jpg` instead of deleting it. `-e liveGuideAnalysisWidth <px>` overrides the 4:3 analysis
 * frame width; rows record the resulting frame size. `-e liveGuideLowQualityControl true` records with the default quality gate,
 * so captures wait for focus and steadiness and a rejected shot is recorded and offered again. Image-motion estimation
 * runs on every frame unless `-e liveGuideFlow false` turns it off, for a cadence baseline; it is recorded, never gated on.
 * Frame rows record every analysis frame's exposure, autofocus and OIS; capture rows add the tap and `takePicture`
 * times and the JPEG's exposure.
 */
@RunWith(AndroidJUnit4::class)
class LiveGuideTraceRecordingTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test
  fun recordLiveCornersScene() {
    assumeMeasuring()
    assumeTrue("Grant CAMERA to the test package before recording",
      context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    val scene = InstrumentationRegistry.getArguments().getString("liveGuideScene", "unlabeled")
      .replace(Regex("[^A-Za-z0-9_-]"), "_")
    val instructions = InstrumentationRegistry.getArguments().getString("liveGuideInstructions", "")
    val offerCapture = InstrumentationRegistry.getArguments().getString("liveGuideCapture", "false").toBoolean()
    val captureCount = InstrumentationRegistry.getArguments().getString("liveGuideCaptures", "1").toInt()
    val keepPhotos = InstrumentationRegistry.getArguments().getString("liveGuideKeepPhotos", "false").toBoolean()
    val analysisFrameWidth = InstrumentationRegistry.getArguments().getString("liveGuideAnalysisWidth")?.toDouble()
    val lowQualityControl = InstrumentationRegistry.getArguments().getString("liveGuideLowQualityControl", "false").toBoolean()
    val flowMotion = InstrumentationRegistry.getArguments().getString("liveGuideFlow", "true").toBoolean()
    val directory = requireNotNull(context.getExternalFilesDir("live-guide-traces")) { "External files are unavailable" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    val recorder = LiveGuideTraceRecorder(GuideStabilizerConfig())
    val shutter = Shutter(recorder, captureCount, File(directory, "$scene-$stamp").takeIf { keepPhotos }, lowQualityControl)
    val stopRequested = CountDownLatch(1)
    val sensors = context.getSystemService(SensorManager::class.java)
    val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val gyroListener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        recorder.addGyro(LiveCornersGyroSample(event.timestamp, event.values[0].toDouble(), event.values[1].toDouble(),
          event.values[2].toDouble()))
      }
      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    try {
      // Register first so every analyzed frame lies inside gyro coverage.
      gyroscope?.let { sensors.registerListener(gyroListener, it, 5_000) }
      ActivityScenario.launch(CameraValidationActivity::class.java).use { scenario ->
        scenario.onActivity {
          it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
          if (analysisFrameWidth != null) it.camera.analysisFrameWidth = analysisFrameWidth
          it.camera.lowQualityControl = LowQualityControl(lowQualityControl, null, null)
          it.camera.flowMotionEnabled = flowMotion
          it.camera.afterUpdate()
        }
        awaitStreaming(scenario)
        scenario.onActivity { activity ->
          activity.camera.liveGuideTraceObserver = recorder
          activity.showTraceControls(listOf("Recording: $scene", instructions).filter { it.isNotEmpty() }
            .joinToString("\n")) { stopRequested.countDown() }
          if (offerCapture) shutter.arm(activity, 1)
          else activity.showCaptureStatus("No capture in this scene")
        }
        // Ends on Stop or at the recorder's 2,048-row / 60 s bound.
        val safetyDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (!stopRequested.await(100, TimeUnit.MILLISECONDS) && !recorder.isFull) {
          check(System.nanoTime() < safetyDeadline) { "Recording neither stopped nor filled" }
        }
        recorder.stopFrames()
        try {
          val captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
          while (shutter.pending.get() > 0 && System.nanoTime() < captureDeadline) Thread.sleep(50)
          assertEquals("Capture analysis did not finish", 0, shutter.pending.get())
        } finally {
          scenario.onActivity { it.camera.liveGuideTraceObserver = null }
        }
      }
    } finally {
      sensors.unregisterListener(gyroListener)
    }
    val trace = recorder.stop().copy(camera = backCamera())
    val file = File(directory, "$scene-$stamp.tsv")
    file.writeText(StringBuilder().also { LiveGuideTraceFormat.write(trace, it) }.toString(), Charsets.UTF_8)
    Log.i(LOG_TAG, "Wrote ${trace.observations.size} observations, ${trace.captures.size} captures and " +
      "${trace.gyro.size} gyro samples to ${file.absolutePath}")
    assertEquals(emptyList<Throwable>(), shutter.failures.toList())
    assertTrue("No live observations were recorded", trace.observations.isNotEmpty())
    assertEquals(trace, LiveGuideTraceFormat.read(file.readText(Charsets.UTF_8)))
  }

  /** Offers [count] shutters in turn, each fired only on the user's tap. [photoPrefix] keeps the JPEGs. With [gated],
   * a rejected shot is recorded and its shutter offered again.
   */
  private class Shutter(
    private val recorder: LiveGuideTraceRecorder,
    private val count: Int,
    private val photoPrefix: File?,
    private val gated: Boolean,
  ) {
    val pending = AtomicInteger()
    val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

    /** Main thread. */
    fun arm(activity: CameraValidationActivity, index: Int) {
      activity.requestManualCapture(if (count == 1) "Tap once to capture this page" else "Tap to capture $index of $count") {
        val tappedNs = System.nanoTime()
        val tapSensorNs = SystemClock.elapsedRealtimeNanos()
        pending.incrementAndGet()
        activity.camera.takePhoto().then { result ->
          val id = result.pageId
          val owner = activity.session.state
          val consumed = java.util.concurrent.atomic.AtomicBoolean()
          var remove: (() -> Unit)? = null
          fun consume(snapshot: ScanSessionSnapshot) {
            val page = snapshot.pages[id]
            if (page?.analyzing == true || !consumed.compareAndSet(false, true)) return
            remove?.invoke()
            val analysis = page?.analysis
            if (analysis == null) {
              failures.add(ScannerException(page?.analysisFailure?.code ?: ScannerErrorCode.PROCESSOR_DISPOSED))
              pending.decrementAndGet()
              return
            }
            java.util.concurrent.CompletableFuture.runAsync {
              owner.leaseOriginal(id).use { source ->
                val original = source.file
                val exposureS = ExifInterface(original.absolutePath).getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                val fire = recorder.gateSince(tapSensorNs)
                recorder.addCapture(LiveCornersCaptureEvent(tappedNs, analysis.candidate?.corners, analysis.candidate?.score,
                  analysis.sourceWidth.toInt(), analysis.sourceHeight.toInt(), tapSensorNs, fire?.takeIf { it.fired }?.sensorClockNs,
                  (exposureS * 1e9).roundToLong().takeIf { it > 0 },
                  gateEvaluationNs = fire?.gateEvaluationNs, gateExposureNs = fire?.gateExposureNs, gateBlur = fire?.gateBlur,
                  gateSource = fire?.gateSource, flow = fire?.flow, flowAgeNs = fire?.flowAgeNs,
                  flowSourceAgeNs = fire?.flowSourceAgeNs))
                photoPrefix?.let { original.copyTo(File("${it.path}-$index.jpg"), overwrite = true) }
              }
            }.whenComplete { _, error ->
              if (error != null) failures.add(error)
              try { owner.removePage(id) } catch (failure: Throwable) { failures.add(failure) }
              activity.runOnUiThread {
                if (index < count) arm(activity, index + 1)
                else activity.showCaptureStatus("Captured. Tap Stop recording when done.")
              }
              pending.decrementAndGet()
            }
          }
          try {
            val registration = owner.observe { change ->
              if (change.closed || id in change.updatedPages || id in change.removedPageIds) consume(owner.snapshot())
            }
            remove = registration.unsubscribe
            consume(registration.snapshot)
          } catch (error: Throwable) { failures.add(error); pending.decrementAndGet() }
        }.catch { error ->
          if (gated) {
            Log.i(LOG_TAG, "Capture $index rejected: ${error.message}")
            val fire = recorder.gateSince(tapSensorNs)
            recorder.addCapture(LiveCornersCaptureEvent(tappedNs, null, null, 0, 0, tapSensorNs,
              firedSensorNs = fire?.takeIf { it.fired }?.sensorClockNs, saved = false, gateEvaluationNs = fire?.gateEvaluationNs,
              gateExposureNs = fire?.gateExposureNs, gateBlur = fire?.gateBlur, gateSource = fire?.gateSource, flow = fire?.flow,
              flowAgeNs = fire?.flowAgeNs, flowSourceAgeNs = fire?.flowSourceAgeNs))
            activity.runOnUiThread { arm(activity, index) }
          } else {
            failures.add(error)
          }
          pending.decrementAndGet()
        }
      }
    }
  }

  /** CameraX's default back camera is the first back-facing id; optics come from the same reader the app uses. */
  private fun backCamera(): LiveCornersCameraRecord? {
    val cameras = context.getSystemService(CameraManager::class.java)
    val characteristics = cameras.cameraIdList.asSequence().map { cameras.getCameraCharacteristics(it) }
      .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: return null
    val optics = CameraOpticsReader.read(characteristics) ?: return null
    return LiveCornersCameraRecord(
      optics, CameraOpticsReader.sharesGyroClock(characteristics),
      characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.map { it.toDouble() },
      characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        ?.let { listOf(it.left, it.top, it.right, it.bottom) },
      characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList(),
    )
  }

  private fun awaitStreaming(scenario: ActivityScenario<CameraValidationActivity>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (System.nanoTime() < deadline) {
      var streaming = false
      scenario.onActivity {
        val preview = (it.camera.view as FrameLayout).getChildAt(0) as PreviewView
        streaming = preview.previewStreamState.value == PreviewView.StreamState.STREAMING
      }
      if (streaming) return
      Thread.sleep(50)
    }
    fail("Camera preview streaming")
  }

  private companion object {
    const val LOG_TAG = "RNDocumentScannerTrace"
  }
}
