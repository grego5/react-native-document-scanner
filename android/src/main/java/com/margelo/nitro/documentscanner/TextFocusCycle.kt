package com.margelo.nitro.documentscanner

/**
 * Main thread, `focusLock`: when the text camera asks the camera for its own locking scan. The first settled lock
 * permits reading, since blurry text misreads, and the text map decides when to read. Continuous autofocus follows
 * the text while the phone moves, but at close range it reports focus it has not reached, so its report is never
 * trusted: on a steady hold, its settling starts a lock that confirms it, as its reporting no focus does, and a dark
 * scene, where continuous autofocus is known to fail, locks without waiting for either. Continuous autofocus that never
 * settles locks once the hold has lasted [SETTLE_WAIT_NS]. A lock is a sweep across the lens's range, which finds the
 * sharp distance itself; a lock whose first reading found no text runs once more. A hold gets [MAX_LOCKS] locks, so a view
 * without text stops scanning. A turn, a deliberate move, the text changing size, which a change of distance does
 * without turning or shaking the phone, or a [reset] for new text ends the lock and the count, and
 * continuous autofocus follows again. Times are sensor nanoseconds.
 */
internal class TextFocusCycle {
  enum class Lens { FREE, SCANNING, LOCKED }

  sealed interface Step {
    data object None : Step
    data class Lock(val why: String) : Step
    data class Release(val why: String) : Step
  }

  /** [quiet]: a steady hand; [turned]: the phone turned to other text; [settled] and [unfocused]: continuous autofocus
   * reports focus, or no focus; [lockRead]: under [Lens.LOCKED], whether this lock's reading found text, null while it
   * still reads; [dark]: a scene too dark for continuous autofocus; [rescaled]: the text under the lock changed size,
   * as moving the phone nearer or farther does, past the lens's depth of focus. */
  class Frame(val sensorNs: Long, val quiet: Boolean, val turned: Boolean, val settled: Boolean, val unfocused: Boolean,
    val lockRead: Boolean?, val lens: Lens, val dark: Boolean = false, val rescaled: Boolean = false)

  private var locks = 0
  private var waitFromNs: Long? = null
  private var movingSinceNs: Long? = null
  private var moveCounted = false

  fun reset() {
    locks = 0
    waitFromNs = null
    movingSinceNs = null
    moveCounted = false
  }

  fun next(frame: Frame): Step {
    val held = frame.lens != Lens.FREE
    if (frame.turned) {
      reset()
      return if (held) Step.Release("phone turned") else Step.None
    }
    if (frame.rescaled && held) {
      reset()
      return Step.Release("distance changed")
    }
    if (!frame.quiet) {
      waitFromNs = null
      val since = movingSinceNs ?: frame.sensorNs.also { movingSinceNs = it }
      // A hand's tremor breaks the quiet for a moment; a move this long carries the phone to another distance.
      if (moveCounted || frame.sensorNs - since < MOVED_NS) return Step.None
      reset()
      movingSinceNs = since
      moveCounted = true
      return if (held) Step.Release("phone moved") else Step.None
    }
    movingSinceNs = null
    moveCounted = false
    if (frame.lens == Lens.SCANNING || locks >= MAX_LOCKS) {
      waitFromNs = null
      return Step.None
    }
    val why = if (frame.lens == Lens.FREE) when {
      frame.dark -> "a dark scene"
      frame.unfocused -> "continuous autofocus reported no focus"
      frame.settled -> "confirming continuous autofocus's focus"
      else -> {
        val from = waitFromNs ?: frame.sensorNs.also { waitFromNs = it }
        if (frame.sensorNs - from < SETTLE_WAIT_NS) return Step.None
        "continuous autofocus did not settle"
      }
    } else if (frame.lockRead == false) "nothing read under the lock" else return Step.None
    locks++
    waitFromNs = null
    return Step.Lock(why)
  }

  companion object {
    /** How long continuous autofocus may search on a steady hold before a lock finds focus for it. */
    const val SETTLE_WAIT_NS = 600_000_000L
    const val MAX_LOCKS = 2
    /** Motion this long moves the phone to another distance. The hand's quiet is judged over 300 ms, so one tremor
     * spike already reads as motion for that long. */
    const val MOVED_NS = 600_000_000L
  }
}
