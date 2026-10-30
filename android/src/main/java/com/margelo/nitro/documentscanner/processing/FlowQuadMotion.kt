package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One frame's homography in normalized upright crop coordinates; physical pixel aspect is included in the matrix.
 * [px] and [py] are the perspective terms; both are zero for a similarity.
 */
internal data class FlowTransform(
  val xx: Double, val xy: Double, val yx: Double, val yy: Double, val tx: Double, val ty: Double,
  val px: Double = 0.0, val py: Double = 0.0,
) {
  fun warp(quad: DocumentQuad): DocumentQuad? {
    fun point(p: DocumentPoint): DocumentPoint? {
      val w = px * p.x + py * p.y + 1
      if (!(w > 0)) return null // Behind the camera's horizon line: not a real page position.
      return DocumentPoint((xx * p.x + xy * p.y + tx) / w, (yx * p.x + yy * p.y + ty) / w)
    }
    val points = arrayOf(point(quad.topLeft), point(quad.topRight), point(quad.bottomRight), point(quad.bottomLeft))
    if (points.any { it == null || !it.x.isFinite() || !it.y.isFinite() }) return null
    return DocumentQuad(points[0]!!, points[1]!!, points[2]!!, points[3]!!)
  }
}

/** A surviving forward/backward track, expressed in upright analysis pixels. */
internal data class FlowTrack(val x: Double, val y: Double, val u: Double, val v: Double)

/** Bounded deterministic consensus fit over the existing feature tracks, with no second tracking pass. A similarity
 * consensus selects inliers; a flat page then moves by a homography, so tilt can change the tracked outline's shape.
 */
internal object FlowTransformFitter {
  fun fit(tracks: List<FlowTrack>, width: Int, height: Int, regionArea: Double, tolerance: Double): FlowTransform? {
    if (tracks.size < 8 || width <= 0 || height <= 0 || regionArea <= 0) return null
    val required = max(8, ceil(tracks.size * .7).toInt())
    // Tilt bends far tracks away from any similarity, so a looser similarity consensus seeds the homography, which is
    // then held to the strict tolerance. A rejected homography falls back to the strict similarity.
    similarity(tracks, regionArea, tolerance * SEED_SLACK, required)?.let { (_, seed) ->
      homography(tracks, seed, width, height, regionArea, tolerance, required)?.let { return it }
    }
    val (refined, _) = similarity(tracks, regionArea, tolerance, required) ?: return null
    return FlowTransform(refined[0], -refined[1] * height / width, refined[1] * width / height, refined[0],
      refined[2] / width, refined[3] / height)
  }

  /** The refined [c, s, tx, ty] pixel similarity and its inliers, or null without a consensus. */
  private fun similarity(
    tracks: List<FlowTrack>, regionArea: Double, tolerance: Double, required: Int,
  ): Pair<DoubleArray, List<FlowTrack>>? {
    var best = emptyList<FlowTrack>()
    // At most 64 pair hypotheses for the normal <=50 tracks. Deterministic spacing covers the feature list.
    val stride = max(1, ceil(tracks.size / 8.0).toInt())
    for (i in 0 until min(8, tracks.size)) for (j in i + 1 until tracks.size step stride) {
      val a = tracks[i]
      val b = tracks[j]
      val dx = b.x - a.x
      val dy = b.y - a.y
      val energy = dx * dx + dy * dy
      if (energy < tolerance * tolerance * 4) continue
      val c = ((b.u - a.u) * dx + (b.v - a.v) * dy) / energy
      val s = ((b.v - a.v) * dx - (b.u - a.u) * dy) / energy
      val model = doubleArrayOf(c, s, a.u - c * a.x + s * a.y, a.v - s * a.x - c * a.y)
      if (hypot(c, s) !in .5..2.0) continue
      val inliers = tracks.filter { residual(it, model) <= tolerance }
      if (inliers.size > best.size) best = inliers
    }
    if (best.size < required) return null
    val model = leastSquares(best) ?: return null
    val inliers = tracks.filter { residual(it, model) <= tolerance }
    if (inliers.size < required || !spread(inliers, regionArea)) return null
    val refined = leastSquares(inliers) ?: return null
    if (inliers.any { residual(it, refined) > tolerance } || hypot(refined[0], refined[1]) !in .5..2.0) return null
    return refined to inliers
  }

