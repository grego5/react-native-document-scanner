package com.margelo.nitro.documentscanner

import java.util.concurrent.Executor

/** View reads share the process image FIFO. Each admitted read owns its own file pin until it runs. */
internal object ScannerImageReads : Executor {
  override fun execute(command: Runnable) {
    error("Scanner reads require a session source; use executeRead.")
  }
}

/** A rejected waiting read must release its pin without running the decoder. Running reads own cleanup. */
internal fun Executor.executeRead(pin: SessionPageSource?, command: Runnable) {
  val started = java.util.concurrent.atomic.AtomicBoolean()
  val operation = Runnable { started.set(true); command.run() }
  val rejected: (Throwable) -> Unit = {
    if (!started.get()) pin?.close()
    if (it !is ScannerException) ScanSessionMainOwner.report(it)
  }
  if (this === ScannerImageReads && pin != null) {
    val owner = pin.workOwner
    owner.queue.submit(owner, ScannerWorkQueue.Kind.DECODE,
      operation = { operation.run() }, onSuccess = {}, onFailure = rejected)
  } else {
    try { execute(operation) } catch (error: Throwable) { rejected(error); throw error }
  }
}
