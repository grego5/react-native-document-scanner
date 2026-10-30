package com.margelo.nitro.documentscanner.processing

import android.content.Context
import android.graphics.Rect
import android.graphics.PixelFormat
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.DocumentQuad
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal enum class LiveGuideStatus { SEARCHING, DETECTED, TRACKING, HELD, UNAVAILABLE }

/** Main-thread focus hint without image ownership; the quad is upright and normalized to [source]. */
internal data class LiveFocusCandidate(
  val quad: DocumentQuad?, val source: OutputTransform, val width: Int, val height: Int,
  val trusted: Boolean, val focusFailed: Boolean,
)

/** [quad] and the [source] transform and dimensions captured at its acceptance are set for DETECTED, TRACKING and HELD; DETECTED is visible but not confirmed for focus or capture;
 * [score] is this frame's
 * guide inference score, null for a frame without one, such as one optical flow alone carried.
 */
internal data class LiveDetectionResult(
  val status: LiveGuideStatus,
  val quad: DocumentQuad? = null,
  val source: OutputTransform? = null,
  val width: Int = 0,
  val height: Int = 0,
  val acceptanceId: Long = 0,
  val holdDeadlineNs: Long = 0,
  val generation: Long = 0,
  val analysisStartedNs: Long = 0,
  val score: Double? = null,
)

/** One-window averages over inferred frames. [cameraToAnalysisMs] is null unless camera timestamps are REALTIME;
 * [peakBlurPercent] is the window's largest motion-blur estimate, null without one, [gatedFrames] counts frames
 * skipped for blur or focusing, and [unmatchedFrames] counts gated-camera frames analyzed before their capture result
 * arrived.
 */
internal data class LiveDetectionStats(
  val rateHz: Double,
  val preprocessingMs: Double,
  val inferenceMs: Double,
  val analysisMs: Double,
  val cameraToAnalysisMs: Double?,
  val width: Int,
  val height: Int,
  val peakBlurPercent: Double?,
  val gatedFrames: Int,
  val unmatchedFrames: Int,
  /** Combined flow + stabilization/shape/boundary work per delivered analysis frame, including throttled frames. */
  val checksMsPerFrame: Double = 0.0,
  val adaptiveStatus: String = "off",
)

/** One frame's flow result and the timing used by analysis statistics and traces. Executor-confined. */
private data class FlowResult(
  val estimate: FlowMotionEstimate,
  val processingNs: Long,
  val latencyNs: Long?,
)

/** One executor/session for a camera view, independent from post-capture analysis.
 * Pixels and ImageProxy never cross into JS or escape analyze(). Call lifecycle methods on main.
 */
