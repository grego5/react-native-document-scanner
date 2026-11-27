package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.FocusReading
import com.margelo.nitro.documentscanner.processing.FocusVerdict

/** One locking scan per steady scene. The document view supplies visual and sensor changes, and owns the target. */
internal class FocusCycle {
  enum class Lens { FREE, SCANNING, LOCKED }
  enum class Print { UNSURE, UNCLEAR, SHARP, DEFOCUSED }
  enum class PressFocus { WORKING, ACCEPTED, UNCONFIRMED }

  private var pending: String? = "initial steady hold"
  val needsScan: Boolean get() = pending != null
  var ended = false
    private set
  private var movingSinceNs: Long? = null
  private var moveCounted = false
  private var moved = false
  private var wasTurned = false
  private var lockGeneration: Long? = null
  private var wasSharp = false
  private var corrected = false

  fun reset() {
    pending = "initial steady hold"
    ended = false
    movingSinceNs = null
    moveCounted = false
    moved = false
    wasTurned = false
    lockGeneration = null
    wasSharp = false
    corrected = false
  }

  /** A deliberate movement can start and end between focus candidates. */
  fun motion(sensorNs: Long, quiet: Boolean) {
    if (quiet) {
      movingSinceNs = null
      moveCounted = false
      return
    }
    val since = movingSinceNs ?: sensorNs.also { movingSinceNs = it }
    if (!moveCounted && sensorNs - since >= FocusRules.MOVED_NS) {
      moveCounted = true
      moved = true
    }
  }

  /** True when the caller should discard any target sampled before this change. */
  fun observe(lens: Lens, generation: Long, print: Print, turned: Boolean, sceneChange: String?): Boolean {
    val turnStarted = turned && !wasTurned
    wasTurned = turned
    val reason = sceneChange ?: when {
      turnStarted -> "phone turned"
      moved -> "phone moved"
      else -> null
    }
    moved = false
    if (reason != null) {
      pending = reason
      ended = false
      wasSharp = false
      corrected = false
      return true
    }
    if (lockGeneration != generation) {
      lockGeneration = generation
      wasSharp = false
    }
    if (lens != Lens.LOCKED || needsScan) return false
    when (print) {
      Print.SHARP -> wasSharp = true
      Print.DEFOCUSED -> {
        if (!wasSharp || corrected) return false
        wasSharp = false
        corrected = true
        pending = "previously sharp print defocused"
        ended = false
        return true
      }
      else -> Unit
    }
    return false
  }

  /** Consume the event before CameraX receives its trigger. A busy lens leaves one request pending. */
  fun started(): String {
    val reason = checkNotNull(pending)
    pending = null
    ended = false
    wasSharp = false
    return reason
  }

  /** A failure waits for a scene event. Preserve an event that already arrived during the scan. */
  fun failed(why: String): String? {
    if (needsScan || ended) return null
    ended = true
    return why
  }

  fun pressFocus(lens: Lens, print: Print): PressFocus = when {
    needsScan || lens == Lens.SCANNING -> PressFocus.WORKING
    ended -> PressFocus.UNCONFIRMED
    lens == Lens.LOCKED && (print == Print.SHARP || print == Print.UNCLEAR) -> PressFocus.ACCEPTED
    else -> PressFocus.WORKING
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
 * [FocusCycle.pressFocus]. [FocusRules.VERDICT_FRAMES] sharp or defocused frames in a row make a verdict, and either
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
