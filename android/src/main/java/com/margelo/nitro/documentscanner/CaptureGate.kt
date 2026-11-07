package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.FlowMotionStatus
import com.margelo.nitro.documentscanner.processing.FrameRecord
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Why a [CaptureStability] sequence last restarted, for the lifecycle log. */
internal enum class StabilityBreak { MOVING, SMEARED, TRACKING_LOST, NO_DOCUMENT, DOCUMENT_MOVED, UNFOCUSED }

/**
 * Framing stability for a capture, built from consecutive analyzed frames fed in order, one [FrameRecord] each. Three
 * separate questions decide whether a frame continues the sequence: its flow from the previous analyzed frame moves under
 * [MAX_SPEED] frame widths per second whatever the exposure (framing); its own smear stays within the blur limit
 * (exposure); and, when a page is required, the page keeps its position, scale and shape within [MAX_SHIFT],
 * [MAX_SCALE] and [MAX_SHAPE] of where the sequence began (geometry). Missing or broken flow, a frame whose flow does not
 * start at the sequence's last frame, and unknown smear never continue a sequence. A frame whose capture result never
 * arrived has unknown exposure, so its smear is unknown: it still checks speed and geometry and keeps the sequence
 * unbroken, but does not count toward it. Flow without enough texture falls back to the frame's gyroscope speed. Main thread.
 */
internal class CaptureStability(private val required: Int = REQUIRED_FRAMES) {
  private var reference: DocumentCorners? = null
  /** Frames in the current sequence. */
  var count = 0
    private set
  /** Sensor timestamps of the sequence's first and latest counted frames; 0 before one qualifies. */
  var firstSensorNs = 0L
    private set
  var latestSensorNs = 0L
    private set
  /** Sensor timestamp of the sequence's latest frame, counted or not, where the next frame's flow must start. */
  private var chainSensorNs = 0L
  var lastBreak: StabilityBreak? = null
    private set

  val stable: Boolean get() = count >= required

  fun reset() {
    count = 0
    reference = null
    firstSensorNs = 0L
    latestSensorNs = 0L
    chainSensorNs = 0L
  }

  /** Breaks the sequence for a frame judged outside it: by default one the reader fell too far behind to see. */
  fun interrupt(reason: StabilityBreak = StabilityBreak.TRACKING_LOST) {
    restart(reason)
  }

  /**
   * Feeds the next analyzed frame. With [requireDocument], a frame without a page breaks the sequence. [blurLimit] above 0
   * checks the frame's own motion blur. Returns [stable].
   */
  fun observe(frame: FrameRecord, requireDocument: Boolean, blurLimit: Double): Boolean {
    val flow = frame.flow ?: return restart(StabilityBreak.TRACKING_LOST)
    // Each estimate starts at the previous analyzed frame; one that does not follow the sequence skipped a frame.
    if (count > 0 && flow.fromSensorNs != chainSensorNs) restart(StabilityBreak.TRACKING_LOST)
    val speed = when (flow.status) {
      FlowMotionStatus.MEASURED -> if (flow.lostRegions > 0) return restart(StabilityBreak.TRACKING_LOST)
        else flow.speedPxPerSecond?.let { it / flow.sourceWidth }
      FlowMotionStatus.NO_REFERENCE, FlowMotionStatus.TRACKING_FAILED -> return restart(StabilityBreak.TRACKING_LOST)
      FlowMotionStatus.TOO_FEW_FEATURES, FlowMotionStatus.POOR_COVERAGE -> frame.gyroSpeed
    } ?: return restart(StabilityBreak.TRACKING_LOST)
    if (speed > MAX_SPEED) return restart(StabilityBreak.MOVING)
    val smearUnknown = blurLimit > 0 && frame.frame == null
    if (blurLimit > 0 && !smearUnknown) {
      val blur = frame.motion?.blur
      if (blur == null || blur > blurLimit) return restart(StabilityBreak.SMEARED)
    }
    if (requireDocument) {
      val corners = frame.guide ?: return restart(StabilityBreak.NO_DOCUMENT)
      // The page moved over the sequence though no single step was fast: this frame begins a new one.
      reference?.let { if (drift(it, corners) != null) restart(StabilityBreak.DOCUMENT_MOVED) }
      if (reference == null) reference = corners
    }
    if (smearUnknown) {
      if (count > 0) chainSensorNs = frame.sensorNs
      return stable
    }
    if (count == 0) firstSensorNs = frame.sensorNs
    chainSensorNs = frame.sensorNs
    latestSensorNs = frame.sensorNs
    count++
    return stable
  }

