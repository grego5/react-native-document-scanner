package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

/** Analysis-owner adapter. The detector shares its native owner with guide acceptance. */
internal class AdaptiveDetectionPolicy(private val guide: LiveQuadStabilizer = LiveQuadStabilizer()) : AutoCloseable {
  val hasReference: Boolean get() = guide.native.hasReferenceNative()
  fun reference(quad: DocumentQuad?) = guide.native.referenceNative(quad?.motionValues())
  fun doubt(quad: DocumentQuad?, width: Int, height: Int): String? = guide.native.doubtNative(quad?.motionValues(), width, height)
  val status: String get() = guide.native.statusNative(false)
  val lastReset: String get() = guide.native.statusNative(true)
  val paused: Boolean get() = status == "paused"
  fun reset() = guide.native.adaptiveResetNative()
  fun confirmed() = guide.native.confirmedNative()
  fun interval(now: Long, trusted: Boolean, tracked: Boolean, degraded: Boolean, doubt: String? = null): Long =
    guide.native.intervalNative(trusted, tracked, degraded, doubt)
  companion object {
    fun changed(reference: DocumentQuad, current: DocumentQuad, width: Int, height: Int): Boolean =
      NativeLiveGuide.changedNative(reference.motionValues(), current.motionValues(), width, height)
  }
  override fun close() = guide.close()
}
