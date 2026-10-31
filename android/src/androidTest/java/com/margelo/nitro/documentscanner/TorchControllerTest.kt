package com.margelo.nitro.documentscanner

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.processing.TorchPolicy
import com.google.common.util.concurrent.ListenableFuture
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Fake hardware/futures on main, combined with the Kotlin auto policy. No camera or shutter opens. */
class TorchControllerTest {
  private fun main(block: () -> Unit) {
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
    failure?.let { throw it }
  }
  @Test fun unsupportedOrUnboundCameraNeverRequestsHardwareOrReadsAutoState() = main {
    Harness().use { h ->
      h.flash = false
      for (mode in TorchMode.entries) h.apply(mode)
      assertTrue(h.commands.isEmpty()); assertTrue(h.controller.pending.not()); assertEquals(0,h.autoReads)
      h.controller.apply(null,TorchMode.AUTO,false) { error("No bound camera") }
      assertTrue(h.commands.isEmpty())
    }
  }
  @Test fun modeChangesDuringCaptureDeferToTheLatestModeAfterCapture() = main {
    Harness().use { h ->
      h.apply(TorchMode.ON); h.futures.last().set(null)
      h.capture = true
      h.apply(TorchMode.OFF)
      h.policy.reset(); h.apply(TorchMode.AUTO)
      h.apply(TorchMode.ON); h.apply(TorchMode.OFF)
      assertEquals(listOf(true),h.commands)
      h.capture = false; h.apply(TorchMode.OFF)
      assertEquals(listOf(true,false),h.commands)
      assertTrue(h.controller.pending)
      h.futures.last().set(null)
      assertEquals(false,h.controller.applied)
      assertFalse(h.controller.pending)
    }
  }
  @Test fun autoLatchDuringCaptureIsAppliedLaterAndReadinessUsesHardwareEvidence() = main {
    Harness().use { h ->
      h.capture = true
      assertFalse(h.policy.observe(0,66_000_000,1600))
      assertTrue(h.policy.observe(1_000_000_000,66_000_000,1600))
      h.apply(TorchMode.AUTO)
      assertTrue(h.commands.isEmpty())
      h.capture = false; h.apply(TorchMode.AUTO)
      assertEquals(listOf(true),h.commands); assertTrue(h.controller.pending)
      assertFalse(h.policy.observe(2_000_000_000,1_000_000,100))
      h.apply(TorchMode.AUTO); assertEquals(1,h.commands.size)
      h.futures.last().set(null)
      assertFalse(h.controller.pending); assertEquals(true,h.controller.applied); assertEquals(1,h.settled)
      h.apply(TorchMode.AUTO); assertEquals(1,h.commands.size)
    }
  }
  @Test fun failedHardwareChangeRetainsAutoLatchAndCanRetry() = main {
    Harness().use { h ->
      h.policy.observe(0,66_000_000,1600); h.policy.observe(1_000_000_000,66_000_000,1600)
      h.apply(TorchMode.AUTO); h.futures.last().fail()
      assertNull(h.controller.applied); assertFalse(h.controller.pending); assertTrue(h.policy.lit)
      h.apply(TorchMode.AUTO)
      assertEquals(listOf(true,true),h.commands)
      h.futures.last().set(null)
      assertEquals(true,h.controller.applied); assertEquals(2,h.settled)
    }
  }
  @Test fun supersededModeRequestCannotPublishItsLateCompletion() = main {
    Harness().use { h ->
      h.apply(TorchMode.ON); val old = h.futures.last()
      h.apply(TorchMode.OFF); val current = h.futures.last()
      old.set(null)
      assertNull(h.controller.applied); assertTrue(h.controller.pending); assertEquals(0,h.settled)
      current.set(null)
      assertEquals(false,h.controller.applied); assertFalse(h.controller.pending); assertEquals(1,h.settled)
    }
  }
  @Test fun cameraStopResetsLatchAndOldBindingCompletionCannotSettleNewRequest() = main {
    Harness().use { h ->
      h.policy.observe(0,66_000_000,1600); h.policy.observe(1_000_000_000,66_000_000,1600)
      h.apply(TorchMode.AUTO); val old = h.futures.last()
      h.controller.reset(); h.policy.reset(); h.apply(TorchMode.AUTO)
      assertEquals(listOf(true,false),h.commands)
      old.set(null); assertNull(h.controller.applied); assertEquals(0,h.settled)
      h.futures.last().set(null)
      assertEquals(false,h.controller.applied); assertEquals(1,h.settled)
    }
  }
  @Test fun completedHardwareFutureIsReadyBeforeQueuedMainCallbackPublishes() = main {
    Harness(queueCallbacks = true).use { h ->
      h.apply(TorchMode.ON); assertTrue(h.controller.pending)
      h.futures.last().set(null)
      assertFalse(h.controller.pending); assertNull(h.controller.applied)
      h.callbacks.removeAt(0).run()
      assertEquals(true,h.controller.applied); assertEquals(1,h.settled)
    }
  }
  private class ControlledFuture : ListenableFuture<Void?> {
    private val result = CompletableFuture<Void?>()
    fun set(value: Void?) { result.complete(value) }
    fun fail() { result.completeExceptionally(IllegalStateException("Fake torch failure")) }
    override fun addListener(listener: Runnable, executor: Executor) { result.whenComplete { _, _ -> executor.execute(listener) } }
    override fun cancel(interrupt: Boolean) = result.cancel(interrupt)
    override fun isCancelled() = result.isCancelled
    override fun isDone() = result.isDone
    override fun get(): Void? = result.get()
    override fun get(timeout: Long, unit: TimeUnit): Void? = result.get(timeout,unit)
  }
  private class Harness(queueCallbacks: Boolean = false) : AutoCloseable {
    var flash = true
    var capture = false
    var settled = 0
    var autoReads = 0
    val commands = mutableListOf<Boolean>()
    val futures = mutableListOf<ControlledFuture>()
    val callbacks = mutableListOf<Runnable>()
    val policy = TorchPolicy()
    val controller = TorchController(Executor { if (queueCallbacks) callbacks.add(it) else it.run() },{ settled++ },{})
    private val info = Proxy.newProxyInstance(CameraInfo::class.java.classLoader,arrayOf(CameraInfo::class.java)) { _, method, _ ->
      when (method.name) { "hasFlashUnit" -> flash; else -> error(method.name) }
    } as CameraInfo
    private val control = Proxy.newProxyInstance(CameraControl::class.java.classLoader,arrayOf(CameraControl::class.java)) { _, method, args ->
      when (method.name) {
        "enableTorch" -> { commands.add(args!![0] as Boolean); ControlledFuture().also { futures.add(it) } }
        else -> error(method.name)
      }
    } as CameraControl
    private val camera = Proxy.newProxyInstance(Camera::class.java.classLoader,arrayOf(Camera::class.java)) { _, method, _ ->
      when (method.name) { "getCameraInfo" -> info; "getCameraControl" -> control; else -> error(method.name) }
    } as Camera
    fun apply(mode: TorchMode) = controller.apply(camera,mode,capture) { autoReads++; policy.lit }
    override fun close() { controller.reset(); policy.reset() }
  }
}