  private fun restart(reason: StabilityBreak): Boolean {
    reset()
    lastBreak = reason
    return false
  }

  companion object {
    /** Consecutive analyzed frames, about 130 ms at 30 fps. A starting setting. */
    const val REQUIRED_FRAMES = 4
    /** Frame widths per second. Recorded steady A4 captures had at most 0.09% flow blur and moving ones at least
     * 0.31%; at a 30 ms exposure those are 3% and 10% of the width per second. A starting setting.
     */
    const val MAX_SPEED = 0.05
    /** Normalized centroid travel, relative scale change and residual corner travel allowed over a sequence. Starting
     * settings above the guide's frame-to-frame model noise.
     */
    const val MAX_SHIFT = 0.02
    const val MAX_SCALE = 0.03
    const val MAX_SHAPE = 0.02

    /** Which of position, scale or shape [to] moved beyond its limit from [from]; null while it holds. */
    fun drift(from: DocumentCorners, to: DocumentCorners): String? {
      val a = corners(from)
      val b = corners(to)
      val (ax, ay) = centroid(a)
      val (bx, by) = centroid(b)
      if (hypot(bx - ax, by - ay) > MAX_SHIFT) return "position"
      val scale = sqrt(area(b) / area(a))
      if (!scale.isFinite() || abs(scale - 1) > MAX_SCALE) return "scale"
      // Corners relative to their centroid, the later outline scaled back, leave perspective and rotation.
      var shape = 0.0
      for (i in 0 until 4) {
        val dx = (b[i * 2] - bx) / scale - (a[i * 2] - ax)
        val dy = (b[i * 2 + 1] - by) / scale - (a[i * 2 + 1] - ay)
        shape = max(shape, hypot(dx, dy))
      }
      return if (shape > MAX_SHAPE) "shape" else null
    }

    private fun corners(q: DocumentCorners) = doubleArrayOf(
      q.topLeft.x, q.topLeft.y, q.topRight.x, q.topRight.y, q.bottomRight.x, q.bottomRight.y, q.bottomLeft.x, q.bottomLeft.y,
    )

    private fun centroid(c: DoubleArray) = (c[0] + c[2] + c[4] + c[6]) / 4 to (c[1] + c[3] + c[5] + c[7]) / 4

    private fun area(c: DoubleArray): Double {
      var sum = 0.0
      for (i in 0 until 4) {
        val j = (i + 1) % 4
        sum += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]
      }
      return abs(sum) / 2
    }
  }
}

/** What a waiting capture is waiting for, in the order it is reported. */
internal enum class WaitReason { DOCUMENT, FOCUS, LIGHT, STEADY }

/** Why a capture's first focus scan starts; the camera view judges each. */
internal enum class ScanTrigger { QUALITY, STALE_FOCUS, DARK, UNSEEN_PAGE }

/** The capture's own focus scan as the focus controller last published it. */
internal enum class OwnScan { NONE, SCANNING, LOCKED, FAILED }

internal sealed interface CaptureDecision {
  /** Re-evaluate on the next event, and at [untilMs] at the latest, when the current deadline expires. */
  data class Wait(val reason: WaitReason, val untilMs: Long) : CaptureDecision
  /** Start the capture's own focus scan, its [attempt]th; [trigger] is why its first one started. The focus change
   * re-evaluates. */
  data class StartFocusScan(val trigger: ScanTrigger, val attempt: Int) : CaptureDecision
  data object Shoot : CaptureDecision
  data class Reject(val code: ScannerErrorCode, val reason: WaitReason?) : CaptureDecision
}

