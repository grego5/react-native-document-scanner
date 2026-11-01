package com.margelo.nitro.documentscanner.processing

import android.content.Context
import androidx.camera.core.ImageProxy
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.DocumentQuad

/**
 * One live inference frame as the model saw it: the analysis crop in sensor orientation, RGBA, [rotation] degrees
 * clockwise from upright. Quads are normalized upright crop coordinates.
 */
internal class LiveFrameSnapshot(
  val pixels: ByteArray,
  val width: Int,
  val height: Int,
  val rotation: Int,
  /** [System.nanoTime] when the frame's inference finished. */
  val recordedNs: Long,
  val sensorTimestampNs: Long,
  /** The model's outline, or null when it found no page. */
  val candidate: DocumentQuad?,
  val score: Double?,
  /** The model that made [candidate]. */
  val model: QuadModelStore.Selection,
)

/** An analysis frame's crop in sensor orientation, RGBA, [rotation] degrees clockwise from upright. */
internal class LiveFrame(val pixels: ByteArray, val width: Int, val height: Int, val rotation: Int)

/** Copies [image]'s crop, or null unless it is a single RGBA plane. */
internal fun copyCrop(image: ImageProxy): LiveFrame? {
  val plane = image.planes.singleOrNull() ?: return null
  if (plane.pixelStride != 4) return null
  val crop = image.cropRect
  val rowBytes = crop.width() * 4
  val pixels = ByteArray(rowBytes * crop.height())
  // A duplicate keeps the plane's own position, which the preprocessor reads.
  val buffer = plane.buffer.duplicate()
  val start = buffer.position()
  for (row in 0 until crop.height()) {
    buffer.position(start + (crop.top + row) * plane.rowStride + crop.left * 4)
    buffer.get(pixels, row * rowBytes, rowBytes)
  }
  return LiveFrame(pixels, crop.width(), crop.height(), image.imageInfo.rotationDegrees)
}

/**
 * Keeps the latest live inference, whatever it found, while sample collection is on; written on the analysis executor,
 * read on main. Turning collection off clears it.
 */
internal class LiveFrameRecorder(private val context: Context) {
  private val lock = Any()
  private var latest: LiveFrameSnapshot? = null

  init {
    SampleCollection.register(this)
  }

  fun record(
    image: ImageProxy,
    sensorTimestampNs: Long,
    candidate: DocumentCandidate?,
    model: QuadModelStore.Selection,
  ) {
    val generation = SampleCollection.whileOn(context) {} ?: return
    val frame = copyCrop(image) ?: return
    val snapshot = LiveFrameSnapshot(frame.pixels, frame.width, frame.height, frame.rotation,
      System.nanoTime(), sensorTimestampNs, candidate?.quad, candidate?.score, model)
    // Collection turning off during the copy voids it.
    SampleCollection.whileOn(context, generation) { synchronized(lock) { latest = snapshot } }
  }

  fun latest(): LiveFrameSnapshot? = synchronized(lock) { latest }

  fun clear() = synchronized(lock) { latest = null }
}
