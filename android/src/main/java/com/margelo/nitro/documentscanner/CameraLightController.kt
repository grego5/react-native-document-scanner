package com.margelo.nitro.documentscanner

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Why `zsl` bound `latency`: a lit torch, white balance that keeps searching, a gated shot the zero-shutter-lag buffer
 * starved, or a sustained dark scene with nothing lighting it. */
internal enum class LightFallback { TORCH, WHITE_BALANCE, STARVED_BUFFER, DARK }

/**
 * Main-thread owner of the camera's light: the torch and low-light boost commands, their CameraX acknowledgements, their
 * deferral while a capture is pending, and the light-driven choice of `latency` for `zsl`. Both controls change the
 * exposure a capture is judged on, so they share one lifecycle: [apply] sends nothing while a capture is pending, and the
 * call after it settles sends the latest wish. [unbind] retires every request of the old binding, so a late completion
 * publishes nothing. The auto torch's decision stays in the portable policy, read through `autoLit`.
 */
internal class CameraLightController(private val executor: Executor, private val settled: () -> Unit,
  private val boostRefused: () -> Unit, private val log: (String) -> Unit) {
  /** What CameraX confirmed for the current binding; null before a confirmation or after a failure. */
  var torchLit: Boolean? = null
    private set
  private var requestedBoost: Boolean? = null
  private val torch = Slot()
  private val boost = Slot()
  /** The camera refused the last boost enable, as it does above 30 fps; cleared when one succeeds. Survives unbinding,
   * so the next binding's mode choice knows. */
  private var boostRejected = false
  /** `zsl`'s light fallback, held across restarts until a bind finds no cause; see [zslFallback]. */
  private var fallback: LightFallback? = null
  /** The torch was lit or lighting when the last binding stopped. The auto torch relearns the room after a stop, which
   * takes a dark second, so the next bind takes it as still lit. */
  private var litAtStop = false
  /** A gated `zsl` shot was rejected with everything but a buffered frame ready; see [bufferStarved]. */
  private var starved = false

  /** A torch or boost change CameraX has not confirmed yet. */
  val pending: Boolean get() = torch.pending || boost.pending

  fun unbind() {
    litAtStop = torch.request?.on ?: (torchLit == true)
    torch.request = null
    boost.request = null
    torchLit = null
    requestedBoost = null
  }

  /**
   * Applies both settings. CameraX keeps only the last one set of the torch and the boost on, so the boost is asked off
   * while the torch is lit, or held lit, and asked on again once it is off; otherwise a torch lit over the boost would
   * leave the boost off for good. The boost and the user's own torch mode wait while a capture is pending
   * ([capturePending]), so a press keeps the light it was admitted with. The auto torch's lit state, from a dark room or
   * the focus cycle's torch step alike, waits only while [autoHeld]: the view holds it for a shot being taken, and for a
   * whole pending capture only with zero shutter lag, whose buffer refuses frames while white balance settles under a
   * new light. A press waiting for focus in the dark otherwise gets that light.
   */
  fun apply(camera: BoundCamera?, mode: TorchMode, lowLightBoost: Boolean, capturePending: Boolean, autoHeld: Boolean,
    autoLit: () -> Boolean) {
    if (camera == null) return
    val flash = camera.info.hasFlashUnit()
    val torchHeld = if (mode == TorchMode.AUTO) autoHeld else capturePending
    val lit = when {
      !flash -> false
      torchHeld -> (torch.request?.on ?: torchLit) == true
      else -> when (mode) { TorchMode.ON -> true; TorchMode.AUTO -> autoLit(); TorchMode.OFF -> false }
    }
    val boost = !capturePending && camera.info.isLowLightBoostSupported
    // The boost goes off before the torch lights, and the torch off before the boost returns.
    if (lit && boost) setBoost(camera, false)
    if (flash && !torchHeld) setTorch(camera, lit)
    if (!lit && boost) setBoost(camera, lowLightBoost)
  }

  /**
   * Main thread: why `zsl` binds `latency`, or null to bind as [requested]. CameraX's zero-shutter-lag buffer accepts
   * only frames reporting focus, exposure and white balance settled and returns the newest one however old. In a
   * sustained [dark] scene with the torch mode `off` and no boost that works, focus scans and autofocus blur, and the
   * buffer returned frames from before the scan or the page. While white balance stays [whiteBalanceUnsettled], the
   * buffer takes no new frame: under the auto torch and a lamp on the SM-G998U every gated shot was rejected. Once a
   * gated shot was rejected for want of a buffered frame ([bufferStarved]), `latency` holds for this controller's life:
   * flickering light or moving shadows keep exposure adjusting, and on the SM-G998U under a ceiling fan the buffer took
   * no frame while page, focus and steadiness held. A lit
   * torch switches by itself: on the SM-G998U white balance under it almost always searched, so waiting for that cost a
   * second and a restart after the press. The torch counts as lit with [mode] `on`, or `auto` once it decided to light
   * ([autoLit]) or was lit when the camera last stopped, on a camera with a [hasFlash] unit.
   *
   * The dark cause applies only with [darkLatency], the experimental `darkLatency` prop: a page press now waits for its
   * focus lock and for the newest frame the buffer accepts, which keeps frames from before the scan out of the photo.
   *
   * A cause latches the fallback at once; only a bind ([atBind]) that finds none releases it. Switching to `latency`
   * is the safe direction and rebinds at once; switching back waits for a bind that happens anyway, such as the one after
   * each shot, so a page never costs two restarts.
   */
  fun zslFallback(requested: CaptureMode, mode: TorchMode, autoLit: Boolean, hasFlash: Boolean, lowLightBoost: Boolean,
    boostSupported: Boolean, dark: Boolean, whiteBalanceUnsettled: Boolean, atBind: Boolean,
    darkLatency: Boolean = false): LightFallback? {
    if (requested != CaptureMode.ZSL) return null
    val torchLit = hasFlash && (mode == TorchMode.ON || mode == TorchMode.AUTO && (autoLit || litAtStop))
    val unlit = mode == TorchMode.OFF && !(lowLightBoost && boostSupported && !boostRejected)
    val cause = when {
      torchLit -> LightFallback.TORCH
      whiteBalanceUnsettled -> LightFallback.WHITE_BALANCE
      starved -> LightFallback.STARVED_BUFFER
      unlit && dark && darkLatency -> LightFallback.DARK
      else -> null
    }
    if (cause != null || atBind) fallback = cause
    return fallback
  }

  /** Main thread: a gated `zsl` shot was rejected because no frame the buffer accepts arrived; later binds of this
   * controller choose `latency`. */
  fun bufferStarved() {
    starved = true
  }

  private fun setTorch(camera: BoundCamera, lit: Boolean) {
    if (lit == (torch.request?.on ?: torchLit)) return
    log("torch lit=$lit")
    send(torch, lit, camera.control.enableTorch(lit)) { ok ->
      torchLit = if (ok) lit else null
      if (ok) log("torch confirmed lit=$lit")
    }
  }

  private fun setBoost(camera: BoundCamera, on: Boolean) {
    if (on == requestedBoost) return
    requestedBoost = on
    log("low light boost enabled=$on")
    send(boost, on, camera.control.enableLowLightBoostAsync(on)) { ok ->
      if (on) {
        boostRejected = !ok
        if (!ok) boostRefused()
      }
    }
  }

  /** CameraX supersedes an earlier change; a newer request or [unbind] retires this one's completion by identity. */
  private fun send(slot: Slot, on: Boolean, future: ListenableFuture<Void>, publish: (Boolean) -> Unit) {
    val next = Request(on, future)
    slot.request = next
    future.addListener({
      if (slot.request !== next) return@addListener
      slot.request = null
      val ok = try { future.get(); true } catch (error: Exception) {
        log("light change on=$on failed ${error.javaClass.simpleName}: ${error.message}")
        false
      }
      publish(ok)
      settled()
    }, executor)
  }

  private class Request(val on: Boolean, val future: ListenableFuture<Void>)
  /** One control's latest request; its future done means CameraX answered, before the callback publishes. */
  private class Slot {
    var request: Request? = null
    val pending: Boolean get() = request?.future?.isDone == false
  }
}
