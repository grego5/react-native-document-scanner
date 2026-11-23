package com.margelo.nitro.documentscanner

/**
 * Main thread, `focusLock`: when either camera asks the camera for its own locking scan, a sweep across the lens's
 * range that finds the sharp distance itself. The document camera and the text camera run the same cycle; each only
 * finds its own focus target and judges whether a lock found what it focused for.
 *
 * Continuous autofocus follows the target while the phone moves and brings the lens near, but it reports focus it has
 * not reached and in low light searches on without settling, so no lock waits for its report: every steady hold with a
 * target locks. A hold gets [FocusRules.MAX_LOCKS] locks. A lock that fails, or finds nothing, takes the next one, with
 * an auto torch lit first where the camera has one and the scene is dark or the lock failed; once they are spent the
 * cycle gives up until the phone moves and holds steady again, turns, or a new target arrives ([reset]).
 *
 * The locked subject changing size past the depth of field, as moving nearer or farther does without turning or shaking
 * the phone, starts the count over and locks again on the next steady frame without releasing: the lens stays where the
 * lock left it, near the new distance, rather than handing it to continuous autofocus. A relock waits for its target,
 * as a confirmed page, and counts only once the camera takes it. A turn of the phone releases the lock and starts over,
 * and so does a deliberate move of a lock that follows no subject's size; a restart offers no lock on the frame it
 * happens, whose target was picked before it.
 *
 * A target found under a lock that aimed elsewhere, as a page found under the document camera's guide lock, keeps the
 * lock when its point lies on the target, which the lock then focused, and replaces it with a lock of its own, counted
 * like any other, when it lies off it. Turns and moves come first, so a target found while the phone turns or moves
 * never keeps a lock that motion releases. Times are sensor nanoseconds.
 */
internal class FocusCycle {
  enum class Lens { FREE, SCANNING, LOCKED }

  /** Where a held lock aims against the frame's target: at it, or, aimed elsewhere before the target was found, at a
   * point that lies on the new target or off it. */
  enum class Aim { TARGET, ON_NEW_TARGET, OFF_NEW_TARGET }

  sealed interface Step {
    data object None : Step
    /** A new lock, with the lens free; the caller reports [started] once the camera takes it. */
    data class Lock(val why: String) : Step
    /** A new scan of the held lock without releasing it; the caller reports [started] once the camera takes it, and
     * until then it is asked for again on each steady frame with a target. */
    data class Relock(val why: String) : Step
    /** The held lock aimed at a point on the new target, so it becomes the target's lock without another scan. */
    data object Keep : Step
    /** The held lock aimed off the new target: release it and lock at the target; the caller reports [started] once
     * the camera takes the new lock. */
    data class Replace(val why: String) : Step
    data class Release(val why: String) : Step
    /** Light the auto torch; the next lock waits for [Frame.lit]. */
    data class Light(val why: String) : Step
    data class GiveUp(val why: String) : Step
  }

  /**
   * One frame. [quiet]: a steady hand; [turned]: the phone turned; [lens]: who holds it; [target]: the lock's focus
   * target is ready, the one a lock or relock now would aim at; [rescaled]: the locked subject changed size past the
   * depth of field; [found]: under [Lens.LOCKED], whether the lock found what it focused for, null until known;
   * [motionReleases]: a deliberate move releases this lock, which follows no subject's size; [canLight]: an auto torch
   * can still light; [dark]: a scene too dark for continuous autofocus; [lightWanted]: the torch asked for is still
   * wanted, its request standing; [lit]: it is lit and the exposure has settled under it; [aim]: under [Lens.LOCKED],
   * where the lock aims against the target.
   */
  class Frame(
    val quiet: Boolean, val turned: Boolean, val lens: Lens, val target: Boolean = true,
    val rescaled: Boolean = false, val found: Boolean? = null, val motionReleases: Boolean = true,
    val canLight: Boolean = false, val dark: Boolean = false, val lightWanted: Boolean = false, val lit: Boolean = false,
    val aim: Aim = Aim.TARGET,
  )

  private var locks = 0
  private var movingSinceNs: Long? = null
  private var moveCounted = false
  /** A deliberate move ended in [motion] that [next] has not acted on yet. */
  private var moved = false
  /** Why the held lock scans again once the camera takes it; null without a relock pending. */
  private var relock: String? = null
  /** Why the next lock of a free lens runs, past the steady hold's own reason. */
  private var lockWhy: String? = null
  /** The torch was asked for and the next lock waits for it. */
  var lighting = false
    private set
  var gaveUp = false
    private set
  /** Rises on every start over, so the caller can pick its target afresh. */
  var restarts = 0L
    private set
  /** The lock under which [Frame.found] already took a step, so a standing verdict counts once. */
  private var judged = false
  private var last: Frame? = null

  /** A new target: the count and a pending relock start over; a torch already asked for stays awaited while wanted. */
  fun reset() {
    locks = 0
    relock = null
    lockWhy = null
    gaveUp = false
    judged = false
    restarts++
  }

  /** The camera took the [Step.Lock], [Step.Relock] or [Step.Replace] just asked for: it counts toward the hold's
   * locks. Called before its trigger is sent, so a trigger that fails at once finds it counted. */
  fun started() {
    locks++
    relock = null
    lockWhy = null
    judged = false
  }

