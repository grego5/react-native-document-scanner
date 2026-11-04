package com.margelo.nitro.documentscanner

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
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
      h.futures.last().set(FocusMeteringResult.create(true))
      val locked = h.focus.snapshot.generation
      h.shot = true; h.advance(5000)
      assertEquals(FocusOwner.TAP,h.focus.snapshot.owner)
      h.shot = false; h.focus.afterCapture()
      assertNull(h.focus.snapshot.owner)
      assertTrue(h.focus.snapshot.generation > locked)
      assertNull(h.options.last().owner)
      assertEquals(1,h.cancels)
    }
  }
  @Test fun takeoverUnbindAndCloseInvalidateOldFuturesAndTimers() = main {
    Harness().use { h ->
      h.tap(); val old = h.futures.last()
      h.advance(100); h.tap(); val fresh = h.futures.last()
      val started = h.focus.snapshot.generation
      old.set(FocusMeteringResult.create(true))
      assertEquals(started,h.focus.snapshot.generation)
      fresh.set(FocusMeteringResult.create(true))
      assertTrue(h.focus.snapshot.locked == true)
      h.focus.unbind(); val unbound = h.focus.snapshot.generation
      h.advance(6000)
      assertNull(h.focus.snapshot.owner)
      assertEquals(unbound,h.focus.snapshot.generation)
      h.tap(); val pending = h.futures.last(); val beforeClose = h.events.size
      h.focus.close(); pending.set(FocusMeteringResult.create(true)); h.advance(12000)
      assertEquals(beforeClose,h.events.size)
    }
  }
  @Test fun aScanTriggersOnlyOnceItsOptionsApplyAndNeverRepeats() = main {
    Harness().use { h ->
      val applied = ControlledFuture<Void?>()
      h.pendingOptions = applied
      h.capture()
      assertEquals(0,h.futures.size)
      applied.set(null)
      assertEquals(1,h.futures.size)
      h.advance(1900)
      assertEquals(1,h.futures.size)
      h.futures.last().set(FocusMeteringResult.create(true))
      assertTrue(h.focus.snapshot.locked == true)
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
      assertNull(h.options.last().owner)
      assertEquals(1,h.cancels)
    }
  }
  @Test fun shotsBlockTakeoverWhileAWaitingCaptureDoesNotOwnFocus() = main {
    Harness().use { h ->
      h.shot = true; h.tap(); h.capture()
      assertTrue(h.futures.isEmpty())
      assertNull(h.focus.snapshot.owner)
      h.shot = false; h.tap()
      assertEquals(FocusOwner.TAP,h.focus.snapshot.owner)
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
    val events = mutableListOf<FocusSnapshot>()
    val options = mutableListOf<FocusSnapshot>()
    private val tasks = mutableListOf<Pair<Long,() -> Unit>>()
    private val control = Proxy.newProxyInstance(CameraControl::class.java.classLoader,arrayOf(CameraControl::class.java)) { _, method, _ ->
      when (method.name) {
        "startFocusAndMetering" -> ControlledFuture<FocusMeteringResult>().also { futures.add(it) }
        "cancelFocusAndMetering" -> { cancels++; ControlledFuture<Void?>().also { it.set(null) } }
        else -> throw UnsupportedOperationException(method.name)
      }
    } as CameraControl
    override val camera = Proxy.newProxyInstance(Camera::class.java.classLoader,arrayOf(Camera::class.java)) { _, method, _ ->
      when (method.name) { "getCameraControl" -> control; else -> throw UnsupportedOperationException(method.name) }
    } as Camera
    override val mainExecutor = Executor { it.run() }
    override fun shotInFlight() = shot
    override fun uptimeNs() = now * 1_000_000
    /** The next options' future, or null for options the camera has at once. */
    var pendingOptions: ControlledFuture<Void?>? = null
    @Suppress("UNCHECKED_CAST")
    override fun applyCaptureOptions(): ListenableFuture<Void>? {
      options.add(focus.snapshot)
      return pendingOptions?.also { pendingOptions = null } as ListenableFuture<Void>?
    }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { tasks.add((now + delayMs) to block) }
    override fun log(message: String) = Unit
    override fun focusChanged(snapshot: FocusSnapshot) { events.add(snapshot) }
    val focus = FocusController(this)
    private val point = SurfaceOrientedMeteringPointFactory(100f,100f).createPoint(50f,50f)
    fun tap() = focus.tap(camera,point)
    fun capture() = focus.capture(camera,point)
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
