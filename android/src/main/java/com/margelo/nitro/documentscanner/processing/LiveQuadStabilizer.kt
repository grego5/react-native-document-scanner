package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

internal data class LiveQuadStabilizerConfig(
  val algorithmVersion: Int = 2,
  val minFrameAreaFraction: Double = 0.03,
  val holdNs: Long = 400_000_000,

)

/** [width] and [height] are upright frame dimensions; [arrivalNs] is monotonic processing time. */
internal data class LiveQuadInput(
  val quad: DocumentQuad?,
  val width: Int,
  val height: Int,
  val sensorTimestampNs: Long,
  val arrivalNs: Long,
)

internal enum class LiveQuadStatus { SEARCHING, TRACKING, HELD }

internal enum class LiveQuadDecision {
  ACQUIRING, ACQUIRED, AGREED, CHALLENGING, REPLACED, MISSING, TOO_SMALL, INVALID, NOT_RECTANGLE, TOO_TILTED, GAP_RESTART,
  DUPLICATE_TIMESTAMP, TIMESTAMP_BACKWARD,
}

/** [accepted] is non-null only for tracking and held outlines. */
internal data class LiveQuadOutput(
  val status: LiveQuadStatus,
  val decision: LiveQuadDecision,
  val accepted: DocumentQuad?,
  val acceptanceId: Long,
  val holdDeadlineNs: Long,
  val chainLength: Int,
  val expiredBeforeInput: Boolean,
)

/** Thin analysis-owner adapter; acceptance history is owned by the portable core. */
internal class LiveQuadStabilizer(config: LiveQuadStabilizerConfig = LiveQuadStabilizerConfig()) : AutoCloseable {
  private var closed = false
  private val owner = lazy { NativeLiveGuide().also { it.configureNative(this.config.minFrameAreaFraction, this.config.holdNs) } }
  internal val native: NativeLiveGuide get() { check(!closed); return owner.value.also { it.ready() } }
  var config = config
    set(value) {
      if (value == field) return
      field = value
      if (owner.isInitialized()) native.configureNative(value.minFrameAreaFraction, value.holdNs)
    }
  fun reset() { if (owner.isInitialized()) native.resetNative() }
  fun update(input: LiveQuadInput): LiveQuadOutput = call(input, false)!!
  fun retainTracked(input: LiveQuadInput): LiveQuadOutput? = call(input, true)
  private fun call(input: LiveQuadInput, retain: Boolean): LiveQuadOutput? {
    val metadata = LongArray(2)
    val v = native.updateNative(input.quad?.motionValues(), input.width, input.height, input.sensorTimestampNs,
      input.arrivalNs, retain, metadata) ?: return null
    return LiveQuadOutput(LiveQuadStatus.entries[v[0].toInt()], LiveQuadDecision.entries[v[1].toInt()],
      if (v.size == 12) v.copyOfRange(4, 12).motionQuad() else null, metadata[0], metadata[1], v[2].toInt(), v[3] != 0.0)
  }
  override fun close() { if (!closed) { closed = true; if (owner.isInitialized()) owner.value.close() } }
}
