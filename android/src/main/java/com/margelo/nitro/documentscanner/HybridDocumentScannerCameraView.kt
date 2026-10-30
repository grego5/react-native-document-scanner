package com.margelo.nitro.documentscanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.MeteringPoint
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import com.facebook.proguard.annotations.DoNotStrip
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.CameraPermissionStatus
import com.margelo.nitro.documentscanner.CapturedPhoto
import com.margelo.nitro.documentscanner.HybridDocumentScannerCameraViewSpec
import com.facebook.react.uimanager.ThemedReactContext
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import com.margelo.nitro.documentscanner.CameraState as ScannerCameraState
import com.margelo.nitro.documentscanner.processing.CameraFrameState
import com.margelo.nitro.documentscanner.processing.CameraOpticsReader
import com.margelo.nitro.documentscanner.processing.QuadTensorModelLoader
import com.margelo.nitro.documentscanner.processing.DocCornerSession
import com.margelo.nitro.documentscanner.processing.LatestFrameCapture
import com.margelo.nitro.documentscanner.processing.LiveFrame
import com.margelo.nitro.documentscanner.processing.SampleTicket
import com.margelo.nitro.documentscanner.processing.SampleCollection
import com.margelo.nitro.documentscanner.processing.GyroHistory
import com.margelo.nitro.documentscanner.processing.GyroscopeSource
import com.margelo.nitro.documentscanner.processing.LiveCameraOptics
import com.margelo.nitro.documentscanner.processing.LiveDetectionStats
import com.margelo.nitro.documentscanner.processing.LiveDocumentDetector
import com.margelo.nitro.documentscanner.processing.LiveGuideStatus
import com.margelo.nitro.documentscanner.processing.LiveQuadTraceObserver
import com.margelo.nitro.documentscanner.processing.LiveQuadStabilizerConfig
import com.margelo.nitro.documentscanner.processing.LiveFocusCandidate
import com.margelo.nitro.documentscanner.processing.TorchPolicy
import com.margelo.nitro.documentscanner.processing.canParkFocus
import com.margelo.nitro.documentscanner.processing.hasAdjustableAutofocus
import com.margelo.nitro.documentscanner.processing.parkDiopters
import com.margelo.nitro.documentscanner.processing.LiveCaptureGateSample
import com.margelo.nitro.documentscanner.processing.FrameRecord

private const val LOG_TAG = "RNDocumentScannerCamera"

