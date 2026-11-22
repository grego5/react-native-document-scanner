package com.margelo.nitro.documentscanner

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/**
 * Follows a locking scan's trigger through the frames' autofocus states. CameraX switches to single-shot autofocus and
 * sends the trigger, but the SM-G998U can take a trigger sent while continuous autofocus still runs: the lens then
 * freezes where it is, without a sweep, CameraX may report that as a lock, and the switch drops the trigger. A frame in
 * single-shot autofocus reporting inactive after the trigger, before any scan, lock or cancel, shows it [Event.LOST]. A
 * lock counts only once a frame after its trigger reports it in single-shot autofocus, [Event.LOCKED]. Each event
 * belongs to [scan], the scan token [arm] was given; its owner acts on it only while that scan is still pending. Not
 * thread-safe: [FocusController] guards it, since frames arrive on a camera thread and scans start on main.
 */
internal class LockFrameWatch {
  enum class Event { NONE, LOST, LOCKED }

  private var triggerFrame = Long.MAX_VALUE
  private var lockedFrame = -1L
  private var awaitingScan = false

  /** The scan token the frames are watched for; 0 while none is. */
  var scan = 0L
    private set

  /** A frame after [scan]'s trigger reported its lock in single-shot autofocus. */
  val lockReported: Boolean get() = triggerFrame != Long.MAX_VALUE && lockedFrame > triggerFrame

  /** Scan [token] starts: its trigger and lock frames are yet to come, and nothing of the previous scan's carries over. */
  fun arm(token: Long) {
    scan = token
    triggerFrame = Long.MAX_VALUE
    lockedFrame = -1L
    awaitingScan = false
  }

  /** One frame: [trigger] is its request's autofocus trigger, with its result's autofocus mode and state. */
  fun observe(frameNumber: Long, trigger: Int?, afMode: Int?, afState: Int?): Event {
    if (trigger == CaptureRequest.CONTROL_AF_TRIGGER_START) {
      triggerFrame = frameNumber
      awaitingScan = true
      return Event.NONE
    }
    // A cancel ends whichever scan was awaited, and returns the camera to inactive with no trigger lost.
    if (trigger == CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) awaitingScan = false
    val singleShot = afMode == CaptureResult.CONTROL_AF_MODE_AUTO
    val lock = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
      afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
    var event = Event.NONE
    if (awaitingScan) {
      if (lock || afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN) awaitingScan = false
      else if (afState == CaptureResult.CONTROL_AF_STATE_INACTIVE && singleShot) {
        awaitingScan = false
        event = Event.LOST
      }
    }
    if (singleShot && lock && frameNumber > triggerFrame && lockedFrame < triggerFrame) {
      lockedFrame = frameNumber
      return Event.LOCKED
    }
    return event
  }
}