  private fun homography(
    tracks: List<FlowTrack>, seed: List<FlowTrack>, width: Int, height: Int, regionArea: Double, tolerance: Double,
    required: Int,
  ): FlowTransform? {
    val first = dlt(seed) ?: return null
    val inliers = tracks.filter { projected(it, first) <= tolerance }
    if (inliers.size < required || !spread(inliers, regionArea)) return null
    val h = dlt(inliers) ?: return null
    if (inliers.any { projected(it, h) > tolerance }) return null
    val w = width.toDouble()
    val v = height.toDouble()
    val t = FlowTransform(h[0], h[1] * v / w, h[3] * w / v, h[4], h[2] / w, h[5] / v, h[6] * w, h[7] * v)
    // One frame cannot tilt the page much: bound the perspective at the frame corners and the scale like a similarity.
    val corners = listOf(1.0, 1 + t.px, 1 + t.py, 1 + t.px + t.py)
    if (corners.any { it !in .9..1.1 } || sqrt(abs(t.xx * t.yy - t.xy * t.yx)) !in .5..2.0) return null
    return t
  }

  private fun projected(p: FlowTrack, h: DoubleArray): Double {
    val w = h[6] * p.x + h[7] * p.y + 1
    return hypot((h[0] * p.x + h[1] * p.y + h[2]) / w - p.u, (h[3] * p.x + h[4] * p.y + h[5]) / w - p.v)
  }

  /** Normalized direct linear transform with h33 = 1, as [h11, h12, h13, h21, h22, h23, h31, h32] in pixels. */
  private fun dlt(points: List<FlowTrack>): DoubleArray? {
    if (points.size < 8) return null
    fun normalizer(xs: List<Double>, ys: List<Double>): DoubleArray? {
      val mx = xs.average()
      val my = ys.average()
      val mean = xs.indices.sumOf { hypot(xs[it] - mx, ys[it] - my) } / xs.size
      return if (mean > 0 && mean.isFinite()) doubleArrayOf(mx, my, sqrt(2.0) / mean) else null
    }
    val a = normalizer(points.map { it.x }, points.map { it.y }) ?: return null
    val b = normalizer(points.map { it.u }, points.map { it.v }) ?: return null
    val normal = Array(8) { DoubleArray(9) }
    for (p in points) {
      val x = (p.x - a[0]) * a[2]
      val y = (p.y - a[1]) * a[2]
      val u = (p.u - b[0]) * b[2]
      val v = (p.v - b[1]) * b[2]
      for (row in arrayOf(doubleArrayOf(x, y, 1.0, .0, .0, .0, -x * u, -y * u, u),
        doubleArrayOf(.0, .0, .0, x, y, 1.0, -x * v, -y * v, v))) {
        for (i in 0 until 8) for (j in 0..8) normal[i][j] += row[i] * row[j]
      }
    }
    val n = solve(normal) ?: return null
    // Undo both normalizations: H = B^-1 * N * A.
    val hn = arrayOf(doubleArrayOf(n[0], n[1], n[2]), doubleArrayOf(n[3], n[4], n[5]), doubleArrayOf(n[6], n[7], 1.0))
    val an = arrayOf(doubleArrayOf(a[2], .0, -a[2] * a[0]), doubleArrayOf(.0, a[2], -a[2] * a[1]), doubleArrayOf(.0, .0, 1.0))
    val bi = arrayOf(doubleArrayOf(1 / b[2], .0, b[0]), doubleArrayOf(.0, 1 / b[2], b[1]), doubleArrayOf(.0, .0, 1.0))
    val h = multiply(bi, multiply(hn, an))
    val scale = h[2][2]
    if (abs(scale) < 1e-12) return null
    val result = doubleArrayOf(h[0][0], h[0][1], h[0][2], h[1][0], h[1][1], h[1][2], h[2][0], h[2][1]).map { it / scale }
    return result.takeIf { values -> values.all { it.isFinite() } }?.toDoubleArray()
  }

