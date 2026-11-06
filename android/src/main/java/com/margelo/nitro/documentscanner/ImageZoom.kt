package com.margelo.nitro.documentscanner

import kotlin.math.max
import kotlin.math.min

/** A scale over the contain fit, and the image center's offset from the view center, in view pixels. */
internal data class ZoomState(val zoom: Float, val panX: Float, val panY: Float) {
  companion object {
    val FIT = ZoomState(1f, 0f, 0f)
  }
}

/**
 * Zoom and pan of an image contain-fitted to a view, between the fit and [maxZoom]. The pan stops at the image's
 * edges: along an axis the zoomed image fills, it keeps the view covered; along the others, it stays centered.
 */
internal class ImageZoom(private val maxZoom: Float) {
  var state = ZoomState.FIT
    private set
  private var viewWidth = 0f
  private var viewHeight = 0f
  private var fitWidth = 0f
  private var fitHeight = 0f

  // The image's place in the view at the current zoom.
  val width get() = fitWidth * state.zoom
  val height get() = fitHeight * state.zoom
  val left get() = viewWidth / 2 + state.panX - width / 2
  val top get() = viewHeight / 2 + state.panY - height / 2

  /** Sets the view and image sizes; false while either is empty. */
  fun layout(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Boolean {
    if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return false
    val fit = min(viewWidth / imageWidth, viewHeight / imageHeight)
    this.viewWidth = viewWidth
    this.viewHeight = viewHeight
    fitWidth = imageWidth * fit
    fitHeight = imageHeight * fit
    state = clamped(state)
    return true
  }

  fun set(next: ZoomState) {
    state = clamped(next)
  }

  /** Scales by [factor], keeping the image point under ([x], [y]) in place. */
  fun scaleAround(factor: Float, x: Float, y: Float) {
    state = clamped(around(state.zoom * factor, x, y))
  }

  fun panBy(dx: Float, dy: Float) {
    state = clamped(state.copy(panX = state.panX + dx, panY = state.panY + dy))
  }

  /** A double tap's target: back to the fit when zoomed in, otherwise [zoom] around ([x], [y]). */
  fun doubleTapTarget(x: Float, y: Float, zoom: Float): ZoomState =
    if (state.zoom > 1f + ZOOMED_EPSILON) ZoomState.FIT else clamped(around(zoom, x, y))

  private fun around(zoom: Float, x: Float, y: Float): ZoomState {
    val next = zoom.coerceIn(1f, maxZoom)
    val ratio = next / state.zoom
    val centerX = viewWidth / 2 + state.panX
    val centerY = viewHeight / 2 + state.panY
    return ZoomState(next, x - (x - centerX) * ratio - viewWidth / 2, y - (y - centerY) * ratio - viewHeight / 2)
  }

  private fun clamped(next: ZoomState): ZoomState {
    val zoom = next.zoom.coerceIn(1f, maxZoom)
    val reachX = max(0f, (fitWidth * zoom - viewWidth) / 2)
    val reachY = max(0f, (fitHeight * zoom - viewHeight) / 2)
    // Adding zero turns a -0 pan into 0, so a clamped fit equals FIT.
    return ZoomState(zoom, next.panX.coerceIn(-reachX, reachX) + 0f, next.panY.coerceIn(-reachY, reachY) + 0f)
  }

  private companion object {
    const val ZOOMED_EPSILON = 0.01f
  }
}
