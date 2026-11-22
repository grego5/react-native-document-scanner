package com.margelo.nitro.documentscanner

/**
 * Main thread: the text camera's zoom requests. [submitted] is the ratio last asked of the camera, so the same `zoom` is
 * not asked again on every prop update, even after the camera refused it. Each request has its own id, so only the
 * newest one's answers count. A request settles once the camera has answered it and the first frame at its ratio is
 * known: [settled] is then the ratio the camera has, which keys the lens memory, and [readFromNs] the sensor time from
 * which frames show it. A request the camera refuses keeps the ratio its capture results show, or else
 * [lastConfirmed], the one it last confirmed.
 */
internal class TextZoom {
  class Request(val ratio: Float, val id: Long)

  var submitted: Float? = null
    private set
  /** The ratio the camera last confirmed, kept while a newer request is pending; null before any this binding. */
  var lastConfirmed: Float? = null
    private set
  private var current: Request? = null
  private var confirmed: Float? = null
  private var fromNs: Long? = null
  private var ids = 0L

  /** The ratio the camera has for the newest request, once the frames at it are known; null while it is pending. */
  val settled: Float? get() = confirmed?.takeIf { fromNs != null }

  /** Frames exposed from this sensor time on show [settled]; none are while a request is pending. */
  val readFromNs: Long get() = when {
    current == null -> Long.MIN_VALUE
    settled == null -> Long.MAX_VALUE
    else -> fromNs!!
  }

  /** A request for [ratio], or null when [ratio] is the one last submitted. */
  fun request(ratio: Float): Request? {
    if (ratio == submitted) return null
    submitted = ratio
    confirmed = null
    fromNs = null
    return Request(ratio, ++ids).also { current = it }
  }

  /**
   * The camera applied [request]. Frames from [fallbackFromNs] show it when capture results cannot tell, null when they
   * can and [seen] gives the first. False when a newer request or [reset] superseded it, or it was answered already.
   */
  fun settle(request: Request, fallbackFromNs: Long?): Boolean {
    if (request !== current || confirmed != null) return false
    confirmed = request.ratio
    lastConfirmed = request.ratio
    if (fromNs == null) fromNs = fallbackFromNs
    return true
  }

  /** The camera refused [request] and kept [kept], as its capture results show, or else [lastConfirmed]; frames from
   * [fromNs] count, since the view did not change. */
  fun refuse(request: Request, kept: Float?, fromNs: Long): Boolean {
    if (request !== current || confirmed != null) return false
    val ratio = kept ?: lastConfirmed ?: 1f
    confirmed = ratio
    lastConfirmed = ratio
    this.fromNs = fromNs
    return true
  }

  /** The first capture result at request [id]'s ratio, exposed at [sensorNs]; false for another request or a later one. */
  fun seen(id: Long, sensorNs: Long): Boolean {
    if (current?.id != id || fromNs != null) return false
    fromNs = sensorNs
    return true
  }

  /** Forgets every request, as when the camera unbinds; answers still on their way settle nothing. */
  fun reset() {
    submitted = null
    lastConfirmed = null
    current = null
    confirmed = null
    fromNs = null
  }
}