  private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>) =
    Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }

  /** Similarity residual allowance, in tolerances, for seeding the homography under tilt. */
  private const val SEED_SLACK = 4.0

  /** Gaussian elimination with partial pivoting on an augmented 8x9 system. */
  private fun solve(m: Array<DoubleArray>): DoubleArray? {
    for (col in 0 until 8) {
      val pivot = (col until 8).maxBy { abs(m[it][col]) }
      if (abs(m[pivot][col]) < 1e-12) return null
      val swap = m[col]; m[col] = m[pivot]; m[pivot] = swap
      for (row in 0 until 8) {
        if (row == col) continue
        val factor = m[row][col] / m[col][col]
        if (factor != 0.0) for (j in col..8) m[row][j] -= factor * m[col][j]
      }
    }
    return DoubleArray(8) { m[it][8] / m[it][it] }
  }

  private fun residual(p: FlowTrack, m: DoubleArray) = hypot(m[0] * p.x - m[1] * p.y + m[2] - p.u,
    m[1] * p.x + m[0] * p.y + m[3] - p.v)

  private fun leastSquares(points: List<FlowTrack>): DoubleArray? {
    val x = points.sumOf { it.x } / points.size
    val y = points.sumOf { it.y } / points.size
    val u = points.sumOf { it.u } / points.size
    val v = points.sumOf { it.v } / points.size
    var energy = 0.0
    var dot = 0.0
    var cross = 0.0
    for (p in points) {
      energy += (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)
      dot += (p.x - x) * (p.u - u) + (p.y - y) * (p.v - v)
      cross += (p.x - x) * (p.v - v) - (p.y - y) * (p.u - u)
    }
    if (!energy.isFinite() || energy <= 0) return null
    val c = dot / energy
    val s = cross / energy
    return doubleArrayOf(c, s, u - c * x + s * y, v - s * x - c * y)
  }

  private fun spread(points: List<FlowTrack>, regionArea: Double): Boolean {
    val area = (points.maxOf { it.x } - points.minOf { it.x }) * (points.maxOf { it.y } - points.minOf { it.y })
    if (area < regionArea * .3) return false
    val x = points.sumOf { it.x } / points.size
    val y = points.sumOf { it.y } / points.size
    val xx = points.sumOf { (it.x - x) * (it.x - x) }
    val yy = points.sumOf { (it.y - y) * (it.y - y) }
    val xy = points.sumOf { (it.x - x) * (it.y - y) }
    return xx * yy - xy * xy > .01 * (xx + yy) * (xx + yy)
  }
}

/** Analysis-executor-only chain of frame transforms. Missing intervals stay unknown, with no interpolation. */
internal class FlowQuadMotion : LiveQuadMotion {
  private data class Step(val from: Long, val to: Long, val transform: FlowTransform)
  private val steps = ArrayDeque<Step>()
  fun clear() = steps.clear()

  fun add(estimate: FlowMotionEstimate) {
    val transform = estimate.transform
    if (estimate.status != FlowMotionStatus.MEASURED || transform == null || estimate.toSensorNs <= estimate.fromSensorNs) {
      clear()
      return
    }
    if (steps.lastOrNull()?.to != estimate.fromSensorNs) clear()
    steps.addLast(Step(estimate.fromSensorNs, estimate.toSensorNs, transform))
    while (steps.size > 32 || steps.isNotEmpty() && estimate.toSensorNs - steps.first().from > 500_000_000L) steps.removeFirst()
  }

  override fun warp(quad: DocumentQuad, fromNs: Long, toNs: Long): DocumentQuad? {
    if (fromNs == toNs) return quad
    if (fromNs > toNs) return null
    var time = fromNs
    var result = quad
    for (step in steps) {
      if (step.to <= time) continue
      if (step.from != time || step.to > toNs) return null
      result = step.transform.warp(result) ?: return null
      time = step.to
      if (time == toNs) return result
    }
    return null
  }
}