internal class LiveDocumentDetector(
  context: Context,
  private val debugLogging: Boolean,
  private val modelFactory: ((Context) -> LiveQuadModel)? = null,
  private val gyro: GyroHistory? = null,
  onResult: (LiveDetectionResult) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
  private val context = context.applicationContext
  val executor: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "DocumentScannerLive") }
  private val state = LiveDetectionState()
  private val handler = Handler(Looper.getMainLooper())
  private val callbackToken = Any()
  private val expiryToken = Any()
  private var onResult: ((LiveDetectionResult) -> Unit)? = onResult
  private var closed = false
  private val bindingLock = Any()
  @Volatile private var binding = 0L
  /** Optics of the bound back camera when its timestamps share the gyroscope clock; null disables motion compensation. */
  @Volatile internal var cameraOptics: LiveCameraOptics? = null
  /** Test-harness trace hook; observations are allocated only while it is set. */
  @Volatile internal var traceObserver: LiveQuadTraceObserver? = null
  /** Main-thread receiver of one-second [LiveDetectionStats] for on-device tuning; null skips them. */
  @Volatile internal var statsListener: ((LiveDetectionStats) -> Unit)? = null
  /** Independent main-thread focus owner receives document targets; focus never gates detection. */
  @Volatile internal var focusCandidateListener: ((LiveFocusCandidate) -> Unit)? = null
  /** Checks for the next analyzed frame; a change starts a fresh stabilizer, dropping its history. */
  @Volatile internal var stabilizerConfig = LiveQuadStabilizerConfig()
  /** Estimates image motion on every analysis frame, guide or not, before inference throttling and gating. */
  @Volatile internal var flowMotionEnabled = false
  /** Use image flow for capture motion records, with the gyroscope as fallback. */
  @Volatile internal var flowGate = false
  @Volatile internal var adaptiveDetection = false
  /** Raw predictions remain available for samples; this threshold only gates live acceptance. */
  @Volatile internal var minCornerScore = 0.95
  /** Experiment switch: disable pacing/adaptive pauses, preserving frame validity gates. */
  @Volatile internal var inferenceThrottling = true
  /** Pause inference above this framing speed, independent of exposure or autofocus. */
  @Volatile internal var maxInferenceSpeedFraction = .05
  /** Keeps each inference frame and its raw model output in [frameRecorder] while sample collection is on. */
  internal val frameRecorder = LiveFrameRecorder(context)
  private val frameRequest = AtomicReference<((LiveFrame) -> Unit)?>(null)

  /**
   * Hands the next analysis frame's crop to [receiver] on the analysis executor, before any gating. A newer request
   * replaces one still waiting, whose receiver is then never called.
   */
  fun requestFrame(receiver: (LiveFrame) -> Unit) = frameRequest.set(receiver)
  /** Guarded by [bindingLock]; changes whenever flow and frame history are invalidated. */
  private var flowGeneration = 0L
  /** Guarded by [bindingLock]; true from [begin] until [stop] or [close], which current-binding frames cannot see. */
  private var flowActive = false
  /** Filled by the camera's capture callback; empty without exposure metadata. */
  internal val frames = CameraFrameHistory()
  /** Analysis results for capture in frame order; cleared when flow is invalidated. */
  internal val frameHistory = FrameHistory()
  /** Coalesced main-thread callback after results are appended to [frameHistory]; set only while a reader waits. */
  @Volatile internal var frameAnalysisListener: (() -> Unit)? = null
  private val frameResultsToken = Any()
  // Executor-confined: the confirmed in-frame outline as of the last published result, and when its hold expires on
  // the System.nanoTime() clock of frame arrival, as main expires the guide; 0 holds it.
  private var captureGuide: DocumentQuad? = null
  private var captureGuideDeadlineNs = 0L
  // Main-thread-only acceptance delivery.
  private var deliveredAcceptanceId = 0L
  private var expiredAcceptanceId = 0L
  // Executor-confined from here down.
  private var session: LiveQuadModel? = null
  private var modelSelection = QuadModelStore.selected
  private val stabilizer = LiveQuadStabilizer()
  private var lastToken = -1L
  private val lastCrop = Rect()
  private var lastRotation = -1
  private var lastObserver: LiveQuadTraceObserver? = null
  private var acceptedId = 0L
  private var acceptedSource: OutputTransform? = null
  private var acceptedWidth = 0
  private var acceptedHeight = 0
  private var tracking = false
  private var appliedMinCornerScore = 0.95
  /** Generation of a low-score scene awaiting measured movement; null permits inference. */
  private var scorePausedGeneration: Long? = null
  private val adaptivePolicy = AdaptiveDetectionPolicy()
  private var adaptiveWasEnabled = false
  /** The last trusted model outline: the shape reference while flow alone tracks the page. */
  private var adaptiveReference: DocumentQuad? = null
  private var trackedRegionSensorNs = 0L
  private val transformFactory = ImageProxyTransformFactory().apply {
    isUsingCropRect = true
    isUsingRotationDegrees = true
  }
  private var windowStartedNs = 0L
  private var received = 0
  private var inferred = 0
  private var dropped = 0
  private var totalLatencyMs = 0.0
  private var totalPreprocessingMs = 0.0
  private var totalInferenceMs = 0.0
  private var totalChecksNs = 0L
  private var totalCameraMs = 0.0
  private var peakBlur: Double? = null
  private var gated = 0
  private var unmatched = 0
  private var lastWidth = 0
  private var lastHeight = 0
  private var lastDeliveryLogNs = 0L // Main-thread-only delivery instrumentation.
  private var flowEstimator: FlowMotionEstimator? = null
  private var flowFailed = false
  private var flowSeenGeneration = -1L
  private val pageMotion = FlowQuadMotion()
  /** The last accepted quad, with the crop and rotation it is normalized to; only selects where features are sought. */
  private var flowRegion: DocumentQuad? = null
  private val flowRegionCrop = Rect()
  private var flowRegionRotation = -1

  /** Runs only while a trace observer is set. */
  private fun frameSample(
    sensorTimestampNs: Long, arrivalNs: Long, frame: CameraFrameState?, analyzed: Boolean, flow: FlowResult?,
  ) = LiveFrameSample(
    sensorTimestampNs, arrivalNs, frame?.exposureNs, frame?.rollingShutterSkewNs, frame?.afState,
    frame?.opticalStabilization, frame?.focusDiopters?.toDouble(), analyzed,
    flow?.estimate, flow?.latencyNs, flow?.processingNs,
  )

  fun begin(enabled: Boolean) {
    synchronized(bindingLock) {
      binding++
      flowActive = true
      clearFlowLocked()
      state.begin(enabled)
    }
    if (enabled) preload()
  }
  fun boundAnalyzer(): ImageAnalysis.Analyzer {
    val boundGeneration = binding
    return ImageAnalysis.Analyzer { image -> analyzeForBinding(image, boundGeneration) }
  }
  fun setEnabled(enabled: Boolean) {
    state.setEnabled(enabled)
    removeCallbacks()
    if (enabled) preload()
  }

  /** Loads and warms the model while the camera opens rather than on its first frame. A failure is left to that
   * frame, which loads again and reports it.
   */
  private fun preload() {
    if (closed) return
    val generation = binding
    executor.execute {
      if (session != null || generation != binding || !state.guidanceEnabled()) return@execute
      session = try {
        (modelFactory?.invoke(context) ?: CornerInference.acquire(context)).also { model ->
          try { model.warmUp() } catch (error: Throwable) { model.close(); throw error }
        }
      } catch (_: Throwable) { null }
    }
  }
  fun invalidate() {
    synchronized(bindingLock) { clearFlowLocked() }
    state.invalidate()
    removeCallbacks()
  }
  fun hasFailed(): Boolean = state.hasFailed()
  internal fun hasPendingExpiry(): Boolean = handler.hasMessages(0, expiryToken)

  fun stop() {
    synchronized(bindingLock) {
      binding++
      flowActive = false
      clearFlowLocked()
      state.stop()
    }
    removeCallbacks()
    if (!closed) executor.execute { releaseSession(); releaseFlow() }
  }

  private fun clearFlowLocked() {
    flowGeneration++
    frameHistory.clear()
  }

  /** Stores this frame's analysis result for its binding, then notifies main that new results are available. */
  private fun recordFrameAnalysis(
    boundGeneration: Long, frameGeneration: Long, sensorTimestampNs: Long, arrivalNs: Long, realtime: Boolean, frame: CameraFrameState?,
    flow: FlowMotionEstimate?, motion: GateMotion?, gyroSpeed: Double?,
  ) {
    synchronized(bindingLock) {
      if (boundGeneration != binding || frameGeneration != flowGeneration || !flowActive) return
      frameHistory.append { sequence ->
        FrameRecord(sequence, sensorTimestampNs, sensorTimestampNs.takeIf { realtime }, arrivalNs, frame, flow,
          motion, gyroSpeed,
          captureGuide?.takeIf {
            state.guidanceEnabled() && (captureGuideDeadlineNs == 0L || arrivalNs < captureGuideDeadlineNs)
          })
      }
    }
    if (frameAnalysisListener == null) return
    handler.removeCallbacksAndMessages(frameResultsToken)
    handler.postAtTime({ frameAnalysisListener?.invoke() }, frameResultsToken, SystemClock.uptimeMillis())
  }

  override fun analyze(image: ImageProxy) = analyzeForBinding(image, binding)

  private fun analyzeForBinding(image: ImageProxy, boundGeneration: Long) {
    frameRequest.getAndSet(null)?.let { receiver -> copyCrop(image)?.let(receiver) }
    val minimumScore = minCornerScore
    if (minimumScore != appliedMinCornerScore) {
      appliedMinCornerScore = minimumScore
      invalidate()
      resetTracking()
      releaseFlow()
    }
    val selected = QuadModelStore.current
    if (selected !== modelSelection) {
      releaseSession()
      releaseFlow()
      val generation = state.modelChanged()
      publish(LiveDetectionResult(LiveGuideStatus.SEARCHING, generation = generation))
      acceptedId = 0L
      acceptedSource = null
      modelSelection = selected
    }
    val frameGeneration = synchronized(bindingLock) { flowGeneration }
    val startedAt = System.nanoTime()
    val startedRealtimeNs = SystemClock.elapsedRealtimeNanos()
    if (debugLogging && windowStartedNs == 0L) windowStartedNs = startedAt
    // Motion gates inference; exposure smear and focus affect capture readiness only.
    val optics = cameraOptics
    val sensorTimestampNs = image.imageInfo.timestamp
    // A frame whose own capture result has not arrived has unknown exposure and focus, so it is never gated.
    val frame = frames.at(sensorTimestampNs)
    // Before throttling and gating, so frames those skip still show the estimator when motion stops.
    val flow = if (flowMotionEnabled) updateFlow(image, boundGeneration, sensorTimestampNs, optics != null)
      else { pageMotion.clear(); null }
    totalChecksNs += flow?.processingNs ?: 0L
    // This frame's own flow estimate is current by construction; the gyroscope is read only when flow has no blur.
    val flowMotion = frame?.let { state -> flow?.estimate?.let { flowBlur(it, state.exposureNs) } }
    val gyroBlur = if (frame == null || flowGate && flowMotion != null) null else gyroBlur(optics, sensorTimestampNs, frame)
    val motion = frame?.let { gateMotion(flow?.estimate, it.exposureNs, gyroBlur) }
    val blur = if (flowGate) motion?.blur else gyroBlur
    if (blur != null) peakBlur = maxOf(peakBlur ?: 0.0, blur)
    val adaptive = adaptiveDetection && inferenceThrottling
    if (adaptive != adaptiveWasEnabled) {
      adaptivePolicy.reset()
      adaptiveReference = null
      adaptiveWasEnabled = adaptive
    }
    val currentCrop = image.cropRect
    val matchingGeometry = currentCrop == flowRegionCrop && image.imageInfo.rotationDegrees == flowRegionRotation
    val estimate = flow?.estimate
    scorePausedGeneration?.let { generation ->
      if (!state.isCurrent(generation) || estimate?.status == FlowMotionStatus.MEASURED &&
        (estimate.speedPxPerSecond ?: 0.0) / estimate.sourceWidth > .01) {
        scorePausedGeneration = null
      }
    }
    val scorePaused = scorePausedGeneration != null
    // Flow carried the page outline to this frame. Lost regions only degrade it; the policy bounds how long.
    val reliable = matchingGeometry && flowRegion != null && estimate?.status == FlowMotionStatus.MEASURED &&
      estimate.transform != null && flowRegion!!.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) } != null
    val shapeReference = adaptiveReference
    val trackedRegion = flowRegion
    val turned = image.imageInfo.rotationDegrees % 180 != 0
    val doubt = when {
      !adaptive || !reliable || shapeReference == null || trackedRegion == null -> null
      AdaptiveDetectionPolicy.changed(shapeReference, trackedRegion, if (turned) currentCrop.height() else currentCrop.width(),
        if (turned) currentCrop.width() else currentCrop.height()) -> "scene changed"
      else -> null
    }
    val gap = if (!inferenceThrottling) 0L else if (adaptive) adaptivePolicy.interval(startedAt, tracking && shapeReference != null, reliable,
      (estimate?.lostRegions ?: 0) > 0, doubt)
      else if (tracking) LiveDetectionState.TRACKING_GAP_NS else 0L
    val moving = inferenceThrottling && maxInferenceSpeedFraction > 0 && estimate?.status == FlowMotionStatus.MEASURED &&
      (estimate.lostRegions > 0 || estimate.speedPxPerSecond?.let { it / image.width > maxInferenceSpeedFraction } == true)
    val unstable = moving
    // Provisional focus inference cannot override a tracking pause; tracked outlines drive focus instead.
    val token = synchronized(bindingLock) {
      when {
        boundGeneration != binding || unstable || scorePaused -> null
        else -> state.acquire(startedAt, gap)
      }
    }
    try {
      received++
      if (unstable) gated++
      traceObserver?.takeIf { it.frameSamplesEnabled }?.onFrame(frameSample(sensorTimestampNs, startedAt, frame, token != null, flow).copy(
        inferenceReason = when {
          boundGeneration != binding -> "binding changed"
          moving -> "motion"
          scorePaused -> "low score: waiting for movement"
          token == null -> "cadence or paused"
          else -> "inference"
        },
        inferenceGapNs = gap, flowReliable = reliable,
        predictedQuad = flowRegion?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) },
        adaptiveDoubt = doubt, focusing = frame?.outOfFocus == true, blur = blur,
      ))
      if (token == null) {
        dropped++
        // Pacing and movement skip inference; flow's own consensus fit decides whether tracking holds.
        if (reliable && estimate?.lostRegions == 0 &&
          state.isCurrent(lastToken) && stabilizerConfig == stabilizer.config && boundGeneration == binding) {
          val predicted = flowRegion?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) }
          val rotation = image.imageInfo.rotationDegrees
          val width = if (rotation % 180 != 0) currentCrop.height() else currentCrop.width()
          val height = if (rotation % 180 != 0) currentCrop.width() else currentCrop.height()
          val retainStarted = System.nanoTime()
          val held = stabilizer.retainTracked(LiveQuadInput(predicted, width, height, sensorTimestampNs, startedAt))
          totalChecksNs += System.nanoTime() - retainStarted
          if (held != null) {
            val source = transformFactory.getOutputTransform(image)
            acceptedId = held.acceptanceId
            acceptedSource = source
            acceptedWidth = width
            acceptedHeight = height
            flowRegion = held.accepted
            trackedRegionSensorNs = sensorTimestampNs
            traceObserver?.onObservation(LiveQuadObservation(lastToken, sensorTimestampNs, startedAt, width, height,
              currentCrop.left, currentCrop.top, currentCrop.right, currentCrop.bottom, rotation, held.accepted, null,
              held.status, held.decision, held.chainLength, false, held.accepted, held.acceptanceId, held.holdDeadlineNs,
              0.0, 0.0, 0, guideEligible = false, trackingOnly = true,
              inferencePaused = adaptive && adaptivePolicy.paused && !unstable))
            publish(LiveDetectionResult(LiveGuideStatus.TRACKING, held.accepted, source, width, height,
              held.acceptanceId, held.holdDeadlineNs, lastToken, startedAt))
            // The tracked outline keeps the document focus lock current: its scale and focus state decide refocus.
            postFocusCandidate(lastToken, startedAt,
              LiveFocusCandidate(held.accepted, source, width, height, true, frame?.outOfFocus == true))
          } else {
            // An expired or invalid tracked guide must wake inference instead of remaining paused forever.
            adaptivePolicy.reset()
            adaptiveReference = null
          }
        }
        return
      }
      val sourceTransform = transformFactory.getOutputTransform(image)
      if (!state.isCurrent(token)) return
      val runtime = session ?: (modelFactory?.invoke(context) ?: CornerInference.acquire(context)).also { session = it }
      val prediction = runtime.analyze(image)
      val score = runtime.lastScore
      val scoreRejected = minimumScore > 0 && score != null && score < minimumScore
      val candidate = prediction.takeUnless { scoreRejected }
      if (scoreRejected) {
        resetTracking()
        scorePausedGeneration = token
      }
      val preprocessingMs = runtime.lastPreprocessingMs
      val inferenceMs = runtime.lastInferenceMs
      val crop = image.cropRect
      val rotation = image.imageInfo.rotationDegrees
      val rotated = rotation % 180 != 0
      val width = if (rotated) crop.height() else crop.width()
      val height = if (rotated) crop.width() else crop.height()
      // Acceptance history is valid only within one generation and frame geometry. A newly attached
      // trace observer also starts from fresh history so its trace replays deterministically.
      val observer = traceObserver
      if (token != lastToken || crop != lastCrop || rotation != lastRotation || (observer != null && observer !== lastObserver)) {
        stabilizer.reset()
        tracking = false
        lastToken = token
        lastCrop.set(crop)
        lastRotation = rotation
      }
      lastObserver = observer
      val config = stabilizerConfig
      if (config != stabilizer.config) {
        stabilizer.config = config
        tracking = false
      }
      val stabilizerStartedNs = System.nanoTime()
      val flowHeld = if (!scoreRejected && reliable && estimate?.lostRegions == 0) {
        val predicted = flowRegion?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) }
        stabilizer.retainTracked(LiveQuadInput(predicted, width, height, sensorTimestampNs, startedAt))
      } else null
      val validated = stabilizer.update(
        LiveQuadInput(candidate?.quad, width, height, sensorTimestampNs, startedAt),
      )
      // A rejected model prediction cannot expire an independently tracked, shape-valid page.
      // Keep the validation decision in the trace; flow retention is not a model confirmation.
      val flowRetained = flowHeld != null && validated.status != LiveQuadStatus.TRACKING
      val output = if (flowRetained) validated.copy(status = LiveQuadStatus.TRACKING) else validated
      val stabilizerNs = System.nanoTime() - stabilizerStartedNs
      totalChecksNs += stabilizerNs
      if (observer != null) {
        observer.onObservation(LiveQuadObservation(
          token, sensorTimestampNs, startedAt, width, height, crop.left, crop.top, crop.right, crop.bottom, rotation,
          prediction?.quad, score, output.status, output.decision, output.chainLength, output.expiredBeforeInput,
          output.accepted, output.acceptanceId, output.holdDeadlineNs, preprocessingMs, inferenceMs,
          stabilizerNs,
          guideEligible = !scoreRejected, flowRetained = flowRetained,
        ))
      }
      frameRecorder.record(image, sensorTimestampNs, prediction,
        (runtime as? CornerInference.Lease)?.lastSelection ?: checkNotNull(modelSelection))
      postFocusCandidate(token, startedAt, LiveFocusCandidate(if (flowRetained) output.accepted else candidate?.quad, sourceTransform, width, height,
        output.status == LiveQuadStatus.TRACKING, frame?.outOfFocus == true))
      tracking = output.status != LiveQuadStatus.SEARCHING
      if (!flowRetained) adaptiveReference = output.accepted.takeIf { output.status == LiveQuadStatus.TRACKING }
      if (adaptive) adaptiveReference?.takeUnless { flowRetained }?.let {
        adaptivePolicy.confirmed()
      }
      flowRegion = output.accepted
      trackedRegionSensorNs = sensorTimestampNs
      flowRegionCrop.set(crop)
      flowRegionRotation = rotation
      if (output.acceptanceId != acceptedId) {
        acceptedId = output.acceptanceId
        acceptedSource = sourceTransform
        acceptedWidth = width
        acceptedHeight = height
      }
      inferred++
      totalLatencyMs += (System.nanoTime() - startedAt) / 1_000_000.0
      totalPreprocessingMs += preprocessingMs
      totalInferenceMs += inferenceMs
      // Back-camera optics are set only when sensor timestamps are REALTIME.
      if (optics != null) totalCameraMs += (startedRealtimeNs - sensorTimestampNs) / 1_000_000.0
      lastWidth = width
      lastHeight = height
      publish(when (output.status) {
        LiveQuadStatus.SEARCHING -> if (candidate != null &&
          (output.decision == LiveQuadDecision.ACQUIRING || output.decision == LiveQuadDecision.GAP_RESTART)) {
          LiveDetectionResult(LiveGuideStatus.DETECTED, candidate.quad, sourceTransform, width, height,
            holdDeadlineNs = startedAt + config.holdNs, generation = token, analysisStartedNs = startedAt,
            score = candidate.score)
        } else LiveDetectionResult(
          LiveGuideStatus.SEARCHING, source = sourceTransform, width = width, height = height, generation = token,
          analysisStartedNs = startedAt, score = score,
        )
        LiveQuadStatus.TRACKING, LiveQuadStatus.HELD -> LiveDetectionResult(
          if (output.status == LiveQuadStatus.TRACKING) LiveGuideStatus.TRACKING else LiveGuideStatus.HELD,
          output.accepted, acceptedSource, acceptedWidth, acceptedHeight,
          output.acceptanceId, output.holdDeadlineNs, token, startedAt,
          score,
        )
      })
    } catch (error: Throwable) {
      if (token != null && state.fail(token)) {
        releaseSession()
        Log.e("RNDocumentScannerLive", "Live detection unavailable; manual capture remains available", error)
        publish(LiveDetectionResult(LiveGuideStatus.UNAVAILABLE, generation = token))
      }
    } finally {
      image.close()
      val gyroSpeed = flow?.estimate?.takeIf {
        it.status == FlowMotionStatus.TOO_FEW_FEATURES || it.status == FlowMotionStatus.POOR_COVERAGE
      }?.let { estimate ->
        if (gyro == null || optics == null) null
        else gyro.peakRate(estimate.fromSensorNs - GYRO_MARGIN_NS, estimate.toSensorNs)
          ?.let { it * optics.focalLengthPx / optics.activeWidth }
      }
      // The result often lands while the frame is analyzed; look again so the record carries it.
      val recorded = frame ?: frames.at(sensorTimestampNs)
      val recordedMotion = if (frame != null || recorded == null) motion
        else gateMotion(flow?.estimate, recorded.exposureNs,
          if (flow?.estimate?.let { flowBlur(it, recorded.exposureNs) } != null) null else gyroBlur(optics, sensorTimestampNs, recorded))
      recordFrameAnalysis(boundGeneration, frameGeneration, sensorTimestampNs, startedAt, optics != null, recorded,
        flow?.estimate, recordedMotion, gyroSpeed)
      logWindow(System.nanoTime())
    }
  }

  /** Rotation blur across [frame]'s exposure, which starts its first row at [sensorTimestampNs] and its last row one
   * readout skew later. Null without the gyroscope, optics or samples.
   */
  private fun gyroBlur(optics: LiveCameraOptics?, sensorTimestampNs: Long, frame: CameraFrameState): Double? =
    if (gyro == null || optics == null) null else motionBlur(
      gyro, optics, sensorTimestampNs - GYRO_MARGIN_NS,
      sensorTimestampNs + frame.rollingShutterSkewNs + frame.exposureNs + GYRO_MARGIN_NS, frame.exposureNs,
    )

  private fun postFocusCandidate(token: Long, startedAt: Long, hint: LiveFocusCandidate) {
    val focusListener = focusCandidateListener
    if (focusListener == null) return
    handler.post {
      if (state.isCurrent(token) && System.nanoTime() - startedAt < 250_000_000L && focusCandidateListener === focusListener) {
        focusListener(hint)
      }
    }
  }

  private fun updateFlow(
    image: ImageProxy, boundGeneration: Long, sensorTimestampNs: Long, realtime: Boolean,
  ): FlowResult? {
    if (flowFailed) return null
    val generation = synchronized(bindingLock) {
      if (!flowActive || boundGeneration != binding) return null else flowGeneration
    }
    val startedNs = System.nanoTime()
    return try {
      val estimator = flowEstimator ?: FlowMotionEstimator().also { flowEstimator = it }
      if (generation != flowSeenGeneration) {
        estimator.reset()
        pageMotion.clear()
        flowRegion = null
        flowSeenGeneration = generation
      }
      val plane = image.planes[0]
      val crop = image.cropRect
      val rotation = image.imageInfo.rotationDegrees
      val region = flowRegion?.takeIf { crop == flowRegionCrop && rotation == flowRegionRotation }
      val estimate = estimator.update(
        plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height, crop.left, crop.top, crop.right,
        crop.bottom, rotation, sensorTimestampNs, region,
        fitTransform = state.guidanceEnabled(),
      )
      pageMotion.add(estimate)
      if (region != null) {
        val advanced = pageMotion.warp(region, trackedRegionSensorNs, sensorTimestampNs)
        if (advanced != null) {
          flowRegion = advanced
          trackedRegionSensorNs = sensorTimestampNs
        }
      }
      FlowResult(
        estimate, System.nanoTime() - startedNs,
        if (realtime) SystemClock.elapsedRealtimeNanos() - sensorTimestampNs else null,
      )
    } catch (error: Throwable) {
      synchronized(bindingLock) { if (generation == flowGeneration) clearFlowLocked() }
      releaseFlow()
      flowFailed = true
      Log.e("RNDocumentScannerLive", "Image motion estimation unavailable", error)
      null
    }
  }

  /** Clears acceptance and pause state when the source, score policy or inference session changes. */
  private fun resetTracking() {
    scorePausedGeneration = null
    stabilizer.reset()
    tracking = false
    adaptivePolicy.reset()
    adaptiveReference = null
    flowRegion = null
    captureGuide = null
  }

  private fun releaseFlow() {
    pageMotion.clear()
    flowEstimator?.close()
    flowEstimator = null
    flowFailed = false
    flowRegion = null
  }

  private fun releaseSession() {
    resetTracking()
    session?.close()
    session = null
  }

  private fun publish(result: LiveDetectionResult) {
    // Capture's page is the guide main shows: a confirmed outline with every corner inside the frame.
    captureGuide = result.quad?.takeIf {
      result.status == LiveGuideStatus.TRACKING && DocCornerSession.validNormalizedQuad(DocCornerSession.corners(it))
    }
    captureGuideDeadlineNs = result.holdDeadlineNs
    handler.removeCallbacksAndMessages(callbackToken)
    handler.postAtTime({ deliver(result) }, callbackToken, SystemClock.uptimeMillis())
  }

  private fun deliver(result: LiveDetectionResult) {
    if (!state.isCurrent(result.generation)) return
    if (result.status == LiveGuideStatus.DETECTED) {
      val remainingNs = result.holdDeadlineNs - System.nanoTime()
      if (remainingNs <= 0) return
      deliveredAcceptanceId = 0L
      handler.removeCallbacksAndMessages(expiryToken)
      handler.postAtTime({
        if (state.isCurrent(result.generation) && deliveredAcceptanceId == 0L) {
          onResult?.invoke(LiveDetectionResult(LiveGuideStatus.SEARCHING, generation = result.generation))
        }
      }, expiryToken, SystemClock.uptimeMillis() + (remainingNs + 999_999) / 1_000_000)
      onResult?.invoke(result)
      return
    }
    if (result.quad != null) {
      // A frame analyzed before the deadline must not resurrect guidance that already expired.
      if (result.acceptanceId <= expiredAcceptanceId) return
      // An inference or main-thread stall can deliver a result after its deadline; never draw it.
      if (System.nanoTime() >= result.holdDeadlineNs) {
        expire(result.acceptanceId, result.generation)
        return
      }
      deliveredAcceptanceId = result.acceptanceId
      scheduleExpiry(result)
    } else {
      handler.removeCallbacksAndMessages(expiryToken)
    }
    onResult?.invoke(result)
    val now = System.nanoTime()
    if (debugLogging && result.analysisStartedNs > 0 && now - lastDeliveryLogNs >= 5_000_000_000L) {
      Log.d("RNDocumentScannerLive", "analysisToOverlayMs=${(now - result.analysisStartedNs) / 1_000_000.0}")
      lastDeliveryLogNs = now
    }
  }

  private fun scheduleExpiry(result: LiveDetectionResult) {
    handler.removeCallbacksAndMessages(expiryToken)
    // Delay from nanoTime; nanoTime and uptimeMillis need not share a base.
    val delayMs = ((result.holdDeadlineNs - System.nanoTime() + 999_999) / 1_000_000).coerceAtLeast(0)
    val id = result.acceptanceId
    val generation = result.generation
    handler.postAtTime({
      if (state.isCurrent(generation) && deliveredAcceptanceId == id) expire(id, generation)
    }, expiryToken, SystemClock.uptimeMillis() + delayMs)
  }

  private fun expire(acceptanceId: Long, generation: Long) {
    handler.removeCallbacksAndMessages(expiryToken)
    expiredAcceptanceId = maxOf(expiredAcceptanceId, acceptanceId)
    onResult?.invoke(LiveDetectionResult(LiveGuideStatus.SEARCHING, generation = generation))
  }

  private fun removeCallbacks() {
    handler.removeCallbacksAndMessages(callbackToken)
    handler.removeCallbacksAndMessages(expiryToken)
    handler.removeCallbacksAndMessages(frameResultsToken)
  }

  private fun logWindow(now: Long) {
    val listener = statsListener
    if (!debugLogging && listener == null) return
    if (windowStartedNs == 0L) windowStartedNs = now
    val seconds = (now - windowStartedNs) / 1_000_000_000.0
    if (seconds < (if (listener != null) 1.0 else 5.0)) return
    val frames = inferred.coerceAtLeast(1)
    if (debugLogging) {
      Log.d("RNDocumentScannerLive", "rate=${inferred / seconds}Hz received=$received inferred=$inferred skipped=$dropped meanAnalysisMs=${totalLatencyMs / frames}")
    }
    if (listener != null) {
      val stats = LiveDetectionStats(
        inferred / seconds, totalPreprocessingMs / frames, totalInferenceMs / frames, totalLatencyMs / frames,
        if (cameraOptics != null && inferred > 0) totalCameraMs / frames else null, lastWidth, lastHeight,
        peakBlur?.let { it * 100 }, gated, unmatched,
        totalChecksNs / 1_000_000.0 / received.coerceAtLeast(1),
        if (adaptiveDetection && inferenceThrottling) "${adaptivePolicy.status}; reset: ${adaptivePolicy.lastReset}" else "off",
      )
      handler.post { if (statsListener === listener) listener(stats) }
    }
    windowStartedNs = now
    received = 0; inferred = 0; dropped = 0; totalLatencyMs = 0.0
    totalPreprocessingMs = 0.0; totalInferenceMs = 0.0; totalCameraMs = 0.0
    totalChecksNs = 0L
    peakBlur = null; gated = 0; unmatched = 0
  }

  override fun close() {
    if (closed) return
    closed = true
    state.dispose()
    onResult = null // Release the owning view immediately, including already-posted callbacks.
    statsListener = null
    synchronized(bindingLock) {
      flowActive = false
      clearFlowLocked()
    }
    removeCallbacks()
    executor.execute { releaseSession(); releaseFlow() }
    executor.shutdown() // Drain the current image and final close; never close interpreter in flight.
  }

}
