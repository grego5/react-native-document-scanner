package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.NativeCornerGeometry
import com.margelo.nitro.documentscanner.ScannerLog
import android.content.Context
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.TextRegion
import com.margelo.nitro.documentscanner.regionInBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal enum class LiveGuideStatus { SEARCHING, DETECTED, TRACKING, HELD, UNAVAILABLE }

/**
 * A frame's page for focus: [corners] in the upright frame of [width] by [height], with the [source] transform, at the
 * frame's sensor timestamp. [sampled] answers the spot request this frame took ([LiveDocumentDetector.requestSpot]),
 * failed picks included; its [spot] is the page's print spot in page coordinates, or without a page the guide area's
 * in upright frame fractions.
 */
internal data class LiveFocusCandidate(
  val corners: DocumentCorners?, val source: OutputTransform, val width: Int, val height: Int, val confirmed: Boolean,
  val sensorNs: Long, val sampled: SampledSpot? = null,
) {
  val spot: DocumentPoint? get() = sampled?.point
}

/** The answer to spot request [request]: the spot picked, or null when it could not be. */
internal data class SampledSpot(val request: Long, val point: DocumentPoint?)

/** The detector's spot requests: [pending], the one the next analysed frame answers, 0 for none, and [taken], the last
 * one a frame answered, at sensor time [takenNs], 0 before any. One value, so a reader never sees a request between
 * the two. */
internal data class SpotRequests(val pending: Long = 0L, val taken: Long = 0L, val takenNs: Long = 0L) {
  /** Request [id] pending, in place of any earlier one. */
  fun request(id: Long) = copy(pending = id)
  /** Request [id] withdrawn if still pending; a later request stays. */
  fun cancel(id: Long) = if (pending == id) copy(pending = 0L) else this
  /** Whatever request is pending withdrawn. */
  fun cancelAll() = copy(pending = 0L)
  /** The pending request taken by the frame at [sensorNs], if any. */
  fun take(sensorNs: Long) = if (pending == 0L) this else SpotRequests(0L, pending, sensorNs)
}

/** [corners] and the [source] transform and dimensions captured at its acceptance are set for DETECTED, TRACKING and HELD; DETECTED is visible but not confirmed for focus or capture;
 * [score] is this frame's
 * guide inference score, null for a frame without one, such as one optical flow alone carried.
 */
