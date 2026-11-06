package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test

class LiveQuadTraceFormatTest {
  private val quad = DocumentQuad(DocumentPoint(0.1 + 0.2, 1.0 / 3), DocumentPoint(.8, .1), DocumentPoint(.9, .8), DocumentPoint(.2, 0.9000000000000001))

  @Test
  fun roundTripsObservationsCapturesGyroCameraAndNullQuadsExactly() {
    val config = LiveQuadStabilizerConfig(holdNs = 350_000_000)
    val trace = LiveQuadTrace(config, listOf(
      observation(arrivalNs = 100, raw = null, score = null, status = LiveQuadStatus.SEARCHING, accepted = null),
      observation(arrivalNs = 200, raw = quad, score = 0.7123456789, status = LiveQuadStatus.TRACKING, accepted = quad)
        .copy(guideEligible = false, trackingOnly = true),
    ), listOf(
      LiveQuadCaptureEvent(150, quad, 0.93, 2992, 2992, 140, 145, 16_666_667,
        gateEvaluationNs = 143, gateExposureNs = 8_333_333, gateBlur = 0.00123456789, gateSource = MotionSource.FLOW,
        flow = FlowMotionEstimate(FlowMotionStatus.MEASURED, 0.1 + 0.2, 320, 60, 93, 0.84, 42, 2), flowAgeNs = 7,
        flowSourceAgeNs = 95_000_007),
      LiveQuadCaptureEvent(250, null, null, 0, 0, 240, saved = false),
    ), listOf(
      LiveQuadGyroSample(90, 0.1 + 0.2, -1.5, 0.0049999999),
      LiveQuadGyroSample(95, 0.0, 2.0, -0.25),
    ), LiveQuadCameraRecord(
      LiveCameraOptics(2791.66650390625, 4000, 3000, 90, calibrated = true), realtimeTimestamps = true,
      listOf(2791.66650390625, 2791.66650390625, 0.0, 0.0, 0.0), listOf(0, 0, 4000, 3000), listOf(0, 1),
    ), listOf(
      LiveFrameSample(93, 100, 10_000_000, 33_000_000, 2, true, 4.5, analyzed = true,
        flow = FlowMotionEstimate(FlowMotionStatus.MEASURED, 4.0, 320, 60, 93, 0.95, 20,
          transform = FlowTransform(1.0, -.02, .03, 1.0, .01, -.01, .002, -.001), transformProcessingNs = 12345), flowAgeNs = 4,
        flowProcessingNs = 1_500_000),
      LiveFrameSample(126, 133, null, null, null, null, null, analyzed = false),
    ))
    val text = StringBuilder().also { LiveQuadTraceFormat.write(trace, it) }.toString()
    assertTrue(text.startsWith(LiveQuadTraceFormat.HEADER_LINE + "\n"))
    // Capture rows interleave by arrival time; frame rows follow, then gyro rows on their own clock.
    assertEquals(listOf("obs", "capture", "obs", "capture", "frame", "frame", "gyro", "gyro"), text.lines().drop(4).filter { it.isNotEmpty() }.map { it.substringBefore('\t') })
    val parsed = LiveQuadTraceFormat.read(text)
    assertEquals(trace, parsed)
    assertEquals((0.1 + 0.2).toRawBits(), parsed.observations[1].rawQuad!!.topLeft.x.toRawBits())
    assertEquals((0.1 + 0.2).toRawBits(), parsed.gyro[0].x.toRawBits())
    val uncalibrated = trace.copy(camera = LiveQuadCameraRecord(LiveCameraOptics(2800.0, 4000, 3000, 90), false))
    assertEquals(uncalibrated, LiveQuadTraceFormat.read(StringBuilder().also { LiveQuadTraceFormat.write(uncalibrated, it) }.toString()))
    val bare = LiveQuadTrace(config, trace.observations)
    assertEquals(bare, LiveQuadTraceFormat.read(StringBuilder().also { LiveQuadTraceFormat.write(bare, it) }.toString()))
  }

  @Test
  fun recorderStopsAtObservationAndDurationBoundsAndIgnoresAppendsAfterStop() {
    val counted = LiveQuadTraceRecorder()
    repeat(2_100) { counted.onObservation(observation(arrivalNs = it * 1_000_000L)) }
    assertTrue(counted.isFull)
    assertEquals(2_048, counted.stop().observations.size)

    val timed = LiveQuadTraceRecorder()
    timed.onObservation(observation(arrivalNs = 5_000_000_000L))
    timed.onObservation(observation(arrivalNs = 64_999_999_999L))
    assertFalse(timed.isFull)
    timed.onObservation(observation(arrivalNs = 65_000_000_000L))
    timed.onObservation(observation(arrivalNs = 65_000_000_001L))
    assertTrue(timed.isFull)
    timed.addCapture(LiveQuadCaptureEvent(66_000_000_000L, null, null, 1, 1))
    timed.addGyro(LiveQuadGyroSample(20, 0.0, 0.0, 1.0))
    timed.addGyro(LiveQuadGyroSample(10, 0.0, 1.0, 0.0))
    val trace = timed.stop()
    assertEquals(listOf(10L, 20L), trace.gyro.map { it.sensorNs })
    assertEquals(listOf(5_000_000_000L, 64_999_999_999L), trace.observations.map { it.arrivalNs })
    assertEquals(1, trace.captures.size)

    timed.onObservation(observation(arrivalNs = 5_000_000_001L))
    timed.addCapture(LiveQuadCaptureEvent(5_000_000_002L, null, null, 1, 1))
    timed.addGyro(LiveQuadGyroSample(30, 0.0, 0.0, 0.0))
    assertEquals(trace, timed.stop())
  }

