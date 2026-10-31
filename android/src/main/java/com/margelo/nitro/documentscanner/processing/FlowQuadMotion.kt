package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

/** One frame's homography in normalized upright crop coordinates; physical pixel aspect is included in the matrix.
 * [px] and [py] are the perspective terms; both are zero for a similarity.
 */
internal data class FlowTransform(
  val xx: Double, val xy: Double, val yx: Double, val yy: Double, val tx: Double, val ty: Double,
  val px: Double = 0.0, val py: Double = 0.0,
) {
  internal fun values() = doubleArrayOf(xx, xy, yx, yy, tx, ty, px, py)
  fun warp(quad: DocumentQuad): DocumentQuad? = NativeMotion.warpNative(values(), quad.motionValues())?.motionQuad()
}

/** A surviving forward/backward track, expressed in upright analysis pixels. */
internal data class FlowTrack(val x: Double, val y: Double, val u: Double, val v: Double)

/** Bounded deterministic consensus fit over the existing feature tracks, with no second tracking pass. A similarity
 * consensus selects inliers; a flat page then moves by a homography, so tilt can change the tracked outline's shape.
 */
internal object FlowTransformFitter {
  fun fit(tracks: List<FlowTrack>, width: Int, height: Int, regionArea: Double, tolerance: Double): FlowTransform? {
    val values = DoubleArray(tracks.size * 4)
    tracks.forEachIndexed { i, p -> values[i * 4] = p.x; values[i * 4 + 1] = p.y; values[i * 4 + 2] = p.u; values[i * 4 + 3] = p.v }
    return NativeMotion.fitNative(values, width, height, regionArea, tolerance)?.motionTransform()
  }
}

/** Analysis-executor-only chain of frame transforms. Missing intervals stay unknown, with no interpolation. */
internal class FlowQuadMotion : LiveQuadMotion, AutoCloseable {
  private var native: NativeFlowHistory? = null
  private var closed = false
  fun clear() { check(!closed); native?.clearNative() }
  fun add(estimate: FlowMotionEstimate) {
    check(!closed)
    if (estimate.status != FlowMotionStatus.MEASURED || estimate.transform == null) { clear(); return }
    val history = native ?: NativeFlowHistory().also { native = it }
    history.addNative(estimate.fromSensorNs, estimate.toSensorNs, estimate.transform.values())
  }
  override fun warp(quad: DocumentQuad, fromNs: Long, toNs: Long): DocumentQuad? {
    check(!closed)
    if (fromNs == toNs) return quad
    return native?.warpNative(quad.motionValues(), fromNs, toNs)?.motionQuad()
  }
  override fun close() {
    if (!closed) { closed = true; native?.close(); native = null }
  }
}
