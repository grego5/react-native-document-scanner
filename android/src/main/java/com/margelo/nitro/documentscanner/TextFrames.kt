package com.margelo.nitro.documentscanner

/**
 * Analysis thread: the sharpest recent frames of the region, kept so a capture takes the best one already seen rather
 * than waiting for a new frame, as zero shutter lag does. Each [Entry] is one frame's upright crop of the region,
 * [image], with its sensor time, sharpness and the focus it was exposed in, [focus], 0 for none. Frames are ranked
 * against others of the same focus, so a new lock's frames are kept however sharp an earlier view was: past
 * [capacity] of one focus, its softest goes. Frames older than [keepNs] behind the newest go, since the view has
 * moved on.
 */
internal class FrameRing<T : Any>(private val capacity: Int = CAPACITY, private val keepNs: Long = KEEP_NS) {
  class Entry<T>(val sensorNs: Long, val sharpness: Double, val focus: Long, val image: T)

  private val entries = mutableListOf<Entry<T>>()

  val size: Int get() = entries.size

  /** Whether a frame of [sharpness] at [sensorNs] under [focus] would be kept, so its image is worth copying. */
  fun wants(sensorNs: Long, sharpness: Double, focus: Long): Boolean {
    val same = entries.filter { it.focus == focus && sensorNs - it.sensorNs <= keepNs }
    return same.size < capacity || same.any { it.sharpness < sharpness }
  }

  fun add(entry: Entry<T>) {
    entries.removeAll { entry.sensorNs - it.sensorNs > keepNs }
    entries.add(entry)
    val same = entries.filter { it.focus == entry.focus }
    if (same.size > capacity) entries.remove(same.minBy { it.sharpness })
  }

  /** The sharpest frame exposed under [focus]; null without one. */
  fun sharpest(focus: Long): Entry<T>? = entries.filter { it.focus == focus }.maxByOrNull { it.sharpness }

  /** The sharpest kept frame of any focus: frames of one view a moment apart compare alike, and the sharpest is the
   * one the lens rendered best, whichever focus it came under. */
  fun sharpest(): Entry<T>? = entries.maxByOrNull { it.sharpness }

  fun clear() = entries.clear()

  companion object {
    const val CAPACITY = 4
    /** How far back a capture may reach: the frames just before the press show what the user aimed at. */
    const val KEEP_NS = 1_000_000_000L
  }
}

/**
 * Analysis thread: when the region is read in the background, once per focus: a settled lock, or continuous autofocus
 * reporting focus where no lock runs. The read waits for [samples] frames under that focus, so the sharpest of them,
 * not the first, is read. A new focus, 0 for none, starts the count over.
 */
internal class FocusedRead(private val samples: Int = SAMPLES) {
  private var focus = 0L
  private var count = 0
  private var done = false

  /** One frame exposed under [focus]; true for the frame after which the read is due, once per focus. */
  fun sampled(focus: Long): Boolean {
    if (focus != this.focus) {
      this.focus = focus
      count = 0
      done = false
    }
    if (focus == 0L || done) return false
    if (++count < samples) return false
    done = true
    return true
  }

  fun reset() {
    focus = 0L
    count = 0
    done = false
  }

  companion object {
    /** Frames compared before the read: enough to pass over one a shake smeared. */
    const val SAMPLES = 4
  }
}

/**
 * Analysis thread: watches the region's image for what the motion sensors cannot see. [still] tells whether the view
 * held still since the previous frame, so a page moved under a steady phone counts as motion. [changed] tells, once
 * per focus, when the view no longer matches the one the focus's read saw: the page moved or another view came in, or
 * the view went soft, as when the phone moves nearer or farther past the lens's depth of field without turning or
 * shaking. Each frame is a [com.margelo.nitro.documentscanner.processing.regionThumbnail], compared by its pattern
 * alone, so exposure changing does not count; a run of [frames] frames must differ, so a shake does not.
 */
