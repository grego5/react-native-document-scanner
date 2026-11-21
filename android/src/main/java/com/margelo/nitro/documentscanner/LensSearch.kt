package com.margelo.nitro.documentscanner

/**
 * Main thread: the scanner's own focus search with autofocus off, scoring the print at each lens distance. It scores
 * [start] and one [STEP] either side first, so a lens already at the peak, as at the distance that last worked, settles
 * after three positions; otherwise it steps toward the sharper side while the score rises, and ends at the sharpest
 * position scored. Contrast autofocus on this hardware reports focus on a false peak over a mostly blank page and parks
 * the lens at its near end on entering single-shot, while the print's own score at the page's print spot is what the
 * scanner judges a lock by anyway.
 *
 * Distances are diopters in the camera's own units, from 0 (infinity) to [nearest]. [target] is where the lens should be
 * scored next; each [observe] takes one settled score there. A position leads only by beating the leader by [MARGIN], so
 * a flat score, as over blank paper, settles where the lens started instead of wandering on noise. [peak] is set once
 * the search ends, [failed] when it ran past [MAX_POSITIONS] without one.
 */
internal class LensSearch(start: Float, private val nearest: Float) {
  private val visited = HashSet<Float>()
  private val home: Float
  private val opening: ArrayDeque<Float>
  private var leader = 0f
  private var leaderScore = 0.0
  private var direction = 0f
  private var misses = 0
  var target: Float
    private set
  var peak: Float? = null
    private set
  var failed = false
    private set
  val done: Boolean get() = peak != null || failed

  init {
    home = clamp(start)
    opening = ArrayDeque(listOf(home, clamp(home + STEP), clamp(home - STEP)).distinct())
    target = opening.removeFirst()
  }

  /** The settled [score] at [target]; moves [target] to the next position or ends the search. */
  fun observe(score: Double) {
    if (done) return
    val first = visited.isEmpty()
    visited += target
    val leads = first || score > leaderScore * (1 + MARGIN)
    if (leads) {
      leader = target
      leaderScore = score
    }
    if (direction == 0f) {
      if (opening.isNotEmpty()) {
        target = opening.removeFirst()
        return
      }
      if (leader == home) return finish(home)
      direction = if (leader > home) STEP else -STEP
      return step(leader)
    }
    if (leads) misses = 0 else misses++
    if (score < leaderScore * DROP || misses >= 2) return finish(leader)
    step(target)
  }

  private fun step(from: Float) {
    val next = clamp(from + direction)
    if (next == from || next in visited) return finish(leader)
    if (visited.size >= MAX_POSITIONS) {
      failed = true
      return
    }
    target = next
  }

  private fun finish(at: Float) {
    peak = at
    target = at
  }

  private fun clamp(diopters: Float) = (Math.round(diopters.coerceIn(0f, nearest) * 100) / 100f)

  companion object {
    /** Lens distance between scored positions, in diopters; the SM-G998U's sharp range at 13–15 cm spans about this. */
    const val STEP = .25f
    /** A position must score this fraction above the leader to lead, past frame-to-frame noise. */
    const val MARGIN = .04
    /** A score this far below the best seen ends the climb at once. */
    const val DROP = .85
    /** Positions scored before the search gives up for the camera's own autofocus: 3 diopters of climb. */
    const val MAX_POSITIONS = 15
  }
}