  /** The camera reported the lock failed and released it; null when the next steady frame simply locks again. */
  fun failed(): Step = attempt("lock failed", failed = true, held = false)

  /**
   * Every analysed frame's steadiness, at [sensorNs]: motion this long, past a hand's tremor, is a deliberate move,
   * which the next [next] acts on. The caller reports each frame, not only those it calls [next] on, so a move between
   * two of them still counts.
   */
  fun motion(sensorNs: Long, quiet: Boolean) {
    if (quiet) {
      movingSinceNs = null
      moveCounted = false
      return
    }
    val since = movingSinceNs ?: sensorNs.also { movingSinceNs = it }
    if (moveCounted || sensorNs - since < FocusRules.MOVED_NS) return
    moveCounted = true
    moved = true
  }

  fun next(frame: Frame): Step {
    last = frame
    val held = frame.lens != Lens.FREE
    // A torch no longer wanted, as the torch mode changing, is not awaited.
    if (lighting && !frame.lightWanted) lighting = false
    if (frame.turned) {
      moved = false
      reset()
      return if (held) Step.Release("phone turned") else Step.None
    }
    if (frame.rescaled && held) {
      reset()
      relock = "distance changed"
    }
    if (moved) {
      // The frame's target was picked before the move started the cycle over, so it waits for the next frame.
      moved = false
      val pending = relock
      reset()
      relock = pending
      return if (held && frame.motionReleases) Step.Release("phone moved") else Step.None
    }
    // A pending relock waits for the next frame, when the kept lock aims at the target.
    if (frame.lens == Lens.LOCKED && frame.aim == Aim.ON_NEW_TARGET) return Step.Keep
    if (!frame.quiet || frame.lens == Lens.SCANNING) return Step.None
    if (frame.lens == Lens.FREE) {
      judged = false
      relock = null
    }
    if (lighting) {
      if (!frame.lit) return Step.None
      lighting = false
      if (frame.lens == Lens.LOCKED) relock = "torch lit" else lockWhy = "torch lit"
    }
    if (frame.lens == Lens.LOCKED && frame.aim == Aim.OFF_NEW_TARGET) {
      // The held lock focused something else, so its relock would too.
      relock = null
      return when {
        gaveUp || !frame.target -> Step.None
        locks < FocusRules.MAX_LOCKS -> Step.Replace("a target found off the lock's point")
        else -> {
          gaveUp = true
          Step.GiveUp("the hold's locks are spent")
        }
      }
    }
    relock?.let { why -> return if (frame.target) Step.Relock(why) else Step.None }
    if (gaveUp) return Step.None
    return when (frame.lens) {
      Lens.FREE -> when {
        !frame.target -> Step.None
        locks < FocusRules.MAX_LOCKS -> Step.Lock(lockWhy ?: "a steady hold")
        else -> {
          gaveUp = true
          Step.GiveUp("the hold's locks are spent")
        }
      }
      Lens.LOCKED -> if (frame.found == false && !judged) {
        judged = true
        attempt("nothing found under the lock", failed = false, held = true)
      } else Step.None
      Lens.SCANNING -> Step.None
    }
  }

  /** The next step after a lock that failed or found nothing: the torch, one more lock, or giving up. */
  private fun attempt(why: String, failed: Boolean, held: Boolean): Step {
    if (lighting || gaveUp) return Step.None
    if (locks >= FocusRules.MAX_LOCKS) {
      gaveUp = true
      return Step.GiveUp(why)
    }
    val frame = last
    if (frame != null && frame.canLight && (failed || frame.dark)) {
      lighting = true
      return Step.Light(why)
    }
    if (!held) return Step.None
    relock = why
    return if (frame?.target != false) Step.Relock(why) else Step.None
  }
}

/** The focus cycle's motion and size rules, the same for both cameras. */
internal object FocusRules {
  /** The hand holds steady when the gyroscope peaks under [QUIET_TURN_RATE] rad/s and the accelerometer under
   * [QUIET_ACCELERATION] m/s² over this span: a resting hand, past sensor noise and tremor. */
  const val QUIET_HAND_NS = 300_000_000L
  const val QUIET_TURN_RATE = .15
  const val QUIET_ACCELERATION = .5
  /** A turn of about 4 degrees over [TURN_NS], twice the drift of a hand holding steady, ends a lock. */
  const val TURN_RADIANS = .07
  const val TURN_NS = 500_000_000L
  /** Motion this long, past a hand's tremor, is a deliberate move. The hand's quiet is judged over [QUIET_HAND_NS], so
   * one spike already reads as motion for that long. */
  const val MOVED_NS = 600_000_000L
  /** Locks per hold, so a view with nothing to focus stops scanning. */
  const val MAX_LOCKS = 2
  /** Frames in a row a subject's size must pass its limit by, so one jittered outline does not count. */
  const val SIZE_FRAMES = 3
  /** The least size change that counts, past the jitter of a tracked outline or recognized bounds. */
  const val SIZE_FLOOR = 1.05
  /** The most size change a lock tolerates, since the focus distance the camera reports is approximate. */
  const val SIZE_CAP = 1.25
  /** The size change that counts without a depth of field. */
  const val SIZE_FALLBACK = 1.15
  /** The side of a tap's focus area, as a fraction of the preview: CameraX's own tap-to-focus size. */
  const val TAP_AREA = 1f / 6f
}