internal class ViewWatch(
  private val frames: Int = FRAMES, private val same: Double = SAME, private val soft: Double = SOFT,
  private val stillness: Double = STILL,
) {
  private var previous: FloatArray? = null
  private var focus = 0L
  private var reference: FloatArray? = null
  private var referenceSharpness = 0.0
  private var run = 0
  private var reported = false

  /** Whether the view held still since the previous frame's [thumb]; without either to compare, it did. */
  fun still(thumb: FloatArray?): Boolean {
    val last = previous
    previous = thumb
    if (thumb == null || last == null) return true
    return similarity(last, thumb) >= stillness
  }

  /** [focus]'s read saw [thumb] at [sharpness]; later frames under it are compared with it. */
  fun reference(focus: Long, thumb: FloatArray?, sharpness: Double) {
    this.focus = focus
    reference = thumb
    referenceSharpness = sharpness
    run = 0
    reported = false
  }

  /** One frame under [focus]; true once when the view no longer matches the read's. */
  fun changed(focus: Long, thumb: FloatArray?, sharpness: Double): Boolean {
    if (focus != this.focus || focus == 0L || reported) return false
    val moved = reference?.let { ref -> thumb != null && similarity(ref, thumb) < same } == true
    val softened = referenceSharpness > 0 && sharpness < referenceSharpness * soft
    run = if (moved || softened) run + 1 else 0
    if (run < frames) return false
    reported = true
    return true
  }

  fun reset() {
    previous = null
    focus = 0L
    reference = null
    referenceSharpness = 0.0
    run = 0
    reported = false
  }

  companion object {
    const val FRAMES = 3
    /** Below this correlation with the read's view, the page moved or the view is another. */
    const val SAME = .75
    /** Under half the read's sharpness: a shake or drift costs less, a lens off the print's distance more. */
    const val SOFT = .5
    /** At least this correlation with the previous frame: the view held still. */
    const val STILL = .9
    /** A thumbnail varying less than this in luma is plain, as blank paper, and matches any other. */
    const val PLAIN = 2.0

    /** The two thumbnails' correlation, -1 to 1, by pattern alone: brightness and contrast cancel out. */
    fun similarity(a: FloatArray, b: FloatArray): Double {
      if (a.size != b.size) return 0.0
      val meanA = a.average()
      val meanB = b.average()
      var ab = 0.0
      var aa = 0.0
      var bb = 0.0
      for (i in a.indices) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        ab += da * db
        aa += da * da
        bb += db * db
      }
      val spreadA = kotlin.math.sqrt(aa / a.size)
      val spreadB = kotlin.math.sqrt(bb / b.size)
      if (spreadA < PLAIN && spreadB < PLAIN) return 1.0
      if (spreadA < PLAIN || spreadB < PLAIN) return 0.0
      return ab / kotlin.math.sqrt(aa * bb)
    }
  }
}

/**
 * Analysis thread: whether the region's print is blurred, by the median width of its edges ([regionEdgeWidth]): wider
 * than [CUT] for [enter] frames in a row turns blurred, and no wider for [leave] frames sharp again, so a frame or two
 * of shake does not flicker it. The camera reports no blur, focusing at the lens's near limit even over print nearer
 * than that, and the recognizer misreads characters there before it stops finding text, so the edges tell. A frame
 * without enough edges to measure, as blank paper or print blurred past recognition, leaves the state as it was.
 * [observe] and [reset] return the new state when it changes.
 */
internal class BlurWatch(private val enter: Int = ENTER, private val leave: Int = LEAVE) {
  var blurred = false
    private set
  private var run = 0

  fun observe(edges: Double?): Boolean? {
    if (edges == null) return null
    val now = !sharp(edges)
    if (now == blurred) {
      run = 0
      return null
    }
    if (++run < if (now) enter else leave) return null
    blurred = now
    run = 0
    return now
  }

  fun reset(): Boolean? {
    run = 0
    if (!blurred) return null
    blurred = false
    return false
  }

  companion object {
    /** The widest edges, in analysis pixels from 10% to 90% of the way from paper to ink, of print read reliably: on
     * an SM-G998U at 1440 by 1080, sharp print measures about 2 and the nearest still read right about 3.4. */
    const val CUT = 3.5
    const val ENTER = 5
    const val LEAVE = 3

    /** Edges of [edges] pixels are sharp enough to trust a read; an unmeasured frame is not. */
    fun sharp(edges: Double?) = edges != null && edges <= CUT
  }
}

