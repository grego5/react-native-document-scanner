package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One recognized word: its text and horizontal extent in the upright crop's pixels. [chars] are the recognizer's own
 * extents for each character of the trimmed text, left then right; without them, or when their count differs, the
 * word's width is shared evenly.
 */
internal data class RecognizedWord(val text: String, val left: Float, val right: Float, val chars: List<Float>? = null)

/**
 * One recognized line in the upright crop's pixels: its words left to right, its vertical extent, and [confidence],
 * 0 when the recognizer gives none.
 */
internal data class RecognizedLine(
  val words: List<RecognizedWord>,
  val top: Float,
  val bottom: Float,
  val confidence: Float,
) {
  val text: String get() = words.joinToString(" ") { it.text.trim() }
  val left: Float get() = words.minOf { it.left }
  val right: Float get() = words.maxOf { it.right }
  val centerY: Float get() = (top + bottom) / 2
}

/**
 * Turns raw recognized lines into one accepted reading. Lines sharing a baseline join left to right, since recognizers
 * split widely spaced groups. Without [allowedCharacters] each joined line is a candidate. With them, every character
 * that is neither allowed nor swappable for an allowed look-alike, whitespace included unless a space is allowed, splits
 * the line into separate candidates, so surrounding words never join the reading. The accepted candidate nearest the
 * crop's centre wins. Same rules as iOS `TextLineFilter`.
 */
