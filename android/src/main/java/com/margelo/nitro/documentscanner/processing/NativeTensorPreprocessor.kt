package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy
import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Executor-confined scratch/kernels; bitmap and tensor storage are borrowed only during prepare. */
@DoNotStrip
internal class NativeTensorPreprocessor(
  inputWidth: Int = INPUT_SIZE,
  inputHeight: Int = INPUT_SIZE,
) : AutoCloseable {
  val inputBytes = CornerModelSize.resolve(inputWidth.toDouble(), inputHeight.toDouble()).inputBytes
  @DoNotStrip private val mHybridData: HybridData
  private var closed = false

  init {
    ReactNativeDocumentScannerOnLoad.initializeNative()
    mHybridData = initHybrid(inputWidth, inputHeight)
  }

  fun prepare(source: Bitmap, input: ByteBuffer) {
    check(!closed) { "The native preprocessor has been closed." }
    require(!source.isRecycled) { "The source bitmap has been recycled." }
    require(input.isDirect && !input.isReadOnly && input.order() == ByteOrder.nativeOrder()) {
      "The tensor buffer must be writable, direct and native-order."
    }
    require(input.position() == 0 && input.remaining() >= inputBytes) { "The tensor buffer is too small or not rewound." }
    prepareNative(source, input)
  }

  /** Prepares the full upright analysis crop. */
  fun prepare(source: ImageProxy, input: ByteBuffer) {
    check(!closed) { "The native preprocessor has been closed." }
    require(source.format == PixelFormat.RGBA_8888 && source.planes.size == 1) { "Expected one CameraX RGBA plane." }
    require(input.isDirect && !input.isReadOnly && input.order() == ByteOrder.nativeOrder())
    require(input.position() == 0 && input.remaining() >= inputBytes)
    val plane = source.planes[0]
    val buffer = plane.buffer
    val crop = source.cropRect
    val rotation = source.imageInfo.rotationDegrees
    prepareRgbaNative(buffer, buffer.position(), buffer.limit(), source.width, source.height,
      plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.right, crop.bottom, rotation, input)
  }

  override fun close() {
    if (!closed) {
      closed = true
      mHybridData.resetNative()
    }
  }

  @DoNotStrip private external fun initHybrid(inputWidth: Int, inputHeight: Int): HybridData
  @DoNotStrip private external fun prepareNative(source: Bitmap, input: ByteBuffer)
  @DoNotStrip private external fun prepareRgbaNative(source: ByteBuffer, position: Int, limit: Int,
    width: Int, height: Int, rowStride: Int, pixelStride: Int, left: Int, top: Int,
    right: Int, bottom: Int, rotation: Int, input: ByteBuffer)

  companion object {
    const val INPUT_SIZE = 256
    const val INPUT_BYTES = INPUT_SIZE * INPUT_SIZE * 3 * 4
  }
}
