package com.margelo.nitro.documentscanner

/**
 * Main thread: the text camera's own focus for `focusLock`. Continuous autofocus at reading distance hunts as the hand
 * drifts and pulls the lens off thin text, so the lens is held with autofocus off instead: it opens at the distance this
 * camera last read text at, or before its first lock at [OPENING_FRACTION] of its nearest focus, a close-up reading
 * distance, and on a steady hold [LensSearch] scores the region's text at nearby distances and holds the sharpest.
 * A search compares the region's fine detail across lens positions, which shows the same text. A hold judges the text by
 * its focus, the ratio of fine to coarse detail, which other text in the region leaves about the same while defocus
 * lowers it: the hold lasts until that turns soft while the hand is steady, as when the text comes closer, which
 * searches again from where the lens is on the next steady frame. A turn of the phone brings other text, usually at the
 * same distance at close range, so a lock is checked rather than searched: the first steady frames at the held distance
 * keep it unless their focus is soft against the focus before the turn. Frames with too little coarse detail to judge,
 * as over blank paper, neither trigger a search nor fail a check. A search that cannot settle hands focus to the
 * camera's autofocus until the phone turns; one the hand does not
 * stay steady through ends after [SEARCH_TIMEOUT_NS] and starts again on the next steady hold. A search that finds no
 * peak, as over blank paper, holds where it started without counting as a lock, and searches again once detail
 * appears.
 */
internal class TextLensFocus(private val host: Host) {
  interface Host {
    /** Puts the lens at [diopters] with autofocus off; false when the request could not be made. */
    fun moveLens(diopters: Float): Boolean
    fun lockExposure(locked: Boolean)
    /** Returns focus to the camera's continuous autofocus on the region. */
    fun cameraAutofocus()
    fun remember(diopters: Float)
    /** Whether [log] records anything; messages are only built when it does. */
    val logging: Boolean
    fun log(message: String)
  }

  /**
   * One analyzed frame: the region's fine detail ([sharpness]), its [focusRatio] of fine to coarse detail and its
   * coarse [detail], whether the motion sensors show the hand [quiet] and the phone [turned], and the frame's own capture
   * result: autofocus off ([manual]), the lens's reading, settled exposure, and the lens distance its request asked for
   * ([requestedDiopters]); [lensMoving] is only evidence when the result reported the lens state ([lensStateReported]).
   */
  class Frame(
    val sensorNs: Long, val sharpness: Double?, val quiet: Boolean, val turned: Boolean,
    val manual: Boolean, val lensDiopters: Float?, val lensMoving: Boolean, val exposureSettled: Boolean,
    val focusRatio: Double? = null, val detail: Double? = null, val quarterFocus: Double? = null,
    val requestedDiopters: Float? = null, val lensStateReported: Boolean = true,
  )

  /** The held text's averaged [focus] ratio, null when its frames had none, and coarse [detail]. */
  private class Level(val focus: Double?, val detail: Double, val quarter: Double? = null)

  /** Logging only: [quarter], a frame's or level's fine to quarter-resolution ratio, against [reference]'s. */
  private fun quarterAgainst(quarter: Double?, reference: Level): String {
    val held = reference.quarter
    return if (quarter == null || held == null || held <= 0) "n/a" else "%.2f".format(quarter / held)
  }

  enum class Stage { HELD, SEARCHING, CAMERA, TAPPED }