internal class TextLineFilter(allowedCharacters: String, pattern: String, minLength: Int) {
  private val allowed: Set<Char>? = allowedCharacters.takeIf { it.isNotEmpty() }?.toSet()
  private val minLength = minLength.coerceAtLeast(1)
  /** Null for no pattern; [patternInvalid] when it does not compile, and nothing is then accepted. */
  private val regex: Regex? = pattern.takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it) }.getOrNull() }
  val patternInvalid = pattern.isNotEmpty() && regex == null

  /** A filtered piece of a line and its extent in the crop. */
  data class Candidate(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
  }

  /** Every filtered piece of [lines] the filter accepts. */
  fun candidatesIn(lines: List<RecognizedLine>): List<Candidate> =
    if (patternInvalid) emptyList() else baselines(lines).flatMap(::candidates).filter(::accepted)

  fun select(lines: List<RecognizedLine>, cropWidth: Float, cropHeight: Float): Candidate? {
    if (patternInvalid || cropWidth <= 0f || cropHeight <= 0f) return null
    return baselines(lines)
      .flatMap(::candidates)
      .filter(::accepted)
      .minByOrNull {
        val dx = (it.centerX - cropWidth / 2) / cropWidth
        val dy = (it.centerY - cropHeight / 2) / cropHeight
        dx * dx + dy * dy
      }
  }

  /** The filtered pieces of [line]: the whole line without a character set, otherwise its allowed runs. */
  fun candidates(line: RecognizedLine): List<Candidate> {
    val set = allowed
      ?: return listOf(Candidate(line.text.trim().replace(WHITESPACE, " "), line.left, line.top, line.right, line.bottom, line.confidence))
    val out = mutableListOf<Candidate>()
    val text = StringBuilder()
    // Each kept character's horizontal extent.
    val lefts = mutableListOf<Float>()
    val rights = mutableListOf<Float>()
    fun flush() {
      // A run may start or end with an allowed space; its extent spans only the kept characters.
      val first = text.indexOfFirst { it != ' ' }
      if (first >= 0) {
        val last = text.indexOfLast { it != ' ' }
        out.add(Candidate(text.substring(first, last + 1), lefts[first], line.top, rights[last], line.bottom, line.confidence))
      }
      text.clear()
      lefts.clear()
      rights.clear()
    }
    fun put(c: Char, left: Float, right: Float) {
      val kept = if (c in set) c else substitute(c, set)
      if (kept == null) return flush()
      text.append(kept)
      lefts.add(left)
      rights.add(right)
    }
    val words = line.words.filter { it.text.isNotBlank() }.sortedBy { it.left }
    words.forEachIndexed { index, word ->
      if (index > 0) put(' ', words[index - 1].right, word.left)
      val chars = word.text.trim()
      val known = word.chars?.takeIf { it.size == chars.length * 2 }
      val width = (word.right - word.left) / chars.length
      chars.forEachIndexed { i, c ->
        if (known != null) put(c, known[2 * i], known[2 * i + 1]) else put(c, word.left + i * width, word.left + (i + 1) * width)
      }
    }
    flush()
    return out
  }

  /** Whether [raw]'s filtered text, as one piece, is long enough and matches the pattern. */
  fun accept(raw: String): String? = candidates(RecognizedLine(listOf(RecognizedWord(raw, 0f, raw.length.toFloat())), 0f, 1f, 0f))
    .singleOrNull()?.takeIf(::accepted)?.text

  private fun accepted(candidate: Candidate): Boolean =
    candidate.text.length >= minLength && (regex == null || regex.matches(candidate.text))

  private fun substitute(c: Char, set: Set<Char>): Char? {
    if (c.isWhitespace()) return if (' ' in set) ' ' else null
    val upper = c.uppercaseChar()
    val lower = c.lowercaseChar()
    if (upper in set) return upper
    if (lower in set) return lower
    return (LOOKALIKES[c].orEmpty() + LOOKALIKES[upper].orEmpty()).firstOrNull { it in set }
  }

  companion object {
    private val WHITESPACE = Regex("\\s+")
    private val LOOKALIKES = mapOf(
      '0' to "OQD", 'O' to "0Q", 'Q' to "O0", 'D' to "0O",
      '1' to "IL|", 'I' to "1L|", 'L' to "1I", '|' to "1IL",
      '2' to "Z", 'Z' to "2", '5' to "S", 'S' to "5",
      '6' to "G", 'G' to "6", '8' to "B", 'B' to "8",
    )

    /** Joins lines whose vertical centres fall within each other's height, their words left to right. */
    fun baselines(lines: List<RecognizedLine>): List<RecognizedLine> {
      val groups = mutableListOf<MutableList<RecognizedLine>>()
      for (line in lines.filter { it.words.any { word -> word.text.isNotBlank() } }.sortedBy { it.centerY }) {
        val group = groups.lastOrNull()
        val last = group?.last()
        if (last != null && line.centerY in last.top..last.bottom && last.centerY in line.top..line.bottom) group.add(line)
        else groups.add(mutableListOf(line))
      }
      return groups.map { group ->
        RecognizedLine(
          group.flatMap { it.words }.sortedBy { it.left },
          group.minOf { it.top }, group.maxOf { it.bottom },
          group.map { it.confidence }.average().toFloat(),
        )
      }
    }
  }
}

/**
 * Agreement over recent frames: the text read most often within [maxAgeMs] and the last [window] frames wins, the
 * newest breaking ties. Frames without an accepted reading count toward the window, so a region that stops showing
 * text clears within it.
 */
internal class TextVotes(private val window: Int = 8, private val maxAgeMs: Long = 1500) {
  data class Winner(val text: String, val confidence: Double, val votes: Int)
  private data class Vote(val text: String?, val confidence: Float, val atMs: Long)
  private val votes = ArrayDeque<Vote>()

  fun add(text: String?, confidence: Float, nowMs: Long): Winner? {
    votes.addLast(Vote(text, confidence, nowMs))
    while (votes.size > window) votes.removeFirst()
    while (votes.isNotEmpty() && nowMs - votes.first().atMs > maxAgeMs) votes.removeFirst()
    return winner()
  }

  fun clear() = votes.clear()

  /** Drops votes older than the age at [nowMs], as [add] does; true when any went, which can change the winner. */
  fun expire(nowMs: Long): Boolean {
    var dropped = false
    while (votes.isNotEmpty() && nowMs - votes.first().atMs > maxAgeMs) {
      votes.removeFirst()
      dropped = true
    }
    return dropped
  }