/**
 * Inputs to the capture readiness check. [focusCheckEnabled] enables focus checking; [documentRequired] also requires
 * a confirmed page. [focused] and [documentFound] come from the focus controller and the newest frame record.
 * [zslCheckEnabled] requires eligible AF/AE/AWB states in [zslReady], whether or not focus is required: CameraX would
 * otherwise return the newest frame its buffer accepted, however old. [lightReady] requires no pending torch or boost
 * change. [motionCheckEnabled] enables [steady], the stability check's result for consecutive frames. [focusingUntilMs]:
 * until when autofocus counts as still moving, a scan pending or a recent frame searching; it extends a focus wait past
 * its deadline, and is null while autofocus is still. A pending scan's is [Long.MAX_VALUE]: its settling is a focus
 * event.
 *
 * The capture's own scan: [scanAvailable] means one could start now (autofocus, a bound camera, focus required and no
 * tap holding the lens); [scanTrigger] is why a first one would help now, or null. [ownScan] is its published state,
 * and [frameSinceFocusChange] whether a frame exposed after the latest focus change has been judged.
 */
internal data class CaptureState(
  val nowMs: Long,
  val focusCheckEnabled: Boolean,
  val documentRequired: Boolean,
  val requireFocus: Boolean,
  val focusTimeoutMs: Long,
  val documentFound: Boolean,
  val focused: Boolean,
  val zslCheckEnabled: Boolean,
  val zslReady: Boolean,
  val lightReady: Boolean,
  val motionCheckEnabled: Boolean,
  val steady: Boolean,
  val focusingUntilMs: Long? = null,
  val scanAvailable: Boolean = false,
  val scanTrigger: ScanTrigger? = null,
  val ownScan: OwnScan = OwnScan.NONE,
  val frameSinceFocusChange: Boolean = true,
)

/**
 * The single owner of a capture's readiness, its own focus scans and its deadlines. Focus, with its page in `scanner`
 * mode, comes first and waits up to the focus timeout from its first evaluation; once it holds, everything must align
 * within [steadyTimeoutMs]. Losing focus or the page after that waits within the same deadline, and accepting them again
 * restarts it. Autofocus still moving
 * extends a focus wait past either deadline, so a shot never fails or fires halfway through focusing. A capture that does
 * not require focus shoots without its page at either deadline, still needing light and steadiness. A skipped gate waits
 * only for a focus scan in progress, which settles on its own, and never scans. The first evaluation where everything
 * holds shoots; there is no settling delay. Main thread.
 *
 * Scans: a first scan starts when its trigger holds before the focus deadline. One that settles without focus is retried
 * once from where it left the lens, whatever its trigger shows by then; the second failure is the last. Motion never
 * starts a scan: it only delays the shot, or rejects it at the steady deadline. A scan in progress holds the page and focus waits past their
 * deadline, and once it settles they wait up to [settledFrameWaitMs] for a frame exposed since, so the page it brought
 * into focus can be seen. The focus controller's own timeout bounds a scan.
 */
