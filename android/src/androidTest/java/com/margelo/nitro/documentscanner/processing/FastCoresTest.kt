package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class FastCoresTest {
  @Test fun overlappingCallsReleaseTheSharedWorkersAfterTheLast() {
    OpenCvRuntime.ensureLoaded()
    // Starts OpenCV's worker pool.
    Mat(2048, 2048, CvType.CV_8U).let { m -> Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), 2.0); m.release() }
    val before = coresByThread()
    val outerPinned = CountDownLatch(1)
    val innerReleased = CountDownLatch(1)
    var outerAfterInner = emptyMap<Int, String>()
    val outer = thread {
      FastCores.run {
        outerPinned.countDown()
        innerReleased.await()
        outerAfterInner = coresByThread()
      }
    }
    outerPinned.await()
    var during = emptyMap<Int, String>()
    FastCores.run { during = coresByThread() }
    innerReleased.countDown()
    outer.join()

    val pinned = changed(before, during)
    assumeTrue("No core is markedly slower here, so nothing is pinned", pinned.isNotEmpty())
    // The inner caller's own thread is released; the outer caller and the shared workers stay pinned.
    assertEquals(pinned - android.os.Process.myTid(), changed(before, outerAfterInner))
    assertEquals(emptySet<Int>(), changed(before, coresByThread()))
  }

  /** The threads whose allowed cores differ from [before]'s, among those alive in both. */
  private fun changed(before: Map<Int, String>, now: Map<Int, String>) =
    now.filter { (tid, cores) -> before[tid] != null && before[tid] != cores }.keys

  /** Each thread of this process with the cores it may run on, as the kernel lists them. */
  private fun coresByThread() = File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
    runCatching {
      task.name.toInt() to File(task, "status").readLines().first { it.startsWith("Cpus_allowed_list") }.substringAfter(':').trim()
    }.getOrNull()
  }.toMap()
}
