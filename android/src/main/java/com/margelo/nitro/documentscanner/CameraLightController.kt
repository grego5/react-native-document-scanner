package com.margelo.nitro.documentscanner

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Why `zsl` bound `latency`: a lit torch, white balance that keeps searching, or a sustained dark scene with nothing
 * lighting it. */
internal enum class LightFallback { TORCH, WHITE_BALANCE, DARK }

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
  var boostOn: Boolean? = null
    private set
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

  /** A torch or boost change CameraX has not confirmed yet. */
  val pending: Boolean get() = torch.pending || boost.pending

  fun unbind() {
    litAtStop = torch.request?.on ?: (torchLit == true)
    torch.request = null
    boost.request = null
    torchLit = null
    boostOn = null
  }

  /** Sends the torch for [mode] and the boost for [lowLightBoost], which applies only with the torch mode `off`, unless
   * a capture is pending. The boost goes off before a torch lights; CameraX turns the torch off when the boost goes on. */
  fun apply(camera: BoundCamera?, mode: TorchMode, lowLightBoost: Boolean, capturePending: Boolean, autoLit: () -> Boolean) {
    if (camera == null || capturePending) return
    val boostSupported = camera.info.isLowLightBoostSupported
    val boosted = boostSupported && lowLightBoost && mode == TorchMode.OFF
    if (boostSupported && !boosted) setBoost(camera, false)
    if (camera.info.hasFlashUnit()) {
      setTorch(camera, when (mode) { TorchMode.ON -> true; TorchMode.AUTO -> autoLit(); TorchMode.OFF -> false })
    }
    if (boosted) setBoost(camera, true)
  }

  /**
   * Main thread: why `zsl` binds `latency`, or null to bind as [requested]. CameraX's zero-shutter-lag buffer accepts
   * only frames reporting focus, exposure and white balance settled and returns the newest one however old. In a
   * sustained [dark] scene with the torch mode `off` and no boost that works, focus scans and autofocus blur, and the
   * buffer returned frames from before the scan or the page. While white balance stays [whiteBalanceUnsettled], the
   * buffer takes no new frame: under the auto torch and a lamp on the SM-G998U every gated shot was rejected. A lit
   * torch switches by itself: on the SM-G998U white balance under it almost always searched, so waiting for that cost a
   * second and a restart after the press. The torch counts as lit with [mode] `on`, or `auto` once it decided to light
   * ([autoLit]) or was lit when the camera last stopped, on a camera with a [hasFlash] unit.
   *
   * A cause latches the fallback at once; only a bind ([atBind]) that finds none releases it. Switching to `latency`
   * is the safe direction and rebinds at once; switching back waits for a bind that happens anyway, such as the one after
   * each shot, so a page never costs two restarts.
   */
  fun zslFallback(requested: CaptureMode, mode: TorchMode, autoLit: Boolean, hasFlash: Boolean, lowLightBoost: Boolean,
    boostSupported: Boolean, dark: Boolean, whiteBalanceUnsettled: Boolean, atBind: Boolean): LightFallback? {
    if (requested != CaptureMode.ZSL) return null
    val torchLit = hasFlash && (mode == TorchMode.ON || mode == TorchMode.AUTO && (autoLit || litAtStop))
    val unlit = mode == TorchMode.OFF && !(lowLightBoost && boostSupported && !boostRejected)
    val cause = when {
      torchLit -> LightFallback.TORCH
      whiteBalanceUnsettled -> LightFallback.WHITE_BALANCE
      unlit && dark -> LightFallback.DARK
      else -> null
    }
    if (cause != null || atBind) fallback = cause
    return fallback
  }

  private fun setTorch(camera: BoundCamera, lit: Boolean) {
    if (lit == (torch.request?.on ?: torchLit)) return
    log("torch lit=$lit")
    send(torch, lit, camera.control.enableTorch(lit)) { ok ->
      torchLit = if (ok) lit else null
    }
  }

  private fun setBoost(camera: BoundCamera, on: Boolean) {
    if (on == (boost.request?.on ?: boostOn)) return
    log("low light boost enabled=$on")
    send(boost, on, camera.control.enableLowLightBoostAsync(on)) { ok ->
      boostOn = if (ok) on else null
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
