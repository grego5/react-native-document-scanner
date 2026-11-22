package com.margelo.nitro.documentscanner

import kotlin.math.abs

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
internal class LensSearch(start: Float, private val nearest: Float, private val refinePeak: Boolean = false) {
  private val visited = HashMap<Float, Double>()
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
    visited[target] = score
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
    val refined = if (refinePeak) interpolatedPeak(at) else at
    peak = refined
    target = refined
  }

  /**
   * The top of the parabola through the scores at [at] and one [STEP] either side, when both were scored and the scores
   * bend down: the sharpest distance usually lies between scored positions, and at close range the sharp zone is thinner
   * than a step. A neighbour within [MARGIN] of [at] did not lead, yet still pulls the estimate toward it; the estimate
   * stays within half a step, nearer [at] than either neighbour.
   */
  private fun interpolatedPeak(at: Float): Float {
    // Keys are clamped values, so the neighbours are looked up as the search made them.
    val left = visited[clamp(at - STEP)]?.takeIf { clamp(at - STEP) != at } ?: return at
    val center = visited[at] ?: return at
    val right = visited[clamp(at + STEP)]?.takeIf { clamp(at + STEP) != at } ?: return at
    val curvature = left - 2 * center + right
    if (curvature >= 0 || !curvature.isFinite()) return at
    val offset = (STEP * (left - right) / (2 * curvature)).coerceIn(-STEP / 2.0, STEP / 2.0)
    return clamp(at + offset.toFloat())
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

/**
 * Main thread: whether a search's frames show the lens at its target and still, from each frame's capture request and
 * result; the caller has already dropped frames whose LENS_STATE says MOVING. Each kind of evidence the camera omits
 * is replaced by a stricter one, not assumed:
 * - The request's LENS_FOCUS_DISTANCE must be the target, which rejects frames queued from the previous move, whose
 *   lens can read within [ARRIVED_DIOPTERS] of the next position. A frame without it counts, as nothing says otherwise.
 * - The lens's reading must be within [ARRIVED_DIOPTERS] of the target, or, on a lens whose reading never matches its
 *   request, changed from where the move began and held for a frame.
 * - Without LENS_STATE, a reading near the target may still be travelling, so it must also repeat on the next frame.
 *   With neither LENS_STATE nor a reading, nothing shows the lens stopped, so [UNCONFIRMED_FRAMES] frames under the
 *   move must pass first.
 * The search's idle bound still ends a search whose frames never arrive.
 */
internal class LensArrival(private var moveReading: Float? = null) {
  private var target = Float.NaN
  private var frames = 0
  private var previous: Float? = null

  /** A move away from the current target was requested while the lens read [reading]. */
  fun moved(reading: Float?) {
    moveReading = reading
  }

  /** One frame with autofocus off and the lens not reported moving: [requested] by its request, [reading] by its
   * result, and whether the result reported LENS_STATE at all. */
  fun arrived(target: Float, requested: Float?, reading: Float?, lensStateReported: Boolean): Boolean {
    if (requested != null && abs(requested - target) > REQUEST_DIOPTERS) return false
    if (target != this.target) {
      this.target = target
      frames = 0
      previous = null
    }
    frames++
    val prior = previous
    previous = reading
    if (reading == null) return lensStateReported || frames > UNCONFIRMED_FRAMES
    val there = abs(reading - target) <= ARRIVED_DIOPTERS || reading != moveReading && reading == prior
    return there && (lensStateReported || reading == prior)
  }

  companion object {
    /** The lens counts as at a requested distance when it reads within this of it. */
    const val ARRIVED_DIOPTERS = .08f
    /** Requests carry the exact target, so this only absorbs float rounding. */
    private const val REQUEST_DIOPTERS = .005f
    /** Frames under a move before a lens that reports neither its state nor its distance counts as stopped. A starting
     * setting: no tested phone omits both, so none has measured it. */
    const val UNCONFIRMED_FRAMES = 3
  }
}