internal class CaptureGate(private val steadyTimeoutMs: Long, private val skipLowQualityControl: Boolean = false,
  private val maxScans: Int = 2, private val settledFrameWaitMs: Long = 500) {
  private var focusDeadlineMs = 0L
  private var steadyDeadlineMs = 0L
  /** Focus, with its page when required, held at the previous evaluation since the steady deadline was set. */
  private var focusHeld = false
  /** Focus stopped being required: its timeout passed on a capture that does not require it. */
  var focusSkipped = false
    private set
  /** Scans this capture started, and why the first one did. */
  var scans = 0
    private set
  private var trigger: ScanTrigger? = null
  private var lastOwnScan = OwnScan.NONE
  /** When this gate first saw its latest scan settled; null while it scans or before one. */
  private var scanSettledMs: Long? = null

  fun next(captureState: CaptureState): CaptureDecision {
    val now = captureState.nowMs
    val ownScan = captureState.ownScan
    if (ownScan == OwnScan.SCANNING) {
      scanSettledMs = null
    } else if (lastOwnScan == OwnScan.SCANNING) {
      scanSettledMs = now
    }
    lastOwnScan = ownScan
    // Per-shot override: lifecycle and permission checks remain in the camera before gate evaluation. Only a focus scan
    // in progress is waited for, and nothing rejects.
    if (skipLowQualityControl) {
      val until = captureState.focusingUntilMs?.takeIf { captureState.focusCheckEnabled && !captureState.focused && it > now }
      return if (until != null) CaptureDecision.Wait(WaitReason.FOCUS, until) else CaptureDecision.Shoot
    }
    scanToStart(captureState)?.let { return it }
    // Autofocus still moving is waited out, until it stops or its evidence goes stale; only a settled miss fails. The
    // capture's own scan also holds a page wait: the page may be too blurred to detect until the scan settles.
    fun focusingUntil(reason: WaitReason?): Long? {
      if (reason != WaitReason.DOCUMENT && reason != WaitReason.FOCUS) return null
      if (ownScan == OwnScan.SCANNING) return Long.MAX_VALUE
      scanSettledMs?.let { settled ->
        val until = settled + settledFrameWaitMs
        if (!captureState.frameSinceFocusChange && until > now) return until
      }
      return captureState.focusingUntilMs?.takeIf { reason == WaitReason.FOCUS && it > now }
    }
    fun blocker(): WaitReason? {
      val needsFocus = captureState.focusCheckEnabled && !focusSkipped
      return when {
        needsFocus && captureState.documentRequired && !captureState.documentFound -> WaitReason.DOCUMENT
        needsFocus && !captureState.focused -> WaitReason.FOCUS
        !captureState.lightReady || captureState.zslCheckEnabled && !captureState.zslReady -> WaitReason.LIGHT
        captureState.motionCheckEnabled && !captureState.steady -> WaitReason.STEADY
        else -> null
      }
    }
    if (steadyDeadlineMs == 0L) {
      val reason = blocker()
      if (reason == WaitReason.DOCUMENT || reason == WaitReason.FOCUS) {
        if (focusDeadlineMs == 0L) focusDeadlineMs = now + captureState.focusTimeoutMs
        if (now < focusDeadlineMs) return CaptureDecision.Wait(reason, focusDeadlineMs)
        focusingUntil(reason)?.let { return CaptureDecision.Wait(reason, it) }
        if (captureState.requireFocus) return CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, reason)
        focusSkipped = true
      }
      steadyDeadlineMs = now + steadyTimeoutMs
      focusHeld = true
    }
    val blocked = blocker()
    // Focus and its page accepted again after a loss restart the steadiness window, as the view restarts its frame
    // count: a refocus that ends late must still leave time for the steady frames.
    val holds = blocked != WaitReason.DOCUMENT && blocked != WaitReason.FOCUS
    if (holds && !focusHeld) steadyDeadlineMs = now + steadyTimeoutMs
    focusHeld = holds
    var reason = blocked ?: return CaptureDecision.Shoot
    if (now < steadyDeadlineMs) return CaptureDecision.Wait(reason, steadyDeadlineMs)
    focusingUntil(reason)?.let { return CaptureDecision.Wait(reason, it) }
    if ((reason == WaitReason.DOCUMENT || reason == WaitReason.FOCUS) && !captureState.requireFocus) {
      focusSkipped = true
      reason = blocker() ?: return CaptureDecision.Shoot
    }
    return CaptureDecision.Reject(when (reason) {
      WaitReason.DOCUMENT, WaitReason.FOCUS -> ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT
      // Exposure, white balance or the torch never settled: a capture failure, as before these checks existed. Zero
      // shutter lag must not shoot anyway: CameraX then returns the newest frame its buffer accepted, however old.
      WaitReason.LIGHT -> ScannerErrorCode.CAPTURE_FAILED
      WaitReason.STEADY -> ScannerErrorCode.CAPTURE_MOTION_TIMEOUT
    }, reason)
  }

  /** The capture's next own scan, if one should start now. */
  private fun scanToStart(captureState: CaptureState): CaptureDecision.StartFocusScan? {
    if (!captureState.scanAvailable || focusSkipped) return null
    val retry = captureState.ownScan == OwnScan.FAILED && scans < maxScans
    val first = scans == 0 && captureState.scanTrigger != null &&
      (focusDeadlineMs == 0L || captureState.nowMs < focusDeadlineMs)
    if (first) trigger = captureState.scanTrigger
    val why = trigger
    if (!(first || retry) || why == null) return null
    scans++
    return CaptureDecision.StartFocusScan(why, scans)
  }
}

/** Public wait status contains no presentation text. */
internal fun WaitReason.toCaptureWaitReason(): CaptureWaitReason = when (this) {
  WaitReason.DOCUMENT -> CaptureWaitReason.DOCUMENT
  WaitReason.FOCUS -> CaptureWaitReason.FOCUS
  WaitReason.LIGHT -> CaptureWaitReason.LIGHT
  WaitReason.STEADY -> CaptureWaitReason.STEADY
}
