package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.FocusReading
import com.margelo.nitro.documentscanner.processing.FocusVerdict

/**
 * Main thread, `focusLock`: when the document camera asks the camera for its own locking scan, a sweep across the lens's
 * range that finds the sharp distance itself. The camera finds its own focus target and judges whether a lock found what
 * it focused for; the text camera judges its locks by the print's blur instead ([TextFocusCycle]).
 *
 * A loop driven by events, with no count of locks and no timers. Continuous autofocus comes first: it follows the target
 * while the phone moves, but it reports focus it has not reached, so the print's edges ([Frame.print]) judge it. A steady
 * hold whose print reads sharp keeps it, and a waiting press shoots on it. The lock is the last resort: a steady hold
 * locks once the print reads defocused, or never reads sharp once the lens has settled, and in the dark at once, where
 * continuous autofocus is known to fail. A lock that holds sharp ends the loop until a move or turn breaks it.
 *
 * A lock that fails, finds nothing or leaves the print defocused lights an auto torch where the camera has one and the
 * scene is dark or the lock failed, and locks again once it is lit. Otherwise the cycle is [finished]: a failed lock
 * means a dark or bad scene, which trying again does not change, so it waits for one that does. A deliberate move
 * followed by a steady hold, a turn, a new target, or the caller's [reset] on a change of light or view starts it over.
 * A waiting press then shoots on what the lens holds, its focus unconfirmed.
 *
 * The locked subject changing size past the depth of field, as moving nearer or farther does without turning or shaking
 * the phone, starts the cycle over and locks again on the next steady frame without releasing: the lens stays where the
 * lock left it, near the new distance, rather than handing it to continuous autofocus. A relock waits for its target,
 * as a confirmed page. A turn of the phone releases the lock and starts over, and so does a deliberate move of a lock
 * that follows no subject's size; a restart offers no lock on the frame it happens, whose target was picked before it.
 * Motion shorter than a deliberate move only pauses the cycle.
 *
 * A target found under a lock that aimed elsewhere, as a page found under the document camera's guide lock, keeps the
 * lock when its point lies on the target, which the lock then focused, and replaces it with a lock of its own when it
 * lies off it. Turns and moves come first, so a target found while the phone turns or moves never keeps a lock that
 * motion releases. Times are sensor nanoseconds.
 */
internal class FocusCycle {
  enum class Lens { FREE, SCANNING, LOCKED }

