package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameHistoryTest {
  @Test
  fun aReaderSeesEveryFrameInOrderEvenWhenItFallsBehind() {
    val log = FrameHistory(capacity = 4)
    val cursor = log.cursor()
    repeat(3) { append(log) }
    val (frames, missed) = log.since(cursor)
    assertEquals(listOf(cursor, cursor + 1, cursor + 2), frames.map { it.sequence })
    assertFalse(missed)
  }

  @Test
  fun aReaderTooFarBehindIsToldItMissedFrames() {
    val log = FrameHistory(capacity = 2)
    val cursor = log.cursor()
    repeat(3) { append(log) }
    val (frames, missed) = log.since(cursor)
    assertEquals(2, frames.size)
    assertTrue(missed)
  }

  @Test
  fun clearingDropsFramesButSequencesKeepRising() {
    val log = FrameHistory()
    append(log)
    val cursor = log.cursor()
    log.clear()
    assertEquals(cursor, log.cursor())
    append(log)
    assertEquals(cursor, log.since(cursor).first.single().sequence)
  }

  @Test
  fun zeroShutterLagEligibilityNeedsFocusExposureAndWhiteBalance() {
    fun state(af: Int, ae: Int, awb: Int) = CameraFrameState(30_000_000, 0, false, af, aeState = ae, awbState = awb)
    val converged = CaptureResult.CONTROL_AE_STATE_CONVERGED
    val balanced = CaptureResult.CONTROL_AWB_STATE_CONVERGED
    assertTrue(frameResult(state(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, converged, balanced)).zslReady)
    assertTrue(frameResult(state(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, converged, balanced)).zslReady)
    assertFalse(frameResult(state(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, converged, balanced)).zslReady)
    assertFalse(frameResult(state(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AE_STATE_SEARCHING, balanced)).zslReady)
    assertFalse(frameResult(state(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, converged, CaptureResult.CONTROL_AWB_STATE_SEARCHING)).zslReady)
    assertFalse(frameResult(null).zslReady)
  }

  @Test
  fun framesExposedBeforeAFocusChangeAreNotAfterIt() {
    val frame = frameResult(null)
    assertTrue(frame.after(realtimeNs = 100, nanoTimeNs = Long.MAX_VALUE))
    assertFalse(frame.after(realtimeNs = 101, nanoTimeNs = 0))
    // Without REALTIME timestamps, arrival at analysis stands in.
    assertTrue(frame.copy(realtimeSensorNs = null).after(realtimeNs = Long.MAX_VALUE, nanoTimeNs = 200))
  }

  @Test
  fun ageUsesExposureTimeEvenWhenAnalysisArrivedRecently() {
    val nowNs = 10_000_000_000L
    val nowRealtimeNs = 50_000_000_000L
    val frame = frameResult(null).copy(
      realtimeSensorNs = nowRealtimeNs - 150_000_000, arrivalNs = nowNs - 80_000_000,
    )
    assertEquals(150_000_000L, frame.ageNanos(nowNs, nowRealtimeNs))
    // Without REALTIME timestamps, arrival at analysis bounds the age instead.
    val arrivalTimed = frame.copy(realtimeSensorNs = null, arrivalNs = nowNs - 150_000_000)
    assertEquals(150_000_000L, arrivalTimed.ageNanos(nowNs, nowRealtimeNs))
    val typical = frame.copy(realtimeSensorNs = nowRealtimeNs - 110_000_000)
    assertEquals(110_000_000L, typical.ageNanos(nowNs, nowRealtimeNs))
  }

  private fun append(log: FrameHistory) = log.append { frameResult(null).copy(sequence = it) }

  private fun frameResult(frame: CameraFrameState?) =
    FrameRecord(0, 100, 100, 200, frame, null, null, null, null)
}