/** Android camera preview and still capture for the Nitro Hybrid View. */
@DoNotStrip
class HybridDocumentScannerCameraView(
  private val themedContext: ThemedReactContext,
) : HybridDocumentScannerCameraViewSpec() {
  private val debugLoggingEnabled =
    themedContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
  private val instanceId = Integer.toHexString(System.identityHashCode(this))
  private val mainHandler = Handler(Looper.getMainLooper())
  private val container = object : FrameLayout(themedContext) {
    init {
      setBackgroundColor(Color.BLACK)
    }

    override fun onAttachedToWindow() {
      super.onAttachedToWindow()
      logLifecycle("view attached size=${width}x$height visibility=$windowVisibility")
      this@HybridDocumentScannerCameraView.onHostViewAttached()
    }

    override fun onDetachedFromWindow() {
      logLifecycle("view detached size=${width}x$height")
      this@HybridDocumentScannerCameraView.onHostViewDetached()
      super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
      super.onWindowVisibilityChanged(visibility)
      logLifecycle("window visibility=$visibility attached=$isAttachedToWindow")
      if (visibility == View.VISIBLE) {
        this@HybridDocumentScannerCameraView.onHostViewVisible()
      } else {
        this@HybridDocumentScannerCameraView.stopCamera("window-hidden")
      }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
      this@HybridDocumentScannerCameraView.layoutStats()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
      super.onSizeChanged(width, height, oldWidth, oldHeight)
      if (props.showStats) post { layoutStats() }
      logLifecycle("view size=${width}x$height previous=${oldWidth}x$oldHeight")
      targetRotation = currentDisplayRotation()
      if (oldWidth > 0 && oldHeight > 0 && (width != oldWidth || height != oldHeight)) {
        // Rebinding closes the camera and fails a photo in flight, so a resize during capture waits for it to settle.
        if (hasPendingCapture()) {
          logLifecycle("viewport resize deferred until capture settles")
          rebindPending = true
          return
        }
        this@HybridDocumentScannerCameraView.stopCamera("viewport-resized")
      }
      if (width > 0 && height > 0) this@HybridDocumentScannerCameraView.startCamera()
    }
  }
  /** Main thread: the preview frame shown, over the live guide, from the shutter through capture and, optionally, the review handoff. */
  private val frozenFrame = ImageView(themedContext).apply {
    scaleType = ImageView.ScaleType.FIT_XY
    visibility = View.GONE
  }
  private val previewView = PreviewView(themedContext).apply {
    scaleType = PreviewView.ScaleType.FIT_CENTER
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
  }
  /** Keep the displayed crop unchanged while a capture defers a use-case rebind. */
  private var previewSquareFrame = false
  private val statusView = TextView(themedContext).apply {
    setTextColor(Color.WHITE)
    textSize = 16f
    gravity = Gravity.CENTER
    setPadding(dp(24), dp(16), dp(24), dp(16))
    setBackgroundColor(0xCC101820.toInt())
  }
  private val gyroscope = GyroscopeSource(themedContext, GyroHistory())
  private val liveOverlay = LiveQuadOverlay(themedContext)
  /** Over the frozen frame, so the shooting stage shows on the photo being taken. */
  private val captureFeedbackView = CaptureFeedbackView(themedContext, liveOverlay)
  private val liveHint = TextView(themedContext).apply {
    setTextColor(Color.WHITE)
    textSize = 14f
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(10), dp(16), dp(10))
    setBackgroundColor(0xCC101820.toInt())
    visibility = View.GONE
  }
  // A fixed line count keeps the strip's height, and so the preview below it, steady as the figures change.
  private val statsLabel = TextView(themedContext).apply {
    setTextColor(Color.WHITE)
    textSize = 12f
    typeface = Typeface.MONOSPACE
    setLines(STATS_LINES)
    setPadding(dp(8), dp(6), dp(8), dp(6))
    setBackgroundColor(0xFF101820.toInt())
    visibility = View.GONE
  }
  /** The last one-second figures below the stats strip's status line; main thread. */
  private var statsFigures = ""
  private var latestModelScore: Double? = null
  /** The guide while it tracks a page, normalized upright in the analysis frame; main thread. */
  private var trackedGuide: DocumentCandidate? = null
  private val liveDetector = LiveDocumentDetector(themedContext, debugLoggingEnabled, gyro = gyroscope.history) { result ->
    latestModelScore = result.score ?: latestModelScore.takeIf { result.quad != null }
    showHint(result.status)
    updateDocumentState(when (result.status) {
      LiveGuideStatus.SEARCHING, LiveGuideStatus.DETECTED -> DocumentState.SEARCHING
      LiveGuideStatus.TRACKING -> DocumentState.TRACKING
      LiveGuideStatus.HELD -> DocumentState.HELD
      LiveGuideStatus.UNAVAILABLE -> DocumentState.UNAVAILABLE
    })
    // A frame optical flow alone carried has no inference score; the guide keeps its latest one. Flow can carry corners
    // past the frame, which photo analysis rejects, so such an outline is not offered and the photo is detected afresh.
    trackedGuide = result.quad
      ?.takeIf { result.status == LiveGuideStatus.TRACKING && DocCornerSession.validNormalizedQuad(DocCornerSession.corners(it)) }
      ?.let { quad -> guideCandidate(quad, result.score ?: trackedGuide?.score ?: 0.0) }
    liveOverlay.show(result, previewView.outputTransform)
    renderStatus()
  }.apply {
    focusCandidateListener = ::onFocusCandidate
  }
  /** Feeds one binding's frame exposure and autofocus state to blur gating; runs on a camera thread. In-flight
   * results can complete after the session closes, so results and posted events of an older binding are dropped.
   */
  private fun frameStateCallback(generation: Long) = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      val afState = result.get(CaptureResult.CONTROL_AF_STATE)
      val autofocus = when (afState) {
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> AutofocusState.SCAN
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> AutofocusState.FOCUSED
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> AutofocusState.UNFOCUSED
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> AutofocusState.LOCKED
        else -> AutofocusState.INACTIVE
      }
      // Autofocus off is a parked lens: its focus is fixed, and unknown only while it moves there.
      val manualFocus = result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF
      val lensMoving = result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING
      val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
      val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
      val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
      val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
      var lightTorch = false
      val changed = synchronized(metadataLock) {
        if (generation != metadataGeneration) return
        // Every lock, a tap's too, moves the parked distance to where the user holds the phone.
        if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED && focusDiopters != null) learnedFocusDiopters = focusDiopters
        exposureIsoMs = if (exposureNs != null && iso != null) exposureNs / 1e6 * iso else null
        if (torchAuto && timestampNs != null && exposureNs != null) lightTorch = torchPolicy.observe(timestampNs, exposureNs, iso)
        if (timestampNs != null && exposureNs != null) liveDetector.frames.add(timestampNs, CameraFrameState(
          exposureNs, result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
          autofocus == AutofocusState.SCAN || autofocus == AutofocusState.UNFOCUSED || manualFocus && lensMoving, afState,
          result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let {
            it == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON
          },
          focusDiopters,
          manualFocus = manualFocus,
          aeState = result.get(CaptureResult.CONTROL_AE_STATE),
          awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
        ))
        (autofocus != autofocusState).also {
          if (it) {
            autofocusState = autofocus
          }
        }
      }
      if (changed) mainHandler.post { if (generation == metadataGeneration) emitAutofocus() }
      if (lightTorch) mainHandler.post { if (generation == metadataGeneration) applyTorch() }
    }
  }
  /** Main thread; for instrumentation tests. */
  internal val gyroscopeRunning: Boolean get() = gyroscope.isRunning
  /** Test-harness live trace hook; nothing in the app sets it. */
  internal var liveQuadTraceObserver: LiveQuadTraceObserver?
    get() = liveDetector.traceObserver
    set(value) { liveDetector.traceObserver = value }
  /** Test-harness switch that estimates image motion for traces even when no gate uses it; main thread. */
  internal var flowMotionEnabled = false
    set(value) {
      field = value
      liveDetector.flowMotionEnabled = value || props.flowMotion || props.guide
    }
  private val displayManager = themedContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
      if (previewView.display?.displayId == displayId) updateTargetRotation(currentDisplayRotation())
    }
  }
  private val metadataExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val pendingPromises = IdentityHashMap<Promise<*>, Unit>()

  private var disposed = false
  /** Main thread: the viewport or a use-case prop changed while a photo was in flight; rebind once captures settle. */
  private var rebindPending = false
  /** Main thread: removes the permission listener while the view is attached. */
  private var removePermissionListener: (() -> Unit)? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var previewUseCase: Preview? = null
  private var imageCapture: ImageCapture? = null
  private var imageAnalysis: ImageAnalysis? = null
  private var observedCameraState: LiveData<CameraState>? = null
  private var cameraStateObserver: Observer<CameraState>? = null
  private var activityLifecycleOwner: LifecycleOwner? = null
  private var activityLifecycleObserver: LifecycleEventObserver? = null
  private var providerRequestGeneration = 0L
  private var cameraStartRequestInFlight = false
  private var targetRotation = Surface.ROTATION_0
  private var displayListenerRegistered = false
  /** Main thread: focus lifecycle on the bound camera; capture and analysis read its snapshot. */
  private val focus = FocusController(object : FocusController.Host {
    override val camera: Camera? get() = boundCamera
    override val mainExecutor get() = ContextCompat.getMainExecutor(themedContext)
    override fun shotInFlight() = hasShotInFlight()
    override fun applyCaptureOptions() = this@HybridDocumentScannerCameraView.applyCaptureOptions()
    override fun postDelayed(delayMs: Long, block: () -> Unit) { mainHandler.postDelayed(block, delayMs) }
    override fun log(message: String) = logLifecycle(message)
    override fun focusChanged(snapshot: FocusSnapshot) {
      // Analysis may trust frames without their own metadata only while an owned scan holds a lock.
      scheduleCaptureEvaluation()
    }
  })
  /** Main thread: what the bound use cases were built with, which hardware-dependent decisions read; null unbound. */
  private var bound: BoundCapture? = null

  override val view: View
    get() = container

  private val tapDetector = GestureDetector(themedContext, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(event: MotionEvent) = true
    override fun onSingleTapUp(event: MotionEvent): Boolean {
      onPreviewTap(event.x, event.y)
      return true
    }
  })
  private val streamObserver = Observer<PreviewView.StreamState> { updateCameraState() }
  private var boundCamera: Camera? = null
  // Last state passed to each callback; main thread. Null re-sends the current state to a newly set callback.
  private var emittedCameraState: ScannerCameraState? = null
  private var emittedAutofocus: AutofocusState? = null
  private var documentState = DocumentState.OFF
  private var emittedDocumentState: DocumentState? = null
  // Capture-callback state; metadataLock orders a late callback against stopCamera's reset.
  private val metadataLock = Any()
  /** The binding whose capture results are accepted; stopCamera retires it. */
  @Volatile private var metadataGeneration = 0L
  @Volatile private var autofocusState = AutofocusState.INACTIVE
  /** Main-thread guidance retained while a capture's waiting reason takes priority. */
  private var hintStatus = LiveGuideStatus.SEARCHING
  /** Auto-torch decision for the current binding, fed by capture results; guarded by [metadataLock]. */
  private val torchPolicy = TorchPolicy()
  @Volatile private var torchAuto = false
  /** Latest exposure time (ms) times ISO, for the stats strip. */
  @Volatile private var exposureIsoMs: Double? = null
  /** What the bound camera confirmed its torch is; main thread, null until confirmed for this binding. */
  private var torchApplied: Boolean? = null
  /** Main thread: the torch change the bound camera has not confirmed yet. */
  private var torchRequest: TorchRequest? = null
  /** Uptime when [autofocusState] last changed. */
  /** takePhoto calls in flight, until the shot has settled; guarded by this. */
  private val captures = IdentityHashMap<Promise<*>, GatedCapture>()
  /** Main thread: the bound camera moves its lens to focus; a fixed-focus camera never reports a focus state. */
  private var hasAutofocus = false
  /** Main thread: the bound camera's nearest focus in diopters when its lens can be parked, else null. */
  private var parkRange: Float? = null
  /** Main thread: `scanner` mode's document focus applies, so the lens parks whenever no owned scan holds it. */
  private val parksFocus: Boolean get() = props.documentFocus && props.guide && parkRange != null

  override var onCameraStateChange: ((state: ScannerCameraState) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post { emittedCameraState = null; updateCameraState() }
    }
  override var onAutofocusChange: ((state: AutofocusState) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post { emittedAutofocus = null; emitAutofocus() }
    }
  override var onDocumentStateChange: ((state: DocumentState) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post { emittedDocumentState = null; updateDocumentState(documentState) }
    }
  override var onCaptureStage: ((stage: CaptureStage) -> Unit)? = null

  // Raw React props; afterUpdate resolves them into [props] once per batch.
  override var quadTensorModel: String? = null
  private var requestedModel: String? = null
  private var modelChecked = false
  private var modelReady = false
  private var modelLoadPending = false
  private var modelGeneration = 0L
  private var modelFailure: Throwable? = null
  override var onError: ((ScannerFailure) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post { if (!disposed) modelFailure?.let { value?.invoke(scannerFailure(it, ScannerErrorCode.MODEL_LOAD_FAILED)) } }
    }
  override var mode: CameraMode? = null
  override var guide: Boolean? = null
  override var adaptiveDetection: Boolean? = null
  override var minCornerScore: Double? = null
  override var qualityGate: Boolean? = null
  override var qualityGateConfig: QualityGateConfig? = null
  override var tapToFocus: Boolean? = null
  override var exposureCompensation: Double? = null
  override var minFrameRate: Double? = null
  override var torch: TorchMode? = null
  override var captureMode: CaptureMode? = null
  override var jpegQuality: Double? = null
  override var guideTransitionDuration: Double? = null
  override var analysisWidth: Double? = null
  override var squareFrame: Boolean? = null
  override var showStats: Boolean? = null
  override var guideColor: Double? = null
  override var captureFeedback: Boolean? = null
  override var retainCapturePreview: Boolean? = null
  override var captureColor: Double? = null
  /** Applied prop values with defaults; main thread. */
  private var props = resolveProps()

  /** Main thread, after each React prop batch; tests that set props directly call it themselves. */
  override fun afterUpdate() {
    if (disposed) return
    updateModel()
    val old = props
    val next = resolveProps()
    if (next == old) return
    props = next
    if (!next.retainCapturePreview && !hasPendingCapture()) clearCapturePreview()
    liveDetector.adaptiveDetection = next.adaptiveDetection
    liveDetector.minCornerScore = next.minCornerScore
    if (old.guide != next.guide || old.documentFocus != next.documentFocus) {
      focus.resetPolicy(keepDocument = next.guide || hasPendingCapture())
    }
    liveDetector.flowGate = next.flowMotion
    liveDetector.flowMotionEnabled = flowMotionEnabled || next.flowMotion || next.guide
    liveOverlay.transitionNs = next.guideTransitionNs
    liveOverlay.color = next.guideColor
    captureFeedbackView.color = next.captureColor
    if (!next.captureFeedback) captureFeedbackView.clear()
    if (old.exposureCompensation != next.exposureCompensation) applyExposureCompensation()
    if (old.minFrameRate != next.minFrameRate || old.guide != next.guide || old.documentFocus != next.documentFocus) {
      applyCaptureOptions()
    }
    if (old.torch != next.torch) {
      synchronized(metadataLock) {
        torchPolicy.reset()
        torchAuto = next.torch == TorchMode.AUTO
      }
      applyTorch()
    }
    liveDetector.statsListener = if (next.showStats) ::renderStats else null
    if (old.showStats != next.showStats) {
      statsFigures = ""
      statsLabel.visibility = if (next.showStats) View.VISIBLE else View.GONE
      renderStatus()
      layoutStats()
    }
    if (old.squareFrame != next.squareFrame && !hasPendingCapture()) layoutStats()
    // A rebind applies the guide when it binds; a deferred one leaves the bound camera, which applies it below.
    var rebinding = false
    if (old.analysisWidth != next.analysisWidth || old.captureMode != next.captureMode ||
      old.jpegQuality != next.jpegQuality || old.squareFrame != next.squareFrame) {
      logLifecycle("use cases changed analysisWidth=${next.analysisWidth} captureMode=${next.captureMode} " +
        "jpegQuality=${next.jpegQuality} squareFrame=${next.squareFrame}")
      if (hasPendingCapture()) {
        logLifecycle("use-case rebind deferred until capture settles")
        rebindPending = true
      } else if (next.active && container.isAttachedToWindow) {
        rebinding = true
        stopCamera("use-cases-changed")
        if (readPermissionStatus() == CameraPermissionStatus.GRANTED) startCamera()
      } else {
        rebinding = true
      }
    }
    if (old.active != next.active) {
      if (next.active && !rebinding) {
        onHostViewVisible()
      } else if (!next.active && !hasPendingCapture()) {
        stopCamera("inactive")
      }
      // An in-flight capture uses the current binding until its final shot settles. Its completion reads the mode.
    }
    if (!rebinding && old.guide != next.guide) {
      liveDetector.setEnabled(next.guide)
      liveOverlay.clear()
      trackedGuide = null
      latestModelScore = null
      showHint(if (next.active && next.guide && liveDetector.hasFailed()) LiveGuideStatus.UNAVAILABLE else LiveGuideStatus.SEARCHING)
      updateDocumentState(boundDocumentState())
    }
    updateGyroscope()
  }



  /** Main thread: source changes invalidate only this view's completion; in-flight captures settle first. */
  private fun updateModel() {
    if (modelChecked && requestedModel == quadTensorModel) return
    modelChecked = true
    requestedModel = quadTensorModel
    modelGeneration++
    modelReady = false
    modelFailure = null
    modelLoadPending = true
    if (!hasPendingCapture()) loadRequestedModel()
  }

  private fun loadRequestedModel() {
    if (!modelLoadPending || disposed) return
    modelLoadPending = false
    stopCamera("model-loading")
    val generation = modelGeneration
    val uri = requestedModel
    fun failed(error: Throwable) {
      if (disposed || generation != modelGeneration) return
      modelFailure = if (error is ScannerException) error else ScannerException(ScannerErrorCode.MODEL_LOAD_FAILED, error)
      modelReady = false
      updateCameraState()
      updateDocumentState(DocumentState.UNAVAILABLE)
      onError?.invoke(scannerFailure(error, ScannerErrorCode.MODEL_LOAD_FAILED))
      Log.e("DocumentScanner", "Could not prepare quad tensor model", error)
    }
    if (uri.isNullOrEmpty()) {
      failed(ScannerException(ScannerErrorCode.INPUT_INVALID))
      return
    }
    QuadTensorModelLoader.load(themedContext.applicationContext, uri).then {
      mainHandler.post {
        if (!disposed && generation == modelGeneration) {
          modelReady = true
          onHostViewVisible()
        }
      }
    }.catch { error -> mainHandler.post { failed(error) } }
  }

  private fun resolveProps(): ResolvedProps {
    val minimumScore = minCornerScore ?: 0.95
    requireInput(minimumScore.isFinite() && minimumScore in 0.0..1.0) { "minCornerScore must be between 0 and 1." }
    val gated = qualityGate ?: true
    val config = qualityGateConfig
    val maxCaptureBlur = if (!gated) 0.0
      else (config?.captureBlurPercent ?: DEFAULT_CAPTURE_BLUR_PERCENT).coerceAtLeast(0.0) / 100
    val focusTimeoutMs = if (!gated) 0L
      else config?.focusTimeout?.takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0L) ?: DEFAULT_FOCUS_TIMEOUT_MS
    return ResolvedProps(
      active = mode != CameraMode.OFF,
      focusTimeoutMs = focusTimeoutMs,
      requireFocus = gated && (config?.requireFocus ?: true),
      guide = guide ?: true,
      adaptiveDetection = adaptiveDetection ?: false,
      minCornerScore = minimumScore,
      qualityGate = gated,
      maxCaptureBlur = maxCaptureBlur,
      flowMotion = maxCaptureBlur > 0,
      // A photo on a parked lens waits for the document or tap focus lock.
      documentFocus = (mode ?: CameraMode.SCANNER) == CameraMode.SCANNER && focusTimeoutMs > 0,
      tapToFocus = tapToFocus ?: true,
      exposureCompensation = exposureCompensation?.takeIf { it.isFinite() } ?: 0.0,
      minFrameRate = minFrameRate?.takeIf { it.isFinite() }?.toInt()?.coerceAtLeast(0) ?: 0,
      torch = torch ?: TorchMode.OFF,
      guideTransitionNs = guideTransitionDuration?.let { (it * 1_000_000).toLong() } ?: QuadTransition.DEFAULT_DURATION_NS,
      squareFrame = squareFrame ?: false,
      analysisWidth = analysisWidth?.toInt()?.coerceIn(160, 1920) ?: DEFAULT_ANALYSIS_WIDTH,
      captureMode = captureMode ?: CaptureMode.ZSL,
      // Negative or non-finite means the capture mode's own quality; a prop setter must not throw.
      jpegQuality = jpegQuality?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { jpegQualityPercent(it.coerceAtMost(1.0)).coerceAtLeast(1) } ?: 0,
      showStats = showStats ?: false,
      // processColor yields a signed or unsigned 32-bit ARGB number.
      guideColor = guideColor?.toLong()?.toInt() ?: LiveQuadOverlay.DEFAULT_COLOR,
      captureFeedback = captureFeedback ?: true,
      retainCapturePreview = retainCapturePreview ?: false,
      captureColor = captureColor?.toLong()?.toInt() ?: CaptureFeedbackView.DEFAULT_COLOR,
    )
  }

  init {
    logLifecycle("view created permission=${readPermissionStatus()}")
    previewView.installHierarchyFitter()
    previewView.previewStreamState.observeForever(streamObserver)
    container.setOnTouchListener { _, event -> tapDetector.onTouchEvent(event) }
    container.addView(
      previewView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )
    container.addView(
      liveOverlay,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    container.addView(
      frozenFrame,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    container.addView(
      captureFeedbackView,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    container.addView(
      statsLabel,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
    )
    container.addView(
      liveHint,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
    )
    container.addView(
      statusView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
      ),
    )
    showPermissionState(readPermissionStatus())
  }

  override fun takePhotoResult(options: TakePhotoOptions?): Promise<Variant_ScannerFailure_CapturedPhoto> =
    takePhoto(options).scannerResult(ScannerErrorCode.CAPTURE_FAILED,
      { Variant_ScannerFailure_CapturedPhoto.create(it) }, { Variant_ScannerFailure_CapturedPhoto.create(it) })

  override fun saveLiveSampleResult(): Promise<Variant_String_ScannerFailure> {
    val frame = liveDetector.frameRecorder.latest()
    val saved = if (frame == null && SampleCollection.active(themedContext) != null) {
      Promise<String>().also { it.reject(ScannerException(ScannerErrorCode.CAMERA_NOT_READY)) }
    } else if (frame == null) {
      Promise<String>().also { it.reject(InvalidInputException("Sample collection is off.")) }
    } else {
      SampleCollection.saveLive(themedContext.applicationContext, frame)
    }
    return saved.scannerResult(ScannerErrorCode.STORAGE_WRITE_FAILED,
      { Variant_String_ScannerFailure.create(it) }, { Variant_String_ScannerFailure.create(it) })
  }

  internal fun takePhoto(options: TakePhotoOptions? = null): Promise<CapturedPhoto> {
    val promise = createPromise<CapturedPhoto>() ?: return Promise<CapturedPhoto>().also {
      it.reject(ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE))
    }
    val skipQualityGate = options?.skipQualityGate == true
    mainHandler.post { takePhotoOnMain(GatedCapture(promise, SystemClock.uptimeMillis(), skipQualityGate)) }
    return promise
  }

  private fun onHostViewAttached() {
    targetRotation = currentDisplayRotation()
    logLifecycle("host attached rotation=$targetRotation permission=${readPermissionStatus()}")
    observeActivityLifecycle()
    if (!displayListenerRegistered) {
      displayManager.registerDisplayListener(displayListener, mainHandler)
      displayListenerRegistered = true
    }
    if (removePermissionListener == null) removePermissionListener = CameraPermissions.addListener(::onPermissionChange)
    showPermissionState(readPermissionStatus())
    onHostViewVisible()
  }

  private fun onPermissionChange(status: CameraPermissionStatus) {
    logLifecycle("permission changed=$status")
    showPermissionState(status)
    if (status == CameraPermissionStatus.GRANTED) onHostViewVisible() else stopCamera("permission-$status")
  }

  private fun observeActivityLifecycle() {
    if (activityLifecycleObserver != null) return
    val lifecycleOwner = themedContext.currentActivity as? LifecycleOwner
    if (lifecycleOwner == null) {
      logLifecycle("activity lifecycle observer skipped: Activity is not a LifecycleOwner")
      return
    }
    val observer = LifecycleEventObserver { _, event ->
      logLifecycle("activity lifecycle event=$event state=${lifecycleOwner.lifecycle.currentState}")
      when (event) {
        Lifecycle.Event.ON_START -> onHostViewVisible()
        Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stopCamera("activity-${event.name.lowercase()}")
        else -> Unit
      }
    }
    activityLifecycleOwner = lifecycleOwner
    activityLifecycleObserver = observer
    lifecycleOwner.lifecycle.addObserver(observer)
    logLifecycle("activity lifecycle observer attached state=${lifecycleOwner.lifecycle.currentState}")
  }

  private fun removeActivityLifecycleObserver() {
    val lifecycleOwner = activityLifecycleOwner
    val observer = activityLifecycleObserver
    if (lifecycleOwner != null && observer != null) lifecycleOwner.lifecycle.removeObserver(observer)
    activityLifecycleOwner = null
    activityLifecycleObserver = null
  }

  private fun onHostViewVisible() {
    logLifecycle("host visible attached=${container.isAttachedToWindow} visibility=${container.windowVisibility} permission=${readPermissionStatus()}")
    if (container.isAttachedToWindow && container.windowVisibility == View.VISIBLE &&
      readPermissionStatus() == CameraPermissionStatus.GRANTED) {
      startCamera()
    }
  }

  private fun onHostViewDetached() {
    logLifecycle("host detached")
    stopCamera("view-detached")
    removeActivityLifecycleObserver()
    unregisterDisplayListener()
    removePermissionListener?.invoke()
    removePermissionListener = null
  }

  override fun onDropView() {
    val promises = synchronized(this) {
      if (disposed) return
      logLifecycle("view dropped pendingPromises=${pendingPromises.size}")
      disposed = true
      pendingPromises.keys.toList().also { pendingPromises.clear() }
    }
    promises.forEach { it.reject(ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE)) }
    removePermissionListener?.invoke()
    removePermissionListener = null
    removeActivityLifecycleObserver()
    unregisterDisplayListener()
    stopCamera("view-dropped")
    clearCapturePreview()
    liveDetector.close()
    metadataExecutor.shutdown()
    previewView.previewStreamState.removeObserver(streamObserver)
  }

  /** Main thread: registers [capture] on its first call, rechecks that it can still shoot on every call, and evaluates readiness.
   * It runs again only on an event: new frames, a focus or torch change, or the capture's deadline.
   */
  private fun takePhotoOnMain(capture: GatedCapture) {
    val promise = capture.promise
    if (!isPending(promise)) return
    if (!modelReady && !capture.registered) {
      rejectPromise(promise, modelFailure ?: ScannerException(ScannerErrorCode.CAMERA_NOT_READY))
      return
    }
    if (!props.active && !capture.registered) {
      rejectPromise(promise, ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE))
      return
    }
    if (!capture.registered) {
      logLifecycle("capture requested attached=${container.isAttachedToWindow} permission=${readPermissionStatus()}")
    }
    if (disposed) {
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE))
      return
    }
    if (!container.isAttachedToWindow) {
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE))
      return
    }
    if (readPermissionStatus() != CameraPermissionStatus.GRANTED) {
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.CAMERA_PERMISSION_DENIED))
      return
    }
    val captureUseCase = imageCapture
    if (captureUseCase == null) {
      logLifecycle("capture rejected: ImageCapture is not bound")
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.CAMERA_NOT_READY))
      return
    }
    if (!capture.registered) {
      capture.registered = true
      // Judgment starts from the frames and focus that follow the press.
      capture.frameCursor = liveDetector.frameHistory.cursor()
      capture.focus = focus.snapshot
      synchronized(this) { captures[promise] = capture }
      // Frame results reach main only while a capture waits; evaluateCaptures unsubscribes once none does.
      liveDetector.frameAnalysisListener = ::evaluateCaptures
      updateCameraState()
      advanceCapture(capture, CaptureStage.WAITING)
    }
    evaluateCapture(capture, captureUseCase)
  }

  /** Main thread: re-evaluates waiting captures after an event changes their readiness. Each goes back
   * through [takePhotoOnMain], so the view, permission and bound camera are rechecked after the wait.
   */
  private fun evaluateCaptures() {
    val waiting = synchronized(this) { captures.values.filter { it.registered && it.stage == CaptureStage.WAITING } }
    if (waiting.isEmpty()) liveDetector.frameAnalysisListener = null
    waiting.forEach(::takePhotoOnMain)
  }

  /** Coalesces events that arrive in one main-thread turn, such as a focus change inside a detection callback. */
  private fun scheduleCaptureEvaluation() {
    mainHandler.removeCallbacks(evaluateCapturesTask)
    mainHandler.post(evaluateCapturesTask)
  }
  private val evaluateCapturesTask = Runnable { evaluateCaptures() }

  /**
   * Main thread: one evaluation of [capture], on an event rather than a poll: new frame results, a focus change, a torch
   * change or a deadline. Frames are consumed in order, each once; those exposed before the latest focus change are
   * skipped, and the change restarts the stability sequence, so a refocus is revalidated. The readiness state then goes to the capture's
   * [CaptureGate], whose decision alone waits, shoots or rejects.
   */
  private fun evaluateCapture(capture: GatedCapture, captureUseCase: ImageCapture) {
    if (!isPending(capture.promise) || capture.stage != CaptureStage.WAITING) return
    val snapshot = focus.snapshot
    if (snapshot.generation != capture.focus.generation) {
      capture.focus = snapshot
      capture.stability.reset()
      capture.latest = null
    }
    val blurLimit = props.maxCaptureBlur
    val motionCheckEnabled = props.qualityGate && blurLimit > 0
    val documentRequired = documentGate
    val nowMs = SystemClock.uptimeMillis()
    val focusCheckEnabled = props.qualityGate && (hasAutofocus || documentRequired) &&
      !(props.focusTimeoutMs <= 0 && !props.requireFocus)
    // A frame's own autofocus state: document detection's scan, or a tap's, must still report its lock; otherwise
    // passive focus counts too.
    fun frameFocused(afState: Int?) = !hasAutofocus || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
      !documentRequired && afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
    val (frames, missed) = liveDetector.frameHistory.since(capture.frameCursor)
    // Whether this evaluation read a record analysis just published, for the log: shooting on one an earlier evaluation
    // read, after a focus or torch change, means analysis had not produced a newer frame.
    var newFrame = false
    if (missed && motionCheckEnabled) capture.stability.interrupt()
    for (frame in frames) {
      capture.frameCursor = frame.sequence + 1
      if (!frame.after(snapshot.changedRealtimeNs, snapshot.changedNs)) continue
      capture.latest = frame
      newFrame = true
      if (!motionCheckEnabled) continue
      // A frame exposed while focus was lost does not continue the sequence; one without its result cannot tell.
      val state = frame.frame
      if (focusCheckEnabled && !capture.gate.focusSkipped && state != null && !frameFocused(state.afState)) {
        capture.stability.interrupt(StabilityBreak.UNFOCUSED)
        continue
      }
      capture.stability.observe(frame, documentRequired && !capture.gate.focusSkipped, blurLimit)
    }
    val latest = capture.latest
    val binding = bound
    val afState = latest?.frame?.afState
    val focused = when {
      // Document detection owns focus: its scan's lock, or a tap's, still reported by the frame.
      documentRequired -> !hasAutofocus || snapshot.lockHeld(nowMs) && frameFocused(afState)
      // A tap's scan in progress is waited for; otherwise the frame's own autofocus state decides.
      hasAutofocus && snapshot.owner == FocusOwner.TAP && snapshot.scanHolds(nowMs) && snapshot.locked == null -> false
      else -> frameFocused(afState)
    }
    // Zero shutter lag saves only frames CameraX finds focused, exposed and balanced; otherwise it takes a new one.
    val waitForZsl = props.qualityGate && binding?.zsl == true && hasAutofocus &&
      !(props.focusTimeoutMs <= 0 && !props.requireFocus)
    val torchPending = torchRequest?.future?.isDone == false
    val captureState = CaptureState(
      nowMs, focusCheckEnabled, documentRequired, props.requireFocus, props.focusTimeoutMs,
      documentFound = latest?.guide != null,
      focused = focused,
      zslCheckEnabled = waitForZsl,
      zslReady = latest?.zslReady == true,
      torchReady = !torchPending,
      motionCheckEnabled = motionCheckEnabled,
      // The sequence must reach the newest frame, so a sequence that stopped short of it is not steady.
      steady = capture.stability.stable && latest != null && latest.sensorNs == capture.stability.latestSensorNs,
    )
    fun traceGate(fired: Boolean) = liveDetector.traceObserver?.onCaptureGate(LiveCaptureGateSample(
      SystemClock.elapsedRealtimeNanos(), fired, latest?.realtimeSensorNs, latest?.frame?.exposureNs, latest?.motion?.blur, latest?.motion?.source,
      latest?.flow, null, latest?.ageNanos(System.nanoTime(), SystemClock.elapsedRealtimeNanos()),
    ))
    when (val decision = capture.gate.next(captureState)) {
      is CaptureDecision.Wait -> {
        if (capture.reason != decision.reason) logLifecycle("capture waiting for ${decision.reason}")
        capture.reason = decision.reason
        showHint(hintStatus)
        mainHandler.removeCallbacksAndMessages(capture.deadlineToken)
        mainHandler.postAtTime({ evaluateCaptures() }, capture.deadlineToken, decision.untilMs)
      }
      is CaptureDecision.Reject -> {
        logLifecycle("capture rejected: ${decision.code} waiting for ${decision.reason} focusGeneration=${snapshot.generation} " +
          "document=${captureState.documentFound} focused=$focused torchReady=${captureState.torchReady} zsl=${latest?.zslReady} " +
          "stableFrames=${capture.stability.count} break=${capture.stability.lastBreak}")
        traceGate(fired = false)
        rejectPromise(capture.promise, ScannerException(decision.code))
      }
      CaptureDecision.Shoot -> {
        if (motionCheckEnabled) {
          val realtimeNs = SystemClock.elapsedRealtimeNanos()
          logLifecycle("capture approved stableFrames=${capture.stability.count} frames=" +
            "${(realtimeNs - capture.stability.firstSensorNs) / 1_000_000}..${(realtimeNs - capture.stability.latestSensorNs) / 1_000_000}ms ago")
        }
        if (!newFrame && latest != null) logLifecycle("capture approved on an earlier frame record age=" +
          "${latest.ageNanos(System.nanoTime(), SystemClock.elapsedRealtimeNanos()) / 1_000_000}ms")
        if (capture.gate.focusSkipped) logLifecycle("capture focus not settled document=${captureState.documentFound}; shooting")
        traceGate(fired = true)
        shoot(capture, captureUseCase)
      }
    }
  }

  /** Main thread: [capture] passed its gate; takes the shot and frees the waiting state. */
  private fun shoot(capture: GatedCapture, captureUseCase: ImageCapture) {
    val requestedMs = capture.requestedMs
    mainHandler.removeCallbacksAndMessages(capture.deadlineToken)
    capture.reason = null
    showHint(hintStatus)
    val outputDirectory = File(themedContext.cacheDir, CACHE_DIRECTORY_NAME)
    if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) {
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.STORAGE_WRITE_FAILED))
      return
    }
    val file = File(outputDirectory, "document-scan-${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
    // Stage times in ms since the request, for comparing capture settings in the lifecycle log.
    val elapsed = { SystemClock.uptimeMillis() - requestedMs }
    logLifecycle("capture timing steady=${elapsed()}ms mode=${bound?.captureMode} jpegQuality=${bound?.jpegQuality}")
    previewView.bitmap?.let {
      frozenFrame.setImageBitmap(it)
      frozenFrame.visibility = View.VISIBLE
    }
    capture.liveCandidate = trackedGuide
    capture.frame = CompletableFuture<LiveFrame>().also { frame -> liveDetector.requestFrame { frame.complete(it) } }
    capture.sample = SampleCollection.beginCapture(themedContext, liveDetector.frameRecorder)
    capture.shutterRealtimeNs = SystemClock.elapsedRealtimeNanos()
    advanceCapture(capture, CaptureStage.SHOOTING)
    try {
      saveShot(captureUseCase, file, capture, elapsed)
    } catch (error: Throwable) {
      file.delete()
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.CAPTURE_FAILED, error))
    }
  }

  /**
   * Main thread: saves [capture]'s one shot to [file], reads its metadata off the main thread, then resolves with it.
   * The user may move once its exposure has started. The file is deleted unless the caller receives it.
   */
  private fun saveShot(useCase: ImageCapture, file: File, capture: GatedCapture, elapsed: () -> Long) {
    val promise = capture.promise
    useCase.takePicture(
      ImageCapture.OutputFileOptions.Builder(file).build(),
      ContextCompat.getMainExecutor(themedContext),
      object : ImageCapture.OnImageSavedCallback {
        override fun onCaptureStarted() {
          logLifecycle("capture shot started=${elapsed()}ms")
          advanceCapture(capture, CaptureStage.CAPTURED)
        }

        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
          logLifecycle("capture shot saved=${elapsed()}ms bytes=${file.length()}")
          if (!isPending(promise)) {
            file.delete()
            endCapture(promise)
            return
          }
          metadataExecutor.execute {
            val described = runCatching { describePhoto(file, capture.liveCandidate, capture.frame) }
            mainHandler.post {
              val photo = described.getOrNull()
              if (photo == null) {
                logLifecycle("capture shot metadata read failed")
                file.delete()
                rejectPromise(promise, ScannerException(ScannerErrorCode.CAPTURE_METADATA_FAILED, described.exceptionOrNull()))
                return@post
              }
              // Registered before the photo resolves, so analysis of the photo always finds its capture.
              capture.sample?.let { SampleCollection.remember(file.name, it) }
              val kept = resolvePromise(promise, photo)
              capture.delivered = kept
              if (kept) advanceCapture(capture, CaptureStage.CAPTURED)
              if (!kept) {
                SampleCollection.forget(listOf(file.name))
                file.delete()
                photo.frameUri?.let { Uri.parse(it).path?.let(::File)?.delete() }
              }
              logLifecycle("capture complete total=${elapsed()}ms resolution=${photo.width}x${photo.height} " +
                "orientation=${photo.orientation}")
              endCapture(promise)
            }
          }
        }

        override fun onError(exception: ImageCaptureException) {
          logLifecycle("capture shot failed code=${exception.imageCaptureError} message=${exception.message}")
          Log.e(LOG_TAG, "CameraX photo capture failed with error ${exception.imageCaptureError}.", exception)
          file.delete()
          rejectPromise(promise, ScannerException(ScannerErrorCode.CAPTURE_FAILED, exception))
        }
      },
    )
  }

  /** Main thread: `scanner` mode with the guide, where a capture waits for document detection to own its focus. */
  private val documentGate: Boolean get() = props.qualityGate && props.documentFocus && props.guide

  /** Main thread; provisional geometry selects focus, but only focus-eligible accepted quads update its scale reference. */
  private fun onFocusCandidate(hint: LiveFocusCandidate) {
    if (!props.active || !props.guide || !hasAutofocus) return
    focus.onCandidate(hint, parked = parksFocus, point = ::meteringPoint)
  }

  /** Main thread: [hint]'s page centre as a metering point on the preview, or null when it maps outside it. */
  private fun meteringPoint(hint: LiveFocusCandidate): MeteringPoint? {
    val target = previewView.outputTransform
    val quad = hint.quad
    val points = if (quad == null || target == null) null else floatArrayOf(
      ((quad.topLeft.x + quad.topRight.x + quad.bottomRight.x + quad.bottomLeft.x) * hint.width / 4).toFloat(),
      ((quad.topLeft.y + quad.topRight.y + quad.bottomRight.y + quad.bottomLeft.y) * hint.height / 4).toFloat(),
    ).let { p ->
      try { CoordinateTransform(hint.source, target).mapPoints(p); p } catch (_: IllegalArgumentException) { null }
    }
    return points?.takeIf {
      it.all(Float::isFinite) && it[0] in 0f..previewView.width.toFloat() && it[1] in 0f..previewView.height.toFloat()
    }?.let { previewView.meteringPointFactory.createPoint(it[0], it[1]) }
  }

  private fun startCamera() {
    if (!modelReady) return
    if (!props.active) {
      logLifecycle("camera start skipped: inactive")
      return
    }
    if (disposed) {
      logLifecycle("camera start skipped: view disposed")
      return
    }
    if (!container.isAttachedToWindow || container.windowVisibility != View.VISIBLE) {
      logLifecycle("camera start skipped: view not visible/attached")
      return
    }
    if (container.width <= 0 || container.height <= 0) {
      logLifecycle("camera start deferred: view not measured size=${container.width}x${container.height}")
      return
    }
    val permissionStatus = readPermissionStatus()
    if (permissionStatus != CameraPermissionStatus.GRANTED) {
      logLifecycle("camera start skipped: permission=$permissionStatus")
      return
    }
    if (cameraProvider != null && previewUseCase != null && imageCapture != null) {
      logLifecycle("camera start skipped: already bound")
      return
    }
    if (cameraStartRequestInFlight) {
      logLifecycle("camera start skipped: provider request in flight")
      return
    }

    val lifecycleOwner = themedContext.currentActivity as? LifecycleOwner
    if (lifecycleOwner == null) {
      showMessage(R.string.document_scanner_camera_activity_unavailable)
      return
    }
    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

    val generation = ++providerRequestGeneration
    cameraStartRequestInFlight = true
    updateCameraState()
    logLifecycle("camera provider requested generation=$generation size=${container.width}x${container.height} rotation=$targetRotation")
    val future = try {
      ProcessCameraProvider.getInstance(themedContext)
    } catch (_: Throwable) {
      cameraStartRequestInFlight = false
      updateCameraState()
      logLifecycle("camera provider request failed")
      showMessage(R.string.document_scanner_camera_start_failed)
      return
    }
    future.addListener({
      if (generation != providerRequestGeneration || disposed || !container.isAttachedToWindow) {
        logLifecycle("camera provider result ignored generation=$generation currentGeneration=$providerRequestGeneration disposed=$disposed attached=${container.isAttachedToWindow}")
        return@addListener
      }
      cameraStartRequestInFlight = false
      if (!props.active) {
        updateCameraState()
        return@addListener
      }
      if (readPermissionStatus() != CameraPermissionStatus.GRANTED) {
        logLifecycle("camera provider result ignored: permission=${readPermissionStatus()}")
        updateCameraState()
        showPermissionState(readPermissionStatus())
        return@addListener
      }
      try {
        val provider = future.get()
        layoutStats()
        val preview = Preview.Builder()
          .setTargetRotation(targetRotation)
          .build()
          .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = ImageCapture.Builder()
          .setCaptureMode(
            when (props.captureMode) {
              CaptureMode.QUALITY -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
              CaptureMode.LATENCY -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
              // CameraX falls back to MINIMIZE_LATENCY where zero shutter lag is unsupported.
              CaptureMode.ZSL -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            },
          )
          .also { if (props.jpegQuality > 0) it.setJpegQuality(props.jpegQuality) }
          .also { observeStillFrames(it, generation) }
          .setResolutionSelector(
            ResolutionSelector.Builder()
              // Reorder all CameraX-advertised sizes across aspect ratios around the target.
              // Keep fallbacks so the three bound use cases can negotiate a supported combination.
              .setResolutionFilter { supportedSizes, _ ->
                CaptureResolutionPolicy.rank(supportedSizes, { it.width }, { it.height }).also { ranked ->
                  logLifecycle("capture candidates=${ranked.joinToString()} target=3200x2400")
                }
              }
              .build(),
          )
          .setTargetRotation(targetRotation)
          .build()
        // Page thumbnails match the live frame, so a capture's latest frame and its renders share one size.
        PageThumbnails.longSide = props.analysisWidth
        val analysis = ImageAnalysis.Builder()
          .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(props.analysisWidth.let { Size(it, it * 3 / 4) }, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
          ).build())
          .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setTargetRotation(targetRotation)
          .also { observeFrameState(it, generation) }
          .build()
        check(previewView.width > 0 && previewView.height > 0) { "The camera viewport is not measured yet." }
        // FIT keeps the whole frame in every use case. A square viewport crops all three to the frame's centered
        // square, so the saved JPEG and the analysis frames match what the preview shows.
        val viewport = (if (props.squareFrame) ViewPort.Builder(Rational(1, 1), targetRotation).setScaleType(ViewPort.FILL_CENTER)
          else ViewPort.Builder(Rational(previewView.width, previewView.height), targetRotation).setScaleType(ViewPort.FIT))
          .setLayoutDirection(previewView.layoutDirection).build()
        val group = UseCaseGroup.Builder().setViewPort(viewport)
          .addUseCase(preview).addUseCase(capture).addUseCase(analysis).build()
        liveDetector.begin(props.guide)
        analysis.setAnalyzer(liveDetector.executor, liveDetector.boundAnalyzer())
        val camera = try {
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        } catch (error: Throwable) {
          analysis.clearAnalyzer()
          liveDetector.stop()
          throw error
        }
        cameraProvider = provider
        boundCamera = camera
        // OFF and EDOF do not scan; minimum focus distance may be missing, so it cannot decide.
        val cameraInfo = Camera2CameraInfo.from(camera.cameraInfo)
        val afModes = cameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        parkRange = cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf {
          canParkFocus(afModes, cameraInfo.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES), it)
        }
        liveDetector.cameraOptics = backCameraOptics(camera.cameraInfo)
        // The ImageCapture above was built from these props in this same call; a later change waits for a rebind.
        bound = BoundCapture(props.captureMode, props.jpegQuality,
          zsl = props.captureMode == CaptureMode.ZSL && camera.cameraInfo.isZslSupported)
        if (props.captureMode == CaptureMode.ZSL) logLifecycle("zero shutter lag supported=${camera.cameraInfo.isZslSupported}")
        applyExposureCompensation()
        applyCaptureOptions()
        applyTorch()
        updateGyroscope()
        previewUseCase = preview
        imageCapture = capture
        imageAnalysis = analysis
        if (debugLoggingEnabled) {
          val stateLiveData = camera.cameraInfo.cameraState
          val observer = Observer<CameraState> { state ->
            logLifecycle("CameraX state=$state")
          }
          stateLiveData.observe(lifecycleOwner, observer)
          observedCameraState = stateLiveData
          cameraStateObserver = observer
        }
        logLifecycle("CameraX bound generation=$generation container=${container.width}x${container.height} preview=${preview.resolutionInfo?.resolution} capture=${capture.resolutionInfo?.resolution} analysis=${analysis.resolutionInfo?.resolution} rotation=$targetRotation shown=${previewView.isShown}")
        statusView.visibility = View.GONE
        updateCameraState()
        updateDocumentState(boundDocumentState())
      } catch (error: Throwable) {
        updateCameraState()
        logLifecycle("camera bind failed generation=$generation message=${error.message}")
        Log.e(LOG_TAG, "CameraX preview binding failed", error)
        showMessage(R.string.document_scanner_camera_start_failed)
      }
    }, ContextCompat.getMainExecutor(themedContext))
  }

  private fun stopCamera(reason: String) {
    rebindPending = false
    providerRequestGeneration += 1L
    cameraStartRequestInFlight = false
    val provider = cameraProvider
    imageAnalysis?.clearAnalyzer()
    liveDetector.stop()
    liveDetector.cameraOptics = null
    synchronized(metadataLock) {
      metadataGeneration = providerRequestGeneration
      liveDetector.frames.clear()
      autofocusState = AutofocusState.INACTIVE
      // A new binding re-evaluates the room lighting; CameraX turns the torch off with the camera.
      torchPolicy.reset()
      exposureIsoMs = null
    }
    torchApplied = null
    torchRequest = null
    boundCamera = null
    bound = null
    parkRange = null
    focus.unbind()
    gyroscope.stop()
    liveOverlay.clear()
    trackedGuide = null
    latestModelScore = null
    showHint(LiveGuideStatus.SEARCHING)
    updateDocumentState(DocumentState.OFF)
    emitAutofocus()
    val useCases = listOfNotNull(previewUseCase, imageCapture, imageAnalysis).toTypedArray()
    logLifecycle("camera stop reason=$reason bound=${provider != null && useCases.isNotEmpty()} generation=$providerRequestGeneration")
    val observer = cameraStateObserver
    val stateLiveData = observedCameraState
    if (observer != null && stateLiveData != null) stateLiveData.removeObserver(observer)
    cameraStateObserver = null
    observedCameraState = null
    if (provider != null && useCases.isNotEmpty()) {
      try {
        provider.unbind(*useCases)
      } catch (_: Throwable) {
        logLifecycle("camera unbind failed reason=$reason")
        // A provider can already be shutting down as its owning Activity stops.
      }
    }
    cameraProvider = null
    previewUseCase = null
    imageCapture = null
    imageAnalysis = null
    // PreviewView may never have left IDLE, so its observer would not report the stop.
    updateCameraState()
  }

  private fun renderStats(stats: LiveDetectionStats) {
    statsFigures = String.format(Locale.US, "%.1f Hz  frame %dx%d\npre %.1f  inf %.1f  total %.1f ms%s",
      stats.rateHz, stats.width, stats.height,
      stats.preprocessingMs, stats.inferenceMs, stats.analysisMs,
      stats.cameraToAnalysisMs?.let { String.format(Locale.US, "\ncamera to analysis %.1f ms", it) } ?: "") +
      String.format(Locale.US, "\nchecks %.2f ms  %s", stats.checksMsPerFrame, stats.adaptiveStatus) +
      String.format(Locale.US, "\ntorch %s%s  exposure x ISO %s", props.torch.name.lowercase(),
        if (torchApplied == true) " lit" else "", exposureIsoMs?.let { String.format(Locale.US, "%.0f", it) } ?: "n/a") +
      (stats.peakBlurPercent?.let { String.format(Locale.US, "\nblur peak %.2f%%  gated %d", it, stats.gatedFrames) } ?: "") +
      (if (stats.unmatchedFrames > 0) String.format(Locale.US, "  no metadata %d", stats.unmatchedFrames) else "")
    renderStatus()
  }

  /** Main thread: the stats strip's first line reports the camera, autofocus and document states. */
  private fun renderStatus() {
    if (!props.showStats) return
    val camera = emittedCameraState?.name?.lowercase() ?: "suspended"
    val focus = emittedAutofocus?.name?.lowercase() ?: "inactive"
    val score = latestModelScore?.let { String.format(Locale.US, "%.4f", it) } ?: "n/a"
    statsLabel.text = "camera $camera  focus $focus\ndocument ${documentState.name.lowercase()}  score $score" +
      if (statsFigures.isEmpty()) "" else "\n$statsFigures"
  }

  /** Main thread: places the stats strip above the preview; React Native's layout never measures native children. */
  private fun layoutStats() {
    val width = container.width
    val height = container.height
    if (width <= 0 || height <= 0) return
    // Explicitly lay out native children: RN does not guarantee another FrameLayout pass after requestLayout.
    // Reserve the same font-scaled height even before the first statistics arrive.
    val top = if (props.showStats) {
      minOf(height, statsLabel.lineHeight * STATS_LINES + statsLabel.paddingTop + statsLabel.paddingBottom)
    } else 0
    fun place(view: View, x: Int, y: Int, childWidth: Int, childHeight: Int) {
      view.measure(
        View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(childHeight, View.MeasureSpec.EXACTLY),
      )
      view.layout(x, y, x + childWidth, y + childHeight)
    }
    place(statsLabel, 0, 0, width, top)
    // FIT_CENTER scales the viewport crop but does not hide the surrounding camera texture inside a
    // rectangular PreviewView. Make the view itself square so it clips to the same area as analysis.
    if (!hasPendingCapture()) previewSquareFrame = props.squareFrame
    liveOverlay.squareFrame = previewSquareFrame
    val cameraWidth = if (previewSquareFrame) minOf(width, height - top) else width
    val cameraHeight = if (previewSquareFrame) cameraWidth else height - top
    val cameraLeft = (width - cameraWidth) / 2
    val cameraTop = top + (height - top - cameraHeight) / 2
    for (view in listOf(previewView, liveOverlay, frozenFrame, captureFeedbackView)) {
      place(view, cameraLeft, cameraTop, cameraWidth, cameraHeight)
    }
    liveHint.measure(
      View.MeasureSpec.makeMeasureSpec(cameraWidth, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(cameraHeight, View.MeasureSpec.AT_MOST),
    )
    liveHint.layout(cameraLeft, cameraTop + cameraHeight - liveHint.measuredHeight,
      cameraLeft + cameraWidth, cameraTop + cameraHeight)
    // Keep the centered status inside the camera region as well.
    statusView.measure(
      View.MeasureSpec.makeMeasureSpec(cameraWidth, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(cameraHeight, View.MeasureSpec.AT_MOST),
    )
    val statusTop = cameraTop + (cameraHeight - statusView.measuredHeight) / 2
    statusView.layout(cameraLeft, statusTop, cameraLeft + cameraWidth, statusTop + statusView.measuredHeight)
  }

  /** Shows what a waiting capture waits for while capture feedback is on; otherwise the unavailable-detection hint for
   * UNAVAILABLE, and nothing otherwise.
   */
  private fun showHint(status: LiveGuideStatus) {
    hintStatus = status
    val reason = synchronized(this) {
      captures.values.firstOrNull { it.stage == CaptureStage.WAITING }?.reason
    }
    val waitingHint = when (reason) {
      WaitReason.DOCUMENT -> R.string.document_scanner_wait_document
      WaitReason.FOCUS -> R.string.document_scanner_wait_focus
      WaitReason.LIGHT -> R.string.document_scanner_wait_light
      WaitReason.STEADY -> R.string.document_scanner_hold_steady
      null -> null
    }
    val text = if (props.captureFeedback && waitingHint != null) waitingHint else when {
      status == LiveGuideStatus.UNAVAILABLE -> R.string.document_scanner_detection_unavailable
      else -> null
    }
    if (text != null) liveHint.setText(text)
    liveHint.visibility = if (text != null) View.VISIBLE else View.GONE
  }

  /** Unless tapToFocus is false, focuses and meters at the tapped point. */
  private fun onPreviewTap(x: Float, y: Float) {
    if (!props.tapToFocus || x < previewView.left || x >= previewView.right ||
      y < previewView.top || y >= previewView.bottom) return
    val camera = boundCamera ?: return
    focus.tap(camera, previewView.meteringPointFactory.createPoint(x - previewView.left, y - previewView.top))
  }

  /** Main thread: sets the bound camera's torch from the mode and the auto decision. A change waits while a photo is in
   * flight, so the light does not change during its shot; the capture's end applies it. A failed change is
   * retried by the next call.
   */
  private fun applyTorch() {
    val camera = boundCamera ?: return
    if (!camera.cameraInfo.hasFlashUnit() || hasPendingCapture()) return
    val lit = when (props.torch) {
      TorchMode.ON -> true
      TorchMode.AUTO -> synchronized(metadataLock) { torchPolicy.lit }
      TorchMode.OFF -> false
    }
    if (lit == (torchRequest?.lit ?: torchApplied)) return
    logLifecycle("torch lit=$lit mode=${props.torch}")
    // A newer change cancels this one, and an unbind drops it; only the current request settles.
    val request = TorchRequest(lit, camera.cameraControl.enableTorch(lit))
    torchRequest = request
    request.future.addListener({
      if (torchRequest !== request) return@addListener
      torchRequest = null
      torchApplied = try { request.future.get(); lit } catch (error: Exception) {
        logLifecycle("torch lit=$lit failed: ${error.message}")
        null
      }
      // A capture waiting for this change can shoot now.
      scheduleCaptureEvaluation()
    }, ContextCompat.getMainExecutor(themedContext))
  }

  private class TorchRequest(val lit: Boolean, val future: ListenableFuture<Void>)

  /** Main thread: sets the bound camera's AE compensation index from exposureCompensation; a newer call cancels it. */
  private fun applyExposureCompensation() {
    val camera = boundCamera ?: return
    val state = camera.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    val range = state.exposureCompensationRange
    val index = CameraExposurePolicy.compensationIndex(
      props.exposureCompensation, state.exposureCompensationStep.toDouble(), range.lower, range.upper,
    )
    logLifecycle("exposure compensation index=$index")
    camera.cameraControl.setExposureCompensationIndex(index)
  }

  /**
   * Main thread: sets the AE target frame-rate range from minFrameRate, or clears it to the camera's default at 0, and
   * points continuous autofocus at the central [CENTRE_FOCUS_AREA] of the frame, where a document is held, so it does
   * not settle on the background. These options override CameraX's own focus regions, so the centre area is left out
   * while a tap's scan holds. With [parksFocus] and no owned scan, autofocus is off and the lens parked instead.
   */
  @OptIn(ExperimentalCamera2Interop::class)
  private fun applyCaptureOptions() {
    val camera = boundCamera ?: return
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val available = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().toList()
    val range = CameraExposurePolicy.targetFpsRange(available, props.minFrameRate, { it.lower }, { it.upper })
    val scanHolds = focus.snapshot.scanHolds(SystemClock.uptimeMillis())
    val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val regions = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val centre = if (props.guide || scanHolds || sensor == null || regions < 1) null else {
      val halfWidth = (sensor.width() * CENTRE_FOCUS_AREA / 2).toInt()
      val halfHeight = (sensor.height() * CENTRE_FOCUS_AREA / 2).toInt()
      MeteringRectangle(
        Rect(sensor.centerX() - halfWidth, sensor.centerY() - halfHeight, sensor.centerX() + halfWidth, sensor.centerY() + halfHeight),
        MeteringRectangle.METERING_WEIGHT_MAX,
      )
    }
    // Lifted for every owned scan, whose autofocus these options would otherwise override.
    val park = parkRange?.takeIf { parksFocus && focus.snapshot.owner == null }?.let { parkDiopters(learnedFocusDiopters, it) }
    logLifecycle("capture options fpsRange=$range centreFocus=${centre != null} parkDiopters=$park")
    val options = CaptureRequestOptions.Builder()
    if (range != null) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
    if (centre != null) options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(centre))
    if (park != null) {
      options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, park)
    }
    // Replaces every option set through Camera2CameraControl, so all of them are set here.
    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
  }

  private fun updateCameraState() {
    if (disposed) return
    val state = when {
      cameraProvider == null -> if (cameraStartRequestInFlight) ScannerCameraState.STARTING else ScannerCameraState.SUSPENDED
      previewView.previewStreamState.value != PreviewView.StreamState.STREAMING -> ScannerCameraState.STARTING
      synchronized(this) { captures.isNotEmpty() } -> ScannerCameraState.CAPTURING
      else -> ScannerCameraState.READY
    }
    if (state == emittedCameraState) return
    emittedCameraState = state
    renderStatus()
    onCameraStateChange?.invoke(state)
  }

  private fun emitAutofocus() {
    if (disposed) return
    val state = autofocusState
    if (state == emittedAutofocus) return
    emittedAutofocus = state
    renderStatus()
    onAutofocusChange?.invoke(state)
  }

  /** The document state right after binding or toggling the guide, before any detector result. */
  private fun boundDocumentState(): DocumentState = when {
    cameraProvider == null || !props.guide -> DocumentState.OFF
    liveDetector.hasFailed() -> DocumentState.UNAVAILABLE
    else -> DocumentState.SEARCHING
  }

  private fun updateDocumentState(state: DocumentState) {
    if (state != documentState) {
      documentState = state
      renderStatus()
    }
    liveOverlay.viewfinder = state == DocumentState.SEARCHING
    if (disposed) return
    if (state == emittedDocumentState) return
    emittedDocumentState = state
    onDocumentStateChange?.invoke(state)
  }

  /** Samples the gyroscope only while the guide or the steady-photo hold can use it. */
  private fun updateGyroscope() {
    val needed = props.guide || props.maxCaptureBlur > 0
    if (liveDetector.cameraOptics != null && needed) gyroscope.start() else gyroscope.stop()
  }

  @OptIn(ExperimentalCamera2Interop::class)
  private fun observeFrameState(builder: ImageAnalysis.Builder, generation: Long) {
    metadataGeneration = generation
    Camera2Interop.Extender(builder).setSessionCaptureCallback(frameStateCallback(generation))
  }

  /**
   * Logs which frame each photo saved: a still request's SENSOR_TIMESTAMP, which a zero-shutter-lag reprocess shares
   * with its buffered input, against the shot's request. CameraX chooses that frame; this only reports it.
   */
  @OptIn(ExperimentalCamera2Interop::class)
  private fun observeStillFrames(builder: ImageCapture.Builder, generation: Long) {
    Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
        if (intent != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE &&
          intent != CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG) return
        val sensorNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val reprocess = request.isReprocess
        mainHandler.post {
          if (generation != metadataGeneration) return@post
          val shutterNs = synchronized(this@HybridDocumentScannerCameraView) {
            captures.values.firstOrNull { it.shutterRealtimeNs != 0L }?.shutterRealtimeNs
          }
          logLifecycle("capture still frame reprocess=$reprocess intent=$intent " +
            "exposedVsShutter=${shutterNs?.let { (sensorNs - it) / 1_000_000 } ?: "-"}ms")
        }
      }
    })
  }

  /** Back-camera optics when its timestamps share the gyroscope clock; otherwise motion compensation stays off. */
  @OptIn(ExperimentalCamera2Interop::class)
  private fun backCameraOptics(info: CameraInfo): LiveCameraOptics? {
    val characteristics = Camera2CameraInfo.extractCameraCharacteristics(info)
    val optics = if (CameraOpticsReader.sharesGyroClock(characteristics)) CameraOpticsReader.read(characteristics) else null
    logLifecycle("camera optics=$optics")
    return optics
  }

  private fun logLifecycle(message: String) {
    if (debugLoggingEnabled) Log.d(LOG_TAG, "view=$instanceId $message")
  }

  private fun updateTargetRotation(rotation: Int) {
    if (targetRotation == rotation) return
    targetRotation = rotation
    stopCamera("display-rotation")
    startCamera()
  }

  private fun unregisterDisplayListener() {
    if (displayListenerRegistered) displayManager.unregisterDisplayListener(displayListener)
    displayListenerRegistered = false
  }

  private fun currentDisplayRotation(): Int = previewView.display?.rotation ?: Surface.ROTATION_0

  private fun readPermissionStatus(): CameraPermissionStatus = CameraPermissions.status()

  private fun showPermissionState(status: CameraPermissionStatus) {
    when (status) {
      CameraPermissionStatus.GRANTED -> statusView.visibility = View.GONE
      CameraPermissionStatus.NOT_DETERMINED -> showMessage(R.string.document_scanner_permission_needed)
      CameraPermissionStatus.DENIED -> showMessage(R.string.document_scanner_permission_denied)
      CameraPermissionStatus.BLOCKED -> showMessage(R.string.document_scanner_permission_blocked)
    }
  }

  private fun showMessage(message: Int) {
    statusView.setText(message)
    statusView.visibility = View.VISIBLE
  }

  private fun describePhoto(file: File, liveCandidate: DocumentCandidate?, frame: Future<LiveFrame>?): CapturedPhoto {
    val exif = ExifInterface(file.absolutePath)
    val orientation = exif.getAttributeInt(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.ORIENTATION_NORMAL,
    )
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, bounds.outWidth)
    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, bounds.outHeight)
    val rotationDegrees = when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90,
      ExifInterface.ORIENTATION_TRANSPOSE -> 90
      ExifInterface.ORIENTATION_ROTATE_180 -> 180
      ExifInterface.ORIENTATION_ROTATE_270,
      ExifInterface.ORIENTATION_TRANSVERSE -> 270
      else -> 0
    }
    return CapturedPhoto(
      originalUri = Uri.fromFile(file).toString(),
      width = width.toDouble(),
      height = height.toDouble(),
      orientation = orientation.toDouble(),
      rotationDegrees = rotationDegrees.toDouble(),
      liveCandidate = liveCandidate,
      frameUri = frame?.let { writeLatestFrameCapture(file, it, liveCandidate) },
    )
  }

  /**
   * Writes the analysis frame taken at the shutter beside [photo] as its latest-frame capture and returns its uri; null when no
   * frame arrived in time or it could not be written. The photo's own save usually outlasts the wait.
   */
  private fun writeLatestFrameCapture(photo: File, frame: Future<LiveFrame>, liveCandidate: DocumentCandidate?): String? {
    val latestFrame = File(photo.parentFile, "frame-${photo.nameWithoutExtension}.jpg")
    return runCatching {
      LatestFrameCapture.write(frame.get(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), liveCandidate?.quad, latestFrame)
      Uri.fromFile(latestFrame).toString()
    }.onFailure {
      latestFrame.delete()
      logLifecycle("latest frame capture unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()
  }

  /** The guide outline as a model candidate; every corner and side is the model's prediction, as in its own output. */
  private fun guideCandidate(quad: DocumentQuad, score: Double): DocumentCandidate {
    val predicted = DocumentPointProvenance.PREDICTED
    return DocumentCandidate(quad, score, DocumentCornerProvenance(predicted, predicted, predicted, predicted),
      DocumentSideProvenance(predicted, predicted, predicted, predicted), null)
  }

  private fun <T> createPromise(): Promise<T>? {
    val promise = Promise<T>()
    val accepted = synchronized(this) {
      if (disposed) false else {
        pendingPromises[promise] = Unit
        true
      }
    }
    if (!accepted) return null
    return promise
  }

  private fun isPending(promise: Promise<*>): Boolean = synchronized(this) {
    pendingPromises.containsKey(promise)
  }

  private fun <T> resolvePromise(promise: Promise<T>, result: T): Boolean {
    val accepted = settle(promise)
    if (accepted) promise.resolve(result)
    return accepted
  }

  /** Main thread: reports [stage] once per capture, and shows it unless feedback is off. Failure follows any stage. */
  private fun advanceCapture(capture: GatedCapture, stage: CaptureStage) {
    val current = capture.stage
    if (current == stage || current == CaptureStage.FAILED) return
    if (stage == CaptureStage.CAPTURED && current != CaptureStage.SHOOTING) return
    capture.stage = stage
    showHint(hintStatus)
    if (props.captureFeedback) captureFeedbackView.show(stage)
    onCaptureStage?.invoke(stage)
  }

  /** Main thread. Also ends a capture: a capture rejects only while its shot is not in flight. */
  private fun <T> rejectPromise(promise: Promise<T>, error: Throwable) {
    val capture = synchronized(this) { captures[promise] }
    if (settle(promise)) {
      promise.reject(error)
      if (capture != null) advanceCapture(capture, CaptureStage.FAILED)
    }
    endCapture(promise)
  }

  private fun settle(promise: Promise<*>): Boolean = synchronized(this) { pendingPromises.remove(promise) != null }

  private fun clearCapturePreview() {
    frozenFrame.visibility = View.GONE
    frozenFrame.setImageDrawable(null)
  }

  /** Ends [promise]'s capture once its shot is not in flight, which can be after the promise resolved: the last
   * capture to end releases the preview unless retained for review, and applies a deferred rebind.
   */
  private fun endCapture(promise: Promise<*>) {
    val ended = synchronized(this) { captures.remove(promise) } ?: return
    // A rejected capture leaves no scheduled deadline callback.
    mainHandler.removeCallbacksAndMessages(ended.deadlineToken)
    mainHandler.post {
      // Focus held still for the shots; a capture still waiting does not hold it.
      if (!hasShotInFlight()) focus.afterCapture(documentWanted = props.guide)
      if (!hasPendingCapture() && modelLoadPending) loadRequestedModel()
      if (!hasPendingCapture()) {
        if (!ended.delivered || !props.retainCapturePreview) clearCapturePreview()
        applyTorch()
      }
      if (!disposed && !props.active && !hasPendingCapture()) {
        stopCamera("inactive-after-capture")
      } else {
        updateCameraState()
        applyPendingRebind()
      }
    }
  }

  private fun hasPendingCapture(): Boolean = synchronized(this) { captures.isNotEmpty() }

  /** A capture has requested its shot, whose focus and exposure must not change until it settles. */
  private fun hasShotInFlight(): Boolean = synchronized(this) {
    captures.values.any { it.stage != null && it.stage != CaptureStage.WAITING }
  }

  /** Main thread: rebinds for a change deferred by a capture once the last capture settles; binding reads current props. */
  private fun applyPendingRebind() {
    if (!rebindPending || disposed || hasPendingCapture()) return
    stopCamera("deferred-rebind")
    startCamera()
  }

  private fun dp(value: Int): Int = (value * themedContext.resources.displayMetrics.density).toInt()

  /** The capture configuration the bound use cases were built with; [zsl] means zero shutter lag is both asked for and
   * supported, so CameraX takes the photo from its buffer.
   */
  private data class BoundCapture(val captureMode: CaptureMode, val jpegQuality: Int, val zsl: Boolean)

  /** One takePhoto's progress through its gate; main thread. */
  private class GatedCapture(val promise: Promise<CapturedPhoto>, val requestedMs: Long, skipQualityGate: Boolean) {
    var registered = false
    /** Owns the capture's deadlines and decides each evaluation. */
    val gate = CaptureGate(STEADY_TIMEOUT_MS, skipQualityGate)
    /** What the capture is waiting for, shown over the guide while it pulses; null once it shoots. */
    var reason: WaitReason? = null
    /** The next frame results to consume, and the newest consumed frame exposed since the last focus change. */
    var frameCursor = 0L
    var latest: FrameRecord? = null
    /** The focus snapshot the consumed frames were evaluated against; a newer generation restarts the sequence. */
    var focus = FocusSnapshot(0, null, null, 0, 0, 0)
    /** Framing stability over consecutive frames since the press and the latest focus change. */
    val stability = CaptureStability()
    /** Identifies this capture's pending deadline on the main handler. */
    val deadlineToken = Any()
    /** elapsedRealtimeNanos() when the shot was requested, for the still frame's timestamp in the lifecycle log. */
    var shutterRealtimeNs = 0L
    /** The last stage reported; null before the capture waits. */
    var stage: CaptureStage? = null
    var delivered = false
    /** The guide at the shutter, for `CapturedPhoto.liveCandidate`. */
    var liveCandidate: DocumentCandidate? = null
    /** The first analysis frame after the shutter, for `CapturedPhoto.frameUri`. */
    var frame: CompletableFuture<LiveFrame>? = null
    /** While sample collection is on, what the photo keeps for a sample. */
    var sample: SampleTicket? = null
  }

  private companion object {
    // 480p analysis retains crop detail; the model input remains 256 x 256.
    const val DEFAULT_ANALYSIS_WIDTH = 640
    /** How long a described photo waits for its latest frame beyond its own save. */
    const val FRAME_WAIT_MS = 300L
    // Unmeasured starting points: about two analysis pixels at 320 wide, and about 8 pixels of a 4000-wide photo.
    const val DEFAULT_CAPTURE_BLUR_PERCENT = 0.2
    const val STEADY_TIMEOUT_MS = 1_500L
    const val DEFAULT_FOCUS_TIMEOUT_MS = 2_000L
    /** Side of the central continuous-autofocus area, as a fraction of the sensor's width and height. */
    const val CENTRE_FOCUS_AREA = 0.3f
    /** Status, rate, timing, camera latency and blur. */
    const val STATS_LINES = 8


    const val CACHE_DIRECTORY_NAME = "react-native-document-scanner"

    /** The back camera's last focus lock in diopters, for the process: a remounted camera parks where the last one
     * focused. Written by capture callbacks.
     */
    @Volatile var learnedFocusDiopters: Float? = null
  }
}

/** Camera view props with defaults applied; blur limits are fractions of the frame width, and zero disables them.
 * Without [qualityGate] both limits are zero. [flowMotion] runs optical flow, which checks both limits, while either is
 * on.
 */
private data class ResolvedProps(
  val active: Boolean,
  val guide: Boolean,
  val adaptiveDetection: Boolean,
  val minCornerScore: Double,
  val qualityGate: Boolean,
  val maxCaptureBlur: Double,
  /** How long a capture waits for autofocus; 0 does not wait. */
  val focusTimeoutMs: Long,
  /** A capture without focus once [focusTimeoutMs] passes rejects instead of shooting. */
  val requireFocus: Boolean,
  val flowMotion: Boolean,
  /** `scanner` mode while photos wait for focus: a parked lens between owned scans, where supported. */
  val documentFocus: Boolean,
  val tapToFocus: Boolean,
  val exposureCompensation: Double,
  val minFrameRate: Int,
  val torch: TorchMode,
  val guideTransitionNs: Long,
  val analysisWidth: Int,
  val squareFrame: Boolean,
  val captureMode: CaptureMode,
  /** 1–100, or 0 for the capture mode's own. */
  val jpegQuality: Int,
  val showStats: Boolean,
  val guideColor: Int,
  val captureFeedback: Boolean,
  val retainCapturePreview: Boolean,
  val captureColor: Int,
)
