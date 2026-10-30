package com.margelo.nitro.documentscanner

import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveCameraRuntimeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test
  fun inactiveCameraStaysSuspendedAcrossForegroundAndSettingsThenResumesWithoutRemounting() {
    assumeTrue(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    val intent = Intent(context, CameraValidationActivity::class.java).putExtra("cameraActive", false)
    ActivityScenario.launch<CameraValidationActivity>(intent).use { scenario ->
      var state: CameraState? = null
      var document: DocumentState? = null
      var original: HybridDocumentScannerCameraView? = null
      scenario.onActivity {
        original = it.camera
        it.camera.onCameraStateChange = { next -> state = next }
        it.camera.onDocumentStateChange = { next -> document = next }
        it.camera.analysisWidth = 640.0
        it.camera.guide = false
        it.camera.afterUpdate()
      }
      await(scenario, "Initially inactive camera") { state == CameraState.SUSPENDED && document == DocumentState.OFF }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onActivity {
        assertEquals(CameraState.SUSPENDED, state)
        assertFalse(it.camera.gyroscopeRunning)
        it.camera.mode = CameraMode.SCANNER
        it.camera.afterUpdate()
        it.camera.mode = CameraMode.OFF
        it.camera.afterUpdate() // A pending provider completion must not reactivate it.
      }
      await(scenario, "Deactivated provider request") { state == CameraState.SUSPENDED }
      Thread.sleep(200)
      scenario.onActivity {
        assertEquals(CameraState.SUSPENDED, state)
        assertFalse(it.camera.gyroscopeRunning)
        it.camera.mode = CameraMode.SCANNER
        it.camera.afterUpdate()
      }
      awaitStreaming(scenario)
      scenario.onActivity { assertSame(original, it.camera) }
    }
  }

  @Test
  fun deactivationDuringCaptureFinishesTheShotBeforeReleasingTheCamera() {
    assumeTrue(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    ActivityScenario.launch(CameraValidationActivity::class.java).use { scenario ->
      var state: CameraState? = null
      scenario.onActivity {
        it.camera.onCameraStateChange = { next -> state = next }
        it.camera.qualityGate = true
        it.camera.afterUpdate()
      }
      awaitStreaming(scenario)
      capture(scenario, label = "Take a photo to test pausing during capture") { activity ->
        activity.camera.mode = CameraMode.OFF
        activity.camera.afterUpdate()
        assertEquals(CameraState.CAPTURING, state)
      }
      await(scenario, "Camera released after the shot") { state == CameraState.SUSPENDED }
      scenario.onActivity {
        assertFalse(it.camera.gyroscopeRunning)
        assertFalse(overlay(it).hasOutline)
        it.camera.mode = CameraMode.SCANNER
        it.camera.afterUpdate()
      }
      awaitStreaming(scenario)
    }
  }

  @Test
  fun fasterGuidanceSurvivesPauseRotationAndBackgroundWithoutCapture() {
    assumeTrue(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    ActivityScenario.launch(CameraValidationActivity::class.java).use { scenario ->
      scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
      awaitStreaming(scenario)
      await(scenario, "First live outline") { overlay(it).hasOutline }
      scenario.onActivity { assertTrue("Back-camera guidance samples the gyroscope", it.camera.gyroscopeRunning) }
      Thread.sleep(20_000) // Four native cadence/latency windows; no shutter is requested.
      scenario.onActivity { it.camera.qualityGate = true; it.camera.guide = false; it.camera.afterUpdate() }
      await(scenario, "Pause cancels outline") { !overlay(it).hasOutline }
      scenario.onActivity { assertTrue("The quality gate keeps the gyroscope", it.camera.gyroscopeRunning) }
      scenario.onActivity { it.camera.qualityGate = false; it.camera.afterUpdate() }
      await(scenario, "Pause without a quality gate stops the gyroscope") { !it.camera.gyroscopeRunning }
      Thread.sleep(200) // Previously scheduled animation must not redraw a stale quad.
      scenario.onActivity { assertFalse(overlay(it).hasOutline) }
      assertStreaming(scenario)
      scenario.onActivity { it.camera.guide = true; it.camera.afterUpdate() }
      await(scenario, "Resumed outline") { overlay(it).hasOutline }
      scenario.onActivity { assertTrue("Resume restarts the gyroscope", it.camera.gyroscopeRunning) }
      scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
      await(scenario, "Landscape viewport") { it.camera.view.width > it.camera.view.height }
      awaitStreaming(scenario)
      await(scenario, "Landscape outline") { overlay(it).hasOutline }
      scenario.moveToState(Lifecycle.State.CREATED)
      await(scenario, "Background cancels outline") { !overlay(it).hasOutline }
      Thread.sleep(200)
      scenario.onActivity { assertFalse(overlay(it).hasOutline) }
      scenario.moveToState(Lifecycle.State.RESUMED)
      awaitStreaming(scenario)
      await(scenario, "Foreground outline") { overlay(it).hasOutline }
      Thread.sleep(10_000)
    }
  }

  @Test
  fun captureSettingChangeDuringCaptureDefersTheRebindButPausesDetectionAtOnce() {
    assumeTrue(context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    ActivityScenario.launch(CameraValidationActivity::class.java).use { scenario ->
      scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
      awaitStreaming(scenario)
      await(scenario, "First live outline") { overlay(it).hasOutline }
      var outlineAfterPause = true
      // One update, while the photo is in flight, that both rebinds and pauses; the photo must still succeed.
      capture(scenario, label = "Capture while settings change") { activity ->
        activity.camera.captureMode = CaptureMode.LATENCY
        activity.camera.guide = false
        activity.camera.afterUpdate()
        outlineAfterPause = overlay(activity).hasOutline
      }
      assertFalse("Pausing in the same update as a deferred rebind clears the outline", outlineAfterPause)
      awaitStreaming(scenario)
      Thread.sleep(1_000) // The deferred rebind must keep detection paused.
      scenario.onActivity { assertFalse(overlay(it).hasOutline) }
      scenario.onActivity { it.camera.guide = true; it.camera.afterUpdate() }
      await(scenario, "Resumed outline after the deferred rebind") { overlay(it).hasOutline }
    }
  }

  @Test
  fun realCameraSupportsGuidanceManualCapturePauseRotationAndBackground() {
    assumeTrue("Grant CAMERA to the test package before running hardware acceptance",
      context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    val records = JSONArray()
    val directory = File(context.filesDir, "doccornernet-camera").apply { mkdirs() }
    val report = JSONObject().put("device", android.os.Build.MODEL).put("stages", records)
    ActivityScenario.launch(CameraValidationActivity::class.java).use { scenario ->
      scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
      awaitStreaming(scenario)
      // Give the real model time to initialize and publish its first outline.
      await(scenario, "First live model outline") { overlay(it).hasOutline }
      Thread.sleep(6_000) // Stable camera window for native rate/latency logging.
      record(scenario, directory, records, "back-portrait")
      capture(scenario)

      scenario.onActivity { it.camera.guide = false; it.camera.afterUpdate() }
      await(scenario, "Paused outline cleared") { !overlay(it).hasOutline }
      assertStreaming(scenario) // Pause does not unbind Preview/ImageCapture.
      capture(scenario)
      scenario.onActivity { it.camera.guide = true; it.camera.afterUpdate() }
      await(scenario, "Resumed live outline") { overlay(it).hasOutline }

      scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
      await(scenario, "Landscape viewport") { it.camera.view.width > it.camera.view.height }
      awaitStreaming(scenario)
      await(scenario, "Landscape outline") { overlay(it).hasOutline }
      record(scenario, directory, records, "back-landscape")
      capture(scenario)

      scenario.moveToState(Lifecycle.State.CREATED)
      await(scenario, "Background clears outline") { !overlay(it).hasOutline }
      scenario.moveToState(Lifecycle.State.RESUMED)
      awaitStreaming(scenario)
      scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
      await(scenario, "Portrait restored") { it.camera.view.height > it.camera.view.width }
      awaitStreaming(scenario)
      await(scenario, "Foreground/rebind outline") { overlay(it).hasOutline }
      record(scenario, directory, records, "back-restored")
      capture(scenario)
      File(directory, "report.json").writeText(report.put("passed", true).toString(2) + "\n")
    }
  }

  private fun awaitStreaming(scenario: ActivityScenario<CameraValidationActivity>) =
    await(scenario, "Camera preview streaming") { preview(it).previewStreamState.value == PreviewView.StreamState.STREAMING }

  private fun assertStreaming(scenario: ActivityScenario<CameraValidationActivity>) =
    scenario.onActivity { assertEquals(PreviewView.StreamState.STREAMING, preview(it).previewStreamState.value) }

  private fun await(scenario: ActivityScenario<CameraValidationActivity>, label: String, condition: (CameraValidationActivity) -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (System.nanoTime() < deadline) {
      var met = false
      scenario.onActivity { met = condition(it) }
      if (met) return
      Thread.sleep(50)
    }
    fail(label)
  }

  /** [whilePending] runs on the main thread just after the capture request is accepted. */
  private fun capture(scenario: ActivityScenario<CameraValidationActivity>,
    label: String = "Capture next camera test photo when ready",
    whilePending: ((CameraValidationActivity) -> Unit)? = null) {
    val completed = CountDownLatch(1)
    val callbacks = AtomicInteger()
    var photo: CapturedPhoto? = null
    var error: Throwable? = null
    scenario.onActivity { activity ->
      activity.requestManualCapture(label) {
        activity.camera.takePhoto().then { photo = it; callbacks.incrementAndGet(); completed.countDown() }
          .catch { error = it; callbacks.incrementAndGet(); completed.countDown() }
        // takePhoto posts its request to the main thread, so a later post runs once the capture is pending.
        if (whilePending != null) activity.camera.view.post { whilePending(activity) }
      }
    }
    assertTrue("Waiting for user shutter/capture timed out", completed.await(180, TimeUnit.SECONDS))
    assertNull(error)
    assertEquals(1, callbacks.get())
    assertTrue(photo!!.width > 0 && photo!!.height > 0)
    val file = File(Uri.parse(photo!!.originalUri).path!!)
    assertTrue(file.isFile)
    file.delete()
  }

  private fun record(scenario: ActivityScenario<CameraValidationActivity>, directory: File, records: JSONArray, stage: String) {
    scenario.onActivity { records.put(JSONObject().put("stage", stage).put("outline", overlay(it).hasOutline)
      .put("width", it.camera.view.width).put("height", it.camera.view.height)
      .put("hardwareAccelerated", it.camera.view.isHardwareAccelerated))
      preview(it).bitmap?.let { bitmap ->
        try { File(directory, "$stage-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
      }
    }
    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
      try { File(directory, "$stage.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
      finally { bitmap.recycle() }
    }
  }

  private fun preview(activity: CameraValidationActivity) = (activity.camera.view as FrameLayout).getChildAt(0) as PreviewView
  private fun overlay(activity: CameraValidationActivity) = (activity.camera.view as FrameLayout).getChildAt(1) as LiveQuadOverlay
}