  fun winner(): Winner? {
    var best: Winner? = null
    val counted = HashSet<String>()
    for (index in votes.indices.reversed()) {
      val text = votes[index].text ?: continue
      if (!counted.add(text)) continue
      val agreeing = votes.filter { it.text == text }
      if (best == null || agreeing.size > best.votes) {
        best = Winner(text, agreeing.map { it.confidence.toDouble() }.average(), agreeing.size)
      }
    }
    return best
  }
}

/**
 * The outline to keep after a recognized frame: the frame's own outline when it boxed the agreed [winner], otherwise
 * [current] only while it still belongs to the winner. A new winner the frame did not box has none yet.
 */
internal fun <T> outlineAfter(current: T?, read: T?, winner: String?, textOf: (T) -> String): T? = when {
  winner == null -> null
  read != null && textOf(read) == winner -> read
  else -> current?.takeIf { textOf(it) == winner }
}

/** Maps [region], fractions of the upright view, into an analysis buffer's pixels. The bound viewport crops every
 * use case to what the view shows, so the view is [cropRect] rotated clockwise by [rotationDegrees]. */
internal fun regionInBuffer(
  region: TextRegion, rotationDegrees: Int, cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
): IntArray {
  val x0 = region.x.coerceIn(0.0, 1.0)
  val y0 = region.y.coerceIn(0.0, 1.0)
  val x1 = (region.x + region.width).coerceIn(x0, 1.0)
  val y1 = (region.y + region.height).coerceIn(y0, 1.0)
  // Upright (u, v) comes from buffer (bu, bv) rotated clockwise by the rotation.
  fun toBuffer(u: Double, v: Double): Pair<Double, Double> = when (rotationDegrees) {
    90 -> v to 1 - u
    180 -> 1 - u to 1 - v
    270 -> 1 - v to u
    else -> u to v
  }
  val (ax, ay) = toBuffer(x0, y0)
  val (bx, by) = toBuffer(x1, y1)
  val left = cropLeft + (minOf(ax, bx) * cropWidth).roundToInt()
  val top = cropTop + (minOf(ay, by) * cropHeight).roundToInt()
  val right = cropLeft + (maxOf(ax, bx) * cropWidth).roundToInt()
  val bottom = cropTop + (maxOf(ay, by) * cropHeight).roundToInt()
  return intArrayOf(left, top, right, bottom)
}

/** [candidate]'s extent in an upright crop of [cropWidth] by [cropHeight] showing [region], as fractions of the view. */
internal fun textBoundsInView(candidate: TextLineFilter.Candidate, region: TextRegion, cropWidth: Float, cropHeight: Float): TextRegion {
  val left = (candidate.left / cropWidth).coerceIn(0f, 1f)
  val top = (candidate.top / cropHeight).coerceIn(0f, 1f)
  val right = (candidate.right / cropWidth).coerceIn(left, 1f)
  val bottom = (candidate.bottom / cropHeight).coerceIn(top, 1f)
  return TextRegion(
    region.x + left * region.width, region.y + top * region.height,
    (right - left) * region.width, (bottom - top) * region.height,
  )
}

/** [this] grown by [pad] view fractions on each side, clamped to the view, as corners. */
/**
 * Whether ([x], [y]) lies on the text outline [corners], taken as the parallelogram its top-left, top-right and
 * bottom-left corners span, widened by [AIM_ALONG] of its length along the line and [AIM_ACROSS] of its height across
 * it, so a hand's in-place motion does not flip it. Judged in the outline's own coordinates, so the view's aspect does
 * not matter.
 */
internal fun onText(corners: DocumentCorners, x: Double, y: Double): Boolean {
  val ox = corners.topLeft.x
  val oy = corners.topLeft.y
  val ax = corners.topRight.x - ox
  val ay = corners.topRight.y - oy
  val bx = corners.bottomLeft.x - ox
  val by = corners.bottomLeft.y - oy
  val det = ax * by - ay * bx
  if (abs(det) < 1e-12) return false
  val px = x - ox
  val py = y - oy
  val along = (px * by - py * bx) / det
  val across = (ax * py - ay * px) / det
  return along in -AIM_ALONG..1 + AIM_ALONG && across in -AIM_ACROSS..1 + AIM_ACROSS
}

