package com.margelo.nitro.documentscanner.processing

import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad

/** Serial main-thread owner; sensor metadata uses only the independent, synchronized learned-position store. */
@DoNotStrip
internal class NativeFocus : AutoCloseable {
  @DoNotStrip private val mHybridData: HybridData
  init { ReactNativeDocumentScannerOnLoad.initializeNative(); mHybridData = initHybrid() }
  @DoNotStrip private external fun initHybrid(): HybridData
  @DoNotStrip external fun observeNative(quad: DoubleArray?, trusted: Boolean, failed: Boolean, now: Long, blocked: Boolean, parked: Boolean): Int
  @DoNotStrip external fun documentResetNative()
  @DoNotStrip external fun documentSettledNative(result: Int, now: Long)
  @DoNotStrip external fun startNative(owner: Int, now: Long): Long
  @DoNotStrip external fun completeNative(token: Long, result: Int, now: Long): Boolean
  @DoNotStrip external fun releaseDocumentNative(shot: Boolean): Boolean
  @DoNotStrip external fun expireTapNative(token: Long, now: Long, shot: Boolean): Boolean
  @DoNotStrip external fun afterCaptureNative(now: Long, wanted: Boolean): Boolean
  @DoNotStrip external fun resetNative(keep: Boolean, shot: Boolean): Boolean
  @DoNotStrip external fun unbindNative()
  @DoNotStrip external fun snapshotNative(): LongArray
  @DoNotStrip external fun deadlinesNative(now: Long): LongArray
  @DoNotStrip external fun wakeNative(now: Long, shot: Boolean): Int
  override fun close() = mHybridData.resetNative()
  companion object {
    init { ReactNativeDocumentScannerOnLoad.initializeNative() }
    @JvmStatic @DoNotStrip external fun learnNative(value: Double, present: Boolean)
    @JvmStatic @DoNotStrip external fun learnedNative(): DoubleArray?
  }
}
