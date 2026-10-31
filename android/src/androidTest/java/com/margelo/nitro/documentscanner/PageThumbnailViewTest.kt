package com.margelo.nitro.documentscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic pixels and controlled workers only; never uses the camera. */
@RunWith(AndroidJUnit4::class)
class PageThumbnailViewTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test fun placeholderHandoffKeepsTheOldBitmapUntilTheNativeRenderIsDecoded() = withTile { tile, fixture, reads ->
    onMain { fixture.admit("page"); tile.bind(fixture.state, "page", PageThumbnailResizeMode.COVER); reads.drain() }
    instrumentation.waitForIdleSync()
    val placeholder = onMain { requireNotNull(tile.bitmap) }
    assertTrue(Color.green(placeholder.getPixel(0, 0)) > 200)
    onMain {
      fixture.worker.drain() // No React tile update.
      assertSame(placeholder, tile.bitmap)
      reads.drain()
      assertSame("posted replacement is not installed yet", placeholder, tile.bitmap)
    }
    instrumentation.waitForIdleSync()
    assertTrue(Color.blue(onMain { requireNotNull(tile.bitmap).getPixel(0, 0) }) > 200)
    assertFalse("published pixels may still be drawn", placeholder.isRecycled)
    onMain { fixture.state.removePage("page"); assertNull(tile.bitmap) }
  }

  @Test fun containAndCoverClipToTileBoundsAndDecodeAtMeasuredPixels() = withTile { tile, fixture, reads ->
    onMain {
      val original = fixture.image(SessionResources.Kind.ORIGINAL, Color.RED)
      val frame = fixture.resources.output(SessionResources.Kind.FRAME).use { output ->
        val pixels = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try { output.file.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
        finally { pixels.recycle() }
        output.commit()
      }
      fixture.state.admit("page", original, frame, null, null)
      tile.bind(fixture.state, "page", PageThumbnailResizeMode.CONTAIN)
      reads.drain()
    }
    instrumentation.waitForIdleSync()
    onMain {
      val pixels = requireNotNull(tile.bitmap)
      assertTrue(maxOf(pixels.width, pixels.height) <= 80)
      assertEquals(0, Color.alpha(pixel(tile)))
      tile.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      assertEquals(255, Color.alpha(pixel(tile)))
      tile.layout(0, 0, 24, 32)
      assertSame("size change retains current pixels while decoding", pixels, tile.bitmap)
      reads.drain()
    }
    instrumentation.waitForIdleSync()
    onMain { assertTrue(requireNotNull(tile.bitmap).let { maxOf(it.width, it.height) <= 32 }) }
  }

  @Test fun unknownRebindAndCloseSuppressLateResultsAndReleaseReadPins() = withTile { tile, fixture, reads ->
    onMain {
      fixture.admit("page")
      tile.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      reads.drain() // Result queued to main, not published.
      tile.bind(fixture.state, "missing", PageThumbnailResizeMode.COVER)
      assertNull(tile.bitmap)
    }
    instrumentation.waitForIdleSync()
    onMain {
      assertNull(tile.bitmap)
      tile.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      val closed = fixture.state.close()
      assertNull(tile.bitmap)
      assertFalse("queued decoder retains its source", closed.isDone)
      fixture.worker.drain()
      reads.drain()
      assertTrue(closed.isDone)
    }
    instrumentation.waitForIdleSync()
    onMain { assertNull(tile.bitmap) }
  }

  @Test fun detachedAndDisposedTilesCannotRestoreStalePixels() = withTile { tile, fixture, reads ->
    onMain {
      fixture.admit("page")
      tile.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      val parent = tile.parent as FrameLayout
      parent.removeView(tile)
      reads.drain()
      assertNull(tile.bitmap)
      parent.addView(tile, 80, 80)
      tile.layout(0, 0, 80, 80)
      reads.drain()
      tile.dispose()
    }
    instrumentation.waitForIdleSync()
    onMain { assertNull(tile.bitmap) }
  }

  @Test fun fiftyTilesReadOnlySmallFrameOrThumbnailSourcesAtTheirOwnSize() = withTile { tile, fixture, reads ->
    val tiles = mutableListOf<PageThumbnailView>()
    val limits = mutableListOf<Int>()
    onMain {
      fixture.admit("page")
      val frameUri = requireNotNull(fixture.state.thumbnailSource("page")).use { it.uri }
      val parent = tile.parent as FrameLayout
      repeat(50) { index ->
        val size = 24 + index % 4
        val item = PageThumbnailView(tile.context, reads) { source, limit ->
          assertEquals(frameUri, source.uri)
          limits.add(limit)
          PageThumbnails.loadTile(source, limit)
        }
        tiles.add(item)
        parent.addView(item, size, size)
        item.layout(0, 0, size, size)
        item.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      }
      reads.drain()
    }
    instrumentation.waitForIdleSync()
    onMain {
      assertEquals(50, limits.size)
      assertTrue(limits.all { it in 24..27 })
      tiles.forEach { assertTrue(requireNotNull(it.bitmap).let { bitmap -> maxOf(bitmap.width, bitmap.height) <= 27 }); it.dispose() }
    }
  }

  @Test fun absentFrameWaitsForThumbnailAndSessionReplacementClearsImmediately() = withTile { tile, fixture, reads ->
    SessionViewFixture(tile.context).use { next ->
      onMain {
        fixture.state.admit("page", fixture.image(SessionResources.Kind.ORIGINAL, Color.RED), null, null, null)
        tile.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
        reads.drain()
        assertNull("no original preview decode without a frame", tile.bitmap)
        fixture.worker.drain()
        reads.drain()
      }
      instrumentation.waitForIdleSync()
      val published = onMain { requireNotNull(tile.bitmap) }
      onMain {
        next.admit("page")
        tile.bind(next.state, "page", PageThumbnailResizeMode.COVER)
        assertNull("another session never retains the previous page", tile.bitmap)
        reads.drain()
      }
      instrumentation.waitForIdleSync()
      onMain {
        assertTrue(Color.green(requireNotNull(tile.bitmap).getPixel(0, 0)) > 200)
        assertFalse(published.isRecycled)
        tile.dispose()
      }
    }
  }

  @Test fun stalePrivatePixelsRecycleWhileBorrowedCachePixelsStaySafe() = withTile { tile, fixture, reads ->
    val results = mutableListOf<Bitmap>()
    var shared = false
    lateinit var custom: PageThumbnailView
    onMain {
      fixture.admit("page")
      custom = PageThumbnailView(tile.context, reads) { _, _ ->
        val pixels = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        results.add(pixels)
        PageThumbnails.TilePixels(pixels, shared)
      }
      (tile.parent as FrameLayout).addView(custom, 80, 80)
      custom.layout(0, 0, 80, 80)
      custom.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      reads.drain()
      custom.bind(fixture.state, "missing", PageThumbnailResizeMode.COVER)
    }
    instrumentation.waitForIdleSync()
    onMain {
      assertTrue(results.single().isRecycled)
      shared = true
      custom.bind(fixture.state, "page", PageThumbnailResizeMode.COVER)
      reads.drain()
      custom.dispose()
    }
    instrumentation.waitForIdleSync()
    assertFalse(results.last().isRecycled)
  }

  @Test fun productionReadAdmissionCancelsQueuedReadsWithTheirSession() = withTile { _, fixture, _ ->
    onMain {
      fixture.admit("page")
      fixture.worker.drain()
      val pin = fixture.state.imageSource("page")
      var decoded = false
      ScannerImageReads.executeRead(pin) {
        try { decoded = true } finally { pin.close() }
      }
      val closing = fixture.state.close()
      assertTrue("cancelled read releases its pin without draining the decoder", closing.isDone)
      fixture.worker.drain()
      assertFalse(decoded)
    }
  }

  private fun withTile(check: (PageThumbnailView, SessionViewFixture, SessionViewFixture.Worker) -> Unit) {
    val activity = instrumentation.startActivitySync(
      Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as ComponentActivity
    val fixture = SessionViewFixture(activity)
    val reads = SessionViewFixture.Worker()
    val tile = onMain {
      PageThumbnailView(activity, reads).also {
        activity.setContentView(FrameLayout(activity).apply { addView(it, 80, 80) })
        it.layout(0, 0, 80, 80)
      }
    }
    instrumentation.waitForIdleSync()
    try { check(tile, fixture, reads) } finally {
      onMain { tile.dispose(); reads.drain() }
      instrumentation.waitForIdleSync()
      fixture.close()
      activity.finish()
    }
  }

  private fun pixel(tile: View): Int {
    val image = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
    return try { tile.draw(Canvas(image)); image.getPixel(0, 0) } finally { image.recycle() }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }
}
