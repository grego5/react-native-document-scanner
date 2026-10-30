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
    val uri = "file:///zoom-image-attach-test.jpg"
    val thumbnail = solid(Color.RED)
    onMain {
      PageThumbnails.put(uri, thumbnail)
      // As a pager outside its window holds a mounted page.
      val parent = image.parent as FrameLayout
      parent.removeView(image)
      ImageLoader(image, onLoaded = {}, onFailed = {}).show(uri, fade = false)
      assertNull("a detached view loads nothing", image.bitmap)
      parent.addView(image, SIZE, SIZE)
      assertSame("attaching shows the cached thumbnail", thumbnail, image.bitmap)
      parent.removeView(image)
      assertNull("detaching lets go of it", image.bitmap)
      assertSame("the cache keeps it", thumbnail, PageThumbnails.cached(uri))
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
