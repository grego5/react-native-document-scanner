package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CaptureResult
import com.margelo.nitro.documentscanner.DocumentQuad

/**
 * Analysis results used to check capture readiness for one frame. [sensorNs] is its exposure start;
 * [realtimeSensorNs] is the same instant on elapsedRealtimeNanos() when camera timestamps are REALTIME, else null.
 * [arrivalNs] is System.nanoTime() when analysis received it. [frame] is its own capture result, null when that had
 * not arrived. [flow] is the motion from the previous analyzed frame, null without flow. [motion] is this exposure's
 * blur estimate and its source, null when unknown. [gyroSpeed] is the rotation speed over the flow interval as frame
 * widths per second, calculated only for the low-texture fallback.
 * [guide] is the confirmed, in-frame page outline after this frame, null while none is tracked.
 */
internal data class FrameRecord(
  val sequence: Long,
  val sensorNs: Long,
  val realtimeSensorNs: Long?,
  val arrivalNs: Long,
  val frame: CameraFrameState?,
  val flow: FlowMotionEstimate?,
  val motion: GateMotion?,
  val gyroSpeed: Double?,
  val guide: DocumentQuad?,
) {
  /** Age of the image itself: from exposure on REALTIME cameras, otherwise from arrival at analysis. */
  fun ageNanos(nowNs: Long, nowRealtimeNs: Long): Long = realtimeSensorNs?.let { nowRealtimeNs - it } ?: (nowNs - arrivalNs)

  /** Exposed after [realtimeNs]/[nanoTimeNs], one instant on both clocks; arrival stands in without REALTIME. */
  fun after(realtimeNs: Long, nanoTimeNs: Long): Boolean =
    realtimeSensorNs?.let { it >= realtimeNs } ?: (arrivalNs >= nanoTimeNs)

  /** The states CameraX requires before a frame enters its zero-shutter-lag buffer; unknown without the result. */
  val zslReady: Boolean get() = frame != null &&
    (frame.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || frame.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) &&
    frame.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED && frame.awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
}

/**
 * The latest analyzed frame results in order, so a reader that fell behind still sees every frame rather than only
 * the newest. Bounded: a reader more than [capacity] frames behind is told it missed some. Written on the analysis
 * executor, read on main.
 */
internal class FrameHistory(private val capacity: Int = 32) {
  private val entries = ArrayDeque<FrameRecord>()
  private var nextSequence = 1L

  /** The sequence the next appended frame will get; a reader starting now passes it to [since]. */
  @Synchronized fun cursor(): Long = nextSequence

  @Synchronized fun append(make: (sequence: Long) -> FrameRecord) {
    entries.addLast(make(nextSequence++))
    while (entries.size > capacity) entries.removeFirst()
  }

  /** Frames from [sequence] on, oldest first, and whether earlier ones from [sequence] were already dropped. */
  @Synchronized fun since(sequence: Long): Pair<List<FrameRecord>, Boolean> {
    val newer = entries.filter { it.sequence >= sequence }
    val missed = (newer.firstOrNull()?.sequence ?: nextSequence) > sequence
    return newer to missed
  }

  @Synchronized fun latest(): FrameRecord? = entries.lastOrNull()

  /** Drops every frame; sequences keep rising, so a reader's cursor never matches a new binding's frames by accident. */
  @Synchronized fun clear() = entries.clear()
}