/** How far past the text's ends, as a fraction of its length, the aim still counts as on it. */
private const val AIM_ALONG = .05
/** How far above or below the text, as a fraction of its height, the aim still counts as on it. */
private const val AIM_ACROSS = .5

internal fun TextRegion.corners(pad: Double = 0.0): DocumentCorners {
  val left = (x - pad).coerceIn(0.0, 1.0)
  val top = (y - pad).coerceIn(0.0, 1.0)
  val right = (x + width + pad).coerceIn(left, 1.0)
  val bottom = (y + height + pad).coerceIn(top, 1.0)
  return DocumentCorners(DocumentPoint(left, top), DocumentPoint(right, top), DocumentPoint(right, bottom), DocumentPoint(left, bottom))
}

/** How much [a] and [b] overlap: their intersection over their union, 0 apart and 1 the same. */
internal fun overlap(a: TextRegion, b: TextRegion): Double {
  val w = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
  val h = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
  if (w <= 0 || h <= 0) return 0.0
  val shared = w * h
  return shared / (a.width * a.height + b.width * b.height - shared)
}

/** [this] quadrilateral's area, in the units of its coordinates squared; its corners go around it in order. */
internal fun DocumentCorners.area(): Double {
  val p = arrayOf(topLeft, topRight, bottomRight, bottomLeft)
  var twice = 0.0
  for (i in p.indices) {
    val a = p[i]
    val b = p[(i + 1) % p.size]
    twice += a.x * b.y - b.x * a.y
  }
  return abs(twice) / 2
}

