package com.margelo.nitro.documentscanner

/**
 * Main thread, the text camera's `focusLock`: when the camera's own locking scan runs, judged by the print's edge width
 * ([BlurWatch.CUT]) rather than by the camera's focus report, which reads focused over blurred print at close range.
 *
 * Continuous autofocus is the first try: while the print is sharp its frames are read and no lock runs. A steady hold
 * on blurred print, past [AUTO_WAIT_NS], is the second: one lock. A lock is armed once per hold; it is armed again
 * only by the print turning sharp since the last lock, so the distance changed, by a deliberate move or turn of the
 * phone, or by a new region or tap ([reset]). So a hold nearer than the lens can focus locks once and stays.
 *
 * Under a lock the print tells which way it went wrong, since blur alone has no sign:
 * - sharp, then blurred: the phone moved off the lock's distance, nearer or farther; the lock is released, armed, and
 *   continuous autofocus refocuses from there.
 * - blurred from the start with the lens at its near limit: the print is nearer than the lens can focus. The lock stays,
 *   the best the lens can do there, so moving back brings the print through its focus.
 * - blurred from the start elsewhere: the lock's scan went wrong; it is released without another try.
 * A move too fast to pass through the lock's sharp distance shows on the motion sensors, which release the lock.
 * Times are sensor nanoseconds.
 */
internal class TextFocusCycle {
  enum class Lens { FREE, SCANNING, LOCKED }

  sealed interface Step {
    data object None : Step
    /** A new lock; the caller reports [started] once the camera takes it. */
    data class Lock(val why: String) : Step
    data class Release(val why: String) : Step
  }

  /**
   * One frame. [quiet]: a steady hand and view; [turned]: the phone turned; [lens]: who holds it; [edges]: the region's
   * edge width, null when unmeasured, as for blank paper or frames of a sweeping lens; [atNearLimit]: the lens sits at
   * the nearest distance it focuses.
   */
  class Frame(
    val sensorNs: Long, val quiet: Boolean, val turned: Boolean, val lens: Lens, val edges: Double?,
    val atNearLimit: Boolean = false,
  )

  /** Whether the hold may still lock. */
  var armed = true
    private set
  private var movingSinceNs: Long? = null
  private var moveCounted = false
  private var moved = false
  /** Since when a steady free lens has seen only blurred print; null otherwise. */
  private var blurredSinceNs: Long? = null
  /** The first frame of the current lock, null before it settles. */
  private var lockedAtNs: Long? = null
  /** The print was sharp under the current lock. */
  private var sharpUnderLock = false
  /** The current lock was judged blurred from the start at the near limit and stays. */
  private var keptAtLimit = false
  private var sharpRun = 0
  private var blurredRun = 0
  /** A release was asked for and the lens is not free yet. */
  private var releasing = false

  /** A new region or a tap: the hold starts over. */
  fun reset() {
    armed = true
    blurredSinceNs = null
    clearLock()
  }

  /** The camera took the [Step.Lock], or a capture's lock: it uses the hold's lock. Called before its trigger is sent,
   * so a trigger that fails at once finds it counted. */
  fun started() {
    armed = false
    releasing = false
    blurredSinceNs = null
    clearLock()
  }

  /** The camera reported the lock failed and released it: continuous autofocus follows, with no other try. */
  fun failed() = clearLock()

  /** Every analysed frame's steadiness: motion past [FocusRules.MOVED_NS] is a deliberate move, which [next] acts on. */
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
    if (!held) releasing = false
    val step = if (frame.turned || moved) {
      val why = if (frame.turned) "phone turned" else "phone moved"
      moved = false
      reset()
      if (held) Step.Release(why) else Step.None
    } else when (frame.lens) {
      Lens.SCANNING -> Step.None
      Lens.FREE -> free(frame)
      Lens.LOCKED -> locked(frame)
    }
    // A release takes the camera a moment; it is asked for once.
    if (step !is Step.Release) return step
    if (releasing) return Step.None
    releasing = true
    return step
  }

  private fun free(frame: Frame): Step {
    if (lockedAtNs != null) clearLock()
    val edges = frame.edges
    if (edges != null) {
      if (BlurWatch.sharp(edges)) {
        blurredSinceNs = null
        // Sharp print since the last lock: the distance changed, so a later blur may lock again.
        if (++sharpRun >= BlurWatch.LEAVE) armed = true
      } else {
        sharpRun = 0
        if (blurredSinceNs == null) blurredSinceNs = frame.sensorNs
      }
    }
    if (!frame.quiet) blurredSinceNs = null
    val since = blurredSinceNs ?: return Step.None
    if (!armed || frame.sensorNs - since < AUTO_WAIT_NS) return Step.None
    return Step.Lock("print blurred under continuous autofocus")
  }

  private fun locked(frame: Frame): Step {
    val since = lockedAtNs ?: frame.sensorNs.also { lockedAtNs = it }
    // A shake blurs the print whatever the lens; a move past one is the motion sensors' to release.
    if (!frame.quiet) return Step.None
    val edges = frame.edges ?: return Step.None
    if (BlurWatch.sharp(edges)) {
      blurredRun = 0
      if (++sharpRun >= BlurWatch.LEAVE) sharpUnderLock = true
      return Step.None
    }
    sharpRun = 0
    if (++blurredRun < BlurWatch.ENTER) return Step.None
    if (sharpUnderLock) {
      // The distance changed since the lock was sharp, so the hold may lock again.
      armed = true
      return Step.Release("print blurred off the lock's distance")
    }
    if (keptAtLimit || frame.sensorNs - since < JUDGE_NS) return Step.None
    if (frame.atNearLimit) {
      keptAtLimit = true
      return Step.None
    }
    return Step.Release("lock left the print blurred")
  }

  private fun clearLock() {
    lockedAtNs = null
    sharpUnderLock = false
    keptAtLimit = false
    sharpRun = 0
    blurredRun = 0
  }

  companion object {
    /** How long a steady hold sees blurred print under continuous autofocus before locking: time for it to settle. */
    const val AUTO_WAIT_NS = 500_000_000L
    /** How long a lock's frames may take to show its focus before it is judged blurred from the start. */
    const val JUDGE_NS = 300_000_000L
    /** A lens within this many diopters of its nearest focus is at its near limit: the depth of field there. */
    const val NEAR_LIMIT_DIOPTERS = .35f
  }
}
