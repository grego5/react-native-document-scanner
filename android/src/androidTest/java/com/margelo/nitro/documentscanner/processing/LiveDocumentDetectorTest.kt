package com.margelo.nitro.documentscanner.processing

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.LiveQuadOverlay
import com.margelo.nitro.documentscanner.processing.LiveGuideStatus.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LiveDocumentDetectorTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Before
  fun initializeNative() {
    SoLoader.init(instrumentation.targetContext, false)
    JNIOnLoad.initializeNativeNitro()
    QuadModelStore.activate(QuadModelStore.prepare(instrumentation.targetContext, "asset:///doccornernet/model.tflite"))
  }

  @Test
  fun lowScoresPauseInferenceAndRawScoresRemainVisible() {
    Harness().use { h ->
      h.model.score = .9f
      val observations = mutableListOf<LiveQuadObservation>()
      h.detector.traceObserver = LiveQuadTraceObserver { observations.add(it) }
      repeat(3) { h.send(); assertEquals(SEARCHING, h.last().status) }
      assertEquals(1, h.model.calls.get())
      assertEquals(.9, h.last().score!!, .00001)
      assertNotNull(observations.last().rawQuad)
      assertFalse(observations.last().guideEligible)
      h.model.score = .96f
      h.detector.minCornerScore = .94
      h.send()
      assertEquals(TRACKING, h.last().status)
      h.model.score = .9f
      h.send()
      assertEquals(SEARCHING, h.last().status)
      assertNull(h.last().quad)
    }
  }

  @Test
  fun invalidCornerGeometryStillRetainsItsLowScoreAndPausesInference() {
    Harness().use { h ->
      h.model.score = .1f
      h.model.next = DocumentQuad(DocumentPoint(-.1, .2), documentQuad.topRight,
        documentQuad.bottomRight, documentQuad.bottomLeft)
      val observations = mutableListOf<LiveQuadObservation>()
      h.detector.traceObserver = LiveQuadTraceObserver { observations.add(it) }
      repeat(3) { h.send() }
      assertEquals(1, h.model.calls.get())
      assertEquals(SEARCHING, h.last().status)
      assertNull(h.last().quad)
      assertEquals(.1, h.last().score!!, .00001)
      assertNull(observations.last().rawQuad)
      assertEquals(.1, observations.last().rawScore!!, .00001)
      assertFalse(observations.last().guideEligible)
    }
  }

  @Test
  fun lowScoreRetriesOnlyAfterMeasuredMovement() {
    Harness().use { h ->
      h.detector.flowMotionEnabled = true
      h.model.score = .9f
      val texture: (Int, Int) -> Int = { x, y ->
        val v = ((x * 73856093) xor (y * 19349663)) ushr 8 and 255
        Color.rgb(v, v, v)
      }
      fun send(offset: Int = 0) {
        val (frame, pending) = h.submit(pixelColor = { x, y -> texture(x + offset, y) })
        pending.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        assertEquals(1, frame.closed.get())
      }
      repeat(3) { send() }
      assertEquals(1, h.model.calls.get())
      h.model.score = 1.0f
      send(3)
      send(3)
      assertEquals(2, h.model.calls.get())
      assertEquals(TRACKING, h.last().status)
    }
  }

  @Test
  fun lowScoreDropsReliableFlowInsteadOfRetainingTheOldOutline() {
    Harness().use { h ->
      h.detector.flowMotionEnabled = true
      h.detector.inferenceThrottling = false
      val observations = mutableListOf<LiveQuadObservation>()
      h.detector.traceObserver = LiveQuadTraceObserver { observations.add(it) }
      val texture: (Int, Int) -> Int = { x, y ->
        val v = ((x * 73856093) xor (y * 19349663)) ushr 8 and 255
        Color.rgb(v, v, v)
      }
      fun send() {
        h.submit(pixelColor = texture).second.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
      }
      send()
      send()
      assertTrue(observations.last().flowRetained || h.last().status == TRACKING)
      h.model.score = .9f
      send()
      assertEquals(SEARCHING, h.last().status)
      assertFalse(observations.last().flowRetained)
      assertNull(h.last().quad)
    }
  }

  @Test
  fun thresholdChangeWakesAdaptiveTrackingAndZeroDisablesRejection() {
    Harness().use { h ->
      h.detector.flowMotionEnabled = true
      h.detector.adaptiveDetection = true
      h.model.score = .96f
      val texture: (Int, Int) -> Int = { x, y ->
        val v = ((x * 73856093) xor (y * 19349663)) ushr 8 and 255
        Color.rgb(v, v, v)
      }
      fun send() {
        h.submit(pixelColor = texture).second.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
      }
      send()
      send()
      assertEquals(1, h.model.calls.get())
      h.detector.minCornerScore = .99
      send()
      assertEquals(2, h.model.calls.get())
      assertEquals(SEARCHING, h.last().status)
      h.detector.minCornerScore = 0.0
      h.model.score = .1f
      send()
      assertEquals(TRACKING, h.last().status)
    }
  }

  @Test
  fun firstValidInferenceEntersAdaptiveTrackingWithoutAnotherModelCall() {
    Harness().use { h ->
      h.detector.flowMotionEnabled = true
      h.detector.adaptiveDetection = true
      val observations = mutableListOf<LiveQuadObservation>()
      h.detector.traceObserver = LiveQuadTraceObserver { observations.add(it) }
      val texture: (Int, Int) -> Int = { x,y ->
        val v=((x*73856093) xor (y*19349663)) ushr 8 and 255
        Color.rgb(v,v,v)
      }
      fun send() {
        val (frame,pending)=h.submit(pixelColor=texture)
        pending.get(10,TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        assertEquals(1,frame.closed.get())
      }
      send()
      assertEquals(TRACKING,h.last().status)
      assertEquals(1,h.model.calls.get())
      send()
      assertEquals(1,h.model.calls.get())
      send()
      assertEquals(1,h.model.calls.get())
      assertTrue(observations.last().inferencePaused)
    }
  }

  @Test
  fun lostTrackingRunsFullInference() {
    Harness().use { h ->
      h.detector.flowMotionEnabled = true
      val (first, pending)=h.submit(pixelColor={ x,y -> Color.rgb((x*13+y*17) and 255,40,80) })
      pending.get(10,TimeUnit.SECONDS)
      assertEquals(1,first.closed.get())
      val (blank, next)=h.submit(pixelColor={ _,_ -> Color.WHITE })
      next.get(10,TimeUnit.SECONDS)
      assertEquals(1,blank.closed.get())
      assertEquals(2,h.model.calls.get())
    }
  }

  @Test
  fun directPlanesMatchBitmapTensorsForOffsetsStridesCropsAndRotations() {
    for ((width, height) in listOf(256 to 256, 320 to 192, 192 to 320)) {
      NativeTensorPreprocessor(width, height).use { native ->
        for (size in listOf(Size(128, 96), Size(641, 479))) {
          for (rotation in listOf(0, 90, 180, 270)) {
            for (crop in listOf(Rect(0, 0, size.width, size.height), Rect(17, 13, size.width - 15, size.height - 13))) {
              for (pixelStride in listOf(4, 8)) {
                val frame = Frame(crop = crop, rotation = rotation, offset = 19, pixelStride = pixelStride, varied = true,
                  frameWidth = size.width, frameHeight = size.height)
                val bitmap = frame.referenceBitmap()
                try {
                  val expected = ByteBuffer.allocateDirect(native.inputBytes).order(ByteOrder.nativeOrder())
                  val actual = ByteBuffer.allocateDirect(native.inputBytes).order(ByteOrder.nativeOrder())
                  native.prepare(bitmap, expected)
                  native.prepare(frame.image, actual)
                  for (index in 0 until native.inputBytes step 4) {
                    if (expected.getInt(index) != actual.getInt(index)) {
                      fail("Tensor mismatch rotation=$rotation stride=$pixelStride crop=$crop float=${index / 4}")
                    }
                  }
                  assertEquals(19, frame.image.planes[0].buffer.position())
                  assertEquals(0, frame.closed.get())
                } finally { bitmap.recycle(); frame.image.close() }
              }
            }
          }
        }
        val invalid = Frame()
        val input = ByteBuffer.allocateDirect(native.inputBytes).order(ByteOrder.nativeOrder())
        invalid.image.planes[0].buffer.limit(7)
        try { native.prepare(invalid.image, input); fail("Truncated plane accepted") }
        catch (_: com.facebook.jni.CppException) {}
        finally { invalid.image.close() }
        assertEquals(1, invalid.closed.get())
      }
    }
  }

  @Test
  fun cameraXMappingRespectsCropRotationMirroringAndResizedViewport() {
    val crop = Rect(16, 12, 112, 84)
    val quad = DocumentQuad(DocumentPoint(.1, .2), DocumentPoint(.8, .1), DocumentPoint(.9, .8), DocumentPoint(.2, .9))
    val normalized = floatArrayOf(.1f, .2f, .8f, .1f, .9f, .8f, .2f, .9f)
    val factory = ImageProxyTransformFactory().apply { isUsingCropRect = true; isUsingRotationDegrees = true }
    for (rotation in listOf(0, 90, 180, 270)) {
      val frame = Frame(crop = crop, rotation = rotation)
      val upright = LiveFrameBitmapOracle.uprightBitmap(frame.image)
      val width = if (rotation % 180 == 0) crop.width() else crop.height()
      val height = if (rotation % 180 == 0) crop.height() else crop.width()
      assertEquals(width, upright.width)
      assertEquals(height, upright.height)
      val expectedTopLeft = when (rotation) {
        0 -> Color.RED
        90 -> Color.BLUE
        180 -> Color.YELLOW
        else -> Color.GREEN
      }
      assertEquals("RGBA crop/rotation at $rotation", expectedTopLeft, upright.getPixel(width / 4, height / 4))
      upright.recycle()
      val source = factory.getOutputTransform(frame.image)
      for (mirror in listOf(false, true)) {
        for (scale in listOf(1f, 2.5f)) {
          val viewWidth = width * scale
          val viewHeight = height * scale
          // Independent normalized sensor -> cropped display transform. Offset simulates
          // FIT_CENTER padding; scale simulates a resized viewport. CameraX owns mirroring.
          val matrix = Matrix().apply {
            setRectToRect(RectF(-1f, -1f, 1f, 1f), RectF(0f, 0f, crop.width().toFloat(), crop.height().toFloat()), Matrix.ScaleToFit.FILL)
            postRotate(rotation.toFloat())
            when (rotation) {
              90 -> postTranslate(crop.height().toFloat(), 0f)
              180 -> postTranslate(crop.width().toFloat(), crop.height().toFloat())
              270 -> postTranslate(0f, crop.width().toFloat())
            }
            postScale(scale, scale)
            if (mirror) { postScale(-1f, 1f); postTranslate(viewWidth, 0f) }
            postTranslate(20f, 30f)
          }
          val result = LiveDetectionResult(TRACKING, quad, source, width, height)
          val points = LiveQuadOverlay.mappedPoints(result, OutputTransform(matrix, Size(crop.width(), crop.height())))!!
          for (index in 0..3) {
            val x = normalized[index * 2] * viewWidth
            assertEquals("rotation=$rotation mirror=$mirror scale=$scale x", 20f + if (mirror) viewWidth - x else x, points[index * 2], .001f)
            assertEquals(30f + normalized[index * 2 + 1] * viewHeight, points[index * 2 + 1], .001f)
          }
        }
      }
      frame.image.close()
    }
  }

  @Test
  fun pausedFramesAreClosedWithoutDecodingAndFailureNeedsRebind() {
    val callbacks = mutableListOf<LiveDetectionResult>() // Accessed only on main.
    val detector = LiveDocumentDetector(instrumentation.targetContext, false) { callbacks.add(it) }
    try {
      instrumentation.runOnMainSync { detector.begin(false) }
      val paused = Frame()
      detector.executor.submit { detector.analyze(paused.image) }.get(10, TimeUnit.SECONDS)
      assertEquals(1, paused.closed.get())
      assertEquals(0, paused.decoded.get())

      instrumentation.runOnMainSync { detector.setEnabled(true) }
      // A model that ran before failing leaves a current generation behind.
      val working = Frame(timestamp = 1)
      detector.executor.submit { detector.analyze(working.image) }.get(15, TimeUnit.SECONDS)
      val failure = Frame(fail = true, timestamp = 2)
      detector.executor.submit { detector.analyze(failure.image) }.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(1, failure.closed.get())
      assertTrue(detector.hasFailed())
      assertEquals(UNAVAILABLE, callbacks.last().status)
      val suppressed = Frame(timestamp = 3)
      detector.executor.submit { detector.analyze(suppressed.image) }.get(10, TimeUnit.SECONDS)
      assertEquals(1, suppressed.closed.get())
      assertEquals(0, suppressed.decoded.get())
      // A frame the quality gate holds back leaves the failure reported.
      detector.frames.add(4, CameraFrameState(10_000_000, 0, outOfFocus = true))
      val unfocused = Frame(timestamp = 4)
      detector.executor.submit { detector.analyze(unfocused.image) }.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(1, unfocused.closed.get())
      assertEquals(UNAVAILABLE, callbacks.last().status)

      instrumentation.runOnMainSync { detector.stop(); detector.begin(true) }
      val recovered = Frame()
      detector.executor.submit { detector.analyze(recovered.image) }.get(15, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(1, recovered.closed.get())
      assertFalse(detector.hasFailed())
      assertNotEquals(UNAVAILABLE, callbacks.last().status)

      val obsoleteAnalyzer = detector.boundAnalyzer()
      instrumentation.runOnMainSync { detector.stop(); detector.begin(true) }
      val oldFrame = Frame()
      detector.executor.submit { obsoleteAnalyzer.analyze(oldFrame.image) }.get(10, TimeUnit.SECONDS)
      assertEquals(1, oldFrame.closed.get())
      assertEquals(0, oldFrame.decoded.get())
    } finally {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    }
  }

  @Test
  fun dropWhileFrameIsInFlightReleasesImageAndSuppressesCallback() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val callbacks = AtomicInteger()
    val detector = LiveDocumentDetector(instrumentation.targetContext, false) { callbacks.incrementAndGet() }
    val frame = Frame(beforeDecode = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
    instrumentation.runOnMainSync { detector.begin(true) }
    val completion = detector.executor.submit { detector.analyze(frame.image) }
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      instrumentation.runOnMainSync { detector.close() }
    } finally { release.countDown() }
    completion.get(15, TimeUnit.SECONDS)
    assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    instrumentation.waitForIdleSync()
    assertEquals(1, frame.closed.get())
    assertEquals(0, callbacks.get())
  }

  @Test
  fun flowEstimatesGuideOffAndGatedFramesWithoutInference() {
    val created = AtomicInteger()
    val callbacks = AtomicInteger()
    val model = FakeModel()
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { created.incrementAndGet(); model }) {
      callbacks.incrementAndGet()
    }
    try {
      detector.flowMotionEnabled = true
      instrumentation.runOnMainSync { detector.begin(false) }
      val frames = (1..3).map { Frame(varied = true, timestamp = it * 33_000_000L) }
      for (frame in frames) detector.executor.submit { detector.analyze(frame.image) }.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      frames.forEach { assertEquals(1, it.closed.get()) }
      assertEquals(66_000_000L, detector.frameHistory.latest()!!.flow!!.fromSensorNs)
      assertEquals(99_000_000L, detector.frameHistory.latest()!!.flow!!.toSensorNs)
      assertEquals(0, created.get())
      // Turning the guide on keeps tracking; a frame the focus gate holds back still updates the estimate.
      instrumentation.runOnMainSync { detector.setEnabled(true) }
      detector.frames.add(132_000_000L, CameraFrameState(10_000_000, 0, outOfFocus = true))
      val gated = Frame(varied = true, timestamp = 132_000_000L)
      detector.executor.submit { detector.analyze(gated.image) }.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(1, gated.closed.get())
      assertEquals(99_000_000L, detector.frameHistory.latest()!!.flow!!.fromSensorNs)
      assertEquals(132_000_000L, detector.frameHistory.latest()!!.flow!!.toSensorNs)
      // Enabling the guide may preload the session; a gated frame must not run inference.
      assertEquals(1, model.calls.get())
      assertEquals(1, callbacks.get())
    } finally {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    }
    assertNull(detector.frameHistory.latest())
  }

  @Test
  fun flowEstimatesThrottledFrames() {
    Harness().use { harness ->
      val samples = Collections.synchronizedList(mutableListOf<LiveFrameSample>())
      harness.detector.traceObserver = object : LiveQuadTraceObserver {
        override fun onObservation(observation: LiveQuadObservation) = Unit
        override fun onFrame(sample: LiveFrameSample) { samples.add(sample) }
      }
      harness.detector.flowMotionEnabled = true
      repeat(3) { harness.send() }
      assertEquals(TRACKING, harness.last().status)
      val analyzed = Frame(timestamp = 1_000_000_000L)
      val throttled = Frame(timestamp = 1_033_000_000L)
      // The first is scheduled past the tracking interval, the second back to back well inside it.
      Thread.sleep(80)
      harness.detector.executor.submit {
        harness.detector.analyze(analyzed.image)
        harness.detector.analyze(throttled.image)
      }.get(10, TimeUnit.SECONDS)
      assertEquals(1, analyzed.closed.get())
      assertEquals(1, throttled.closed.get())
      val sample = samples.last()
      assertEquals(1_033_000_000L, sample.sensorTimestampNs)
      assertFalse(sample.analyzed)
      assertEquals(1_033_000_000L, sample.flow!!.toSensorNs)
      assertEquals(1_000_000_000L, sample.flow!!.fromSensorNs)
    }
  }

  @Test
  fun stopRebindAndInvalidateFenceOldEstimates() {
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { FakeModel() }) {}
    try {
      detector.flowMotionEnabled = true
      fun analyze(timestamp: Long, analyzer: (ImageProxy) -> Unit = detector::analyze) {
        val frame = Frame(varied = true, timestamp = timestamp)
        detector.executor.submit { analyzer(frame.image) }.get(10, TimeUnit.SECONDS)
        assertEquals(1, frame.closed.get())
      }
      instrumentation.runOnMainSync { detector.begin(false) }
      analyze(33_000_000L)
      val first = detector.frameHistory.latest()!!
      val obsolete = detector.boundAnalyzer()
      instrumentation.runOnMainSync { detector.stop() }
      assertNull(detector.frameHistory.latest())
      instrumentation.runOnMainSync { detector.begin(false) }
      analyze(66_000_000L, obsolete::analyze)
      assertNull(detector.frameHistory.latest())
      analyze(99_000_000L)
      val rebound = detector.frameHistory.latest()!!
      assertTrue(rebound.sequence > first.sequence)
      assertEquals(FlowMotionStatus.NO_REFERENCE, rebound.flow!!.status)
      analyze(132_000_000L)
      assertNotEquals(FlowMotionStatus.NO_REFERENCE, detector.frameHistory.latest()!!.flow!!.status)
      instrumentation.runOnMainSync { detector.invalidate() }
      assertNull(detector.frameHistory.latest())
      analyze(165_000_000L)
      assertEquals(FlowMotionStatus.NO_REFERENCE, detector.frameHistory.latest()!!.flow!!.status)
    } finally {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    }
  }

  @Test
  fun flowRestartsAfterCropOrRotationChange() {
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { FakeModel() }) {}
    try {
      detector.flowMotionEnabled = true
      instrumentation.runOnMainSync { detector.begin(false) }
      val narrow = Rect(16, 0, 112, 96)
      val statuses = listOf(Rect(0, 0, 128, 96) to 0, Rect(0, 0, 128, 96) to 0, narrow to 0, narrow to 90, narrow to 90)
        .mapIndexed { index, (crop, rotation) ->
          val frame = Frame(crop = crop, rotation = rotation, varied = true, timestamp = (index + 1) * 33_000_000L)
          detector.executor.submit { detector.analyze(frame.image) }.get(10, TimeUnit.SECONDS)
          assertEquals(1, frame.closed.get())
          detector.frameHistory.latest()!!.flow!!.status == FlowMotionStatus.NO_REFERENCE
        }
      assertEquals(listOf(true, false, true, true, false), statuses)
    } finally {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    }
  }

  @Test
  fun closeDuringFlowReleasesImageAndPublishesNothing() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { FakeModel() }) {}
    detector.flowMotionEnabled = true
    val frame = Frame(varied = true, beforeDecode = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
    instrumentation.runOnMainSync { detector.begin(false) }
    val completion = detector.executor.submit { detector.analyze(frame.image) }
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      instrumentation.runOnMainSync { detector.close() }
    } finally { release.countDown() }
    completion.get(15, TimeUnit.SECONDS)
    assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    assertEquals(1, frame.closed.get())
    assertNull(detector.frameHistory.latest())
  }

  @Test
  fun invalidationDuringFlowDoesNotPublishTheOldFrame() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { FakeModel() }) {}
    detector.flowMotionEnabled = true
    val frame = Frame(varied = true, beforeDecode = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
    instrumentation.runOnMainSync { detector.begin(false) }
    val completion = detector.executor.submit { detector.analyze(frame.image) }
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS))
      instrumentation.runOnMainSync { detector.invalidate() }
      release.countDown()
      completion.get(15, TimeUnit.SECONDS)
      assertEquals(1, frame.closed.get())
      assertNull(detector.frameHistory.latest())
    } finally {
      release.countDown()
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    }
  }

  @Test
  fun framesQueuedBeforeCloseOrAfterStopPublishNothing() {
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { FakeModel() }) {}
    detector.flowMotionEnabled = true
    instrumentation.runOnMainSync { detector.begin(false) }
    val afterStop = Frame(varied = true, timestamp = 33_000_000L)
    try {
      instrumentation.runOnMainSync { detector.stop() }
      // analyze() follows the current binding, so only stopping fences it.
      detector.executor.submit { detector.analyze(afterStop.image) }.get(10, TimeUnit.SECONDS)
      assertEquals(1, afterStop.closed.get())
      assertNull(detector.frameHistory.latest())
      instrumentation.runOnMainSync { detector.begin(false) }
    } catch (error: Throwable) {
      instrumentation.runOnMainSync { detector.close() }
      throw error
    }
    val blocked = CountDownLatch(1)
    val release = CountDownLatch(1)
    detector.executor.execute { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
    val queued = Frame(varied = true, timestamp = 66_000_000L)
    val completion = detector.executor.submit { detector.analyze(queued.image) }
    try {
      assertTrue(blocked.await(10, TimeUnit.SECONDS))
      instrumentation.runOnMainSync { detector.close() }
    } finally { release.countDown() }
    completion.get(15, TimeUnit.SECONDS)
    assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    assertEquals(1, queued.closed.get())
    assertNull(detector.frameHistory.latest())
  }

  @Test
  fun overlayAcceptsTheFirstValidFrame() {
    Harness().use { harness ->
      repeat(3) { harness.send() }
      assertEquals(listOf(TRACKING, TRACKING, TRACKING), harness.statuses())
      val tracking = harness.last()
      assertEquals(3L, tracking.acceptanceId)
      assertNotNull(tracking.source)
      assertEquals(128, tracking.width)
      assertEquals(96, tracking.height)
    }
  }

  @Test
  fun unfocusedFramesStillDetectAndConfirmThePage() {
    Harness().use { h ->
      val observations=mutableListOf<LiveQuadObservation>()
      h.detector.traceObserver=LiveQuadTraceObserver { observations.add(it) }
      repeat(3) {
        val (frame,pending)=h.submit(afState=android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
        pending.get(10,TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        assertEquals(1,frame.closed.get())
        assertTrue(observations.last().guideEligible)
      }
      assertEquals(TRACKING,h.last().status)
    }
  }

  @Test
  fun fixedFocusAndOwnedLockAllowGuidanceWithoutPassiveAutofocusMetadata() {
    for (fixedFocus in listOf(true, false)) Harness().use { harness ->
      repeat(3) { harness.send() }
      assertEquals(TRACKING, harness.last().status)
    }
  }

  @Test
  fun stoppingWithAFramePendingCannotDeliverAFocusHint() {
    Harness().use { harness ->
      val hints = AtomicInteger()
      val release = CountDownLatch(1)
      harness.detector.focusCandidateListener = { hints.incrementAndGet() }
      harness.model.gate = release
      val (frame, pending) = harness.submit()
      try { instrumentation.runOnMainSync { harness.detector.stop() } } finally { release.countDown() }
      pending.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(0, hints.get())
      assertEquals(1, frame.closed.get())
    }
  }

  @Test
  fun heldGuidanceExpiresWithoutAnotherFrame() {
    Harness().use { harness ->
      repeat(3) { harness.send() }
      val tracking = harness.last()
      val (expired, deliveredAt) = harness.await(4)
      assertEquals(SEARCHING, expired.status)
      assertExpiryDelay(tracking, deliveredAt)
      assertFalse(harness.detector.hasPendingExpiry())
    }
  }

  @Test
  fun reacceptanceKeepsGuidancePastTheFirstDeadline() {
    Harness().use { harness ->
      repeat(3) { harness.send() }
      val first = harness.last()
      sleepUntil(first.analysisStartedNs + 300_000_000)
      harness.send()
      val renewed = harness.last()
      assertEquals(TRACKING, renewed.status)
      assertEquals(4L, renewed.acceptanceId)
      val renewalCount = harness.count()
      val (expired, deliveredAt) = harness.await(renewalCount + 1)
      assertEquals(SEARCHING, expired.status)
      assertExpiryDelay(renewed, deliveredAt)
    }
  }

  @Test
  fun heldResultAnalyzedBeforeExpiryCannotResurrectGuidance() {
    Harness().use { harness ->
      repeat(3) { harness.send() }
      val tracking = harness.last()
      sleepUntil(tracking.analysisStartedNs + 300_000_000)
      val gate = CountDownLatch(1)
      harness.model.next = null
      harness.model.gate = gate
      val (frame, pending) = harness.submit()
      try {
        assertEquals(SEARCHING, harness.await(4).first.status)
      } finally { gate.countDown() }
      pending.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(1, frame.closed.get())
      assertTrue(harness.statuses().drop(3).all { it == SEARCHING })
      assertFalse(harness.detector.hasPendingExpiry())
    }
  }

  @Test
  fun overdueTrackingResultIsExpiredInsteadOfDrawn() {
    Harness().use { harness ->
      val observed=mutableListOf<LiveQuadObservation>()
      harness.detector.traceObserver=LiveQuadTraceObserver { observed.add(it) }
      harness.send()
      harness.send()
      val release = stallMain()
      try {
        harness.submit().second.get(10, TimeUnit.SECONDS) // Confirms, but stays queued behind the stall.
        Thread.sleep(450)
      } finally { release.countDown() }
      instrumentation.waitForIdleSync()
      assertEquals(listOf(TRACKING, TRACKING, SEARCHING), harness.statuses())
      assertFalse(harness.detector.hasPendingExpiry())
      // The stabilizer also expired that quad, so guidance returns after a fresh confirmation.
      repeat(3) { harness.send() }
      assertEquals(observed.map { "${it.decision}:${it.expiredBeforeInput}" }.toString(),listOf(TRACKING, TRACKING, TRACKING), harness.statuses().drop(3))
    }
  }

  @Test
  fun overdueHeldResultWhoseExpiryNeverRanIsNotDrawn() {
    Harness().use { harness ->
      harness.send()
      harness.send()
      val release = stallMain()
      try {
        harness.submit().second.get(10, TimeUnit.SECONDS)
        harness.model.next = null
        harness.submit().second.get(10, TimeUnit.SECONDS) // HELD replaces the undelivered TRACKING.
        Thread.sleep(450)
      } finally { release.countDown() }
      instrumentation.waitForIdleSync()
      assertEquals(listOf(TRACKING, TRACKING, SEARCHING), harness.statuses())
      assertFalse(harness.detector.hasPendingExpiry())
    }
  }

  @Test
  fun pauseStopAndCloseAfterMissingDetectionLeaveNoExpiry() {
    for (action in listOf("pause", "stop", "close")) {
      Harness().use { harness ->
        repeat(3) { harness.send() }
        harness.model.next = null
        harness.send()
        assertEquals(SEARCHING, harness.last().status)
        assertFalse(harness.detector.hasPendingExpiry())
        instrumentation.runOnMainSync {
          when (action) {
            "pause" -> harness.detector.setEnabled(false)
            "stop" -> harness.detector.stop()
            else -> harness.detector.close()
          }
        }
        assertFalse(action, harness.detector.hasPendingExpiry())
        Thread.sleep(600)
        instrumentation.waitForIdleSync()
        assertEquals(action, 4, harness.count())
      }
    }
  }

  @Test
  fun cropOrRotationChangeRestartsAcquisition() {
    Harness().use { harness ->
      val moved = Rect(8, 8, 120, 88)
      harness.send()
      harness.send()
      harness.send(crop = moved)
      assertEquals(listOf(TRACKING, TRACKING, TRACKING), harness.statuses())
      harness.send(crop = moved)
      harness.send(crop = moved)
      assertEquals(TRACKING, harness.last().status)
      assertEquals(112, harness.last().width)
      harness.send(crop = moved, rotation = 90)
      assertEquals(TRACKING, harness.last().status)
      assertTrue(harness.detector.hasPendingExpiry())
    }
  }

  @Test
  fun traceObserverSeesFreshHistoryOnlyWhileAttached() {
    Harness().use { harness ->
      harness.send()
      harness.send()
      val observed = Collections.synchronizedList(mutableListOf<LiveQuadObservation>())
      harness.detector.traceObserver = LiveQuadTraceObserver { observed.add(it) }
      repeat(3) { harness.send() }
      // Attaching restarts acceptance history, so the trace replays from a fresh stabilizer.
      assertEquals(listOf(LiveQuadDecision.ACQUIRED, LiveQuadDecision.REPLACED, LiveQuadDecision.REPLACED), observed.map { it.decision })
      val acquired = observed.last()
      assertEquals(128, acquired.width)
      assertEquals(96, acquired.height)
      assertEquals(5 * 66_000_000L, acquired.sensorTimestampNs)
      assertEquals(1.0, acquired.rawScore!!, 1e-6)
      assertEquals(acquired.rawQuad, acquired.accepted)
      assertEquals(harness.last().acceptanceId, acquired.acceptanceId)
      assertTrue(acquired.stabilizerNs > 0)
      harness.detector.traceObserver = null
      harness.send()
      assertEquals(3, observed.size)
      assertEquals(TRACKING, harness.last().status)
    }
  }

  @Test
  fun heldOverlayDimsInPlaceAndClearsToFullOpacity() {
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ComponentActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
    try {
      // A fixed size keeps the check independent of whether the device lays out the window (screen on or off).
      val overlay = onMain {
        LiveQuadOverlay(activity).also { overlay ->
          activity.setContentView(android.widget.FrameLayout(activity).apply { addView(overlay, 400, 300) })
          overlay.layout(0, 0, 400, 300)
        }
      }
      instrumentation.waitForIdleSync()
      assertTrue(onMain { overlay.isAttachedToWindow && overlay.width == 400 })
      val target = onMain { OutputTransform(Matrix(), Size(overlay.width, overlay.height)) }
      fun show(status: LiveGuideStatus, id: Long) = onMain {
        overlay.show(LiveDetectionResult(status, documentQuad, target, overlay.width, overlay.height, acceptanceId = id), target)
      }
      show(TRACKING, 1)
      assertEquals(255, onMain { overlay.strokeAlpha })
      val tracked = onMain { overlay.drawnPoints }
      show(HELD, 1)
      assertEquals(115, onMain { overlay.strokeAlpha })
      assertArrayEquals(tracked, onMain { overlay.drawnPoints }, 0f)
      show(TRACKING, 2)
      assertEquals(255, onMain { overlay.strokeAlpha })
      assertEquals(2L, onMain { overlay.drawnAcceptanceId })
      // The viewfinder appears only while no guide is drawn.
      onMain { overlay.viewfinder = true }
      assertFalse(onMain { overlay.showsViewfinder })
      show(SEARCHING, 0)
      assertTrue(onMain { overlay.showsViewfinder })
      onMain { overlay.viewfinder = false }
      assertFalse(onMain { overlay.showsViewfinder })
      show(TRACKING, 2)

      show(HELD, 2)
      show(SEARCHING, 0)
      assertFalse(onMain { overlay.hasOutline })
      assertEquals(255, onMain { overlay.strokeAlpha })

      show(TRACKING, 3)
      onMain { overlay.layout(0, 0, overlay.width / 2, overlay.height / 2) }
      assertFalse(onMain { overlay.hasOutline })
      show(HELD, 3)
      assertTrue(onMain { overlay.hasOutline })
      assertEquals(115, onMain { overlay.strokeAlpha })
      assertEquals(3L, onMain { overlay.drawnAcceptanceId })
    } finally {
      activity.finish()
    }
  }

  private val documentQuad = DocumentQuad(DocumentPoint(.2, .2), DocumentPoint(.8, .2), DocumentPoint(.8, .8), DocumentPoint(.2, .8))

  private fun <T> onMain(block: () -> T): T {
    var value: Result<T>? = null
    instrumentation.runOnMainSync { value = runCatching(block) }
    return value!!.getOrThrow()
  }

  /** Blocks the main thread, as a UI stall would, until the returned latch is released. */
  private fun stallMain(): CountDownLatch {
    val stalled = CountDownLatch(1)
    val release = CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      stalled.countDown()
      release.await(10, TimeUnit.SECONDS)
    }
    assertTrue(stalled.await(5, TimeUnit.SECONDS))
    return release
  }

  private fun sleepUntil(deadlineNs: Long) {
    val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
    if (remainingMs > 0) Thread.sleep(remainingMs)
  }

  private fun assertExpiryDelay(accepted: LiveDetectionResult, deliveredAtNs: Long) {
    val delayMs = (deliveredAtNs - accepted.analysisStartedNs) / 1_000_000.0
    assertTrue("expired after $delayMs ms", delayMs in 400.0..600.0)
  }

  /** Scripted live inference; [gate] blocks the next analysis until released. */
  @Test
  fun unthrottledInferenceKeepsFlowTrackingAcrossModelFailures() {
    Harness().use { h ->
      val observations = mutableListOf<LiveQuadObservation>()
      h.detector.traceObserver = LiveQuadTraceObserver { observations.add(it) }
      h.detector.inferenceThrottling = false
      h.detector.flowMotionEnabled = true
      h.detector.stabilizerConfig = LiveQuadStabilizerConfig(holdNs=1_000_000_000)
      val texture: (Int,Int) -> Int = { x,y ->
        val value = ((x*73856093) xor (y*19349663)) and 255
        Color.rgb(value,value,value)
      }
      fun send() {
        val (frame,pending) = h.submit(pixelColor=texture)
        pending.get(10,TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        assertEquals(1,frame.closed.get())
      }
      send()
      assertEquals(TRACKING,h.last().status)
      h.model.next = null
      repeat(15) {
        send()
        assertEquals(TRACKING,h.last().status)
        assertTrue(observations.last().flowRetained)
        assertEquals(LiveQuadDecision.MISSING,observations.last().decision)
        assertFalse(observations.last().expiredBeforeInput)
      }
      assertEquals(16,h.model.calls.get())
      // Removing the flow reference must still let an unsupported outline expire.
      h.detector.flowMotionEnabled = false
      Thread.sleep(1100)
      send()
      assertEquals(SEARCHING,h.last().status)
    }
  }

  @Test
  fun framingMotionPausesInferenceAndToggleResumesIt() {
    val model = FakeModel()
    val detector = LiveDocumentDetector(instrumentation.targetContext,false,{ model }) {}
    val samples = mutableListOf<LiveFrameSample>()
    detector.traceObserver = object : LiveQuadTraceObserver {
      override fun onObservation(observation: LiveQuadObservation) = Unit
      override fun onFrame(sample: LiveFrameSample) { samples.add(sample) }
    }
    try {
      detector.flowMotionEnabled = true
      detector.maxInferenceSpeedFraction = .05
      instrumentation.runOnMainSync { detector.begin(true) }
      for (i in 0..2) {
        if (i == 2) detector.inferenceThrottling = false
        val frame = Frame(timestamp=(i+1)*33_333_333L,pixelColor={ x,y ->
          val value = (((x-i*2)*73856093) xor (y*19349663)) and 255
          Color.rgb(value,value,value)
        })
        detector.executor.submit { detector.analyze(frame.image) }.get(10,TimeUnit.SECONDS)
        assertEquals(1,frame.closed.get())
        if (i == 1) {
          assertEquals(FlowMotionStatus.MEASURED,detector.frameHistory.latest()!!.flow!!.status)
          assertEquals(1,model.calls.get())
        }
      }
      assertEquals(2,model.calls.get())
      assertEquals(listOf("inference","motion","inference"),samples.map { it.inferenceReason })
      assertEquals(listOf(true,false,true),samples.map { it.analyzed })
      assertEquals(FlowMotionStatus.MEASURED,samples[1].flow?.status)
    } finally {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(10,TimeUnit.SECONDS))
    }
  }

  @Test
  fun unthrottledInferenceAcceptsOutOfFocusFrames() {
    val model = FakeModel()
    val detector = LiveDocumentDetector(instrumentation.targetContext,false,{ model }) {}
    try {
      detector.inferenceThrottling = false
      instrumentation.runOnMainSync { detector.begin(true) }
      for (i in 1..3) {
        val frame = Frame(timestamp = i*33_333_333L)
        detector.frames.add(i*33_333_333L,CameraFrameState(10_000_000,0,outOfFocus=i==3))
        detector.executor.submit { detector.analyze(frame.image) }.get(10,TimeUnit.SECONDS)
        assertEquals(1,frame.closed.get())
      }
      assertEquals(3,model.calls.get())
    } finally {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(10,TimeUnit.SECONDS))
    }
  }

  private inner class FakeModel : LiveQuadModel {
    val calls = AtomicInteger()
    @Volatile var next: DocumentQuad? = documentQuad
    @Volatile var score = 1.0f
    @Volatile var gate: CountDownLatch? = null
    override val lastPreprocessingMs = 0.0
    override val lastInferenceMs = 0.0
    override var lastScore: Double? = null
      private set
    override fun analyze(image: ImageProxy): DocumentCandidate? {
      calls.incrementAndGet()
      gate?.let { gate = null; check(it.await(10, TimeUnit.SECONDS)) }
      lastScore = score.toDouble()
      val quad = next ?: return null
      return DocCornerSession.decodeOutput(floatArrayOf(
        quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat(), quad.topRight.x.toFloat(), quad.topRight.y.toFloat(),
        quad.bottomRight.x.toFloat(), quad.bottomRight.y.toFloat(), quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat(), score,
      ))
    }
    override fun close() = Unit
  }

  /** Feeds frames with explicit sensor timestamps, spaced beyond the 60 ms scheduling interval. */
  private inner class Harness : AutoCloseable {
    val model = FakeModel()
    private val callbacks = Collections.synchronizedList(mutableListOf<Pair<LiveDetectionResult, Long>>())
    val detector = LiveDocumentDetector(instrumentation.targetContext, false, { model }) { result ->
      synchronized(callbacks) {
        val previous = callbacks.lastOrNull()
        // A first-pass preview and the final result describe the same source frame.
        if (result.analysisStartedNs > 0 && previous?.first?.analysisStartedNs == result.analysisStartedNs) {
          callbacks[callbacks.lastIndex] = result to System.nanoTime()
        } else callbacks.add(result to System.nanoTime())
      }
    }
    private var lastSubmittedNs = 0L
    private var timestampNs = 0L

    init { instrumentation.runOnMainSync { detector.begin(true) } }

    fun submit(crop: Rect = Rect(0, 0, 128, 96), rotation: Int = 0, pixelColor: ((Int, Int) -> Int)? = null,
      afState: Int? = null): Pair<Frame, Future<*>> {
      sleepUntil(lastSubmittedNs + 80_000_000)
      timestampNs += 66_000_000
      val frame = Frame(crop = crop, rotation = rotation, timestamp = timestampNs, pixelColor = pixelColor)
      if (afState != null) detector.frames.add(timestampNs, CameraFrameState(10_000_000, 0,
        outOfFocus = afState != android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED &&
          afState != android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
        afState = afState))
      lastSubmittedNs = System.nanoTime()
      return frame to detector.executor.submit { detector.analyze(frame.image) }
    }

    fun send(crop: Rect = Rect(0, 0, 128, 96), rotation: Int = 0) {
      val (frame, pending) = submit(crop, rotation)
      pending.get(10, TimeUnit.SECONDS)
      instrumentation.waitForIdleSync()
      assertEquals(1, frame.closed.get())
    }

    fun count(): Int = callbacks.size
    fun statuses(): List<LiveGuideStatus> = synchronized(callbacks) { callbacks.map { it.first.status } }
    fun last(): LiveDetectionResult = synchronized(callbacks) { callbacks.last().first }

    fun await(count: Int): Pair<LiveDetectionResult, Long> {
      val deadline = System.nanoTime() + 2_000_000_000L
      while (callbacks.size < count) {
        check(System.nanoTime() < deadline) { "Timed out waiting for callback $count" }
        Thread.sleep(5)
      }
      return callbacks[count - 1]
    }

    override fun close() {
      instrumentation.runOnMainSync { detector.close() }
      assertTrue(detector.executor.awaitTermination(15, TimeUnit.SECONDS))
    }
  }

  /** ImageProxy test double, avoiding a camera/permission dependency for transform/lifetime tests. */
  private class Frame(
    private val crop: Rect = Rect(0, 0, 128, 96),
    private val rotation: Int = 0,
    fail: Boolean = false,
    beforeDecode: () -> Unit = {},
    private val offset: Int = 0,
    private val pixelStride: Int = 4,
    private val frameWidth: Int = 128,
    private val frameHeight: Int = 96,
    private val varied: Boolean = false,
    private val timestamp: Long = 0L,
    private val pixelColor: ((Int, Int) -> Int)? = null,
  ) {
    val closed = AtomicInteger()
    val decoded = AtomicInteger()
    private val plane: ImageProxy.PlaneProxy = object : ImageProxy.PlaneProxy {
      // Padded rows ensure the native conversion honors rowStride.
      private val stride = frameWidth * pixelStride + 16
      private val pixels = ByteBuffer.allocateDirect(offset + stride * frameHeight).apply {
        for (y in 0 until frameHeight) {
          for (x in 0 until frameWidth) {
            val color = pixelColor?.invoke(x, y) ?: if (varied) Color.rgb((x * 13 + y * 7) and 255,
              (x * 3 + y * 17) and 255, (x * 23 + y * 5) and 255) else when {
              y < frameHeight / 2 && x < frameWidth / 2 -> Color.RED
              y < frameHeight / 2 -> Color.GREEN
              x < frameWidth / 2 -> Color.BLUE
              else -> Color.YELLOW
            }
            val address = this@Frame.offset + y * stride + x * this@Frame.pixelStride
            put(address, Color.red(color).toByte())
            put(address + 1, Color.green(color).toByte())
            put(address + 2, Color.blue(color).toByte())
            put(address + 3, 255.toByte())
          }
        }
        position(offset)
      }
      override fun getRowStride() = stride
      override fun getPixelStride() = this@Frame.pixelStride
      override fun getBuffer(): ByteBuffer {
        decoded.incrementAndGet()
        beforeDecode()
        if (fail) throw IllegalStateException("Intentional plane failure")
        return pixels
      }
    }
    fun referenceBitmap(): Bitmap {
      val raw = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
      val buffer = plane.buffer
      for (y in 0 until frameHeight) for (x in 0 until frameWidth) {
        val address = offset + y * plane.rowStride + x * pixelStride
        raw.setPixel(x, y, Color.rgb(buffer.get(address).toInt() and 255,
          buffer.get(address + 1).toInt() and 255, buffer.get(address + 2).toInt() and 255))
      }
      val upright = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(),
        Matrix().apply { postRotate(rotation.toFloat()) }, false)
      if (upright !== raw) raw.recycle()
      return upright
    }
    private val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
      when (method.name) {
        "getRotationDegrees" -> rotation
        "getTimestamp" -> timestamp
        "getSensorToBufferTransformMatrix" -> Matrix()
        else -> null
      }
    } as ImageInfo
    val image = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { proxy, method, _ ->
      when (method.name) {
        "getWidth" -> frameWidth
        "getHeight" -> frameHeight
        "getCropRect" -> Rect(crop)
        "getImageInfo" -> info
        "getPlanes" -> arrayOf(plane)
        "getFormat" -> PixelFormat.RGBA_8888
        "close" -> { closed.incrementAndGet(); null }
        "toBitmap" -> {
          decoded.incrementAndGet()
          beforeDecode()
          if (fail) throw IllegalStateException("Intentional decoder failure")
          // ImageProxy.toBitmap delegates here; use the real CameraX RGBA converter.
          androidx.camera.core.internal.utils.ImageUtil.createBitmapFromImageProxy(proxy as ImageProxy)
        }
        else -> throw UnsupportedOperationException(method.name)
      }
    } as ImageProxy
  }
}
