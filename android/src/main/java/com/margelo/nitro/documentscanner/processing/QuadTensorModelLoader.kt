package com.margelo.nitro.documentscanner.processing

import android.content.Context
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.*
import java.util.concurrent.Executors

/** Local source I/O is separate from inference. Only the newest request may activate. */
internal object QuadTensorModelLoader {
  private val lock = Any()
  private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ScannerModelSources").apply { isDaemon = true }
  }
  private var generation = 0L
  private var pending: Promise<Boolean>? = null

  private var latestUri: String? = null
  private var latest: Promise<Boolean>? = null
  private var activeSelection: QuadModelStore.Selection? = null

  fun load(context: Context, uri: String): Promise<Boolean> {
    val promise = Promise<Boolean>()
    val request = synchronized(lock) {
      if (latestUri == uri && latest != null && (pending != null || activeSelection === QuadModelStore.selected)) return latest!!
      pending?.reject(ScannerException(ScannerErrorCode.MODEL_SUPERSEDED))
      pending = promise
      latest = promise
      latestUri = uri
      ++generation
    }
    worker.execute {
      try {
        if (synchronized(lock) { request != generation }) return@execute
        requireInput(uri.isNotEmpty()) { "Model URI must not be empty." }
        val selected = QuadModelStore.prepare(context, uri)
        synchronized(lock) {
          if (request != generation) return@execute
          QuadModelStore.activate(selected)
          activeSelection = selected
          pending = null
          promise.resolve(true)
        }
      } catch (error: Throwable) {
        synchronized(lock) {
          if (request != generation) return@execute
          pending = null
          latest = null
          promise.reject(error)
        }
      }
    }
    return promise
  }
}