internal data class LiveDetectionResult(
  val status: LiveGuideStatus,
  val corners: DocumentCorners? = null,
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
  val adaptiveReset: String? = null,
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
  private val modelFactory: ((Context) -> LiveCornerModel)? = null,
  private val gyro: GyroHistory? = null,
  /** Linear acceleration, attached only on phones with the sensor. */
  private val acceleration: AccelerationHistory? = null,
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
  @Volatile internal var traceObserver: LiveGuideTraceObserver? = null
  /** Main-thread receiver of one-second [LiveDetectionStats] for on-device tuning; null skips them. */
  @Volatile internal var statsListener: ((LiveDetectionStats) -> Unit)? = null
  @Volatile internal var analysisSlowListener: (() -> Unit)? = null
  private var analysisBinding = -1L
  private var analysisRate = AnalysisRateMonitor()
  /** Independent main-thread focus owner receives document targets; focus never gates detection. */
  @Volatile internal var focusCandidateListener: ((LiveFocusCandidate) -> Unit)? = null
  /** Spot requests: set by main, taken here by the next analysed frame, whose focus candidate carries the answer. */
  private val spotRequest = java.util.concurrent.atomic.AtomicReference(SpotRequests())
  /** Main thread: the next analysed frame picks a spot and answers request [id], a positive number. */
  fun requestSpot(id: Long) { spotRequest.updateAndGet { it.request(id) } }
  /** Main thread: withdraws request [id] if it is still pending, leaving any later request in place. */
  fun cancelSpot(id: Long) { spotRequest.updateAndGet { it.cancel(id) } }
  /** Main thread: withdraws whatever request is pending, as the camera stops. */
  fun cancelSpots() { spotRequest.updateAndGet { it.cancelAll() } }
  /** Which request is pending and which a frame last answered, so the main thread can tell an answer lost on its way,
   * as a candidate dropped for its age, from one still coming. */
  val spotRequests: SpotRequests get() = spotRequest.get()
  /** Checks for the next analyzed frame; a change starts a fresh stabilizer, dropping its history. */
  @Volatile internal var stabilizerConfig = GuideStabilizerConfig()
  /** Estimates image motion on every analysis frame, guide or not, before inference throttling and gating. */
  @Volatile internal var flowMotionEnabled = false
  /** Use image flow for capture motion records, with the gyroscope as fallback. */
  @Volatile internal var inferenceThrottling = false
  /** Raw predictions remain available for samples; this threshold only gates live acceptance. */
  @Volatile internal var minCornerScore = 0.95
  /** Experiment switch: disable pacing/adaptive pauses, preserving frame validity gates. */
  @Volatile internal var pacingEnabled = true
  /** Pause inference above this framing speed, independent of exposure or autofocus. */
  @Volatile internal var maxInferenceSpeedFraction = .01
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
  /** One waiting shutter may bypass inference pacing for frames after its focus scan. */
  @Volatile internal var captureInferenceFrame: ((sensorNs: Long, arrivalNs: Long, realtime: Boolean) -> Boolean)? = null
  /** Set on main when a focus lock just sharpened the frame: the next frame bypasses inference pacing, since a search
   * otherwise retries only once a second while flow on a blurred frame reads as motion. */
  @Volatile internal var inferenceWanted = false
  private val frameResultsToken = Any()
  // Executor-confined: the confirmed in-frame outline as of the last published result, and when its hold expires on
  // the System.nanoTime() clock of frame arrival, as main expires the guide; 0 holds it.
  private var captureGuide: DocumentCorners? = null
  private var captureGuideDeadlineNs = 0L
  // Main-thread-only acceptance delivery.
  private var deliveredAcceptanceId = 0L
  private var expiredAcceptanceId = 0L
  // Executor-confined from here down.
  private var session: LiveCornerModel? = null
  private var modelSelection = CornerModelStore.selected
  private val stabilizer = GuideStabilizer()
  private var lastToken = -1L
  private val lastCrop = Rect()
  private var lastRotation = -1
  private var lastObserver: LiveGuideTraceObserver? = null
  private var acceptedId = 0L
  private var acceptedSource: OutputTransform? = null
  private var acceptedWidth = 0
  private var acceptedHeight = 0
  private var tracking = false
  private var appliedMinCornerScore = 0.95
  /** Generation of a low-score scene awaiting measured movement; null permits inference. */
  private var scorePausedGeneration: Long? = null
  private var cadence = AdaptiveInferenceCadence()
  private var cadenceGeneration = -1L
  private val cadenceCrop = Rect()
  private var cadenceRotation = -1
  private val adaptivePolicy = AdaptiveDetectionPolicy()
  private var adaptiveWasEnabled = false
  private var trackedRegionSensorNs = 0L
  private val transformFactory = ImageProxyTransformFactory().apply {
    isUsingCropRect = true
    isUsingRotationDegrees = true
  }
  private var windowStartedNs = 0L
  private var received = 0
  /** Debug timing: the previous frame record's arrival and the window's longest gap between records. */
  private var lastRecordNs = 0L
  private var maxRecordGapNs = 0L
  private var inferred = 0
  private var dropped = 0
  private var totalLatencyMs = 0.0
  private var totalPreprocessingMs = 0.0
  private var totalInferenceMs = 0.0
  private var totalChecksNs = 0L
  private var totalCameraMs = 0.0
  private var peakBlur: Double? = null
  /** Logging only: the latest frame's edge width, whether it was measured over the page or the guide area, and its
   * focus reading. */
  private var lastEdges: EdgeWidth? = null
  private var lastEdgesOnPage = false
  private var lastFocus: FocusReading? = null
  /** Each frame's focus by its print edges, learned per binding: [focusBinding] is the binding it learned under. */
  private val focusEvidence = FocusEvidence()
  private var focusBinding = -1L
  private var gated = 0
  private var unmatched = 0
  private var lastWidth = 0
  private var lastHeight = 0
  private var lastDeliveryLogNs = 0L // Main-thread-only delivery instrumentation.
  private var flowEstimator: FlowMotionEstimator? = null
  private var flowFailed = false
  private var flowSeenGeneration = -1L
  private val pageMotion = FlowDocumentMotion()
  /** The last accepted corners, with the crop and rotation it is normalized to; only selects where features are sought. */
  private var flowRegion: DocumentCorners? = null
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
    flow: FlowMotionEstimate?, motion: GateMotion?, gyroDrift: Double?, acceleration: Double?, inferenceFinished: Boolean,
    focus: FocusReading?,
  ) {
    synchronized(bindingLock) {
      if (boundGeneration != binding || frameGeneration != flowGeneration || !flowActive) return
      if (debugLogging) {
        if (lastRecordNs != 0L) maxRecordGapNs = maxOf(maxRecordGapNs, arrivalNs - lastRecordNs)
        lastRecordNs = arrivalNs
      }
      frameHistory.append { sequence ->
        FrameRecord(sequence, sensorTimestampNs, sensorTimestampNs.takeIf { realtime }, arrivalNs, frame, flow,
          motion, gyroDrift, acceleration,
          captureGuide?.takeIf {
            state.guidanceEnabled() && (captureGuideDeadlineNs == 0L || arrivalNs < captureGuideDeadlineNs)
          }, inferenceFinished, focus)
      }
    }
    if (frameAnalysisListener == null) return
    handler.removeCallbacksAndMessages(frameResultsToken)
    handler.postAtTime({ frameAnalysisListener?.invoke() }, frameResultsToken, SystemClock.uptimeMillis())
  }

  override fun analyze(image: ImageProxy) = analyzeForBinding(image, binding)

  private fun analyzeForBinding(image: ImageProxy, boundGeneration: Long) {
    val startedAt = System.nanoTime()
    analysisSlowListener?.let { listener ->
      if (boundGeneration == binding) {
        if (analysisBinding != boundGeneration) {
          analysisBinding = boundGeneration
          analysisRate = AnalysisRateMonitor()
        }
        if (analysisRate.observe(startedAt)) handler.post {
          if (boundGeneration == binding && analysisSlowListener === listener) listener()
        }
      }
    }
    frameRequest.getAndSet(null)?.let { receiver -> copyCrop(image)?.let(receiver) }
    val minimumScore = minCornerScore
    if (minimumScore != appliedMinCornerScore) {
      appliedMinCornerScore = minimumScore
      invalidate()
      resetTracking()
      releaseFlow()
    }
    val selected = CornerModelStore.current
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
    // Flow's blur only paces the guide's inference. A frame's smear for capture is the gyroscope's: flow over soft or
    // noisy frames, out of focus or in the dark, reads motion a still hand does not make.
    val flowMotion = frame?.let { state -> flow?.estimate?.let { flowBlur(it, state.exposureNs) } }
    val gyroBlur = frame?.let { gyroBlur(optics, sensorTimestampNs, it) }
    val motion = gyroBlur?.let { GateMotion(it, MotionSource.GYRO) }
    val blur = gyroBlur
    // The framing drift is the phone's net turn over the span before this frame, as frame widths.
    val gyroDrift = if (gyro == null || optics == null) null
      else gyro.turn(sensorTimestampNs - DRIFT_SPAN_NS, sensorTimestampNs)
        ?.let { it * optics.focalLengthPx / optics.activeWidth }
    // The accelerometer's peak over the frame interval before it: the gyroscope sees rotation, not a move along or
    // toward the page.
    val peakAcceleration = acceleration?.peak(sensorTimestampNs - SPEED_SPAN_NS - GYRO_MARGIN_NS, sensorTimestampNs)
    if (blur != null) peakBlur = maxOf(peakBlur ?: 0.0, blur)
    val adaptive = inferenceThrottling && pacingEnabled
    if (adaptive != adaptiveWasEnabled) {
      adaptivePolicy.reset()
      cadence = AdaptiveInferenceCadence()
      scorePausedGeneration = null
      adaptiveWasEnabled = adaptive
    }
    val currentCrop = image.cropRect
    val rotationDegrees = image.imageInfo.rotationDegrees
    if (adaptive && (cadenceGeneration != frameGeneration || cadenceCrop != currentCrop || cadenceRotation != rotationDegrees)) {
      resetTracking()
      cadenceGeneration = frameGeneration
      cadenceCrop.set(currentCrop)
      cadenceRotation = rotationDegrees
    }
    val matchingGeometry = currentCrop == flowRegionCrop && rotationDegrees == flowRegionRotation
    val estimate = flow?.estimate
    scorePausedGeneration?.let { generation ->
      if (!NativeLiveGuide.keepScorePauseNative(state.isCurrent(generation), estimate?.speedPxPerSecond ?: 0.0,
        estimate?.status == FlowMotionStatus.MEASURED, estimate?.sourceWidth ?: 0)) {
        scorePausedGeneration = null
      }
    }
    val scorePaused = scorePausedGeneration != null
    // Flow carried the page outline to this frame. Lost regions only degrade it; the policy bounds how long.
    val reliable = matchingGeometry && flowRegion != null && estimate?.status == FlowMotionStatus.MEASURED &&
      estimate.transform != null && flowRegion!!.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) } != null
    val turned = rotationDegrees % 180 != 0
    val searching = adaptive && (!tracking || !adaptivePolicy.hasReference)
    val trackingGap = if (adaptive) adaptivePolicy.next(tracking, reliable, (estimate?.lostRegions ?: 0) > 0, flowRegion,
      if (turned) currentCrop.height() else currentCrop.width(), if (turned) currentCrop.width() else currentCrop.height())
      else if (pacingEnabled && tracking) LiveDetectionState.TRACKING_GAP_NS else 0L
    val doubt = if (adaptive) adaptivePolicy.doubt else null
    val speedLimit = if (searching) maxInferenceSpeedFraction / 2 else maxInferenceSpeedFraction
    val moving = pacingEnabled && maxInferenceSpeedFraction > 0 && estimate?.status == FlowMotionStatus.MEASURED &&
      ((!adaptive && estimate.lostRegions > 0) || estimate.speedPxPerSecond?.let { it / image.width > speedLimit } == true)
    val forCapture = captureInferenceFrame?.invoke(sensorTimestampNs, startedAt, optics != null) == true
    val wanted = inferenceWanted
    val heldUntil = captureGuideDeadlineNs.takeIf { tracking && matchingGeometry && state.isCurrent(lastToken) && it != 0L }
    val gap = if (forCapture || wanted) 0L else if (adaptive) cadence.gap(startedAt, searching, moving,
      estimate?.status == FlowMotionStatus.MEASURED && estimate.lostRegions == 0, trackingGap, blurFraction = flowMotion,
      heldUntil = heldUntil)
      else if (moving || scorePaused) null else trackingGap
    // Provisional focus inference cannot override a tracking pause; tracked outlines drive focus instead.
    val token = synchronized(bindingLock) {
      when {
        boundGeneration != binding || gap == null -> null
        else -> state.acquire(startedAt, gap)
      }
    }
    if (wanted && token != null) inferenceWanted = false
    var inferenceFinished = false
    var focusReading: FocusReading? = null
    try {
      received++
      if (moving) gated++
      val predicted = flowRegion?.takeIf { matchingGeometry }?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) }
      val sampling = traceObserver?.frameSamplesEnabled == true
      val edges = edgeWidth(image, predicted)
      if (focusBinding != boundGeneration) {
        focusBinding = boundGeneration
        focusEvidence.reset()
      }
      // The result often lands while the frame is analyzed; a frame still without one reads unknown.
      val focusFrame = frame ?: frames.at(sensorTimestampNs)
      focusReading = focusEvidence.judge(edges, maxOf(image.width, image.height), focusFrame,
        if (focusFrame != null && frame == null) gyroBlur(optics, sensorTimestampNs, focusFrame) else blur)
      if (debugLogging) {
        lastEdges = edges
        lastEdgesOnPage = predicted != null
        lastFocus = focusReading
      }
      if (sampling) traceObserver?.onFrame(frameSample(sensorTimestampNs, startedAt, frame, token != null, flow).copy(
        inferenceReason = when {
          boundGeneration != binding -> "binding changed"
          token != null -> "inference"
          moving -> "motion"
          scorePaused -> "low score: waiting for movement"
          else -> "cadence or paused"
        },
        inferenceGapNs = gap ?: Long.MAX_VALUE, flowReliable = reliable,
        predictedCorners = flowRegion?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) },
        adaptiveDoubt = doubt, focusing = frame?.outOfFocus == true, blur = blur, drift = gyroDrift,
        acceleration = peakAcceleration,
        edges = edges, edgesOnPage = predicted != null, focus = focusReading,
      ))
      if (token == null) {
        dropped++
        // Pacing and movement skip inference; flow's own consensus fit decides whether tracking holds.
        if (reliable && estimate.lostRegions == 0 &&
          state.isCurrent(lastToken) && stabilizerConfig == stabilizer.config && boundGeneration == binding) {
          val predicted = flowRegion?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) }
          val rotation = image.imageInfo.rotationDegrees
          val width = if (rotation % 180 != 0) currentCrop.height() else currentCrop.width()
          val height = if (rotation % 180 != 0) currentCrop.width() else currentCrop.height()
          val retainStarted = System.nanoTime()
          val held = stabilizer.retainTracked(GuideInput(predicted, width, height, sensorTimestampNs, startedAt))
          totalChecksNs += System.nanoTime() - retainStarted
          if (held != null) {
            val source = transformFactory.getOutputTransform(image)
            acceptedId = held.acceptanceId
            acceptedSource = source
            acceptedWidth = width
            acceptedHeight = height
            flowRegion = held.accepted
            trackedRegionSensorNs = sensorTimestampNs
            traceObserver?.onObservation(LiveDocumentObservation(lastToken, sensorTimestampNs, startedAt, width, height,
              currentCrop.left, currentCrop.top, currentCrop.right, currentCrop.bottom, rotation, held.accepted, null,
              held.status, held.decision, held.chainLength, false, held.accepted, held.acceptanceId, held.holdDeadlineNs,
              0.0, 0.0, 0, guideEligible = false, trackingOnly = true,
              inferencePaused = adaptive && adaptivePolicy.paused && !moving))
            publish(LiveDetectionResult(LiveGuideStatus.TRACKING, held.accepted, source, width, height,
              held.acceptanceId, held.holdDeadlineNs, lastToken, startedAt))
            // The tracked outline keeps the page, and so continuous autofocus, while inference is paused.
            postFocusCandidate(lastToken, startedAt, LiveFocusCandidate(held.accepted, source, width, height, true, sensorTimestampNs,
              spot(image, held.accepted, sensorTimestampNs)))
          } else {
            // An expired or invalid tracked guide must wake inference instead of remaining paused forever.
            adaptivePolicy.reset()
          }
        }
        return
      }
      val sourceTransform = transformFactory.getOutputTransform(image)
      if (!state.isCurrent(token)) return
      val runtime = session ?: (modelFactory?.invoke(context) ?: CornerInference.acquire(context)).also { session = it }
      if (adaptive) cadence.didAttempt(startedAt)
      val prediction = runtime.analyze(image)
      val score = runtime.lastScore
      val scoreRejected = NativeLiveGuide.scoreRejectedNative(score ?: 0.0, score != null, minimumScore)
      val candidate = prediction.takeUnless { scoreRejected }
      if (scoreRejected) {
        clearTrackedOutline()
        if (!adaptive) scorePausedGeneration = token
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
      val flowHeld = if (!scoreRejected && reliable && estimate.lostRegions == 0) {
        val predicted = flowRegion?.let { pageMotion.warp(it, trackedRegionSensorNs, sensorTimestampNs) }
        stabilizer.retainTracked(GuideInput(predicted, width, height, sensorTimestampNs, startedAt))
      } else null
      val validated = stabilizer.update(
        GuideInput(candidate?.corners, width, height, sensorTimestampNs, startedAt),
      )
      // Keep the validation decision in the trace; flow retention is not a model confirmation.
      val flowRetained = flowHeld != null && validated.status != GuideTrackingStatus.TRACKING
      val output = if (flowRetained) validated.copy(status = GuideTrackingStatus.TRACKING) else validated
      val stabilizerNs = System.nanoTime() - stabilizerStartedNs
      totalChecksNs += stabilizerNs
      if (observer != null) {
        observer.onObservation(LiveDocumentObservation(
          token, sensorTimestampNs, startedAt, width, height, crop.left, crop.top, crop.right, crop.bottom, rotation,
          prediction?.corners, score, output.status, output.decision, output.chainLength, output.expiredBeforeInput,
          output.accepted, output.acceptanceId, output.holdDeadlineNs, preprocessingMs, inferenceMs,
          stabilizerNs,
          guideEligible = !scoreRejected, flowRetained = flowRetained,
        ))
      }
      postFocusCandidate(token, startedAt,
        LiveFocusCandidate(if (flowRetained) output.accepted else candidate?.corners, sourceTransform, width, height,
          output.status == GuideTrackingStatus.TRACKING, sensorTimestampNs,
          spot(image, if (flowRetained) output.accepted else candidate?.corners, sensorTimestampNs)))
      tracking = output.status != GuideTrackingStatus.SEARCHING
      if (!flowRetained) {
        if (output.status == GuideTrackingStatus.TRACKING) {
          adaptivePolicy.reference(output.accepted)
          if (adaptive) adaptivePolicy.confirmed()
        } else if (!adaptive || output.status == GuideTrackingStatus.SEARCHING) {
          adaptivePolicy.reference(null)
        }
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
      val keepVisible = adaptive && !forCapture && !wanted && cadence.holdRetry &&
        output.status == GuideTrackingStatus.SEARCHING && heldUntil?.let { startedAt < it } == true
      publish(when (output.status) {
        GuideTrackingStatus.SEARCHING -> if (candidate != null &&
          (output.decision == GuideDecision.ACQUIRING || output.decision == GuideDecision.GAP_RESTART)) {
          LiveDetectionResult(LiveGuideStatus.DETECTED, candidate.corners, sourceTransform, width, height,
            holdDeadlineNs = startedAt + config.holdNs, generation = token, analysisStartedNs = startedAt,
            score = candidate.score)
        } else LiveDetectionResult(
          LiveGuideStatus.SEARCHING, source = sourceTransform, width = width, height = height, generation = token,
          analysisStartedNs = startedAt, score = score,
        )
        GuideTrackingStatus.TRACKING, GuideTrackingStatus.HELD -> LiveDetectionResult(
          if (output.status == GuideTrackingStatus.TRACKING) LiveGuideStatus.TRACKING else LiveGuideStatus.HELD,
          output.accepted, acceptedSource, acceptedWidth, acceptedHeight,
          output.acceptanceId, output.holdDeadlineNs, token, startedAt,
          score,
        )
      }, keepVisible)
      inferenceFinished = state.isCurrent(token)
    } catch (error: Throwable) {
      if (token != null && state.fail(token)) {
        releaseSession()
        ScannerLog.e("RNDocumentScannerLive", "Live detection unavailable; manual capture remains available", error)
        publish(LiveDetectionResult(LiveGuideStatus.UNAVAILABLE, generation = token))
      }
    } finally {
      image.close()
      // The result often lands while the frame is analyzed; look again so the record carries it.
      val recorded = frame ?: frames.at(sensorTimestampNs)
      val recordedMotion = if (frame != null || recorded == null) motion
        else gyroBlur(optics, sensorTimestampNs, recorded)?.let { GateMotion(it, MotionSource.GYRO) }
      recordFrameAnalysis(boundGeneration, frameGeneration, sensorTimestampNs, startedAt, optics != null, recorded,
        flow?.estimate, recordedMotion, gyroDrift, peakAcceleration, inferenceFinished, focusReading)
      logWindow(System.nanoTime())
    }
  }

  /** The print's edge width ([regionEdgeWidth]) over the box around [page], upright frame
   * fractions, else over the guide area ([GUIDE_AREA]), where the page usually lies before it is found; null without
   * enough print edges to measure. */
  private fun edgeWidth(image: ImageProxy, page: DocumentCorners?): EdgeWidth? {
    val area = page ?: GUIDE_AREA
    val points = listOf(area.topLeft, area.topRight, area.bottomRight, area.bottomLeft)
    val left = points.minOf { it.x }
    val top = points.minOf { it.y }
    val crop = image.cropRect
    val box = regionInBuffer(TextRegion(left, top, points.maxOf { it.x } - left, points.maxOf { it.y } - top),
      image.imageInfo.rotationDegrees, crop.left, crop.top, crop.width(), crop.height())
    val plane = image.planes[0]
    val x0 = box[0].coerceIn(0, image.width)
    val y0 = box[1].coerceIn(0, image.height)
    return regionEdgeWidth(plane.buffer, plane.rowStride, plane.pixelStride, x0, y0,
      box[2].coerceIn(x0, image.width), box[3].coerceIn(y0, image.height))
  }

  /** Rotation blur across [frame]'s exposure, which starts its first row at [sensorTimestampNs] and its last row one
   * readout skew later. Null without the gyroscope, optics or samples.
   */
  private fun gyroBlur(optics: LiveCameraOptics?, sensorTimestampNs: Long, frame: CameraFrameState): Double? =
    if (gyro == null || optics == null) null else motionBlur(
      gyro, optics, sensorTimestampNs - GYRO_MARGIN_NS,
      sensorTimestampNs + frame.rollingShutterSkewNs + frame.exposureNs + GYRO_MARGIN_NS, frame.exposureNs,
    )

  private fun spot(image: ImageProxy, corners: DocumentCorners?, sensorNs: Long): SampledSpot? {
    val request = spotRequest.getAndUpdate { it.take(sensorNs) }.pending
    if (request == 0L) return null
    return SampledSpot(request, pickSpot(image, corners))
  }

  private fun pickSpot(image: ImageProxy, corners: DocumentCorners?): DocumentPoint? {
    val plane = image.planes[0]
    val crop = image.cropRect
    val rotation = image.imageInfo.rotationDegrees
    return if (corners != null) {
      pickPrintSpot(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.right, crop.bottom,
        rotation, corners)
    } else {
      pickGuideSpot(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.right, crop.bottom,
        rotation)
    }
  }

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
      ScannerLog.e("RNDocumentScannerLive", "Image motion estimation unavailable", error)
      null
    }
  }

  /** Clears acceptance and pause state when the source, score policy or inference session changes. */
  private fun resetTracking() {
    cadence = AdaptiveInferenceCadence()
    scorePausedGeneration = null
    clearTrackedOutline()
  }

  private fun clearTrackedOutline() {
    stabilizer.reset()
    tracking = false
    adaptivePolicy.reset()
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

  private fun publish(result: LiveDetectionResult, keepVisible: Boolean = false) {
    // Capture's page is the guide main shows: a confirmed outline with every corner inside the frame.
    captureGuide = result.corners?.takeIf {
      result.status == LiveGuideStatus.TRACKING && NativeCornerGeometry.prediction(NativeCornerGeometry.coordinates(it))
    }
    captureGuideDeadlineNs = if (keepVisible) 0L else result.holdDeadlineNs
    // Main already has an expiry scheduled for the existing outline.
    if (keepVisible) return
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
          traceObserver?.onGuideExpired(0L)
          onResult?.invoke(LiveDetectionResult(LiveGuideStatus.SEARCHING, generation = result.generation))
        }
      }, expiryToken, SystemClock.uptimeMillis() + (remainingNs + 999_999) / 1_000_000)
      onResult?.invoke(result)
      return
    }
    if (result.corners != null) {
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
      ScannerLog.d("RNDocumentScannerLive", "analysisToOverlayMs=${(now - result.analysisStartedNs) / 1_000_000.0}")
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
    traceObserver?.onGuideExpired(acceptanceId)
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
      ScannerLog.d("RNDocumentScannerLive", "rate=${inferred / seconds}Hz received=$received inferred=$inferred skipped=$dropped meanAnalysisMs=${totalLatencyMs / frames} " +
        "maxRecordGapMs=${maxRecordGapNs / 1_000_000} edges=${lastEdges ?: "unmeasured"} over ${if (lastEdgesOnPage) "page" else "guide"} focus=${lastFocus ?: "unjudged"}")
      maxRecordGapNs = 0L
    }
    if (listener != null) {
      val stats = LiveDetectionStats(
        inferred / seconds, totalPreprocessingMs / frames, totalInferenceMs / frames, totalLatencyMs / frames,
        if (cameraOptics != null && inferred > 0) totalCameraMs / frames else null, lastWidth, lastHeight,
        peakBlur?.let { it * 100 }, gated, unmatched,
        totalChecksNs / 1_000_000.0 / received.coerceAtLeast(1),
        if (inferenceThrottling && pacingEnabled) adaptivePolicy.status else "off",
        adaptivePolicy.lastReset.takeIf { inferenceThrottling && pacingEnabled },
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
    analysisSlowListener = null
    synchronized(bindingLock) {
      flowActive = false
      clearFlowLocked()
    }
    removeCallbacks()
    executor.execute { releaseSession(); releaseFlow(); pageMotion.close(); stabilizer.close() }
    executor.shutdown() // Drain the current image and final close; never close interpreter in flight.
  }
}
