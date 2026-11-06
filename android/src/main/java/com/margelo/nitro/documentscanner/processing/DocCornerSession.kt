package com.margelo.nitro.documentscanner.processing

import android.content.Context
import android.graphics.Bitmap
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType

/** Confined to its owner's single analysis executor. Does not retain the source bitmap. */
internal class DocCornerSession(
  context: Context,
  assetName: String = BUNDLED_ASSET,
  modelBytes: ByteArray? = null,
) : LiveQuadModel {
  val size: CornerModelSize
  private val model = (modelBytes ?: context.assets.open(assetName).use { it.readBytes() }).let { bytes ->
    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
  }
  private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
  private val input: ByteBuffer
  private val output = Array(1) { FloatArray(9) }
  private val preprocessor: NativeTensorPreprocessor
  private var closed = false
  override var lastPreprocessingMs = 0.0
    private set
  override var lastInferenceMs = 0.0
    private set
  override var lastScore: Double? = null
    private set

  init {
    try {
      check(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) { "Unexpected DocCornerNet tensor count." }
      val tensor = interpreter.getInputTensor(0)
      val outputTensor = interpreter.getOutputTensor(0)
      val dimensions = NativeTensorPreprocessor.validateTensorsNative(interpreter.inputTensorCount, interpreter.outputTensorCount,
        tensor.dataType() == DataType.FLOAT32, outputTensor.dataType() == DataType.FLOAT32, tensor.shape(), outputTensor.shape())
      size = CornerModelSize.resolve(dimensions[0].toDouble(), dimensions[1].toDouble())
      input = ByteBuffer.allocateDirect(size.inputBytes).order(ByteOrder.nativeOrder())
      preprocessor = NativeTensorPreprocessor(size.width, size.height)
    } catch (error: Throwable) {
      interpreter.close()
      throw error
    }
  }

  fun analyze(source: Bitmap): DocumentCandidate? = analyzePrepared { preprocessor.prepare(source, input) }

  override fun analyze(image: androidx.camera.core.ImageProxy): DocumentCandidate? =
    analyzePrepared { preprocessor.prepare(image, input) }

  /** One inference on a blank input, so the interpreter's first-run setup is paid before the first frame. */
  override fun warmUp() {
    check(!closed) { "The DocCornerNet session is closed." }
    input.clear()
    while (input.hasRemaining()) input.putFloat(0f)
    input.rewind()
    interpreter.run(input, output)
  }

  private inline fun analyzePrepared(prepare: () -> Unit): DocumentCandidate? {
    check(!closed) { "The DocCornerNet session is closed." }
    val startedAt = System.nanoTime()
    input.clear()
    prepare()
    input.rewind()
    val inferenceStartedAt = System.nanoTime()
    lastPreprocessingMs = (inferenceStartedAt - startedAt) / 1_000_000.0
    interpreter.run(input, output)
    lastInferenceMs = (System.nanoTime() - inferenceStartedAt) / 1_000_000.0
    return decodeOutput(output[0]).also { lastScore = output[0][8].toDouble() }
  }

  override fun close() {
    if (!closed) {
      closed = true
      preprocessor.close()
      interpreter.close()
    }
  }

  companion object {
    const val BUNDLED_ASSET = "doccornernet/model.tflite"
    const val BUNDLED_INPUT_SIZE = 224

    fun decodeOutput(values: FloatArray): DocumentCandidate? {
      val decoded = NativeTensorPreprocessor.decodeOutputNative(values) ?: return null
      val points = (0..3).map { DocumentPoint(decoded[it * 2], decoded[it * 2 + 1]) }
      return DocumentCandidate(DocumentQuad(points[0], points[1], points[2], points[3]), decoded[8])
    }

  }
}
