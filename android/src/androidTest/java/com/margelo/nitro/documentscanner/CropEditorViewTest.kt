package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropEditorViewTest {
  @Test
  fun sessionEditorDraftAndProgrammaticGeometryNeverSubmitPageEdits() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    com.facebook.soloader.SoLoader.init(context, false)
    com.margelo.nitro.JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    SessionViewFixture(context).use { fixture ->
      val presets = mutableListOf<CropDraftPreset>()
      lateinit var hybrid: HybridDocumentCropEditorView
      instrumentation.runOnMainSync {
        fixture.admit("page")
        val original = DocumentQuad(DocumentPoint(.1, .1), DocumentPoint(.9, .1),
          DocumentPoint(.9, .9), DocumentPoint(.1, .9))
        fixture.state.setDocumentCrop("page", original)
        val react = com.facebook.react.bridge.BridgeReactContext(context)
        val themed = com.facebook.react.uimanager.ThemedReactContext(react, context, "CropValidation", -1)
        hybrid = HybridDocumentCropEditorView(themed)
        hybrid.onPresetChange = { presets.add(it) }
        hybrid.session = HybridScanSession(fixture.state)
        hybrid.pageId = "page"
        hybrid.afterUpdate()
      }
      val before = fixture.state.snapshot().pages.getValue("page")
      val result = java.util.concurrent.CompletableFuture<Variant_Boolean_ScannerFailure>()
      hybrid.setPresetResult(CropPreset.NONE).then { result.complete(it) }
      assertEquals(true, result.get(5, java.util.concurrent.TimeUnit.SECONDS).asFirstOrNull())
      val draft = java.util.concurrent.CompletableFuture<Variant_ScannerFailure_DocumentQuad>()
      hybrid.getQuadResult().then { draft.complete(it) }
      assertEquals(ScanPage.fullFrame(), draft.get(5, java.util.concurrent.TimeUnit.SECONDS).asSecondOrNull())
      assertEquals(before, fixture.state.snapshot().pages.getValue("page"))
      assertEquals(listOf(CropDraftPreset.CUSTOM, CropDraftPreset.NONE), presets)
      instrumentation.runOnMainSync {
        val editor = hybrid.view as CropEditorView
        val custom = doubleArrayOf(.2, .2, .8, .2, .8, .8, .2, .8)
        editor.replaceQuad(custom)
        editor.onQuadEdited?.invoke(custom)
      }
      assertEquals(CropDraftPreset.CUSTOM, presets.last())
      val reset = java.util.concurrent.CompletableFuture<Variant_Boolean_ScannerFailure>()
      hybrid.setPresetResult(CropPreset.NONE).then { reset.complete(it) }
      assertEquals(true, reset.get(5, java.util.concurrent.TimeUnit.SECONDS).asFirstOrNull())
      val submitted = java.util.concurrent.CompletableFuture<Variant_Boolean_ScannerFailure>()
      hybrid.submitResult().then { submitted.complete(it) }
      assertEquals(true, submitted.get(5, java.util.concurrent.TimeUnit.SECONDS).asFirstOrNull())
      assertEquals(ScanPage.fullFrame(), fixture.state.snapshot().pages.getValue("page").quad)
      instrumentation.runOnMainSync { hybrid.onDropView() }
      val dropped = java.util.concurrent.CompletableFuture<Variant_ScannerFailure_DocumentQuad>()
      hybrid.getQuadResult().then { dropped.complete(it) }
      assertEquals(ScannerErrorCode.INPUT_INVALID, dropped.get(5, java.util.concurrent.TimeUnit.SECONDS).asFirstOrNull()?.code)
      val rejectedSubmit = java.util.concurrent.CompletableFuture<Variant_Boolean_ScannerFailure>()
      hybrid.submitResult().then { rejectedSubmit.complete(it) }
      assertEquals(ScannerErrorCode.INPUT_INVALID, rejectedSubmit.get(5, java.util.concurrent.TimeUnit.SECONDS).asSecondOrNull()?.code)
    }
  }


  @Test
  fun edgeDragKeepsLastValidSharedQuadAndPublishesOnceOnRelease() = withEditor(assertUnchanged = false) { editor, touches ->
    editor.replaceQuad(doubleArrayOf(.1, .1, .9, .1, .9, .9, .1, .9))
    var edits = 0
    var published: DoubleArray? = null
    editor.onQuadEdited = { edits++; published = it }
    touches.begin(500f, 100f)
    touches.send(MotionEvent.ACTION_MOVE, 500f, 200f)
    val valid = doubleArrayOf(.1, .2, .9, .2, .9, .9, .1, .9)
    assertArrayEquals(valid, editor.quad, 1e-9)
    touches.send(MotionEvent.ACTION_MOVE, 500f, 950f)
    assertArrayEquals(valid, editor.quad, 1e-9)
    assertEquals(0, edits)
    touches.send(MotionEvent.ACTION_UP, 500f, 950f)
    assertEquals(1, edits)
    assertArrayEquals(valid, published, 1e-9)
  }

  @Test
  fun twoFingerTranslationWorksWithoutPinchingAndStopsWhenOneFingerLifts() = withEditor { editor, touches ->
    touches.zoomIn()
    assertEquals(Color.RED, pixel(editor, 500, 500))
    touches.begin(300f, 500f)
    touches.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
      300f, 500f, 700f, 500f)
    touches.send(MotionEvent.ACTION_MOVE, 380f, 500f, 780f, 500f)
    assertEquals(Color.RED, pixel(editor, 580, 500))
    assertEquals(Color.DKGRAY, pixel(editor, 500, 500))

    touches.send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
      380f, 500f, 780f, 500f)
    touches.send(MotionEvent.ACTION_MOVE, 480f, 500f)
    assertEquals(Color.RED, pixel(editor, 580, 500))
    touches.send(MotionEvent.ACTION_UP, 480f, 500f)
  }

  @Test
  fun simultaneousPinchAndPanCountsTranslationOnce() = withEditor { editor, touches ->
    touches.zoomIn()
    touches.begin(300f, 500f)
    touches.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
      300f, 500f, 700f, 500f)
    touches.send(MotionEvent.ACTION_MOVE, 150f, 500f, 850f, 500f) // Start scaling, centred on the marker.
    touches.send(MotionEvent.ACTION_MOVE, 80f, 500f, 1080f, 500f) // Scale and translate its centre by 80 px.
    assertEquals(Color.RED, pixel(editor, 580, 500))
    assertEquals(Color.DKGRAY, pixel(editor, 500, 500))
    touches.send(MotionEvent.ACTION_CANCEL, 80f, 500f, 1080f, 500f)
  }

  private fun withEditor(assertUnchanged: Boolean = true, check: (CropEditorView, Touches) -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      val photo = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
      photo.eraseColor(Color.DKGRAY)
      Canvas(photo).drawRect(48f, 48f, 52f, 52f, Paint().apply { color = Color.RED })
      val editor = CropEditorView(instrumentation.targetContext)
      editor.layout(0, 0, 1000, 1000)
      editor.bitmap = photo
      val original = editor.quad.copyOf()
      var edits = 0
      editor.onQuadEdited = { edits++ }
      try {
        check(editor, Touches(editor))
        if (assertUnchanged) {
          assertArrayEquals(original, editor.quad, 0.0)
          assertEquals("Viewport gestures do not edit the crop", 0, edits)
        }
      } finally {
        editor.bitmap = null
        photo.recycle()
      }
    }
  }

  private fun pixel(editor: CropEditorView, x: Int, y: Int): Int {
    val output = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
    return try {
      editor.draw(Canvas(output))
      output.getPixel(x, y)
    } finally {
      output.recycle()
    }
  }

  private class Touches(private val editor: CropEditorView) {
    private var time = SystemClock.uptimeMillis()
    private var downTime = time

    fun begin(x: Float, y: Float) {
      time += 500 // Separate gestures must not become a double tap.
      downTime = time
      send(MotionEvent.ACTION_DOWN, x, y)
    }

    fun zoomIn() {
      begin(250f, 500f)
      send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        250f, 500f, 750f, 500f)
      send(MotionEvent.ACTION_MOVE, 100f, 500f, 900f, 500f)
      send(MotionEvent.ACTION_MOVE, 0f, 500f, 1000f, 500f)
      send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        0f, 500f, 1000f, 500f)
      send(MotionEvent.ACTION_UP, 0f, 500f)
    }

    fun send(action: Int, vararg positions: Float) {
      time += 16
      val count = positions.size / 2
      val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
        id = index
        toolType = MotionEvent.TOOL_TYPE_FINGER
      } }
      val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
        x = positions[index * 2]
        y = positions[index * 2 + 1]
        pressure = 1f
        size = 1f
      } }
      val event = MotionEvent.obtain(downTime, time, action, count, properties, coordinates,
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
      try {
        editor.onTouchEvent(event)
      } finally {
        event.recycle()
      }
    }
  }
}