/** The axis-aligned box around [this]. */
internal fun DocumentCorners.boundingRegion(): TextRegion {
  val xs = doubleArrayOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
  val ys = doubleArrayOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
  return TextRegion(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
}

/**
 * The part of the text [box] flow tracks: all of it up to [TEXT_FLOW_ASPECT] line heights long, else that much around
 * the aim at [aimX]. Flow's fit needs its points spread across the area it watches, not along one thin line, and a
 * stretch of the line moves as the whole line does. [pixelAspect] is the view's width over its height in pixels.
 */
internal fun textFlowWindow(box: TextRegion, aimX: Double, pixelAspect: Double): TextRegion {
  val longest = TEXT_FLOW_ASPECT * box.height / pixelAspect
  if (box.width <= longest) return box
  val x = (aimX - longest / 2).coerceIn(box.x, box.x + box.width - longest)
  return TextRegion(x, box.y, longest, box.height)
}

/**
 * Where flow looks for the text in [box]: the text with a quarter of its length to either side and two line heights
 * above and below, so its strokes fill flow's grid across and some scene holds it along; clamped to the view.
 */
internal fun textFlowArea(box: TextRegion): TextRegion {
  val padX = box.width * TEXT_FLOW_ALONG
  val padY = box.height * TEXT_FLOW_ACROSS
  return clampedRegion(TextRegion(box.x - padX, box.y - padY, box.width + 2 * padX, box.height + 2 * padY))
}

/**
 * Whether [area], chosen for earlier text, still holds [box] clear of its edges by half the margin it was given, so flow
 * keeps its crop, and its reference, while the text moves a little.
 */
internal fun textFlowAreaHolds(area: TextRegion, box: TextRegion): Boolean {
  val marginX = box.width * TEXT_FLOW_ALONG / 2
  val marginY = box.height * TEXT_FLOW_ACROSS / 2
  return box.x >= area.x + marginX && box.y >= area.y + marginY &&
    box.x + box.width <= area.x + area.width - marginX && box.y + box.height <= area.y + area.height - marginY
}

/**
 * The analysis buffer pixels flow reads for one area of the view, [left] to [bottom] in buffer coordinates, and the
 * upright [view] box they show, as view fractions. Cropping before flow downscales keeps the text's own strokes as
 * features, which a downscale of the whole frame blurs away.
 */
internal data class FlowCrop(val left: Int, val top: Int, val right: Int, val bottom: Int, val view: TextRegion) {
  /** [corners], in view fractions, as fractions of this crop: the frame flow measures. */
  fun toCrop(corners: DocumentCorners) = corners.mapPoints { x, y -> DocumentPoint((x - view.x) / view.width, (y - view.y) / view.height) }
  /** [corners], as fractions of this crop, in view fractions. */
  fun toView(corners: DocumentCorners) = corners.mapPoints { x, y -> DocumentPoint(view.x + x * view.width, view.y + y * view.height) }
}

/**
 * The buffer pixels under [area], an upright box of the view, which shows the buffer's [cropLeft] to [cropBottom]
 * turned by [rotationDegrees] clockwise; null when too small to track.
 */
internal fun flowCrop(area: TextRegion, cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int, rotationDegrees: Int): FlowCrop? {
  val l = area.x
  val t = area.y
  val r = area.x + area.width
  val b = area.y + area.height
  // The upright box in buffer fractions: the view turns the buffer clockwise.
  val box = when (rotationDegrees) {
    90 -> doubleArrayOf(t, 1 - r, b, 1 - l)
    180 -> doubleArrayOf(1 - r, 1 - b, 1 - l, 1 - t)
    270 -> doubleArrayOf(1 - b, l, 1 - t, r)
    else -> doubleArrayOf(l, t, r, b)
  }
  val w = cropRight - cropLeft
  val h = cropBottom - cropTop
  // Outward to whole pixels, past rounding error, so a box on a pixel edge keeps it.
  val left = floor(box[0] * w + PIXEL_EPSILON).toInt().coerceIn(0, w)
  val top = floor(box[1] * h + PIXEL_EPSILON).toInt().coerceIn(0, h)
  val right = ceil(box[2] * w - PIXEL_EPSILON).toInt().coerceIn(left, w)
  val bottom = ceil(box[3] * h - PIXEL_EPSILON).toInt().coerceIn(top, h)
  if (right - left < FLOW_MIN_PIXELS || bottom - top < FLOW_MIN_PIXELS) return null
  val x0 = left.toDouble() / w
  val y0 = top.toDouble() / h
  val x1 = right.toDouble() / w
  val y1 = bottom.toDouble() / h
  val view = when (rotationDegrees) {
    90 -> TextRegion(1 - y1, x0, y1 - y0, x1 - x0)
    180 -> TextRegion(1 - x1, 1 - y1, x1 - x0, y1 - y0)
    270 -> TextRegion(y0, 1 - x1, y1 - y0, x1 - x0)
    else -> TextRegion(x0, y0, x1 - x0, y1 - y0)
  }
  return FlowCrop(cropLeft + left, cropTop + top, cropLeft + right, cropTop + bottom, view)
}

private inline fun DocumentCorners.mapPoints(f: (Double, Double) -> DocumentPoint) =
  DocumentCorners(f(topLeft.x, topLeft.y), f(topRight.x, topRight.y), f(bottomRight.x, bottomRight.y), f(bottomLeft.x, bottomLeft.y))

private const val FLOW_MIN_PIXELS = 32
private const val PIXEL_EPSILON = 1e-6
private const val TEXT_FLOW_ASPECT = 6.0
private const val TEXT_FLOW_ALONG = .25
private const val TEXT_FLOW_ACROSS = 2.0

internal fun clampedRegion(region: TextRegion): TextRegion {
  val x = region.x.coerceIn(0.0, 1.0)
  val y = region.y.coerceIn(0.0, 1.0)
  return TextRegion(x, y, region.width.coerceIn(0.0, 1.0 - x), region.height.coerceIn(0.0, 1.0 - y))
}