  @Test
  fun recorderBoundsFramesAndMatchesGateDecisionsToTaps() {
    val recorder = LiveQuadTraceRecorder(maxFrames = 2)
    repeat(3) { recorder.onFrame(LiveFrameSample(it.toLong(), it.toLong(), null, null, null, null, null, true)) }
    recorder.onCaptureGate(LiveCaptureGateSample(100))
    recorder.onCaptureGate(LiveCaptureGateSample(300, fired = false))
    assertEquals(100L, recorder.gateSince(100)?.sensorClockNs)
    assertEquals(LiveCaptureGateSample(300, fired = false), recorder.gateSince(101))
    assertNull(recorder.gateSince(301))
    assertEquals(listOf(0L, 1L), recorder.stop().frames.map { it.sensorTimestampNs })
  }

  @Test
  fun stoppingFramesKeepsLateCaptureFireAndItsMeasurementUntilFinalStop() {
    val recorder = LiveQuadTraceRecorder()
    val frame = LiveFrameSample(100, 100, null, null, null, null, null, false)
    recorder.onFrame(frame)
    recorder.stopFrames()
    recorder.onFrame(frame.copy(sensorTimestampNs = 200))
    recorder.onObservation(observation())
    val fire = LiveCaptureGateSample(300, gateEvaluationNs = 290, gateExposureNs = 10_000_000, gateBlur = .001,
      gateSource = MotionSource.GYRO)
    recorder.onCaptureGate(fire)
    assertEquals(fire, recorder.gateSince(250))
    recorder.addCapture(LiveQuadCaptureEvent(250, null, null, 4000, 3000, 250, fire.sensorClockNs,
      gateEvaluationNs = fire.gateEvaluationNs, gateExposureNs = fire.gateExposureNs, gateBlur = fire.gateBlur))
    recorder.addGyro(LiveQuadGyroSample(295, 0.0, 0.0, 1.0))
    val trace = recorder.stop()
    assertEquals(listOf(frame), trace.frames)
    assertTrue(trace.observations.isEmpty())
    assertEquals(300L, trace.captures.single().firedSensorNs)
    assertEquals(1, trace.gyro.size)
    recorder.onCaptureGate(LiveCaptureGateSample(400))
    assertNull(recorder.gateSince(350))
  }

  @Test
  fun summaryKeepsRecordedGateBlurSeparateFromJpegExposureEstimate() {
    val trace = LiveQuadTrace(LiveQuadStabilizerConfig(), emptyList(), captures = listOf(
      LiveQuadCaptureEvent(80_000_000, null, null, 4000, 3000, 80_000_000, 100_000_000, 100_000_000,
        gateEvaluationNs = 90_000_000, gateExposureNs = 10_000_000, gateBlur = .001),
    ), gyro = listOf(LiveQuadGyroSample(100_000_000, 1.0, 0.0, 0.0)),
      camera = LiveQuadCameraRecord(LiveCameraOptics(1000.0, 1000, 1000, 0), true))
    val summary = LiveQuadTraceReplay.summarize("exposure-change", trace, emptyList(), "n/a")
    assertTrue(summary.contains("gateEvaluationNs=90000000 gateExposureMs=10.00 gateBlur=0.10%"))
    assertTrue(summary.contains("jpegExposureMs=100.00 jpegExposureGyroBlur=10.00%"))
    val withoutGyro = LiveQuadTraceReplay.summarize("no-gyro", trace.copy(gyro = emptyList()), emptyList(), "n/a")
    assertTrue(withoutGyro.contains("gateBlur=0.10%"))
    assertTrue(withoutGyro.contains("jpegExposureGyroBlur=-"))
  }

  private fun observation(
    arrivalNs: Long = 0,
    raw: DocumentQuad? = null,
    score: Double? = null,
    status: LiveQuadStatus = LiveQuadStatus.SEARCHING,
    accepted: DocumentQuad? = null,
  ) = LiveQuadObservation(
    generation = 3, sensorTimestampNs = arrivalNs - 7, arrivalNs = arrivalNs, width = 480, height = 640,
    cropLeft = 2, cropTop = 4, cropRight = 638, cropBottom = 476, rotationDegrees = 270, rawQuad = raw, rawScore = score,
    status = status, decision = LiveQuadDecision.ACQUIRING, chainLength = 2, expiredBeforeInput = true, accepted = accepted,
    acceptanceId = 9, holdDeadlineNs = arrivalNs + 400_000_000, preprocessingMs = 1.7000000000000002, inferenceMs = 22.25,
    stabilizerNs = 4_321,
  )
}
