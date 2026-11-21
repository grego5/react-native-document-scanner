package com.margelo.nitro.documentscanner

/**
 * Main thread: judges the scanner's page locks by the print's own sharpness at the focus aim, since a lock's scan can
 * report success on a false contrast peak. Scores come from counted frames only: steady, exposure and white balance
 * converged, autofocus not scanning. [best] is the sharpest median of [FRAMES] consecutive scores this page reached,
 * which continuous autofocus usually passes through even when it settles soft. A settled lock's verdict is the median of
 * its next [FRAMES] scores: below [MIN_RATIO] of [best] it missed; with no [best] yet it is accepted and becomes it.
 */
internal class LockSharpnessJudge {
  private val recent = ArrayDeque<Double>()
  private val lockScores = ArrayList<Double>(FRAMES)
  var best: Double? = null
    private set
  /** A settled lock waits for its verdict. */
  var pending = false
    private set
  /** The last verdict's lock score, for the log. */
  var lastLockScore = 0.0
    private set

  /** Forgets the page's scores: another page, another patch of print, or another cycle. */
  fun reset() {
    recent.clear()
    lockScores.clear()
    best = null
    pending = false
  }

  /** A lock settled successfully; its verdict waits for [FRAMES] counted frames after it. */
  fun lockSettled() {
    pending = true
    lockScores.clear()
  }

  /** The lock ended before its verdict. */
  fun cancel() {
    pending = false
    lockScores.clear()
  }

  /** A counted frame's [score]; true when it completes a lock's acceptance, false a miss, null no verdict. */
  fun observe(score: Double): Boolean? {
    if (pending) {
      lockScores += score
      if (lockScores.size < FRAMES) return null
      val lock = median(lockScores)
      pending = false
      lockScores.clear()
      lastLockScore = lock
      val reference = best
      if (reference != null && lock < reference * MIN_RATIO) return false
      best = maxOf(reference ?: lock, lock)
      recent.clear()
      return true
    }
    recent.addLast(score)
    if (recent.size > FRAMES) recent.removeFirst()
    if (recent.size == FRAMES) best = median(recent).let { maxOf(best ?: it, it) }
    return null
  }

  private fun median(scores: Collection<Double>) = scores.sorted()[scores.size / 2]

  companion object {
    const val FRAMES = 3
    /** A starting setting, to be tuned from the logged scores of sharp and blurred locks. */
    const val MIN_RATIO = .7
  }
}
