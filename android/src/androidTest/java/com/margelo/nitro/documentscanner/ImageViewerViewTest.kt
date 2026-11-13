package com.margelo.nitro.documentscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageViewerViewTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun sessionPlaceholderIsImmediateAndBoundedWithoutAJavaScriptHandoff() = withImage { image ->
    SessionViewFixture(image.context).use { fixture ->
      fixture.admit("a"); fixture.admit("b")
      val preview = solid(Color.RED)
      onMain {
        fixture.state.retainCapturePlaceholder("a", preview)
        fixture.state.retainCapturePlaceholder("b", preview)
        assertNull(fixture.state.capturePlaceholder("a"))
        val queued = java.util.ArrayDeque<Runnable>()
        val reads = java.util.concurrent.Executor { queued.add(it) }
        var failures = 0
        val loader = ImageLoader(image, onLoaded = {}, onFailed = { failures++ }, reads = reads)
        loader.bind(fixture.state, "b")
        assertSame(preview, image.bitmap)
        fixture.state.releaseCapturePlaceholder("b")
        assertSame("the view independently retains its pixels", preview, image.bitmap)
        fixture.state.removePage("b")
        assertNull(image.bitmap)
        assertEquals("expected deletion is a lifecycle change", 0, failures)
        loader.clear()
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertNull(image.bitmap)
        fixture.state.retainCapturePlaceholder("a", preview)
        fixture.state.removePage("a")
        assertNull(fixture.state.capturePlaceholder("a"))
      }
    }
  }

  @Test
  fun rapidReplacementFadesInOverTheLatestImageOnly() = withImage { image ->
    val center = onMain {
      image.bitmap = solid(Color.RED)
      image.fadeIn(solid(Color.GREEN), fit = true)
      // The first fade ends at once, so green is underneath, opaque, and red is gone.
      image.fadeIn(solid(Color.BLUE), fit = true)
      center(image)
    }
    assertEquals(0, Color.red(center))
    assertTrue("opaque throughout the fade", Color.alpha(center) == 255)
  }

  @Test
  fun clearingResetsAZoomInProgress() = withImage { image ->
    val scales = mutableListOf<Float>()
    onMain {
      image.bitmap = solid(Color.RED)
      image.onZoom = { scales += it }
      doubleTap(image)
    }
    // The double tap animates toward its zoom.
    SystemClock.sleep(400)
    assertTrue("zoomed", onMain { image.isZoomed })
    onMain { image.clearImage() }
    assertEquals(1f, onMain { image.zoomScale })
    assertEquals(1f, scales.last())
  }

  @Test
  fun holdsTheImageOnlyWhileAttached() = withImage { image ->
    SessionViewFixture(image.context).use { fixture ->
      fixture.admit("page")
      val thumbnail = solid(Color.GREEN)
      fixture.state.imageSource("page").use { PageThumbnails.put(it.uri, thumbnail) }
      onMain {
        val parent = image.parent as FrameLayout
        parent.removeView(image)
        val loader = ImageLoader(image, onLoaded = { assertEquals("page", it) }, onFailed = { throw it })
        loader.bind(fixture.state, "page")
        assertNull("a detached view loads nothing", image.bitmap)
        parent.addView(image, SIZE, SIZE)
        assertSame("attaching shows the cached placeholder", thumbnail, image.bitmap)
        parent.removeView(image)
        assertNull("detaching lets go of it", image.bitmap)
        loader.clear()
      }
    }
  }

  @Test
  fun renderRetryDeletionAndSessionReplacementSuppressQueuedReads() = withImage { image ->
    SessionViewFixture(image.context).use { first ->
      SessionViewFixture(image.context).use { second ->
        val reads = SessionViewFixture.Worker()
        val loaded = mutableListOf<String>()
        val failures = mutableListOf<ScannerException>()
        lateinit var loader: ImageLoader
        onMain {
          first.admit("first"); second.admit("second")
          loader = ImageLoader(image, onLoaded = { loaded.add(it) }, onFailed = { failures.add(it) }, reads = reads)
          loader.bind(first.state, "first")
          loader.bind(second.state, "second") // First read is still queued, with its own pin.
          reads.drain()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("second"), loaded)
        onMain {
          first.worker.drain()
          second.failRender = true
          second.worker.drain() // Failed rendering preserves the placeholder.
          assertTrue(image.bitmap != null)
          second.failRender = false
          second.state.setFilter("second", DocumentFilter.NONE)
          second.worker.drain()
          reads.drain()
          second.state.removePage("second") // Decode result posted, but not installed.
          assertNull(image.bitmap)
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("second"), loaded)
        assertTrue("expected removal clears presentation without an error", failures.isEmpty())
        onMain { loader.clear() }
      }
    }
  }

  @Test
  fun closeWaitsForQueuedReadPinsAndNeverInstallsTheirResult() = withImage { image ->
    SessionViewFixture(image.context).use { fixture ->
      val reads = SessionViewFixture.Worker()
      var loads = 0
      onMain {
        fixture.admit("page")
        val loader = ImageLoader(image, onLoaded = { loads++ }, onFailed = {}, reads = reads)
        loader.bind(fixture.state, "page")
        val closing = fixture.state.close()
        assertNull(image.bitmap)
        assertTrue("queued read still owns its pin", !closing.isDone)
        fixture.worker.drain()
        reads.drain()
        assertTrue("the skipped read releases its pin", closing.isDone)
        loader.clear()
      }
      instrumentation.waitForIdleSync()
      assertEquals(0, loads)
    }
  }

  private fun withImage(check: (ImageViewerView) -> Unit) {
    val activity = instrumentation.startActivitySync(
      Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as ComponentActivity
    try {
      // A fixed size keeps the check independent of whether the device lays out the window.
      val image = onMain {
        ImageViewerView(activity).also {
          activity.setContentView(FrameLayout(activity).apply { addView(it, SIZE, SIZE) })
          it.layout(0, 0, SIZE, SIZE)
        }
      }
      instrumentation.waitForIdleSync()
      check(image)
    } finally {
      activity.finish()
    }
  }

  private fun doubleTap(image: ImageViewerView) {
    val start = SystemClock.uptimeMillis()
    fun send(action: Int, at: Long) {
      val event = MotionEvent.obtain(start, start + at, action, SIZE / 2f, SIZE / 2f, 0)
      image.dispatchTouchEvent(event)
      event.recycle()
    }
    send(MotionEvent.ACTION_DOWN, 0)
    send(MotionEvent.ACTION_UP, 50)
    send(MotionEvent.ACTION_DOWN, 150)
    send(MotionEvent.ACTION_UP, 200)
  }

  private fun solid(color: Int) = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

  private fun center(image: ImageViewerView): Int {
    val frame = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    image.draw(Canvas(frame))
    return frame.getPixel(SIZE / 2, SIZE / 2).also { frame.recycle() }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }

  private companion object {
    const val SIZE = 300
  }
}
