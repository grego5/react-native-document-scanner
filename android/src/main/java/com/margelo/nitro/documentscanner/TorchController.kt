package com.margelo.nitro.documentscanner

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Main-thread hardware request owner. Auto decisions stay in the portable policy; readiness is CameraX evidence. */
internal class TorchController(private val executor: Executor, private val settled: () -> Unit,
  private val log: (String) -> Unit) {
  var applied: Boolean? = null
    private set
  private var request: Request? = null
  val pending: Boolean get() = request?.future?.isDone == false
  fun reset() { request = null; applied = null }
  fun apply(camera: BoundCamera?, mode: TorchMode, capturePending: Boolean, autoLit: () -> Boolean) {
    if (camera == null || !camera.info.hasFlashUnit() || capturePending) return
    val lit = when (mode) { TorchMode.ON -> true; TorchMode.AUTO -> autoLit(); TorchMode.OFF -> false }
    if (lit == (request?.lit ?: applied)) return
    log("torch lit=$lit mode=$mode")
    // CameraX supersedes an earlier change. Reset/new request invalidates the old callback by identity.
    val next = Request(lit, camera.control.enableTorch(lit))
    request = next
    next.future.addListener({
      if (request !== next) return@addListener
      request = null
      applied = try { next.future.get(); lit } catch (error: Exception) {
        log("torch lit=$lit failed: ${error.message}")
        null
      }
      settled()
    }, executor)
  }
  private class Request(val lit: Boolean, val future: ListenableFuture<Void>)
}