  /** What the print's edges say about the lens's focus on the target, over the frames since the lens last changed
   * hands: [UNJUDGED] without such evidence, [UNSURE] while no run of frames agrees yet, [UNCLEAR] once the lens has
   * rested [FocusRules.UNCLEAR_FRAMES] frames without the print reading sharp. */
  enum class Print { UNJUDGED, UNSURE, UNCLEAR, SHARP, DEFOCUSED }

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
    /** The cycle has ended without focus ([finished]). */
    data class Finish(val why: String) : Step
  }

  /**
   * One frame. [quiet]: a steady hand; [turned]: the phone turned; [lens]: who holds it; [target]: the lock's focus
   * target is ready, the one a lock or relock now would aim at; [rescaled]: the locked subject changed size past the
   * depth of field; [found]: under [Lens.LOCKED], whether the lock found what it focused for, null until known;
   * [motionReleases]: a deliberate move releases this lock, which follows no subject's size; [canLight]: an auto torch
   * can still light; [dark]: a scene too dark for continuous autofocus; [lightWanted]: the torch asked for is still
   * wanted, its request standing; [lit]: it is lit and the exposure has settled under it; [aim]: under [Lens.LOCKED],
   * where the lock aims against the target; [print]: what the print's edges say about focus; [generation]: the focus
   * generation the lens is in, which a pending relock belongs to.
   */
  class Frame(
    val quiet: Boolean, val turned: Boolean, val lens: Lens, val target: Boolean = true,
    val rescaled: Boolean = false, val found: Boolean? = null, val motionReleases: Boolean = true,
    val canLight: Boolean = false, val dark: Boolean = false, val lightWanted: Boolean = false, val lit: Boolean = false,
    val aim: Aim = Aim.TARGET, val print: Print = Print.UNJUDGED, val generation: Long = 0,
  )

  /** A relock the held lock owes, [why], for the lock of focus [generation]. */
  private data class Relock(val why: String, val generation: Long)

  private var movingSinceNs: Long? = null
  private var moveCounted = false
  /** A deliberate move ended in [motion] that [next] has not acted on yet. */
  private var moved = false
  /**
   * The relock the held lock owes, null without one. It belongs to the lock, not to the cycle's attempts: a [reset]
   * keeps it, a relock the camera refuses stays pending, and the camera taking it ([started]), a release, a replacement
   * or the lens changing generation without one retires it. While it is pending a press does not take the lock.
   */
  private var relock: Relock? = null
  /** Why the next lock of a free lens runs, past the steady hold's own reason. */
  private var lockWhy: String? = null
  /** The torch was asked for and the next lock waits for it. */
  var lighting = false
    private set
  /** The cycle ended without focus and waits for an event that starts it over. */
  var finished = false
    private set
  /** Rises on every start over, so the caller can pick its target afresh. */
  var restarts = 0L
    private set
  /** The lock under which [Frame.found] or [Frame.print] already took a step, so a standing verdict counts once. */
  private var judged = false

  /** Starts over: a new target, or a change of light or view that a finished cycle waits for. The held lock's pending
   * relock stays, and a torch already asked for stays awaited while wanted. */
  fun reset() {
    lockWhy = null
    finished = false
    judged = false
    restarts++
  }

  /** The camera took the [Step.Lock], [Step.Relock] or [Step.Replace] just asked for. Called before its trigger is
   * sent, so a trigger that fails at once finds it taken. */
  fun started() {
    relock = null
    lockWhy = null
    judged = false
  }

  /** What a held scanner lock is worth to a waiting press. */
  enum class Readiness { WORKING, ACCEPTED, REJECTED }

  /**
   * The held scanner lock's [Readiness] by its [print]: rejected once it found nothing or left the print defocused, which
   * the cycle answers with the torch or its end; accepted once the print reads sharp under it, or unclear, since the
   * lock is the last resort and print too sparse to judge stays unclear; working until then, and while a relock is
   * pending. A press never shoots on a lock before its own frames have judged it.
   */
  fun lockReadiness(generation: Long, print: Print): Readiness = when {
    judged || print == Print.DEFOCUSED -> Readiness.REJECTED
    relock?.generation == generation -> Readiness.WORKING
    print == Print.SHARP || print == Print.UNCLEAR -> Readiness.ACCEPTED
    else -> Readiness.WORKING
  }

  /** What the scanner's focus offers a waiting press. */
  enum class PressFocus { WORKING, ACCEPTED, UNCONFIRMED }

  /**
   * The scanner's focus for a press, in focus [generation] with the [lens] as the caller holds it and the [print] read
   * under it: a held lock its frames accepted ([lockReadiness]), or continuous autofocus whose print reads sharp outside
   * the [dark]; otherwise unconfirmed once the cycle has [ended], and working before. The caller still decides whether
   * its kind of lock satisfies the press.
   */
  fun pressFocus(lens: Lens, generation: Long, print: Print, dark: Boolean): PressFocus = when {
    lens == Lens.LOCKED && lockReadiness(generation, print) == Readiness.ACCEPTED -> PressFocus.ACCEPTED
    lens == Lens.FREE && print == Print.SHARP && !dark -> PressFocus.ACCEPTED
    ended -> PressFocus.UNCONFIRMED
    else -> PressFocus.WORKING
  }

  /** The cycle ended without focus and waits for no torch: a waiting press shoots on what the lens holds. */
  val ended: Boolean get() = finished && !lighting

  /** The camera released a failed lock. The failure can arrive between frames, so use current torch eligibility. */
  fun failed(canLight: Boolean): Step = attempt("lock failed", canLight)

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
    val held = frame.lens != Lens.FREE
    // A torch no longer wanted, as the torch mode changing, is not awaited.
    if (lighting && !frame.lightWanted) lighting = false
    // The lens changed generation without the relock being taken: the lock it was owed to is gone.
    if (relock?.generation != frame.generation) relock = null
    if (frame.turned) {
      moved = false
      reset()
      relock = null
      return if (held) Step.Release("phone turned") else Step.None
    }
    if (frame.rescaled && held) {
      reset()
      relock = Relock("distance changed", frame.generation)
    }
    if (moved) {
      // The frame's target was picked before the move started the cycle over, so it waits for the next frame.
      moved = false
      reset()
      if (held && frame.motionReleases) {
        relock = null
        return Step.Release("phone moved")
      }
      return Step.None
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
      if (frame.lens == Lens.LOCKED) relock = Relock("torch lit", frame.generation) else lockWhy = "torch lit"
    }
    if (frame.lens == Lens.LOCKED && frame.aim == Aim.OFF_NEW_TARGET) {
      // The held lock focused something else, so its relock would too. A new target is worth a lock of its own.
      relock = null
      return if (frame.target) Step.Replace("a target found off the lock's point") else Step.None
    }
    relock?.let { pending -> return if (frame.target) Step.Relock(pending.why) else Step.None }
    if (finished) return Step.None
    return when (frame.lens) {
      Lens.FREE -> if (frame.target && lockFree(frame)) Step.Lock(lockWhy ?: freeWhy(frame)) else Step.None
      Lens.LOCKED -> if ((frame.found == false || frame.print == Print.DEFOCUSED) && !judged) {
        judged = true
        attempt(if (frame.found == false) "nothing found under the lock" else "the print reads defocused under the lock",
          frame.canLight && frame.dark)
      } else Step.None
      Lens.SCANNING -> Step.None
    }
  }

  /** Whether the free lens's steady hold with a target locks now: always without the print's evidence, in the dark or
   * once the torch is lit; never while the print reads sharp or no verdict has formed yet; and once it reads defocused
   * or [Print.UNCLEAR]. */
  private fun lockFree(frame: Frame): Boolean =
    frame.print == Print.UNJUDGED || frame.dark || lockWhy != null ||
      frame.print == Print.DEFOCUSED || frame.print == Print.UNCLEAR

  private fun freeWhy(frame: Frame): String = when {
    frame.print == Print.UNJUDGED -> "a steady hold"
    frame.dark -> "a steady hold in the dark"
    frame.print == Print.DEFOCUSED -> "the print reads defocused"
    else -> "no sharp print once the lens settled"
  }

  /** The next step after a lock that failed, found nothing or left the print defocused: the torch, or the cycle's end. */
  private fun attempt(why: String, lightTorch: Boolean): Step {
    if (lighting || finished) return Step.None
    if (lightTorch) {
      lighting = true
      return Step.Light(why)
    }
    finished = true
    return Step.Finish(why)
  }
}

