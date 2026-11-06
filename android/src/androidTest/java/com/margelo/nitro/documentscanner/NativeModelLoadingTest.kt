package com.margelo.nitro.documentscanner

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Model lifecycle only: mode stays off throughout; no camera or shutter is opened. */
@RunWith(AndroidJUnit4::class)
class NativeModelLoadingTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val readyField = HybridDocumentScannerCameraView::class.java.getDeclaredField("modelReady").apply { isAccessible = true }

  @Before fun initialize() {
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
  }

  private fun scenario() = ActivityScenario.launch<CameraValidationActivity>(
    Intent(context, CameraValidationActivity::class.java)
      .putExtra("cameraActive", false).putExtra("quadTensorModel", ""))

  @Test fun missingAndInvalidSourcesReportErrorsAndValidSourceRecovers() {
    scenario().use { host ->
      val missing = CountDownLatch(1)
      host.onActivity { activity ->
        activity.camera.onError = { if (it.code == ScannerErrorCode.INPUT_INVALID) missing.countDown() }
      }
      assertTrue(missing.await(10, TimeUnit.SECONDS))
      val failed = CountDownLatch(1)
      host.onActivity { activity ->
        activity.camera.onError = { if (it.code == ScannerErrorCode.INPUT_INVALID) failed.countDown() }
        activity.camera.quadTensorModel = "/missing-native-model.tflite"
        activity.camera.afterUpdate()
      }
      assertTrue(failed.await(10, TimeUnit.SECONDS))
      val camera = AtomicReference<HybridDocumentScannerCameraView>()
      host.onActivity { activity ->
        camera.set(activity.camera)
        assertFalse(readyField.getBoolean(activity.camera))
        activity.camera.quadTensorModel = "asset:///doccornernet/model.tflite"
        activity.camera.afterUpdate()
      }
      val deadline = SystemClock.uptimeMillis() + 10_000
      var ready = false
      while (!ready && SystemClock.uptimeMillis() < deadline) {
        instrumentation.runOnMainSync { ready = readyField.getBoolean(camera.get()) }
        if (!ready) SystemClock.sleep(20)
      }
      assertTrue("A source-only change must prepare even when other props are unchanged", ready)
      host.onActivity { activity -> assertEquals(CameraMode.OFF, activity.camera.mode) }
    }
  }

  @Test fun oldCompletionCannotReadyAnInvalidReplacement() {
    scenario().use { host ->
      val failed = CountDownLatch(1)
      host.onActivity { activity ->
        activity.camera.onError = { if (it.code == ScannerErrorCode.INPUT_INVALID) failed.countDown() }
        activity.camera.quadTensorModel = "asset:///doccornernet/model.tflite"
        activity.camera.afterUpdate()
        activity.camera.quadTensorModel = "/missing-replacement-model.tflite"
        activity.camera.afterUpdate()
      }
      assertTrue(failed.await(10, TimeUnit.SECONDS))
      instrumentation.waitForIdleSync()
      host.onActivity { activity -> assertFalse(readyField.getBoolean(activity.camera)) }
    }
  }
}
