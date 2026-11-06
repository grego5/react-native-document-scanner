package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

internal object LiveFrameBitmapOracle {
    internal fun uprightBitmap(image: ImageProxy): Bitmap {
      val bitmap = image.toBitmap() // CameraX handles the RGBA plane layout/row stride.
      try {
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val upright = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height(), matrix, false)
        if (upright !== bitmap) bitmap.recycle()
        return upright
      } catch (error: Throwable) {
        bitmap.recycle()
        throw error
      }
    }
  }
