package com.margelo.nitro.documentscanner.processing

import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad

/** One analysis-executor owner for guide acceptance; synchronous value-only calls. */
@DoNotStrip
internal class NativeLiveGuide : AutoCloseable {
  private var closed = false
  @DoNotStrip private val mHybridData: HybridData
  init { ReactNativeDocumentScannerOnLoad.initializeNative(); mHybridData = initHybrid() }
  fun ready() { check(!closed) { "The live guide is closed." } }
  override fun close() { if (!closed) { closed = true; mHybridData.resetNative() } }
  @DoNotStrip private external fun initHybrid(): HybridData
  @DoNotStrip external fun configureNative(area: Double, hold: Long)
  @DoNotStrip external fun resetNative()
  @DoNotStrip external fun updateNative(quad: DoubleArray?, width: Int, height: Int, sensor: Long, arrival: Long,
    retain: Boolean, metadata: LongArray): DoubleArray?
  companion object {
    init { ReactNativeDocumentScannerOnLoad.initializeNative() }
    @JvmStatic @DoNotStrip external fun scoreRejectedNative(score: Double, hasScore: Boolean, minimum: Double): Boolean
    @JvmStatic @DoNotStrip external fun keepScorePauseNative(current: Boolean, speed: Double, measured: Boolean, width: Int): Boolean
  }
}
