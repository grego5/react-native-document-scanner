package com.margelo.nitro.documentscanner

/**
 * Analysis thread, `focusLock`: the text read around the aim, as boxes in view fractions that move with the page, so
 * the aim selects whatever text it is on without a new recognition. Each recognition reads the region as a tile: a
 * line found where the map has one is another read of it, at its newly read place; a new line is added; a line the
 * tile held and the read no longer finds is gone. Lines outside the tile keep their reads. The map keeps only the
 * lines near the aim, since its motion is measured on one line and drifts with distance from it.
 */
internal class TextMap {
  /** One recognition's line: its [text], ML Kit's [confidence] and its box in view fractions. */
  data class Read(val text: String, val confidence: Float, val corners: DocumentCorners)

  /** One line of the map: where it is now and its recent reads, which agree once [AGREEMENT] read the same text. */
  class Line(corners: DocumentCorners) {
    var corners = corners
      internal set
    internal var readAtNs = 0L
    private val reads = ArrayDeque<Pair<String, Float>>()
    // Tallied when a read arrives, not on each frame that selects the line.
    private var tallied: TextVotes.Winner? = null

    internal fun add(text: String, confidence: Float) {
      reads.addLast(text to confidence)
      while (reads.size > MAX_READS) reads.removeFirst()
      // The most reads win, then the higher mean confidence, then the text read first.
      tallied = reads.groupBy { it.first }
        .map { (text, same) -> TextVotes.Winner(text, same.map { it.second.toDouble() }.average(), same.size) }
        .maxWithOrNull(compareBy<TextVotes.Winner> { it.votes }.thenBy { it.confidence })
    }

    /** The text most of its reads give, with their mean confidence and count; null before a read. */
    fun winner(): TextVotes.Winner? = tallied

    /** The text [AGREEMENT] reads agree on; null until they do. */
    fun agreed(): TextVotes.Winner? = tallied?.takeIf { it.votes >= AGREEMENT }

    /** Its reads ran out without agreeing: more reads at this place would not settle it. */
    val exhausted: Boolean get() = reads.size >= MAX_READS && agreed() == null
  }

  private val lines = mutableListOf<Line>()

  /** The area of the last recognition, moved with the page; null before one and after the map is lost. */
  var tile: DocumentCorners? = null
    private set
  private var tileReadAtNs: Long? = null

  val size: Int get() = lines.size

  fun clear() {
    lines.clear()
    tile = null
    tileReadAtNs = null
  }

  /** A recognition of [area], the region read, found [reads]. */
  fun merge(area: TextRegion, reads: List<Read>, sensorNs: Long) {
    val read = HashSet<Line>()
    val added = mutableListOf<Line>()
    for (next in reads) {
      val box = next.corners.boundingRegion()
      // The line at the same place, read before; a read spelling it differently is still its read, and the votes decide.
      val line = lines.filter { it !in read }
        .map { it to overlap(it.corners.boundingRegion(), box) }
        .filter { it.second >= SAME_PLACE }
        .maxByOrNull { it.second }?.first
        ?: Line(next.corners).also { added.add(it) }
      line.corners = next.corners
      line.readAtNs = sensorNs
      line.add(next.text, next.confidence)
      read.add(line)
    }
    lines.removeAll { it !in read && area.holds(it.corners) }
    lines.addAll(added)
    tile = area.corners()
    tileReadAtNs = sensorNs.takeIf { reads.isNotEmpty() }
  }

  /** Moves every line, and the tile, by [move]; a line it cannot move loses the map, whose places are then unknown. */
  fun move(move: (DocumentCorners) -> DocumentCorners?): Boolean {
    val moved = lines.map { move(it.corners) ?: return false.also { clear() } }
    val movedTile = tile?.let { move(it) ?: return false.also { clear() } }
    lines.forEachIndexed { i, line -> line.corners = moved[i] }
    tile = movedTile
    return true
  }

  /** Drops lines that left the aim's neighbourhood or have gone too long without an OCR position. */
  fun prune(keep: TextRegion, oldestReadNs: Long) {
    lines.removeAll { it.readAtNs < oldestReadNs || !keep.holds(it.corners) }
  }

  /** The line the aim at [x], [y] is on, the nearest of any that overlap there; null over no text. */
  fun at(x: Double, y: Double): Line? = lines.asSequence().filter { it.corners.containsPoint(x, y) }.minByOrNull { it.distance(x, y) }

  /** The line nearest the aim, which flow tracks so the lines around the aim move most accurately. */
  fun nearest(x: Double, y: Double): Line? = lines.minByOrNull { it.distance(x, y) }

  /** Reads when the tile changes, needs new OCR positions, or the aimed line still needs agreement. */
  fun needsRead(area: TextRegion, aimed: Line?, sensorNs: Long): Boolean {
    if (!covers(area)) return true
    if (tileReadAtNs?.let { sensorNs - it >= REFRESH_NS } == true) return true
    if (aimed == null) return false
    return aimed.agreed() == null && !aimed.exhausted
  }

  private fun DocumentCorners.containsPoint(x: Double, y: Double): Boolean {
    fun side(a: DocumentPoint, b: DocumentPoint) = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)

    val top = side(topLeft, topRight)
    val right = side(topRight, bottomRight)
    val bottom = side(bottomRight, bottomLeft)
    val left = side(bottomLeft, topLeft)
    val clockwise = top >= 0.0 && right >= 0.0 && bottom >= 0.0 && left >= 0.0
    val counterclockwise = top <= 0.0 && right <= 0.0 && bottom <= 0.0 && left <= 0.0
    return (clockwise || counterclockwise) && area() > 0.0
  }

  /** Whether [area], the region, still lies mostly within the last tile, so reading it would find what the map has. */
  fun covers(area: TextRegion): Boolean {
    val last = tile ?: return false
    return overlap(last.boundingRegion(), area) >= TILE_OVERLAP
  }

  private fun Line.distance(x: Double, y: Double): Double {
    val box = corners.boundingRegion()
    val dx = box.x + box.width / 2 - x
    val dy = box.y + box.height / 2 - y
    return dx * dx + dy * dy
  }

  private fun TextRegion.holds(corners: DocumentCorners): Boolean {
    val box = corners.boundingRegion()
    val cx = box.x + box.width / 2
    val cy = box.y + box.height / 2
    return cx >= x && cx <= x + width && cy >= y && cy <= y + height
  }

  /**
   * A lock's recognitions until they show whether its focus found readable text: the aimed line agreeing, or
   * [MAX_READS] without. Until then the region is read whatever the map holds, since one read that misses is no verdict.
   */
  class LockReads {
    private var reads = 0
    var reported = false
      private set

    fun reset() {
      reads = 0
      reported = false
    }

    /** One recognition, after which the aimed line agrees when [found]; the verdict once there is one, else null. */
    fun recognized(found: Boolean): Boolean? {
      if (reported) return null
      reads++
      if (!found && reads < MAX_READS) return null
      reported = true
      return found
    }
  }

  companion object {
    const val REFRESH_NS = 500_000_000L
    const val LINE_LIFETIME_NS = 1_500_000_000L
    /** Reads that must agree on a line: one may misread. */
    const val AGREEMENT = 2
    /** Reads a line keeps; a line whose reads never agree is not read again until the region moves on. */
    const val MAX_READS = 4
    /** How much a read must overlap a line to be another read of it. */
    const val SAME_PLACE = .3
    /** How much of the region the last tile must still cover for the map to stand for it. */
    const val TILE_OVERLAP = .6
  }
}
