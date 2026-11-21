package com.margelo.nitro.documentscanner

/** What the scanner's focus cycle has for a waiting press; see [ScannerPress]. */
internal enum class PressFocus { WORKING, START_FINAL_SCAN, ACCEPTED, EXHAUSTED }

/**
 * Main thread: the focus budget of one shutter press while the scanner's focus cycle owns focus (`focusLock` with live
 * corners). The cycle keeps every focus attempt for the press: its page or centre locks, their retry, the torch step, and
 * at most one final scan by the camera's own autofocus, started when the cycle gives up or with [FINAL_SCAN_RESERVE_MS]
 * of the budget left, whichever comes first, so a cycle still waiting on frames that never come cannot hold the press.
 * The press then resolves on a lock the cycle accepted, or fails once the budget of [BUDGET_MS] from the press runs out
 * or the final scan fails. The budget runs on uptime: the view wakes the press at [finalScanAtMs] and [deadlineMs]
 * itself, and a cycle that restarts on a new steady hold does not refill it.
 */
internal class ScannerPress(startedMs: Long) {
  val deadlineMs = startedMs + BUDGET_MS
  val finalScanAtMs = deadlineMs - FINAL_SCAN_RESERVE_MS
  private var finalScanStarted = false
  private var exhausted = false
  private var acceptedSinceMs: Long? = null

  /**
   * The press's state at [nowMs]. [lockAccepted]: a lock the cycle accepted, a tap's, or the final scan's holds the lens.
   * [cycleGaveUp]: the cycle has no step left. [finalScan]: the final scan's state once started. [lensFree]: nothing
   * keeps the final scan from starting now, as a tap's lock or a shot being taken would.
   */
  fun next(nowMs: Long, lockAccepted: Boolean, cycleGaveUp: Boolean, finalScan: OwnScan, lensFree: Boolean): PressFocus {
    if (exhausted) return PressFocus.EXHAUSTED
    if (lockAccepted) {
      if (acceptedSinceMs == null) acceptedSinceMs = nowMs
      return PressFocus.ACCEPTED
    }
    acceptedSinceMs = null
    if (nowMs >= deadlineMs) return exhaust()
    if (finalScanStarted) return if (finalScan == OwnScan.SCANNING) PressFocus.WORKING else exhaust()
    if ((cycleGaveUp || nowMs >= finalScanAtMs) && lensFree) {
      finalScanStarted = true
      return PressFocus.START_FINAL_SCAN
    }
    return PressFocus.WORKING
  }

  /** Until when the shot's page and focus waits hold for this press: the budget while it works, and once a lock is
   * accepted, at least [SETTLED_FRAME_WAIT_MS] for a frame exposed after it. Null once exhausted. */
  fun holdsUntilMs(): Long? {
    if (exhausted) return null
    val accepted = acceptedSinceMs ?: return deadlineMs
    return maxOf(deadlineMs, accepted + SETTLED_FRAME_WAIT_MS)
  }

  private fun exhaust(): PressFocus {
    exhausted = true
    return PressFocus.EXHAUSTED
  }

  companion object {
    /** A press's whole focus wait: a search and its retry, the torch settling, and the final scan, which on the
     * SM-G998U starts from the lens's near end. */
    const val BUDGET_MS = 8_000L
    /** Budget kept for the final scan. */
    const val FINAL_SCAN_RESERVE_MS = 2_000L
    const val SETTLED_FRAME_WAIT_MS = 500L
  }
}