  var stage = Stage.HELD
    private set
  /** The distance last requested with autofocus off. */
  var heldDiopters = 0f
    private set
  /** Whether the lens holds where a search found the text sharpest; false before the first search and after a turn,
   * a tap or a fallback, until the next search settles. */
  var locked = false
    private set
  private var nearest = 0f
  private var needsSearch = true
  private val baseline = ArrayList<Frame>()
  private var level: Level? = null
  private var softFrames = 0
  private var search: LensSearch? = null
  private val scores = ArrayList<Double>()
  /** Logging only: the scored frames' quarter-resolution ratios at the current position. */
  private val quarters = ArrayList<Double>()
  private var idleFrames = 0
  private var positions = 0
  private var searchStartNs = 0L
  /** The lowest and highest averaged scores of the search, which tell a peak from a flat field. */
  private var searchLow = 0.0
  private var searchHigh = 0.0
  private var risingFrames = 0
  /** The held level before a turn, while the frames after it are checked against it; null otherwise. */
  private var checkReference: Level? = null
  private val checkFrames = ArrayList<Frame>()
  private var arrival = LensArrival()
  private val trail = StringBuilder()
  /** Why the next search runs, and the sensor time it began waiting for a steady hand: -1 until the next frame. */
  private var searchReason = "opening"
  private var waitingSinceNs = -1L
  private var searchFrames = 0
  private var lastFrameNs = 0L
  // Each held stretch's tuning record since its last report: frames, steady ones, and the score against the held level.
  private var heldFrames = 0
  private var heldQuiet = 0
  private var quarterLow = Double.POSITIVE_INFINITY
  private var quarterHigh = 0.0
  private var heldLow = Double.MAX_VALUE
  private var heldHigh = 0.0
  private var heldUnjudged = 0

  /** Opens the lens for a newly bound camera whose nearest focus is [nearest] diopters, at [remembered] if known. */
  fun start(nearest: Float, remembered: Float?) {
    this.nearest = nearest
    val opening = (remembered ?: nearest * OPENING_FRACTION).coerceIn(0f, nearest)
    resetHold(needsSearch = true)
    searchReason = if (remembered == null) "opening" else "opening at the last lock"
    if (!hold(opening)) return fallBack("the lens could not be set")
    log { "text lens opened at %.2f diopters%s".format(opening, if (remembered == null) " for close-up text" else " from the last lock") }
  }

  fun observe(frame: Frame) {
    if (stage == Stage.TAPPED) return
    lastFrameNs = frame.sensorNs
    if (needsSearch && waitingSinceNs < 0) waitingSinceNs = frame.sensorNs
    if (stage == Stage.SEARCHING) searchFrames++
    if (stage == Stage.HELD && host.logging) recordHeld(frame)
    if (frame.turned) return turned(frame)
    if (stage == Stage.SEARCHING && frame.sensorNs - searchStartNs > SEARCH_TIMEOUT_NS) {
      return interrupt(frame, "the hand did not stay steady through the search")
    }
    if (!frame.quiet) return
    when (stage) {
      Stage.CAMERA -> if (needsSearch) startSearch(frame.lensDiopters ?: heldDiopters, frame.sensorNs)
      Stage.HELD -> held(frame)
      Stage.SEARCHING -> advance(frame)
      Stage.TAPPED -> Unit
    }
  }

  private fun interrupt(frame: Frame, why: String) = restart(frame.lensDiopters?.takeIf { frame.manual }, why)

  /** A turn: a lock with a known level is checked on the next steady frames; anything else searches. */
  private fun turned(frame: Frame) {
    val reference = checkReference ?: level
    if (stage != Stage.HELD || !locked || reference == null) return interrupt(frame, "phone turned")
    if (checkReference == null) log { "text lens: phone turned; checking the held distance on the next steady hold" }
    checkReference = reference
    checkFrames.clear()
    baseline.clear()
    level = null
    softFrames = 0
    risingFrames = 0
    resetHeldRecord()
  }

  /** One steady frame of a turn's check, against the held [reference] level before it. */
  private fun check(frame: Frame, reference: Level) {
    if (!frame.manual || frame.lensMoving || frame.detail == null) return
    checkFrames += frame
    if (checkFrames.size < CHECK_SCORES) return
    val next = levelOf(checkFrames)
    checkReference = null
    checkFrames.clear()
    val ratio = focusAgainst(next, reference)
    if (ratio == null || ratio >= SOFT) {
      level = if (ratio == null) reference else next
      log {
        "text lens: kept %.2f diopters after a turn, %s, quarter %s; observed focus %.3f, detail %.0f".format(heldDiopters,
          ratio?.let { "focus %.2f of the held".format(it) } ?: "too little detail to judge",
          quarterAgainst(next.quarter, reference), next.focus ?: Double.NaN, next.detail)
      }
      return
    }
    searchReason = "soft after a turn, focus %.2f of the held, quarter %s".format(ratio, quarterAgainst(next.quarter, reference))
    startSearch(frame.lensDiopters ?: heldDiopters, frame.sensorNs)
  }

