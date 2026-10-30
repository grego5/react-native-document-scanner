package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

/** Pixel-free metadata for one live inference and its acceptance decision. Crop is in sensor buffer pixels. */
internal data class LiveQuadObservation(
  val generation: Long,
  val sensorTimestampNs: Long,
  val arrivalNs: Long,
  val width: Int,
  val height: Int,
  val cropLeft: Int,
  val cropTop: Int,
  val cropRight: Int,
  val cropBottom: Int,
  val rotationDegrees: Int,
  val rawQuad: DocumentQuad?,
  val rawScore: Double?,
  val status: LiveQuadStatus,
  val decision: LiveQuadDecision,
  val chainLength: Int,
  val expiredBeforeInput: Boolean,
  val accepted: DocumentQuad?,
  val acceptanceId: Long,
  val holdDeadlineNs: Long,
  val preprocessingMs: Double,
  val inferenceMs: Double,
  val stabilizerNs: Long,
  /** False when the raw prediction is ineligible for guide confirmation, including low confidence. */
  val guideEligible: Boolean = true,
  /** Tracked-guide heartbeat, not an inference or model confirmation. */
  val trackingOnly: Boolean = false,
  val flowRetained: Boolean = false,
  /** Adaptive inference pause on a motion-eligible frame, not a cadence or blur skip. */
  val inferencePaused: Boolean = false,
)

/** Pixel-free metadata for every analysis frame, analyzed or skipped. Camera fields are null when the frame's capture
 * result had not arrived. [flow] is this frame's image-motion estimate, null while estimation is off; [flowAgeNs] is its delay from exposure start to
 * publication (REALTIME cameras only) and [flowProcessingNs] the estimator's time on this frame.
 */
internal data class LiveFrameSample(
  val sensorTimestampNs: Long,
  val arrivalNs: Long,
  val exposureNs: Long?,
  val rollingShutterSkewNs: Long?,
  val afState: Int?,
  val opticalStabilization: Boolean?,
  val focusDiopters: Double?,
  val analyzed: Boolean,
  val flow: FlowMotionEstimate? = null,
  val flowAgeNs: Long? = null,
  val flowProcessingNs: Long? = null,
  val inferenceReason: String = "unknown",
  val inferenceGapNs: Long = 0,
  val flowReliable: Boolean = false,
  val predictedQuad: DocumentQuad? = null,
  val adaptiveDoubt: String? = null,
  val focusing: Boolean = false,
  val provisional: Boolean = false,
  val blur: Double? = null,

)

/** The capture gate's final evaluation: `takePicture` was called ([fired]) or the steady wait ran out. Times are on the
 * gyroscope clock. Gate fields are null when the motion limit is off; blur can also be unknown without metadata.
 * [gateSource] names the estimate the gate judged. [flow] is the published image-motion estimate it read, whether or
 * not the gate used it; [flowAgeNs] is the time since its publication and [flowSourceAgeNs] the age of its source
 * frame, which freshness judges.
 */
internal data class LiveCaptureGateSample(
  val sensorClockNs: Long,
  val fired: Boolean = true,
  val gateEvaluationNs: Long? = null,
  val gateExposureNs: Long? = null,
  val gateBlur: Double? = null,
  val gateSource: MotionSource? = null,
  val flow: FlowMotionEstimate? = null,
  val flowAgeNs: Long? = null,
  val flowSourceAgeNs: Long? = null,
)

/** Called on the live analysis executor, except [onCaptureGate] on main; must not block. Test harnesses only. */
internal fun interface LiveQuadTraceObserver {
  fun onObservation(observation: LiveQuadObservation)

  /** Disable frame sampling without replacing the observer or resetting confirmation history. */
  val frameSamplesEnabled: Boolean get() = true

  fun onFrame(sample: LiveFrameSample) = Unit

  /** The capture gate fired `takePicture` or rejected the capture. */
  fun onCaptureGate(sample: LiveCaptureGateSample) = Unit
}
