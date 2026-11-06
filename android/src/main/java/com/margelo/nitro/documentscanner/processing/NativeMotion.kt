package com.margelo.nitro.documentscanner.processing

import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import java.nio.ByteBuffer

internal fun DocumentQuad.motionValues() = doubleArrayOf(topLeft.x, topLeft.y, topRight.x, topRight.y,
  bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y)
internal fun DoubleArray.motionQuad() = DocumentQuad(DocumentPoint(this[0], this[1]), DocumentPoint(this[2], this[3]),
  DocumentPoint(this[4], this[5]), DocumentPoint(this[6], this[7]))
internal fun DoubleArray.motionTransform(offset: Int = 0) = FlowTransform(this[offset], this[offset + 1], this[offset + 2],
  this[offset + 3], this[offset + 4], this[offset + 5], this[offset + 6], this[offset + 7])

@DoNotStrip
internal object NativeMotion {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }
  @JvmStatic @DoNotStrip external fun fitNative(tracks: DoubleArray, width: Int, height: Int, area: Double, tolerance: Double): DoubleArray?
  @JvmStatic @DoNotStrip external fun warpNative(transform: DoubleArray, quad: DoubleArray): DoubleArray?
  @JvmStatic @DoNotStrip external fun gyroNative(times: LongArray, rates: DoubleArray, from: Long, to: Long,
    correlation: LongArray?, operation: Int, focal: Double, width: Int, exposure: Long): DoubleArray?
  @JvmStatic @DoNotStrip external fun geometryNative(optics: DoubleArray, dimensions: IntArray): DoubleArray
  @JvmStatic @DoNotStrip external fun rotationWarpNative(quad: DoubleArray, frame: DoubleArray, rotation: DoubleArray): DoubleArray?
  @JvmStatic @DoNotStrip external fun gateNative(speed: DoubleArray?, width: Int, lost: Int, exposure: Long, gyro: DoubleArray?): DoubleArray?
}

/** Confined to the analysis executor, including reset and close. HybridData owns all native state. */
@DoNotStrip
internal class NativeFlowEstimator(edge: Int, features: Int, gap: Long) : AutoCloseable {
  @DoNotStrip private val mHybridData: HybridData
  private var closed = false
  init {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    mHybridData = initHybrid(edge, features, gap)
  }
  fun reset() { check(!closed) { "The motion estimator is closed." }; resetNative() }
  fun update(pixels: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
    left: Int, top: Int, right: Int, bottom: Int, rotation: Int, sensor: Long, region: DocumentQuad?, fit: Boolean): FlowMotionEstimate {
    check(!closed) { "The motion estimator is closed." }
    val times = LongArray(2)
    val v = updateNative(pixels, pixels.position(), pixels.limit(), width, height, rowStride, pixelStride,
      left, top, right, bottom, rotation, sensor, region?.motionValues(), fit, times)
    return FlowMotionEstimate(FlowMotionStatus.entries[v[0].toInt()], v[1].takeUnless { it.isNaN() }, width,
      times[0], times[1], v[2], v[3].toInt(), v[4].toInt(),
      if (v.size == 14) v.motionTransform(6) else null, v[5].takeUnless { it.isNaN() }?.toLong())
  }
  override fun close() { if (!closed) { closed = true; mHybridData.resetNative() } }
  @DoNotStrip private external fun initHybrid(edge: Int, features: Int, gap: Long): HybridData
  @DoNotStrip private external fun resetNative()
  @DoNotStrip private external fun updateNative(pixels: ByteBuffer, position: Int, limit: Int, width: Int, height: Int,
    rowStride: Int, pixelStride: Int, left: Int, top: Int, right: Int, bottom: Int, rotation: Int, sensor: Long,
    region: DoubleArray?, fit: Boolean, times: LongArray): DoubleArray
}

/** Lazily created and released on the same analysis executor as the estimator. */
@DoNotStrip
internal class NativeFlowHistory : AutoCloseable {
  @DoNotStrip private val mHybridData: HybridData
  init {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    mHybridData = initHybrid()
  }
  @DoNotStrip private external fun initHybrid(): HybridData
  @DoNotStrip external fun clearNative()
  @DoNotStrip external fun addNative(from: Long, to: Long, transform: DoubleArray?)
  @DoNotStrip external fun warpNative(quad: DoubleArray, from: Long, to: Long): DoubleArray?
  override fun close() = mHybridData.resetNative()
}