  /** [frames]' averaged focus, over those that have one, and coarse detail. */
  private fun levelOf(frames: List<Frame>): Level {
    val ratios = frames.mapNotNull { it.focusRatio }
    val quarters = frames.mapNotNull { it.quarterFocus }
    return Level(if (ratios.isEmpty()) null else ratios.average(), frames.map { it.detail ?: 0.0 }.average(),
      if (quarters.isEmpty()) null else quarters.average())
  }

  /** [current]'s focus as a fraction of [reference]'s, or null when either has too little detail to judge it. */
  private fun focusAgainst(current: Level, reference: Level): Double? {
    val focus = current.focus ?: return null
    val held = reference.focus ?: return null
    if (held <= 0 || current.detail < reference.detail * MIN_DETAIL) return null
    return focus / held
  }

  /** The region now holds other text: the search's scores and the held text's sharpness no longer apply, so the next
   * steady frame searches from where the lens is, [lensDiopters]. A tap's hold keeps the lens until it resumes. */
  fun regionChanged(lensDiopters: Float?) {
    if (stage != Stage.TAPPED) restart(lensDiopters, "the region changed")
  }

  /** Ends a search with the lens held at [lensDiopters], or where it was asked to be, and searches again on the next
   * steady frame. */
  private fun restart(lensDiopters: Float?, why: String) {
    if (stage == Stage.SEARCHING) {
      endSearch()
      hold(lensDiopters ?: heldDiopters)
    }
    if (!needsSearch) log { "text lens: $why; search on the next steady hold" }
    resetHold(needsSearch = true)
    searchReason = why
  }

  /** A tap's own autofocus takes the lens; a search in progress ends. */
  fun suspend() {
    if (stage == Stage.SEARCHING) endSearch()
    stage = Stage.TAPPED
  }

  /** The tap's hold ended: the lens stays where the tap put it, [lensDiopters], until the next steady hold searches. */
  fun resume(lensDiopters: Float?) {
    if (stage != Stage.TAPPED) return
    resetHold(needsSearch = true)
    searchReason = "a tap's hold ended"
    if (!hold(lensDiopters ?: heldDiopters)) fallBack("the lens could not be set")
  }

  private fun held(frame: Frame) {
    checkReference?.let { return check(frame, it) }
    if (needsSearch) return startSearch(frame.lensDiopters?.takeIf { frame.manual } ?: heldDiopters, frame.sensorNs)
    if (!frame.manual || frame.lensMoving) return
    val detail = frame.detail ?: return
    val reference = level
    if (reference == null) {
      baseline += frame
      if (baseline.size >= BASELINE_SCORES) {
        val next = levelOf(baseline)
        level = next
        log { "text lens: held text focus %.3f, quarter %.4g, detail %.0f".format(next.focus ?: Double.NaN, next.quarter ?: Double.NaN, next.detail) }
      }
      return
    }
    // Other text in the region leaves the focus ratio about the same; defocus lowers it.
    val ratio = focusAgainst(Level(frame.focusRatio, detail), reference)
    softFrames = if (ratio != null && ratio < SOFT) softFrames + 1 else 0
    // Detail appearing over a hold without a lock, as over blank paper, searches; over a lock it is new text to judge
    // by its own level.
    val rising = detail > reference.detail * RISE
    risingFrames = if (rising) risingFrames + 1 else 0
    if (softFrames >= SOFT_FRAMES || risingFrames >= SOFT_FRAMES && !locked) {
      searchReason = if (softFrames >= SOFT_FRAMES) "held text turned soft, focus %.2f of the held, quarter %s".format(ratio,
          quarterAgainst(frame.quarterFocus, reference))
        else "detail appeared, %.1fx the held".format(detail / reference.detail.coerceAtLeast(1e-9))
      startSearch(frame.lensDiopters ?: heldDiopters, frame.sensorNs)
    } else if (risingFrames >= SOFT_FRAMES) {
      log { "text lens: more detail, %.1fx the held; taking its level".format(detail / reference.detail.coerceAtLeast(1e-9)) }
      baseline.clear()
      level = null
      risingFrames = 0
      softFrames = 0
    }
  }

