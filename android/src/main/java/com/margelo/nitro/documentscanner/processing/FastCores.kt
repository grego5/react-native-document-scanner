package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad

/**
 * Runs heavy image work on the fast cores: those the kernel rates at least half the fastest, or by top clock speed
 * where it rates none, so little cores are left out and the strong mid cores of all-big designs stay in. Left to the
 * scheduler, a render after a moment's idle ran largely on slow cores at low clocks, about 380 ms on the SM-G998U,
 * against 255 ms with the work and OpenCV's workers held to its four big cores; the three mid cores or the one fastest
 * alone were slower. Android's performance hint sessions are unavailable there. Best effort: where no core is markedly slower or
 * the system refuses, the work runs as before. Each call pins anew, since moving between foreground and background
 * resets affinity, and releases every core after, OpenCV's workers, which serve the camera too, once the last
 * overlapping call ends.
 */
internal object FastCores {
  fun <T> run(block: () -> T): T {
    val pinned = runCatching {
      OpenCvRuntime.ensureLoaded()
      ReactNativeDocumentScannerOnLoad.initializeNative()
      pinNative(true)
    }.getOrDefault(false)
    try {
      return block()
    } finally {
      if (pinned) runCatching { pinNative(false) }
    }
  }

  /**
   * Holds the calling thread alone to the prime cores, those rated as the fastest, for a thread that runs only short
   * bursts, such as corner inference: on the SM-G998U live inference with frame preparation took a median 24 ms on its
   * prime core against 29 ms left to the scheduler, 24 against 32 ms after a pause. Best effort, like [run]; there is no
   * release, so pin only a dedicated thread.
   */
  fun prime(): Boolean = runCatching {
    OpenCvRuntime.ensureLoaded()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    primeNative()
  }.getOrDefault(false)

  @JvmStatic @DoNotStrip private external fun pinNative(fast: Boolean): Boolean
  @JvmStatic @DoNotStrip private external fun primeNative(): Boolean
}