/** The focus cycle's motion and size rules, the same for both cameras. */
internal object FocusRules {
  /** The hand holds steady when the phone turns under [QUIET_TURN] radians net and the accelerometer peaks under
   * [QUIET_ACCELERATION] m/s² over this span: a resting hand, past sensor noise and tremor. A handheld phone over a
   * page on the SM-G998U peaked at 0.2 to 0.4 rad/s and 0.3 to 0.9 m/s² from tremor alone, while its frames measured
   * as sharp as a still one's; a deliberate move peaked at 2 to 4 m/s². The turn is about 1% of the view's width. */
  const val QUIET_HAND_NS = 300_000_000L
  const val QUIET_TURN = .015
  const val QUIET_ACCELERATION = 1.0
  /** A turn of about 4 degrees over [TURN_NS], twice the drift of a hand holding steady, ends a lock. */
  const val TURN_RADIANS = .07
  const val TURN_NS = 500_000_000L
  /** Motion this long, past a hand's tremor, is a deliberate move. The hand's quiet is judged over [QUIET_HAND_NS], so
   * one spike already reads as motion for that long. */
  const val MOVED_NS = 600_000_000L
  /** Analysis frames in a row that make a verdict, so one noisy measure does not. */
  const val VERDICT_FRAMES = 3
  /** Analysis frames with the lens at rest and no sharp print after which the print reads [FocusCycle.Print.UNCLEAR]:
   * about a third of a second, past the sharp run continuous autofocus's settling on a page gives. */
  const val UNCLEAR_FRAMES = 10
  /** How much the print's edge count over the page or guide must grow from where a finished cycle ended for the view
   * to count as changed: print coming into view, which gives a lock something to find. */
  const val VIEW_CHANGE = 2.0
  const val VIEW_CHANGE_EDGES = 100
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

/**
 * Main thread: the analysis frames' focus verdicts ([FocusVerdict]) since the lens last changed hands, for
 * [FocusCycle.Frame.print]. [FocusRules.VERDICT_FRAMES] sharp or defocused frames in a row make a verdict, and either
 * breaks the other's run; a smeared or unknown frame says nothing about focus.
 *
 * A sharp run also anchors the lens's reported focus distance: while each frame's lens stays within the depth of field
 * of where the print last read sharp, the print still reads sharp, whatever continuous autofocus reports, since it
 * flips between searching and focused on a still page while the lens wanders by tenths of a diopter. Edge widths at the
 * analysis resolution see only errors of about a diopter, which is where continuous autofocus's real misses lie; the
 * reported distance sees the wandering. A lens past the depth of field loses the anchor, and the edges judge again; so
 * does a defocused run, as the page's distance changing under a still lens.
 */
internal class PrintFocusRun {
  private var generation = Long.MIN_VALUE
  private var sharp = 0
  private var defocused = 0
  /** Diopters where the print last read sharp in a run; null without one. */
  private var anchor: Double? = null
  /** The latest frame's lens lay within [tolerance] of [anchor]. */
  private var anchored = false
  /** Frames in a row with the lens at rest and no sharp print. */
  private var unclear = 0
  private var tolerance = DEFAULT_TOLERANCE