/**
 * Where a text lock aims, in view fractions: [x], [y] and the focus box's [size]: the region's most textured spot,
 * which a blurred frame keeps over print, else the region's centre.
 */
internal data class TextLockAim(val x: Double, val y: Double, val size: Double)

/**
 * A capture's words from the [lines] recognized in its [width] by [height] image, in reading order along the text's
 * slant: lines by their place across the text's direction, the median of the lines' angles, and words along it. Each
 * word has its corners, turned with the text, and the upright box around them, as fractions of the image; a word the
 * recognizer gave no corners spans its line's height upright.
 */
internal fun captureWords(lines: List<RecognizedLine>, width: Float, height: Float): Array<TextWord> {
  if (width <= 0f || height <= 0f) return emptyArray()
  fun upright(left: Float, top: Float, right: Float, bottom: Float) = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
  fun lineQuad(line: RecognizedLine) = line.corners
    ?: upright(line.words.minOfOrNull { it.left } ?: 0f, line.top, line.words.maxOfOrNull { it.right } ?: 0f, line.bottom)
  fun centre(q: FloatArray) = Pair((q[0] + q[2] + q[4] + q[6]) / 4, (q[1] + q[3] + q[5] + q[7]) / 4)
  // The text's direction: the median of its lines' top edges, so one odd line does not turn the rest.
  val angles = lines.map { lineQuad(it).let { q -> kotlin.math.atan2((q[3] - q[1]).toDouble(), (q[2] - q[0]).toDouble()) } }.sorted()
  val angle = if (angles.isEmpty()) 0.0 else angles[angles.size / 2]
  val alongX = kotlin.math.cos(angle)
  val alongY = kotlin.math.sin(angle)
  fun along(q: FloatArray) = centre(q).let { (x, y) -> x * alongX + y * alongY }
  fun across(q: FloatArray) = centre(q).let { (x, y) -> -x * alongY + y * alongX }
  return lines.sortedBy { across(lineQuad(it)) }.flatMapIndexed { index, line ->
    line.words.filter { it.text.isNotBlank() }
      .map { word -> word to (word.corners ?: upright(word.left, line.top, word.right, line.bottom)) }
      .sortedBy { (_, q) -> along(q) }
      .map { (word, q) ->
        val xs = floatArrayOf(q[0], q[2], q[4], q[6])
        val ys = floatArrayOf(q[1], q[3], q[5], q[7])
        fun point(i: Int) = DocumentPoint((q[2 * i] / width).toDouble(), (q[2 * i + 1] / height).toDouble())
        TextWord(word.text,
          TextRegion((xs.min() / width).toDouble(), (ys.min() / height).toDouble(),
            ((xs.max() - xs.min()) / width).toDouble(), ((ys.max() - ys.min()) / height).toDouble()),
          DocumentCorners(point(0), point(1), point(2), point(3)), index.toDouble(), line.confidence.toDouble())
      }
  }.toTypedArray()
}

/** An upright greyscale crop of the region: [width] by [height] luma bytes, row by row. OCR needs no colour, so the
 * text camera reads the frame's luma plane alone. */
internal class LumaCrop(val width: Int, val height: Int, val bytes: ByteArray)

/**
 * [source], a [width] by [height] block of a buffer's luma, turned upright by the frame's [rotation], the clockwise
 * turn from the buffer to the upright view, as `regionInBuffer` maps it.
 */
internal fun uprightLuma(source: ByteArray, width: Int, height: Int, rotation: Int): LumaCrop {
  if (rotation == 0) return LumaCrop(width, height, source)
  val sideways = rotation == 90 || rotation == 270
  val uprightWidth = if (sideways) height else width
  val uprightHeight = if (sideways) width else height
  val out = ByteArray(source.size)
  for (y in 0 until uprightHeight) {
    for (x in 0 until uprightWidth) {
      val (bx, by) = when (rotation) {
        90 -> y to height - 1 - x
        180 -> width - 1 - x to height - 1 - y
        else -> width - 1 - y to x
      }
      out[y * uprightWidth + x] = source[by * width + bx]
    }
  }
  return LumaCrop(uprightWidth, uprightHeight, out)
}
