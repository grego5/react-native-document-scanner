package com.margelo.nitro.documentscanner

import android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER_START
import android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_AUTO
import android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_INACTIVE
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.google.common.util.concurrent.ListenableFuture
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FocusControllerTest {
  /** A future completed by the test; its listeners run on the rig's main queue. */
  private class Settable<T> : ListenableFuture<T> {
    private var value: T? = null
    private var done = false
    private val listeners = mutableListOf<Pair<Runnable, Executor>>()
    fun complete(result: T) {
      value = result
      done = true
      listeners.forEach { (listener, executor) -> executor.execute(listener) }
      listeners.clear()
    }
    override fun addListener(listener: Runnable, executor: Executor) {
      if (done) executor.execute(listener) else listeners += listener to executor
    }
    override fun cancel(mayInterruptIfRunning: Boolean) = false
    override fun isCancelled() = false
    override fun isDone() = done
    @Suppress("UNCHECKED_CAST") override fun get(): T = value as T
    override fun get(timeout: Long, unit: TimeUnit): T = get()
  }

  private class Rig {
    val triggers = mutableListOf<Settable<FocusMeteringResult>>()
    var cancels = 0
    var resumes = 0
    var shot = false
    val failed = mutableListOf<FocusOwner>()
    private val queue = ArrayDeque<Runnable>()
    private var frameNumber = 0L
    private val control = Proxy.newProxyInstance(CameraControl::class.java.classLoader, arrayOf(CameraControl::class.java)) { _, method, _ ->
      when (method.name) {
        "startFocusAndMetering" -> Settable<FocusMeteringResult>().also { triggers += it }
        "cancelFocusAndMetering" -> { cancels++; Settable<Void?>().also { it.complete(null) } }
        else -> null
      }
    } as CameraControl
    private val info = Proxy.newProxyInstance(CameraInfo::class.java.classLoader, arrayOf(CameraInfo::class.java)) { _, _, _ -> null } as CameraInfo
    val camera = BoundCamera(control, info)
    val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
    val focus = FocusController(object : FocusController.Host {
      override val camera get() = this@Rig.camera
      override val mainExecutor = Executor { queue.addLast(it) }
      override fun shotInFlight() = shot
      override fun resumeContinuousFocus() { resumes++ }
      override fun postDelayed(delayMs: Long, block: () -> Unit) {}
      override fun log(message: String) {}
      override fun focusChanged(snapshot: FocusSnapshot) {}
      override fun scannerLockFailed(owner: FocusOwner) { failed += owner }
      override val confirmsLockFrames get() = true
      override fun uptimeNs() = 0L
    })

    /** Runs what the camera and CameraX handed to main. */
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    fun frame(state: Int, trigger: Boolean = false, mode: Int = CONTROL_AF_MODE_AUTO) =
      focus.observeFrame(++frameNumber, if (trigger) CONTROL_AF_TRIGGER_START else null, mode, state)
    fun pageLock() = focus.lock(camera, point, FocusOwner.PAGE)
    fun completeLast(success: Boolean) = triggers.last().complete(FocusMeteringResult.create(success))
  }

  @Test fun aTapReplacingAPendingScanIsNotLostByTheOldScansFrames() {
    val rig = Rig()
    rig.pageLock()
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    // The page lock's trigger reads as lost, but a tap replaces the scan before main handles it.
    rig.frame(CONTROL_AF_STATE_INACTIVE)
    rig.focus.tap(rig.camera, rig.point, rig.point)
    rig.drain()
    // Frames before the tap's own trigger are not its loss either.
    rig.frame(CONTROL_AF_STATE_INACTIVE)
    rig.drain()
    assertEquals(2, rig.triggers.size)
    assertEquals(FocusOwner.TAP, rig.focus.snapshot.owner)
    assertNull(rig.focus.snapshot.locked)
  }

  @Test fun cameraXReportingALockBeforeItsFrameWaitsForTheFrame() {
    val rig = Rig()
    rig.pageLock()
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    rig.frame(CONTROL_AF_STATE_ACTIVE_SCAN)
    rig.completeLast(true)
    rig.drain()
    assertNull(rig.focus.snapshot.locked)
    rig.frame(CONTROL_AF_STATE_FOCUSED_LOCKED)
    rig.drain()
    assertEquals(true, rig.focus.snapshot.locked)
  }

  @Test fun cameraXReportingALockAfterItsFrameSettlesAtOnce() {
    val rig = Rig()
    rig.pageLock()
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    rig.frame(CONTROL_AF_STATE_FOCUSED_LOCKED)
    rig.drain()
    assertNull(rig.focus.snapshot.locked)
    rig.completeLast(true)
    rig.drain()
    assertEquals(true, rig.focus.snapshot.locked)
  }

  @Test fun aTriggerLostTwiceFailsTheLockAfterOneResend() {
    val rig = Rig()
    rig.pageLock()
    // The trigger freezes continuous autofocus, then the switch to single-shot autofocus drops it.
    rig.frame(CONTROL_AF_STATE_FOCUSED_LOCKED, trigger = true, mode = CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    rig.frame(CONTROL_AF_STATE_INACTIVE)
    rig.drain()
    assertEquals(2, rig.triggers.size)
    // CameraX's report for the first trigger belongs to a replaced scan.
    rig.triggers.first().complete(FocusMeteringResult.create(true))
    rig.drain()
    assertNull(rig.focus.snapshot.locked)
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    rig.frame(CONTROL_AF_STATE_INACTIVE)
    rig.drain()
    assertEquals(2, rig.triggers.size)
    assertNull(rig.focus.snapshot.owner)
    assertEquals(listOf(FocusOwner.PAGE), rig.failed)
    assertEquals(1, rig.cancels)
  }

  @Test fun aReleaseDuringAStillWaitsForTheShotToSettle() {
    val rig = Rig()
    rig.pageLock()
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    rig.frame(CONTROL_AF_STATE_FOCUSED_LOCKED)
    rig.completeLast(true)
    rig.drain()
    rig.shot = true
    // A brief turn asks for the release once, then stops asking.
    assertEquals(FocusController.Release.DEFERRED, rig.focus.releaseScannerLock())
    assertEquals(FocusOwner.PAGE, rig.focus.snapshot.owner)
    assertEquals(0, rig.cancels)
    rig.shot = false
    rig.focus.afterCapture()
    rig.drain()
    assertNull(rig.focus.snapshot.owner)
    assertEquals(1, rig.cancels)
    assertEquals(1, rig.resumes)
  }

  @Test fun unbindingRetiresAWaitingRelease() {
    val rig = Rig()
    rig.pageLock()
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    rig.frame(CONTROL_AF_STATE_FOCUSED_LOCKED)
    rig.completeLast(true)
    rig.drain()
    rig.shot = true
    assertEquals(FocusController.Release.DEFERRED, rig.focus.releaseScannerLock())
    rig.focus.unbind()
    rig.shot = false
    rig.pageLock()
    rig.focus.afterCapture()
    assertEquals(FocusOwner.PAGE, rig.focus.snapshot.owner)
    assertEquals(0, rig.cancels)
    assertEquals(FocusController.Release.RELEASED, rig.focus.releaseScannerLock())
  }

  @Test fun aReleaseAimsContinuousAutofocusOnceAfterTheCancellation() {
    val rig = Rig()
    rig.pageLock()
    rig.frame(CONTROL_AF_STATE_INACTIVE, trigger = true)
    rig.frame(CONTROL_AF_STATE_FOCUSED_LOCKED)
    rig.completeLast(true)
    rig.drain()
    assertEquals(FocusController.Release.RELEASED, rig.focus.releaseScannerLock())
    // Not before CameraX completes the cancellation, and the host holds its own aim meanwhile.
    assertEquals(0, rig.resumes)
    assertTrue(rig.focus.resuming)
    rig.drain()
    assertFalse(rig.focus.resuming)
    assertEquals(1, rig.cancels)
    assertEquals(1, rig.resumes)
    assertEquals(FocusController.Release.NONE, rig.focus.releaseScannerLock())
  }
}