  /**
   * One analysis frame's [reading] under focus [generation], with whether its lens was [lensMoving] and where it was,
   * [diopters], null when unreported; [tolerance] is the depth of field's, in diopters, null when unknown.
   */
  fun observe(generation: Long, reading: FocusReading?, lensMoving: Boolean, diopters: Double?, tolerance: Double?) {
    if (generation != this.generation) {
      this.generation = generation
      sharp = 0
      defocused = 0
      unclear = 0
      anchor = null
    }
    this.tolerance = tolerance ?: DEFAULT_TOLERANCE
    when (reading?.verdict) {
      FocusVerdict.SHARP -> {
        sharp++
        defocused = 0
        if (sharp >= FocusRules.VERDICT_FRAMES && diopters != null) anchor = diopters
      }
      FocusVerdict.DEFOCUSED -> {
        defocused++
        sharp = 0
        if (defocused >= FocusRules.VERDICT_FRAMES) anchor = null
      }
      FocusVerdict.UNKNOWN -> if (lensMoving) { sharp = 0; defocused = 0 }
      FocusVerdict.SMEARED, null -> Unit
    }
    anchored = anchoredAt(generation, diopters)
    if (anchor != null && diopters != null && !anchored) anchor = null
    unclear = when {
      lensMoving || reading?.verdict == FocusVerdict.SHARP || anchored -> 0
      reading?.verdict == FocusVerdict.SMEARED -> unclear
      else -> unclear + 1
    }
  }

  /** The verdict under focus [generation]: a run's, sharp while the lens holds the anchor, or unclear once the lens has
   * rested [FocusRules.UNCLEAR_FRAMES] frames without one. */
  fun print(generation: Long): FocusCycle.Print = when {
    generation != this.generation -> FocusCycle.Print.UNSURE
    defocused >= FocusRules.VERDICT_FRAMES -> FocusCycle.Print.DEFOCUSED
    sharp >= FocusRules.VERDICT_FRAMES || anchored -> FocusCycle.Print.SHARP
    unclear >= FocusRules.UNCLEAR_FRAMES -> FocusCycle.Print.UNCLEAR
    else -> FocusCycle.Print.UNSURE
  }

  /** Under focus [generation], a lens at [diopters] lies within the depth of field of where the print last read sharp
   * under it. */
  fun anchoredAt(generation: Long, diopters: Double?): Boolean {
    if (generation != this.generation) return false
    val at = anchor ?: return false
    return diopters != null && kotlin.math.abs(diopters - at) <= tolerance
  }

  private companion object {
    /** Without the lens's depth of field: the SM-G998U main camera's. */
    const val DEFAULT_TOLERANCE = .3
  }
}
