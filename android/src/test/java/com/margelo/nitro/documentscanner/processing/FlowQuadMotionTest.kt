package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class FlowQuadMotionTest {
  private val quad = DocumentQuad(DocumentPoint(.2, .2), DocumentPoint(.8, .2), DocumentPoint(.8, .8), DocumentPoint(.2, .8))

  @Test fun fitsTranslationRotationAndScaleInNonSquareFrames() {
    val angle = .06
    val c = cos(angle) * 1.04
    val s = sin(angle) * 1.04
    val tracks = grid { x, y -> FlowTrack(x, y, c * x - s * y + 7, s * x + c * y - 3) }
    val fit = FlowTransformFitter.fit(tracks, 320, 240, 200.0 * 160, 1.0)!!
    val moved = fit.warp(quad)!!
    assertEquals((c * 64 - s * 48 + 7) / 320, moved.topLeft.x, 1e-9)
    assertEquals((s * 64 + c * 48 - 3) / 240, moved.topLeft.y, 1e-9)
  }

  @Test fun fitsPerspectiveSoTiltChangesTheOutlineShape() {
    // A small per-frame tilt in pixels: the far side shrinks while the near side stays.
    val h = doubleArrayOf(1.01, .02, 3.0, -.01, 1.0, -2.0, 1e-4, -5e-5)
    fun project(x: Double, y: Double): Pair<Double, Double> {
      val w = h[6] * x + h[7] * y + 1
      return (h[0] * x + h[1] * y + h[2]) / w to (h[3] * x + h[4] * y + h[5]) / w
    }
    val tracks = grid { x, y -> project(x, y).let { (u, v) -> FlowTrack(x, y, u, v) } }
    val fit = FlowTransformFitter.fit(tracks, 320, 240, 200.0 * 160, 1.0)!!
    assertNotEquals(.0, fit.px)
    val moved = fit.warp(quad)!!
    for ((point, expected) in listOf(moved.topLeft to project(64.0, 48.0), moved.bottomRight to project(256.0, 192.0))) {
      assertEquals(expected.first / 320, point.x, 1e-6)
      assertEquals(expected.second / 240, point.y, 1e-6)
    }
  }

  @Test fun similarityTracksFitWithoutPerspectiveTerms() {
    val fit = FlowTransformFitter.fit(grid { x, y -> FlowTrack(x, y, x + 5, y - 2) }, 320, 240, 200.0 * 160, 1.0)!!
    assertEquals(.0, fit.px, 1e-9)
    assertEquals(.0, fit.py, 1e-9)
  }

  @Test fun rejectsOutliersWithoutAnotherOpticalFlowPass() {
    val tracks = grid { x, y -> FlowTrack(x, y, x + 5, y - 2) }.toMutableList()
    for (i in listOf(1, 4, 9, 16, 23)) tracks[i] = tracks[i].copy(u = 300.0, v = -100.0)
    val fit = FlowTransformFitter.fit(tracks, 320, 240, 200.0 * 160, 1.0)!!
    assertEquals(5.0 / 320, fit.tx, 1e-9)
    assertEquals(-2.0 / 240, fit.ty, 1e-9)
  }

  @Test fun insufficientNarrowCollinearAndInconsistentTracksAreUnknown() {
    val tracks = grid { x, y -> FlowTrack(x, y, x + 5, y - 2) }
    assertNull(FlowTransformFitter.fit(tracks.take(7), 320, 240, 320.0 * 240, 1.0))
    assertNull(FlowTransformFitter.fit(tracks, 320, 240, 3200.0 * 2400, 1.0))
    assertNull(FlowTransformFitter.fit((0..20).map { FlowTrack(it * 10.0, it * 10.0, it * 10.0 + 5, it * 10.0) },
      320, 240, 200.0 * 160, 1.0))
    assertNull(FlowTransformFitter.fit(tracks.mapIndexed { i, p -> p.copy(u = p.u + (i % 3) * 30, v = p.v + (i % 4) * 40) },
      320, 240, 200.0 * 160, 1.0))
  }

  @Test fun historyComposesOnlyContiguousIntervalsAndClearsOnUnknown() {
    val history = FlowQuadMotion()
    val shift = FlowTransform(1.0, 0.0, 0.0, 1.0, .02, -.01)
    history.add(estimate(0, 33, shift))
    history.add(estimate(33, 66, shift))
    assertEquals(.24, history.warp(quad, 0, 66_000_000)!!.topLeft.x, 1e-9)
    assertNull(history.warp(quad, 10_000_000, 66_000_000))
    assertNull(history.warp(quad, 0, 100_000_000))
    history.add(estimate(66, 100, null))
    assertNull(history.warp(quad, 0, 66_000_000))
    history.add(estimate(100, 133, shift))
    assertEquals(.22, history.warp(quad, 100_000_000, 133_000_000)!!.topLeft.x, 1e-9)
    history.add(estimate(200, 233, shift))
    assertNull(history.warp(quad, 100_000_000, 233_000_000))
    history.clear()
    assertNull(history.warp(quad, 200_000_000, 233_000_000))
  }

  @Test fun historyIsBoundedByTime() {
    val history = FlowQuadMotion()
    val identity = FlowTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    for (i in 0..20) history.add(estimate(i * 33L, (i + 1) * 33L, identity))
    assertNull(history.warp(quad, 0, 693_000_000))
    assertEquals(quad, history.warp(quad, 660_000_000, 693_000_000))
  }

  private fun estimate(fromMs: Long, toMs: Long, transform: FlowTransform?) = FlowMotionEstimate(
    FlowMotionStatus.MEASURED, 0.0, 320, fromMs * 1_000_000, toMs * 1_000_000, 1.0, 25, transform = transform)

  private fun grid(map: (Double, Double) -> FlowTrack) = (0..4).flatMap { y ->
    (0..4).map { x -> map(60.0 + x * 50, 40.0 + y * 40) }
  }
}
