package com.margelo.nitro.documentscanner

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
class CameraLightControllerTest {
  private fun main(block: () -> Unit) {
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
    failure?.let { throw it }
  }
  @Test fun unsupportedOrUnboundCameraNeverRequestsHardwareOrReadsAutoState() = main {
    Harness().use { h ->
      h.flash = false
      for (mode in TorchMode.entries) h.apply(mode)
      assertTrue(h.commands.isEmpty()); assertTrue(h.boosts.isEmpty()); assertTrue(h.controller.pending.not()); assertEquals(0,h.autoReads)
      h.controller.apply(null,TorchMode.AUTO,true,false) { error("No bound camera") }
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
      assertEquals(false,h.controller.torchLit)
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
      assertFalse(h.controller.pending); assertEquals(true,h.controller.torchLit); assertEquals(1,h.settled)
      h.apply(TorchMode.AUTO); assertEquals(1,h.commands.size)
    }
  }
  @Test fun failedHardwareChangeRetainsAutoLatchAndCanRetry() = main {
    Harness().use { h ->
      h.policy.observe(0,66_000_000,1600); h.policy.observe(1_000_000_000,66_000_000,1600)
      h.apply(TorchMode.AUTO); h.futures.last().fail()
      assertNull(h.controller.torchLit); assertFalse(h.controller.pending); assertTrue(h.policy.lit)
      h.apply(TorchMode.AUTO)
      assertEquals(listOf(true,true),h.commands)
      h.futures.last().set(null)
      assertEquals(true,h.controller.torchLit); assertEquals(2,h.settled)
    }
  }
  @Test fun supersededModeRequestCannotPublishItsLateCompletion() = main {
    Harness().use { h ->
      h.apply(TorchMode.ON); val old = h.futures.last()
      h.apply(TorchMode.OFF); val current = h.futures.last()
      old.set(null)
      assertNull(h.controller.torchLit); assertTrue(h.controller.pending); assertEquals(0,h.settled)
      current.set(null)
      assertEquals(false,h.controller.torchLit); assertFalse(h.controller.pending); assertEquals(1,h.settled)
    }
  }
  @Test fun cameraStopResetsLatchAndOldBindingCompletionCannotSettleNewRequest() = main {
    Harness().use { h ->
      h.policy.observe(0,66_000_000,1600); h.policy.observe(1_000_000_000,66_000_000,1600)
      h.apply(TorchMode.AUTO); val old = h.futures.last()
      h.controller.unbind(); h.policy.reset(); h.apply(TorchMode.AUTO)
      assertEquals(listOf(true,false),h.commands)
      old.set(null); assertNull(h.controller.torchLit); assertEquals(0,h.settled)
      h.futures.last().set(null)
      assertEquals(false,h.controller.torchLit); assertEquals(1,h.settled)
    }
  }
  @Test fun completedHardwareFutureIsReadyBeforeQueuedMainCallbackPublishes() = main {
    Harness(queueCallbacks = true).use { h ->
      h.apply(TorchMode.ON); assertTrue(h.controller.pending)
      h.futures.last().set(null)
      assertFalse(h.controller.pending); assertNull(h.controller.torchLit)
      h.callbacks.removeAt(0).run()
      assertEquals(true,h.controller.torchLit); assertEquals(1,h.settled)
    }
  }
  @Test fun boostFollowsTheTorchModeAndDefersWithItDuringCapture() = main {
    Harness().use { h ->
      h.boostSupported = true
      // Torch on: boost goes off first, then the torch lights.
      h.apply(TorchMode.ON, boost = true)
      assertEquals(listOf("boost=false","torch=true"),h.order)
      h.completeAll()
      // Torch off during a capture: neither the torch nor the boost changes until it settles.
      h.capture = true; h.apply(TorchMode.OFF, boost = true)
      assertEquals(2,h.order.size)
      h.capture = false; h.apply(TorchMode.OFF, boost = true)
      assertEquals(listOf("boost=false","torch=true","torch=false","boost=true"),h.order)
      assertTrue(h.controller.pending)
      h.completeAll()
      assertEquals(true,h.controller.boostOn); assertFalse(h.controller.pending)
    }
  }
  @Test fun refusedBoostReportsAndLeavesADarkSceneUnlitForTheModeChoice() = main {
    Harness().use { h ->
      h.boostSupported = true
      fun fallback(atBind: Boolean = false) = h.controller.zslFallback(CaptureMode.ZSL,TorchMode.OFF,false,true,true,true,
        dark = true, whiteBalanceUnsettled = false, atBind = atBind)
      assertNull(fallback(atBind = true))
      h.apply(TorchMode.OFF, boost = true); h.boostFutures.last().fail()
      assertEquals(1,h.refused); assertNull(h.controller.boostOn)
      assertEquals(LightFallback.DARK,fallback())
      // The refusal outlives the binding, so the next bind still binds `latency`; a later success clears it.
      h.controller.unbind()
      assertEquals(LightFallback.DARK,fallback(atBind = true))
      h.apply(TorchMode.OFF, boost = true); h.boostFutures.last().set(null)
      assertEquals(true,h.controller.boostOn)
      assertNull(fallback(atBind = true))
    }
  }
  @Test fun staleBoostCompletionAfterUnbindPublishesNothing() = main {
    Harness().use { h ->
      h.boostSupported = true
      h.apply(TorchMode.OFF, boost = true); val old = h.boostFutures.last()
      h.controller.unbind()
      old.fail()
      assertEquals(0,h.refused); assertEquals(0,h.settled); assertNull(h.controller.boostOn)
    }
  }
  @Test fun fallbackLatchesUntilABindFindsNoCauseAndExplicitModesNeverFallBack() = main {
    Harness().use { h ->
      val c = h.controller
      // requested, torch mode, auto lit, flash, boost wanted, boost supported, dark, white balance searching, at bind
      assertEquals(LightFallback.WHITE_BALANCE,c.zslFallback(CaptureMode.ZSL,TorchMode.OFF,false,true,false,false,false,true,false))
      assertEquals(LightFallback.WHITE_BALANCE,c.zslFallback(CaptureMode.ZSL,TorchMode.OFF,false,true,false,false,false,false,false))
      assertNull(c.zslFallback(CaptureMode.LATENCY,TorchMode.ON,false,true,false,false,false,true,true))
      assertNull(c.zslFallback(CaptureMode.ZSL,TorchMode.OFF,false,true,false,false,false,false,true))
      // A working boost lights a dark scene; nothing else does without a torch.
      assertNull(c.zslFallback(CaptureMode.ZSL,TorchMode.OFF,false,true,true,true,true,false,true))
      assertEquals(LightFallback.DARK,c.zslFallback(CaptureMode.ZSL,TorchMode.OFF,false,true,true,false,true,false,true))
    }
  }
  @Test fun aLitTorchFallsBackAtOnceAndAnAutoTorchLitAtTheStopCountsAtTheNextBind() = main {
    Harness().use { h ->
      val c = h.controller
      assertEquals(LightFallback.TORCH,c.zslFallback(CaptureMode.ZSL,TorchMode.ON,false,true,false,false,false,false,true))
      // Without a flash unit the torch lights nothing; auto counts only once it decided to light.
      assertNull(c.zslFallback(CaptureMode.ZSL,TorchMode.ON,false,false,false,false,false,false,true))
      assertNull(c.zslFallback(CaptureMode.ZSL,TorchMode.AUTO,false,true,false,false,true,false,true))
      assertEquals(LightFallback.TORCH,c.zslFallback(CaptureMode.ZSL,TorchMode.AUTO,true,true,false,false,true,false,false))
      // The auto torch relearns the room after a stop; lit at the stop, the next bind still binds `latency`.
      h.policy.observe(0,66_000_000,1600); h.policy.observe(1_000_000_000,66_000_000,1600)
      h.apply(TorchMode.AUTO); h.futures.last().set(null)
      c.unbind()
      assertEquals(LightFallback.TORCH,c.zslFallback(CaptureMode.ZSL,TorchMode.AUTO,false,true,false,false,false,false,true))
      // Not relit during that binding: the following bind returns to `zsl`.
      c.unbind()
      assertNull(c.zslFallback(CaptureMode.ZSL,TorchMode.AUTO,false,true,false,false,false,false,true))
    }
  }
  private class ControlledFuture : ListenableFuture<Void?> {
    private val result = CompletableFuture<Void?>()
    fun set(value: Void?) { result.complete(value) }
    fun fail() { result.completeExceptionally(IllegalStateException("Fake light failure")) }
    override fun addListener(listener: Runnable, executor: Executor) { result.whenComplete { _, _ -> executor.execute(listener) } }
    override fun cancel(interrupt: Boolean) = result.cancel(interrupt)
    override fun isCancelled() = result.isCancelled
    override fun isDone() = result.isDone
    override fun get(): Void? = result.get()
    override fun get(timeout: Long, unit: TimeUnit): Void? = result.get(timeout,unit)
  }
  private class Harness(queueCallbacks: Boolean = false) : AutoCloseable {
    var flash = true
    var boostSupported = false
    var capture = false
    var settled = 0
    var refused = 0
    var autoReads = 0
    val commands = mutableListOf<Boolean>()
    val boosts = mutableListOf<Boolean>()
    val order = mutableListOf<String>()
    val futures = mutableListOf<ControlledFuture>()
    val boostFutures = mutableListOf<ControlledFuture>()
    val callbacks = mutableListOf<Runnable>()
    val policy = TorchPolicy()
    val controller = CameraLightController(Executor { if (queueCallbacks) callbacks.add(it) else it.run() },
      { settled++ }, { refused++ }, {})
    private val info = Proxy.newProxyInstance(CameraInfo::class.java.classLoader,arrayOf(CameraInfo::class.java)) { _, method, _ ->
      when (method.name) { "hasFlashUnit" -> flash; "isLowLightBoostSupported" -> boostSupported; else -> error(method.name) }
    } as CameraInfo
    private val control = Proxy.newProxyInstance(CameraControl::class.java.classLoader,arrayOf(CameraControl::class.java)) { _, method, args ->
      when (method.name) {
        "enableTorch" -> {
          commands.add(args!![0] as Boolean); order.add("torch=${args[0]}"); ControlledFuture().also { futures.add(it) }
        }
        "enableLowLightBoostAsync" -> {
          boosts.add(args!![0] as Boolean); order.add("boost=${args[0]}"); ControlledFuture().also { boostFutures.add(it) }
        }
        else -> error(method.name)
      }
    } as CameraControl
    private val camera = BoundCamera(control, info)
    fun apply(mode: TorchMode, boost: Boolean = false) = controller.apply(camera,mode,boost,capture) { autoReads++; policy.lit }
    fun completeAll() { (futures + boostFutures).forEach { if (!it.isDone) it.set(null) } }
    override fun close() { controller.unbind(); policy.reset() }
  }
}
