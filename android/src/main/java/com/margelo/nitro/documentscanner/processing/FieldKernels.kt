package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Small float maps applied over a full-size page, natively in one pass with rows spread over the cores. Each map is
 * enlarged bilinearly as Imgproc.resize with INTER_LINEAR would, a row at a time, so no full-size map is allocated.
 * Separate OpenCV passes, over 16-row strips or a full-size enlarged map, took four to seven times as long.
 */
internal object FieldKernels {
  /**
   * Sharpens each 8-bit channel of [channels] in place against its blur in [blurred] and deepens its ink:
   * x + a × (x − blur) − k × ink, with a from [amount], k from [deepen] and [ink] per pixel, absent when null.
   */
  fun ink(channels: List<Mat>, blurred: List<Mat>, ink: Mat?, deepen: Mat, amount: Mat) {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    inkNative(
      LongArray(channels.size) { channels[it].nativeObj }, LongArray(blurred.size) { blurred[it].nativeObj },
      ink?.nativeObj ?: 0L, deepen.nativeObj, amount.nativeObj,
    )
  }

  /** Multiplies the colour channels of the 8-bit RGBA [rgba] in place by [gain]; alpha is unchanged. */
  fun gain(rgba: Mat, gain: Mat) {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    gainNative(rgba.nativeObj, gain.nativeObj)
  }

  /**
   * Divides each channel c of the 8-bit [source], one or three, by the paper and multiplies it by [scales][c], into the
   * 8-bit [into][c]; with one channel, [into] may hold [source] itself. The paper is the float [level] enlarged and
   * rounded to 8 bits, as Imgproc.resize and convertTo would; where it is zero the result is zero, as Core.divide.
   */
  fun divide(source: Mat, into: List<Mat>, level: Mat, scales: DoubleArray) {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    into.forEach { it.create(source.rows(), source.cols(), CvType.CV_8U) }
    divideNative(source.nativeObj, LongArray(into.size) { into[it].nativeObj }, level.nativeObj, FloatArray(scales.size) { scales[it].toFloat() })
  }

  /**
   * Writes into [into] the gray of the 8-bit BGR [source] balanced by [gains], [weights] times its channels each clipped
   * at white, rounded to 8 bits, divided by the paper as [divide] does and multiplied by [scale]. Up to rounding, the gray
   * of the balanced page made in its own pass.
   */
  fun grayDivide(source: Mat, into: Mat, level: Mat, weights: DoubleArray, gains: DoubleArray, scale: Double) {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    into.create(source.rows(), source.cols(), CvType.CV_8U)
    grayDivideNative(source.nativeObj, into.nativeObj, level.nativeObj, FloatArray(3) { weights[it].toFloat() },
      FloatArray(3) { gains[it].toFloat() }, scale.toFloat())
  }

  @JvmStatic @DoNotStrip private external fun divideNative(source: Long, into: LongArray, level: Long, scales: FloatArray)

  @JvmStatic @DoNotStrip private external fun grayDivideNative(source: Long, into: Long, level: Long, weights: FloatArray, gains: FloatArray, scale: Float)

  @JvmStatic @DoNotStrip private external fun inkNative(channels: LongArray, blurred: LongArray, ink: Long, deepen: Long, amount: Long)

  @JvmStatic @DoNotStrip private external fun gainNative(rgba: Long, gain: Long)
}
