package com.margelo.nitro.documentscanner

import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** Fake CameraX futures/clock; no camera opens or shutter calls. All policy/hardware adaptation runs on main. */
class FocusControllerTest {
  private fun main(block: () -> Unit) {
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
    failure?.let { throw it }
  }
  @Test fun tapExpiryWaitsForShotAndPublishesBeforeRestoringHardware() = main {
    Harness().use { h ->
      h.tap()
      assertEquals(FocusOwner.TAP,h.focus.snapshot.owner)
      assertEquals(5000L,h.focus.snapshot.heldUntilMs)
      h.futures.single().set(FocusMeteringResult.create(true))
      val locked = h.focus.snapshot.generation
      assertTrue(h.focus.snapshot.locked == true)
      h.shot = true; h.advance(5000)
      assertEquals(FocusOwner.TAP,h.focus.snapshot.owner)
      h.shot = false; h.focus.afterCapture()
      assertNull(h.focus.snapshot.owner)
      assertTrue(h.focus.snapshot.generation > locked)
      assertEquals(1,h.cancels)
      assertNull(h.resumes.single().owner)
    }
  }
  @Test fun aTapOwnsTheLensBeforeItsScanIsSentAndAShotRefusesIt() = main {
    Harness().use { h ->
      h.shot = true; h.tap()
      assertTrue(h.futures.isEmpty())
      assertNull(h.focus.snapshot.owner)
      h.shot = false; h.tap()
      // Published before the request, so no capture decision reads the old focus while the lens moves.
      assertEquals(listOf(FocusOwner.TAP),h.ownersAtRequest)
      assertEquals(FocusOwner.TAP,h.events.last().owner)
      h.futures.single().set(FocusMeteringResult.create(false))
      assertEquals(false,h.focus.snapshot.locked)
      h.advance(5000)
      assertNull(h.focus.snapshot.owner)
    }
  }
  @Test fun takeoverUnbindAndCloseInvalidateOldFuturesAndTimers() = main {
    Harness().use { h ->
      h.tap()
      h.capture(); val old = h.futures.last()
      assertEquals(FocusOwner.CAPTURE,h.focus.snapshot.owner)
      h.advance(100); h.capture(); val fresh = h.futures.last()
      val started = h.focus.snapshot.generation
      old.set(FocusMeteringResult.create(true))
      assertEquals(started,h.focus.snapshot.generation)
      fresh.set(FocusMeteringResult.create(true))
      assertTrue(h.focus.snapshot.locked == true)
      h.focus.unbind(); val unbound = h.focus.snapshot.generation
      h.advance(6000)
      assertNull(h.focus.snapshot.owner)
      assertEquals(unbound,h.focus.snapshot.generation)
      h.capture(); val pending = h.futures.last(); val beforeClose = h.events.size
      h.focus.close(); pending.set(FocusMeteringResult.create(true)); h.tap(); h.advance(12000)
      assertEquals(beforeClose,h.events.size)
    }
  }
  @Test fun captureTriggersAtOnceAndItsReleaseAimsContinuousFocusAfterTheCancel() = main {
    Harness().use { h ->
      h.capture()
      assertEquals(1,h.futures.size)
      h.advance(1900)
      assertEquals(1,h.futures.size)
      h.futures.last().set(FocusMeteringResult.create(true))
      assertTrue(h.focus.snapshot.locked == true)
      val cancelled = ControlledFuture<Void?>()
      h.pendingCancel = cancelled
      h.focus.afterCapture()
      assertNull(h.focus.snapshot.owner)
      // Cancelling clears the regions, so continuous autofocus is aimed only once it is done.
      assertTrue(h.resumes.isEmpty())
      cancelled.set(null)
      assertNull(h.resumes.single().owner)
    }
  }
  @Test fun captureScanTimeoutSettlesAndHoldsTheLensUntilAfterCapture() = main {
    Harness().use { h ->
      h.capture()
      assertEquals(FocusOwner.CAPTURE,h.focus.snapshot.owner)
      h.advance(2000)
      assertEquals(false,h.focus.snapshot.locked)
      h.advance(5000)
      assertEquals(FocusOwner.CAPTURE,h.focus.snapshot.owner)
      h.focus.afterCapture()
      assertNull(h.focus.snapshot.owner)
      assertNull(h.resumes.last().owner)
      assertEquals(1,h.cancels)
    }
  }
  @Test fun shotsBlockACaptureScanWhileAWaitingCaptureDoesNotOwnFocus() = main {
    Harness().use { h ->
      h.shot = true; h.capture()
      assertTrue(h.futures.isEmpty())
      assertNull(h.focus.snapshot.owner)
      h.shot = false; h.capture()
      assertEquals(FocusOwner.CAPTURE,h.focus.snapshot.owner)
      assertEquals(1,h.futures.size)
    }
  }
  private class ControlledFuture<T> : ListenableFuture<T> {
    private val result = CompletableFuture<T>()
    fun set(value: T) { result.complete(value) }
    override fun addListener(listener: Runnable, executor: Executor) { result.whenComplete { _, _ -> executor.execute(listener) } }
    override fun cancel(interrupt: Boolean) = result.cancel(interrupt)
    override fun isCancelled() = result.isCancelled
    override fun isDone() = result.isDone
    override fun get(): T = result.get()
    override fun get(timeout: Long, unit: TimeUnit): T = result.get(timeout, unit)
  }
  private class Harness : FocusController.Host, AutoCloseable {
    var now = 0L
    var shot = false
    var cancels = 0
    val futures = mutableListOf<ControlledFuture<FocusMeteringResult>>()
    /** The published owner each time a scan's request reached the camera. */
    val ownersAtRequest = mutableListOf<FocusOwner?>()
    val events = mutableListOf<FocusSnapshot>()
    /** The snapshot each time continuous autofocus was aimed again. */
    val resumes = mutableListOf<FocusSnapshot>()
    /** The next cancel's future, or null for a cancel the camera completes at once. */
    var pendingCancel: ControlledFuture<Void?>? = null
    private val tasks = mutableListOf<Pair<Long,() -> Unit>>()
    private val control = Proxy.newProxyInstance(CameraControl::class.java.classLoader,arrayOf(CameraControl::class.java)) { _, method, _ ->
      when (method.name) {
        "startFocusAndMetering" -> { ownersAtRequest.add(focus.snapshot.owner); ControlledFuture<FocusMeteringResult>().also { futures.add(it) } }
        "cancelFocusAndMetering" -> { cancels++; pendingCancel?.also { pendingCancel = null } ?: ControlledFuture<Void?>().also { it.set(null) } }
        else -> throw UnsupportedOperationException(method.name)
      }
    } as CameraControl
    private val info = Proxy.newProxyInstance(CameraInfo::class.java.classLoader,arrayOf(CameraInfo::class.java)) { _, method, _ ->
      throw UnsupportedOperationException(method.name)
    } as CameraInfo
    override val camera = BoundCamera(control, info)
    override val mainExecutor = Executor { it.run() }
    override fun shotInFlight() = shot
    override fun uptimeNs() = now * 1_000_000
    override fun resumeContinuousFocus() { resumes.add(focus.snapshot) }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { tasks.add((now + delayMs) to block) }
    override fun log(message: String) = Unit
    override fun focusChanged(snapshot: FocusSnapshot) { events.add(snapshot) }
    val focus = FocusController(this)
    private val point = SurfaceOrientedMeteringPointFactory(100f,100f).createPoint(50f,50f)
    fun capture() = focus.capture(camera,point)
    fun tap() = focus.tap(camera,point,point)
    fun advance(time: Long) {
      now = time
      var iterations = 0
      while (true) {
        val task = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: break
        tasks.remove(task); task.second()
        check(++iterations < 1000) { "Wakeup loop" }
      }
    }
    override fun close() = focus.close()
  }
}
