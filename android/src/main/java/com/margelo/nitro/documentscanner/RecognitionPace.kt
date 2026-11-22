package com.margelo.nitro.documentscanner

import kotlin.math.abs

/**
 * Analysis thread: how often the text camera recognizes. Recognitions at `recognitionRate` let frames agree quickly on
 * new text, but once [SETTLED_AGREEMENT] in a row have read the agreed text and the view holds still, more add little,
 * so it slows to one each [SETTLED_INTERVAL_MS], which still notices text that changes without the camera moving. It
 * returns to the full rate when a recognition disagrees, the agreed text's outline moves past [MOVE_HEIGHTS] of its
 * height or [MOVE_WIDTHS] of its width since the last recognition, its outline is lost, or main wakes it, as after a
 * turn.
 */
internal class RecognitionPace {
  private var streak = 0
  private var wake = 0L
  /** The agreed text's box at the last recognition, as view fractions, which motion is measured from. */
  private var anchor: TextRegion? = null

  val settled: Boolean get() = streak >= SETTLED_AGREEMENT

  /** The interval between recognitions, given the `recognitionRate` one, [rateMs]. */
  fun intervalMs(rateMs: Long): Long = if (settled) maxOf(rateMs, SETTLED_INTERVAL_MS) else rateMs

  /** A recognition read [text] where the votes agree on [winner], boxed at [box]. */
  fun recognized(text: String?, winner: String?, box: TextRegion?) {
    streak = if (text != null && text == winner) streak + 1 else 0
    anchor = box
  }

  /** Main's wake [token]; a new one returns to the full rate. */
  fun wake(token: Long) {
    if (token == wake) return
    wake = token
    streak = 0
  }

  /** The agreed text's outline moved to [box]; far enough from the last recognition returns to the full rate. */
  fun moved(box: TextRegion) {
    val from = anchor ?: return
    val dx = abs((box.x + box.width / 2) - (from.x + from.width / 2))
    val dy = abs((box.y + box.height / 2) - (from.y + from.height / 2))
    if (dx > from.width * MOVE_WIDTHS || dy > from.height * MOVE_HEIGHTS) streak = 0
  }

  /** The agreed text's outline was lost, as when flow cannot follow the frame: a recognition must place it again. */
  fun lost() {
    streak = 0
  }

  /** New votes, as after a region, filter or binding change. */
  fun reset() {
    streak = 0
    anchor = null
  }

  companion object {
    /** Recognitions in a row that read the agreed text before the pace slows. */
    const val SETTLED_AGREEMENT = 4
    const val SETTLED_INTERVAL_MS = 1_000L
    const val MOVE_HEIGHTS = .5
    const val MOVE_WIDTHS = .1
  }
}