  /** Logging only: how a held stretch's focus and hand compare with the triggers, for tuning [SOFT], [MIN_DETAIL] and the
   * steady-hand limits; reported every [HELD_REPORT_FRAMES] frames. */
  private fun recordHeld(frame: Frame) {
    val reference = level ?: return
    heldFrames++
    if (frame.quiet) heldQuiet++
    val detail = frame.detail
    if (detail != null && frame.manual && !frame.lensMoving) {
      val frameQuarter = frame.quarterFocus
      val heldQuarter = reference.quarter
      if (frameQuarter != null && heldQuarter != null && heldQuarter > 0 && detail >= reference.detail * MIN_DETAIL) {
        quarterLow = minOf(quarterLow, frameQuarter / heldQuarter)
        quarterHigh = maxOf(quarterHigh, frameQuarter / heldQuarter)
      }
      focusAgainst(Level(frame.focusRatio, detail), reference)?.let {
        heldLow = minOf(heldLow, it)
        heldHigh = maxOf(heldHigh, it)
      } ?: heldUnjudged++
    }
    if (heldFrames < HELD_REPORT_FRAMES) return
    val range = if (heldLow == Double.MAX_VALUE) "n/a" else "%.2f-%.2f".format(heldLow, heldHigh)
    val quarter = if (quarterLow.isFinite()) "%.2f-%.2f".format(quarterLow, quarterHigh) else "n/a"
    host.log("text lens held%s at %.2f diopters: focus %s of %.3f, quarter %s, %d frames too bare to judge, steady %d%%".format(
      if (locked) "" else " without a lock", heldDiopters, range, reference.focus ?: Double.NaN, quarter, heldUnjudged,
      heldQuiet * 100 / heldFrames))
    resetHeldRecord()
  }

  private fun resetHeldRecord() {
    heldUnjudged = 0
    heldFrames = 0
    heldQuiet = 0
    quarterLow = Double.POSITIVE_INFINITY
    quarterHigh = 0.0
    heldLow = Double.MAX_VALUE
    heldHigh = 0.0
  }

  private inline fun log(message: () -> String) {
    if (host.logging) host.log(message())
  }

  private fun startSearch(from: Float, nowNs: Long) {
    val next = LensSearch(from, nearest, refinePeak = true)
    if (!hold(next.target)) return fallBack("the lens could not be set")
    host.lockExposure(true)
    search = next
    stage = Stage.SEARCHING
    resetHold(needsSearch = false)
    val waitedMs = if (waitingSinceNs >= 0) (nowNs - waitingSinceNs) / 1_000_000 else 0
    waitingSinceNs = -1L
    searchStartNs = nowNs
    searchFrames = 0
    searchLow = Double.MAX_VALUE
    searchHigh = 0.0
    scores.clear()
    quarters.clear()
    idleFrames = 0
    positions = 0
    arrival = LensArrival()
    trail.setLength(0)
    log { "text lens search from %.2f diopters: %s, after %d ms waiting for a steady hold".format(next.target, searchReason, waitedMs) }
  }

  private fun advance(frame: Frame) {
    val active = search ?: return
    if (++idleFrames > IDLE_FRAMES) return fallBack("no settled score")
    if (!frame.manual || frame.lensMoving) return
    val target = active.target
    val reading = frame.lensDiopters
    if (!arrival.arrived(target, frame.requestedDiopters, reading, frame.lensStateReported)) return
    if (active.done) return finish(reading)
    val score = frame.sharpness ?: return
    if (!frame.exposureSettled) return
    scores += score
    frame.quarterFocus?.let(quarters::add)
    if (scores.size < SEARCH_SCORES) return
    val average = scores.sum() / scores.size
    scores.clear()
    // Logging only: the same text's quarter-resolution ratio at each position shows how far defocus moves it.
    if (host.logging) trail.append(" %.2f:%.0f%s".format(target, average, if (quarters.isEmpty()) "" else "/q%.3g".format(quarters.average())))
    quarters.clear()
    searchLow = minOf(searchLow, average)
    searchHigh = maxOf(searchHigh, average)
    active.observe(average)
    idleFrames = 0
    positions++
    when {
      active.failed -> fallBack("no peak after $positions positions")
      active.target == heldDiopters -> if (active.done) finish(reading)
      else -> {
        arrival.moved(reading)
        if (!hold(active.target)) fallBack("the lens could not be set")
      }
    }
  }

