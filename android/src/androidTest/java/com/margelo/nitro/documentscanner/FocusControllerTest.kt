package com.margelo.nitro.documentscanner

import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.transform.OutputTransform
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.processing.LiveFocusCandidate
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Fake CameraX futures/clock; no camera opens or shutter calls. All policy/hardware adaptation runs on main. */
class FocusControllerTest {
  @Before fun initializeNative() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
  }
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
      h.shot = false; h.focus.afterCapture(true)
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
  @Test fun retriggerKeepsPolicyGenerationAndOnlyLatestAttemptCanSettle() = main {
    Harness().use { h ->
      h.candidate(); val first = h.futures.last(); val generation = h.focus.snapshot.generation
      h.advance(700)
      assertEquals(2,h.futures.size)
      assertEquals(generation,h.focus.snapshot.generation)
      first.set(FocusMeteringResult.create(true))
      assertNull(h.focus.snapshot.locked)
      h.futures.last().set(FocusMeteringResult.create(true))
      assertTrue(h.focus.snapshot.locked == true)
      h.advance(2500)
      assertEquals(FocusOwner.DOCUMENT,h.focus.snapshot.owner)
    }
  }
  @Test fun documentTimeoutSettlesButShotDefersReleaseUntilAfterCapture() = main {
    Harness().use { h ->
      h.candidate(); h.shot = true; h.advance(2000)
      assertEquals(false,h.focus.snapshot.locked)
      assertEquals(FocusOwner.DOCUMENT,h.focus.snapshot.owner)
      assertEquals(1,h.futures.size)
      h.shot = false; h.focus.afterCapture(true)
      assertNull(h.focus.snapshot.owner)
    }
  }
  @Test fun targetLossDeadlineReleasesWithoutInventingNewGeometry() = main {
    Harness().use { h ->
      h.candidate(); h.futures.last().set(FocusMeteringResult.create(true))
      h.advance(200); h.candidate()
      h.advance(500); h.candidate(null)
      h.advance(1199); assertEquals(FocusOwner.DOCUMENT,h.focus.snapshot.owner)
      h.advance(1200); assertNull(h.focus.snapshot.owner)
      assertEquals(1,h.futures.size)
      h.advance(1300); h.candidate()
      assertEquals(2,h.futures.size)
    }
  }
  @Test fun lateSuccessReleasesAnAlreadyMissingTargetUnlessAShotHoldsIt() = main {
    for (shot in listOf(false, true)) Harness().use { h ->
      h.candidate()
      h.advance(100); h.candidate(null)
      h.advance(1500)
      h.shot = shot
      h.futures.last().set(FocusMeteringResult.create(true))
      if (shot) {
        assertEquals(FocusOwner.DOCUMENT, h.focus.snapshot.owner)
        h.shot = false
        h.focus.afterCapture(true)
      }
      assertNull(h.focus.snapshot.owner)
      assertEquals(1, h.cancels)
      h.advance(3000)
      assertNull(h.focus.snapshot.owner)
    }
  }
  @Test fun confirmedScaleChangeAndFailureRetryStillNeedFreshCandidates() = main {
    Harness().use { h ->
      h.candidate(); h.futures.last().set(FocusMeteringResult.create(true))
      h.advance(100); h.candidate()
      h.advance(200); h.candidate(h.largePage)
      assertEquals(1,h.futures.size)
      h.advance(300); h.candidate(h.largePage)
      assertEquals(2,h.futures.size)
      h.futures.last().set(FocusMeteringResult.create(false))
      assertNull(h.focus.snapshot.owner)
      h.advance(1299); h.candidate()
      assertEquals(2,h.futures.size)
      h.advance(1300)
      assertEquals(2,h.futures.size) // Wakeup alone does not reuse the last candidate.
      h.candidate(); assertEquals(3,h.futures.size)
    }
  }
  @Test fun shotsBlockTakeoverWhileAWaitingCaptureDoesNotOwnFocus() = main {
    Harness().use { h ->
      h.shot = true; h.tap(); h.candidate()
      assertTrue(h.futures.isEmpty())
      h.shot = false; h.candidate()
      assertEquals(FocusOwner.DOCUMENT,h.focus.snapshot.owner)
      h.tap()
      assertEquals(FocusOwner.TAP,h.focus.snapshot.owner)
      h.candidate(h.largePage)
      assertEquals(2,h.futures.size)
    }
  }
  @Test fun unavailableMeteringPointUsesFailureCooldownAndOldTargetCannotHoldLens() = main {
    Harness().use { h ->
      h.candidate(); h.futures.last().set(FocusMeteringResult.create(true))
      h.advance(100); h.candidate()
      h.advance(200); h.candidate(h.largePage)
      h.advance(300); h.candidate(h.largePage, validPoint = false)
      assertNull(h.focus.snapshot.owner)
      h.advance(1299); h.candidate()
      assertEquals(1,h.futures.size)
      h.advance(1300); h.candidate()
      assertEquals(2,h.futures.size)
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
    override fun applyCaptureOptions() { options.add(focus.snapshot) }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { tasks.add((now + delayMs) to block) }
    override fun log(message: String) = Unit
    override fun focusChanged(snapshot: FocusSnapshot) { events.add(snapshot) }
    val focus = FocusController(this)
    val page = rect(.2,.2,.8,.8)
    val largePage = rect(.16,.16,.84,.84)
    private val point = SurfaceOrientedMeteringPointFactory(100f,100f).createPoint(50f,50f)
    fun tap() = focus.tap(camera,point)
    fun candidate(quad: DocumentQuad? = page, validPoint: Boolean = true) = focus.onCandidate(
      LiveFocusCandidate(quad,OutputTransform(Matrix(),Size(1000,1000)),1000,1000,true,false),true) {
      point.takeIf { validPoint }
    }
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
    private fun rect(l: Double,t: Double,r: Double,b: Double) = DocumentQuad(DocumentPoint(l,t),DocumentPoint(r,t),DocumentPoint(r,b),DocumentPoint(l,b))
  }
}
