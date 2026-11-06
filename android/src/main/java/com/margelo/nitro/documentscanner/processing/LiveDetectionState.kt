package com.margelo.nitro.documentscanner.processing

/** Generation and throttle policy shared by camera callbacks and the analysis executor. */
internal class LiveDetectionState {
  private var generation = 0L
  private var active = false
  private var enabled = true
  private var failed = false
  private var disposed = false
  private var lastScheduledNs: Long? = null

  @Synchronized fun begin(enable: Boolean) {
    check(!disposed)
    generation++
    active = true
    enabled = enable
    failed = false
    lastScheduledNs = null
  }

  @Synchronized fun setEnabled(enable: Boolean) {
    if (enabled == enable) return
    enabled = enable
    invalidate()
  }

  @Synchronized fun invalidate() {
    generation++
    lastScheduledNs = null
  }

  @Synchronized fun modelChanged(): Long {
    failed = false
    invalidate()
    return generation
  }

  /** Admits a frame at least [minGapNs] after the previous one and returns its generation, or null to skip it. */
  @Synchronized fun acquire(nowNs: Long, minGapNs: Long = TRACKING_GAP_NS): Long? {
    if (!active || !enabled || failed || disposed) return null
    val previous = lastScheduledNs
    if (previous != null && nowNs - previous < minGapNs) return null
    lastScheduledNs = nowNs
    return generation
  }

  @Synchronized fun isCurrent(token: Long): Boolean = !disposed && active && enabled && generation == token

  @Synchronized fun fail(token: Long): Boolean {
    if (!isCurrent(token)) return false
    failed = true
    return true
  }

  @Synchronized fun hasFailed(): Boolean = failed
  /** Whether optional guide-only work is useful, independent of inference throttling. */
  @Synchronized fun guidanceEnabled(): Boolean = active && enabled && !failed && !disposed

  @Synchronized fun stop() {
    active = false
    invalidate()
  }

  @Synchronized fun dispose() {
    disposed = true
    stop()
  }

  companion object {
    /** About 15 Hz while a document is tracked or held; acquisition takes every frame. */
    const val TRACKING_GAP_NS = 60_000_000L
  }
}