  /** Holds the search's sharpest position, and counts it as a lock, remembered, only when the scores had a peak. */
  private fun finish(reading: Float?) {
    endSearch()
    val peaked = positions > 1 && searchHigh > 0 && searchHigh >= searchLow * PEAK_RATIO
    locked = peaked
    if (peaked) host.remember(heldDiopters)
    log {
      "text lens %s at %.2f diopters (reads %.2f) after %d positions, %d ms, %d frames, peak %.2fx:%s".format(
        if (peaked) "settled" else "found no peak; holding", heldDiopters, reading ?: Float.NaN, positions,
        (lastFrameNs - searchStartNs) / 1_000_000, searchFrames, if (searchLow > 0) searchHigh / searchLow else 0.0, trail)
    }
  }

  private fun fallBack(why: String) {
    if (search != null) endSearch()
    stage = Stage.CAMERA
    resetHold(needsSearch = false)
    log { "text lens: $why after %d ms; camera autofocus until the phone turns".format((lastFrameNs - searchStartNs) / 1_000_000) }
    host.cameraAutofocus()
  }

  /** Ends the search with the lens held where it is, exposure unlocked. */
  private fun endSearch() {
    search = null
    stage = Stage.HELD
    host.lockExposure(false)
  }

  private fun hold(diopters: Float): Boolean {
    if (!host.moveLens(diopters)) return false
    heldDiopters = diopters
    if (stage != Stage.SEARCHING) stage = Stage.HELD
    return true
  }

  private fun resetHold(needsSearch: Boolean) {
    this.needsSearch = needsSearch
    locked = false
    baseline.clear()
    level = null
    softFrames = 0
    risingFrames = 0
    checkReference = null
    checkFrames.clear()
    if (needsSearch) waitingSinceNs = -1L
    resetHeldRecord()
  }

  companion object {
    /** The opening distance before a camera's first lock, as a fraction of its nearest focus: 7 diopters, about 14 cm,
     * on the SM-G998U, whose near end reads 10 diopters; the middle of a 13–15 cm close-up reading distance. */
    const val OPENING_FRACTION = .7f
    const val SEARCH_SCORES = 2
    /** Steady frames a search waits for a settled score before the camera's autofocus takes over. */
    const val IDLE_FRAMES = 20
    /** Frames averaged into the held text's level after a lock. */
    const val BASELINE_SCORES = 3
    /** A held focus ratio this far under the level's, for [SOFT_FRAMES] steady frames, searches again; a turn's check
     * keeps the lock at or above it. */
    const val SOFT = .7
    /** A frame needs this fraction of the level's coarse detail for its focus to be judged: fainter text is judged,
     * while a region nearly bare of it, as blank paper, scores mostly sensor noise, whose ratio says nothing of focus. */
    const val MIN_DETAIL = .05
    /** Coarse detail this far over the level's, for [SOFT_FRAMES] steady frames, searches a hold without a lock and
     * takes a new level for a lock. */
    const val RISE = 2.5
    const val SOFT_FRAMES = 8
    /** Steady scores at the held distance a turn's check averages. */
    const val CHECK_SCORES = 3
    /** Frames of a held stretch between its tuning reports, about 3 s. */
    const val HELD_REPORT_FRAMES = 90
    /** A search's best score must beat its worst by this factor to count as a lock; less is a flat field. */
    const val PEAK_RATIO = 1.15
    /** A search that has not ended this long after it started ends, so exposure is not left locked while the hand moves. */
    const val SEARCH_TIMEOUT_NS = 4_000_000_000L
  }
}
