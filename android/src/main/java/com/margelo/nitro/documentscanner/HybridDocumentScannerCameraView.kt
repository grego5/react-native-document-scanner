package com.margelo.nitro.documentscanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.camera2.interop.applyCamera2InteropAsync
import androidx.camera.camera2.interop.camera2Interop
import androidx.camera.camera2.interop.cameraCharacteristics
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.LowLightBoostState
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
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
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.CameraPermissionStatus
import com.margelo.nitro.documentscanner.HybridDocumentScannerCameraViewSpec
import com.facebook.react.uimanager.ThemedReactContext
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.margelo.nitro.documentscanner.CameraState as ScannerCameraState
import com.margelo.nitro.documentscanner.processing.CameraFrameState
import com.margelo.nitro.documentscanner.processing.CameraOpticsReader
import com.margelo.nitro.documentscanner.processing.CornerTensorModelLoader
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
import com.margelo.nitro.documentscanner.processing.LiveGuideTraceObserver
import com.margelo.nitro.documentscanner.processing.GuideStabilizerConfig
import com.margelo.nitro.documentscanner.processing.LiveFocusCandidate
import com.margelo.nitro.documentscanner.processing.pagePoint
import com.margelo.nitro.documentscanner.processing.pageCoordinates
import com.margelo.nitro.documentscanner.processing.insidePage
import com.margelo.nitro.documentscanner.processing.TorchPolicy
import com.margelo.nitro.documentscanner.processing.DarkScenePolicy
import com.margelo.nitro.documentscanner.processing.WhiteBalancePolicy
import com.margelo.nitro.documentscanner.processing.hasAdjustableAutofocus
import com.margelo.nitro.documentscanner.processing.LiveCaptureGateSample
import com.margelo.nitro.documentscanner.processing.FrameRecord

import kotlin.math.abs

private const val LOG_TAG = "RNDocumentScannerCamera"

/** Android camera preview and still capture for the Nitro Hybrid View. */
@DoNotStrip
open class HybridDocumentScannerCameraView(
  private val themedContext: ThemedReactContext,
) : HybridDocumentScannerCameraViewSpec() {
  /** Debug builds log; any build does once the app turns diagnostics on before the view is created. */
  private val debugLoggingEnabled =
    themedContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 || ScannerDiagnostics.enabled
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
          requestRebind(RebindReason.CONFIGURATION)
          return
        }
        this@HybridDocumentScannerCameraView.stopCamera("viewport-resized")
      }
      if (width > 0 && height > 0) this@HybridDocumentScannerCameraView.startCamera()
    }
  }
  /** Main thread: the preview frame shown, over the live guide, from the shutter through capture. */
  private val frozenFrame = ImageView(themedContext).apply {
    scaleType = ImageView.ScaleType.FIT_XY
    visibility = View.GONE
  }
  private val previewView = PreviewView(themedContext).apply {
    scaleType = PreviewView.ScaleType.FIT_CENTER
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    // A tap claims focus before its scan is sent, so no capture decision sees the old focus; CameraX's own tap
    // reports its start only after sending it. A tap is a single short touch, as CameraX counts one.
    setOnTouchListener { _, event ->
      if (event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_UP &&
        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) onPreviewTap(event.x, event.y)
      false
    }
  }
  /** Keep the displayed crop unchanged while a capture defers a use-case rebind. */
  private var previewSquareFrame = false
  private val gyroscope = GyroscopeSource(themedContext, GyroHistory())
  private val hand = HandMotion(gyroscope.history, gyroscope.acceleration)
  private val liveOverlay = LiveGuideOverlay(themedContext)
  private val focusBoxOverlay = FocusBoxOverlay(themedContext)
  /** Over the frozen frame, so the shooting stage shows on the photo being taken. */
  private val captureFeedbackView = CaptureFeedbackView(themedContext, liveOverlay)
  /** The last 15 s of camera and guide decisions, kept for a diagnostics export while diagnostics was on at creation. */
  private val standaloneTrace = if (ScannerDiagnostics.enabled) StandaloneCameraTrace().also(ScannerDiagnostics::attach) else null
  private val statsCells = List(21) {
    TextView(themedContext).apply {
      setTextColor(Color.WHITE)
      textSize = 12f
      typeface = Typeface.MONOSPACE
      isSingleLine = true
      ellipsize = TextUtils.TruncateAt.END
      includeFontPadding = false
      setPadding(dp(8), 0, dp(8), 0)
    }
  }
  private val statsGrid = LinearLayout(themedContext).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(6), 0, dp(6))
    for (cells in statsCells.chunked(2)) {
      addView(LinearLayout(themedContext).apply {
        for (cell in cells) addView(cell, LinearLayout.LayoutParams(0, cell.lineHeight, 1f))
        if (cells.size == 1) addView(View(themedContext), LinearLayout.LayoutParams(0, 0, 1f))
      }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
  }
  private val statsScroll = ScrollView(themedContext).apply {
    setBackgroundColor(0xFF101820.toInt())
    addView(statsGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    visibility = View.GONE
  }
  /** The last one-second figures below the stats strip's status line; main thread. */
  private var statsFigures: LiveDetectionStats? = null
  private var latestModelScore: Double? = null
  /** The guide while it tracks a page, normalized upright in the analysis frame; main thread. */
  private var trackedGuide: DocumentCandidate? = null
  private val liveDetector = LiveDocumentDetector(themedContext, debugLoggingEnabled, gyro = gyroscope.history,
    acceleration = gyroscope.acceleration.takeIf { gyroscope.hasAcceleration }) { result ->
    latestModelScore = result.score ?: latestModelScore.takeIf { result.corners != null }
    updateDocumentState(when (result.status) {
      LiveGuideStatus.SEARCHING, LiveGuideStatus.DETECTED -> DocumentState.SEARCHING
      LiveGuideStatus.TRACKING -> DocumentState.TRACKING
      LiveGuideStatus.HELD -> DocumentState.HELD
      LiveGuideStatus.UNAVAILABLE -> DocumentState.UNAVAILABLE
    })
    // A frame optical flow alone carried has no inference score; the guide keeps its latest one. Flow can carry corners
    // past the frame, which photo analysis rejects, so such an outline is not offered and the photo is detected afresh.
    trackedGuide = result.corners
      ?.takeIf { result.status == LiveGuideStatus.TRACKING && NativeCornerGeometry.prediction(NativeCornerGeometry.coordinates(it)) }
      ?.let { corners -> DocumentCandidate(corners, result.score ?: trackedGuide?.score ?: 0.0) }
    liveOverlay.show(result, previewView.outputTransform)
    renderStatus()
  }.apply {
    focusCandidateListener = ::onFocusCandidate
    analysisSlowListener = ::onAnalysisSlow
    traceObserver = standaloneTrace
  }

  private fun onAnalysisSlow() {
    if (!disposed && props.active && boundCamera != null) {
      onError?.invoke(ScannerFailure(ScannerErrorCode.CAMERA_ANALYSIS_SLOW))
    }
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
      // Autofocus off fixes the lens: its focus is unknown only while it moves.
      val manualFocus = result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF
      val lensMoving = result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING
      val focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
      val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
      val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
      val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
      standaloneTrace?.record("camera", timestampNs,
        "binding=$generation frame=${result.frameNumber} af=$afState trigger=${request.get(CaptureRequest.CONTROL_AF_TRIGGER)} " +
          "mode=${result.get(CaptureResult.CONTROL_AF_MODE)} regions=${request.get(CaptureRequest.CONTROL_AF_REGIONS)?.contentToString()} " +
          "lens=$focusDiopters moving=$lensMoving ae=${result.get(CaptureResult.CONTROL_AE_STATE)} " +
          "awb=${result.get(CaptureResult.CONTROL_AWB_STATE)} gains=${result.get(CaptureResult.COLOR_CORRECTION_GAINS)} " +
          "exposure=$exposureNs iso=$iso flash=${result.get(CaptureResult.FLASH_STATE)}")
      // Debug timing: each raw AF state change, to measure how long scans take on a device.
      if (debugLoggingEnabled && afState != loggedAfState) {
        loggedAfState = afState
        logLifecycle("af state=$afState mode=${result.get(CaptureResult.CONTROL_AF_MODE)} diopters=$focusDiopters sensorNs=$timestampNs " +
          "sharpRange=${result.get(CaptureResult.LENS_FOCUS_RANGE)} pipelineDepth=${result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)}")
      }
      var lightTorch = false
      var darknessFlipped = false
      var whiteBalanceFlipped = false
      val changed = synchronized(metadataLock) {
        if (generation != metadataGeneration) return
        // Under the lock, so a retired binding's frames never reach the focus attempt.
        focus.observeFrame(result.frameNumber, request.get(CaptureRequest.CONTROL_AF_TRIGGER),
          result.get(CaptureResult.CONTROL_AF_MODE), afState)
        // When autofocus last reached focus, on the sensor clock, recorded with each frame so that frame alone decides
        // whether its focus predates the page: a page that appeared later is not what it focused on.
        val focusedNow = autofocus == AutofocusState.FOCUSED || autofocus == AutofocusState.LOCKED
        if (focusedNow && autofocusState != AutofocusState.FOCUSED && autofocusState != AutofocusState.LOCKED &&
          timestampNs != null) focusReachedSensorNs = timestampNs
        exposureIsoMs = if (exposureNs != null && iso != null) exposureNs / 1e6 * iso else null
        aeAwbStates = "${result.get(CaptureResult.CONTROL_AE_STATE) ?: "n/a"}/${result.get(CaptureResult.CONTROL_AWB_STATE) ?: "n/a"}"
        if (torchAuto && timestampNs != null && exposureNs != null) lightTorch = torchPolicy.observe(SystemClock.uptimeMillis() * 1_000_000, exposureNs, iso)
        if (timestampNs != null && exposureNs != null) darknessFlipped = darkScene.observe(SystemClock.uptimeMillis() * 1_000_000, exposureNs, iso)
        whiteBalanceFlipped = whiteBalance.observe(SystemClock.uptimeMillis() * 1_000_000,
          result.get(CaptureResult.CONTROL_AE_STATE), result.get(CaptureResult.CONTROL_AWB_STATE))
        if (timestampNs != null && exposureNs != null) liveDetector.frames.add(timestampNs, CameraFrameState(
          exposureNs, result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
          autofocus == AutofocusState.SCAN || autofocus == AutofocusState.UNFOCUSED || manualFocus && lensMoving, afState,
          result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let {
            it == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON
          },
          focusDiopters,
          focusReachedSensorNs = focusReachedSensorNs,
          aeState = result.get(CaptureResult.CONTROL_AE_STATE),
          awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
          torchFired = result.get(CaptureResult.FLASH_STATE)?.let { it == CaptureResult.FLASH_STATE_FIRED },
        ))
        (autofocus != autofocusState).also {
          if (it) {
            autofocusState = autofocus
          }
        }
      }
      if (changed) mainHandler.post { if (generation == metadataGeneration) emitAutofocus() }
      // A lit torch binds `latency` for `zsl`; that rebind lights it on the new binding.
      if (lightTorch) mainHandler.post {
        if (generation != metadataGeneration) return@post
        logLifecycle("auto torch lit for a dark room exposure*iso=%.0f".format((exposureNs ?: 0L) / 1e6 * (iso ?: 0)))
        if (!rebindForLight()) applyLight()
      }
      if (darknessFlipped || whiteBalanceFlipped) mainHandler.post { if (generation == metadataGeneration) rebindForLight() }
    }
  }
  /** Main thread; for instrumentation tests. */
  internal val gyroscopeRunning: Boolean get() = gyroscope.isRunning
  /** Test-harness live trace hook; nothing in the app sets it. */
  internal var liveGuideTraceObserver: LiveGuideTraceObserver?
    get() = liveDetector.traceObserver
    set(value) { liveDetector.traceObserver = value }
  /** Test-harness switch that estimates image motion for traces even when no gate uses it; main thread. */
  internal var flowMotionEnabled = false
    set(value) {
      field = value
      liveDetector.flowMotionEnabled = value || props.guide
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
  /** Main thread: a rebind waiting for captures to settle, and why; null when none waits. */
  private var pendingRebind: RebindReason? = null
  /** Main thread: an exposureCompensation change arrived while a capture was pending; [endCapture] applies it. */
  private var exposureDeferred = false
  /** Main thread: removes the permission listener while the view is attached. */
  private var removePermissionListener: (() -> Unit)? = null
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
    override val camera: BoundCamera? get() = boundCamera
    override val mainExecutor get() = ContextCompat.getMainExecutor(themedContext)
    override fun shotInFlight() = hasShotInFlight()
    override fun resumeContinuousFocus() = aimContinuousFocus()
    override fun postDelayed(delayMs: Long, block: () -> Unit) { mainHandler.postDelayed(block, delayMs) }
    override fun log(message: String) = logLifecycle(message)
    override fun focusChanged(snapshot: FocusSnapshot) {
      // A held lock can report the camera's autofocus as inactive, so the dot's colour follows the lock here.
      emitAutofocus()
      // Analysis may trust frames without their own metadata only while an owned scan holds a lock.
      scheduleCaptureEvaluation()
    }
    override fun scannerLockFailed(owner: FocusOwner) {
      if (props.focusLock) follow(cycle.failed(), null)
    }
    override val confirmsLockFrames get() = hasSingleShotAutofocus
  })
  /** Main thread: what the bound use cases were built with, which hardware-dependent decisions read; null unbound. */
  private var bound: BoundCapture? = null

  override val view: View
    get() = container

  private val streamObserver = Observer<PreviewView.StreamState> { updateCameraState() }
  /** Main thread: binds the use cases to [previewView]; one per binding. */
  private var cameraController: LifecycleCameraController? = null
  private var boundCamera: BoundCamera? = null
  @Volatile private var cameraFeatures: CameraFeatureSupport? = null
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
  /** The bound camera offers single-shot autofocus, which CameraX locks in; without it a lock cannot be confirmed by its
   * frames and CameraX's report stands. Main thread. */
  private var hasSingleShotAutofocus = false
  /** Sensor time of the frame on which autofocus last reached focus or a lock, or null; guarded by [metadataLock]. */
  private var focusReachedSensorNs: Long? = null
  /** Camera thread, debug timing: the last raw AF state logged. */
  private var loggedAfState: Int? = null
  /** Auto-torch decision for the current binding, fed by capture results; guarded by [metadataLock]. */
  private val torchPolicy = TorchPolicy()
  /** Sustained room darkness for [captureModeFor]; guarded by [metadataLock]. It outlives bindings, since the
   * rebind it causes must not reset it. */
  private val darkScene = DarkScenePolicy()
  /** Sustained searching white balance for [captureModeFor]; guarded by [metadataLock], outlives bindings. */
  private val whiteBalance = WhiteBalancePolicy()
  @Volatile private var torchAuto = false
  /** Latest exposure time (ms) times ISO, for the stats strip. */
  @Volatile private var exposureIsoMs: Double? = null
  /** Latest raw CONTROL_AE_STATE/CONTROL_AWB_STATE, for the stats strip: zero shutter lag needs both converged (2). */
  @Volatile private var aeAwbStates = "n/a"
  /** Main thread: torch and boost commands and their evidence, and `zsl`'s light fallback. A refused boost may leave a
   * dark scene unlit, so it re-evaluates the bound mode. */
  private val light = CameraLightController(ContextCompat.getMainExecutor(themedContext),
    ::scheduleCaptureEvaluation, { rebindForLight() }, ::logLifecycle)
  /** takePhoto calls in flight, until the shot has settled; guarded by this. */
  private val captureAdmission = CaptureAdmission<GatedCapture>(this)
  /** Main thread: the bound camera moves its lens to focus; a fixed-focus camera never reports a focus state. */
  private var hasAutofocus = false
  /** Main thread: the newest analyzed frame's page, where a capture's scan focuses; null without one. */
  private var focusCandidate: LiveFocusCandidate? = null
  /**
   * Main thread: sensor time of the first frame showing the page in view, which a focus must postdate to be the page's;
   * a page gone for [PAGE_LOST_NS] arrives anew. Null before any page.
   */
  private var pageArrivedSensorNs: Long? = null
  private var pageSeenSensorNs = 0L
  /** Main thread: where continuous autofocus aims while no scan holds the lens. */
  private val focusAim = FocusAim()
  /** Main thread: the camera image's width and height as fractions of the preview view's, which letterboxes it; focus
   * box sizes are fractions of the image. */
  private var previewImageScale = floatArrayOf(1f, 1f)
  /** Main thread: the camera's steadiness by the capture's motion rule without its blur check, read from analysis frames
   * from [steadinessCursor] on; the print spot and `focusLock` wait for it. A defocused, mostly blank frame often has no
   * blur reading or a high one, so the blur check would hold back the very lock that makes the frame sharp. */
  private val steadiness = CaptureStability()
  private var steadinessCursor = 0L
  /** Main thread: the page's print spot in page coordinates; null before it is picked or without a page. */
  private var printSpot: DocumentPoint? = null
  /** Main thread: the generation of the guide lock whose settling last asked analysis for a frame, and the sensor time
   * it settled at, from which a page has [RECOVERY_NS] to appear. */
  private var guideSettledGeneration: Long? = null
  private var guideSettledSensorNs = 0L
  /** Main thread, `focusLock`: the guide area's most textured spot in upright frame fractions, asked for once the
   * phone holds steady without a page and forgotten once it moves. */
  private val guideSpot = GuideSpot()
  /** Main thread: the last spot request's number; each request is a new one, so an answer names what it answers. */
  private var spotRequests = 0L
  /** Main thread, `focusLock`: where the guide lock scans, in upright frame fractions. */
  private var guideLockPoint: DocumentPoint? = null
  /** Main thread, `focusLock`: the page's size under each page lock, keyed by the lock's generation. */
  private val pageSize = SizeWatch()
  /** Main thread, `focusLock`: the page lock whose page changed size, which relocks on the next steady frame; a press
   * does not accept it meanwhile. */
  private var pageRelockGeneration = -1L
  /** Main thread: the bound camera's depth of field; null without the lens and sensor sizes or calibrated focus. */
  private var depthOfField: DepthOfField? = null
  /** Main thread: whether `focusLock` with live corners was last applied on, and whether turning it off waits for a
   * shot being taken to settle. */
  private var scannerLensOn = false
  private var scannerLensDeferred = false

  /**
   * Main thread: applies a change to `focusLock` with live corners. Off releases the scanner's own lock, so continuous
   * autofocus takes the lens again, once a shot being taken has settled. On needs nothing: the focus cycle starts from
   * continuous autofocus on the next frames.
   */
  private fun syncScannerLens() {
    scannerLensDeferred = false
    val want = props.focusLock && props.guide
    if (want == scannerLensOn) return
    if (hasShotInFlight()) {
      scannerLensDeferred = true
      return
    }
    scannerLensOn = want
    // A released lock's cancellation re-aims continuous autofocus once it completes.
    if (want || releaseScannerLock("prop off") == FocusController.Release.NONE) aimContinuousFocus()
  }

  /** Main thread: the focus cycle's steady hold: the motion sensors' quiet, or [steadiness] without them. */
  private var steadyHold = false
  /** Main thread: the steadiness trace's current second. */
  private var steadinessWait: SteadinessWait? = null
  /** Main thread, `focusLock`: when the camera's own locking scan runs, as for the text camera. */
  private val cycle = FocusCycle()
  /** Main thread: the [FocusCycle.restarts] the targets were last picked under. */
  private var cycleRestarts = 0L
  /** Main thread: the sensor time since which analysis has found no page; null while one is in view. */
  private var noPageSinceNs: Long? = null

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
  override var onLowLightBoostActiveChange: ((active: Boolean) -> Unit)? = null

  override fun getCameraFeatures(): CameraFeatureSupport? = cameraFeatures
  override var onDocumentStateChange: ((state: DocumentState) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post { emittedDocumentState = null; updateDocumentState(documentState) }
    }
  private var emittedCaptureState: CameraCaptureState? = null
  override var onCaptureStateChange: ((CameraCaptureState) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post {
        if (field === value) { emittedCaptureState = null; updateCaptureState() }
      }
    }

  override var imageCorrection = ImageCorrectionOptions(null, null, null)
  override var outputOptions = ImageOutputOptions(null, null, null, null, null)

  private fun resolveRenderOptions(): DocumentRenderOptions = renderOptions(imageCorrection, outputOptions)

  @Volatile private var requestedSession: HybridScanSessionSpec? = null
  private var sessionGeneration = 0L
  private var observedSession: ScanSessionState? = null
  private var removeSessionObserver: (() -> Unit)? = null
  override var session: HybridScanSessionSpec
    get() = requestedSession ?: throw InvalidInputException("Camera requires a session.")
    set(value) {
      synchronized(this) {
        if (requestedSession === value) return
        requestedSession = value
        sessionGeneration++
      }
      mainHandler.post { updateSession() }
    }

  private fun updateSession() {
    if (disposed) return
    val next = (requestedSession as? HybridScanSession)?.state
    if (next !== observedSession) {
      removeSessionObserver?.invoke()
      removeSessionObserver = null
      clearCapturePreview()
      observedSession = next
      next?.let { owner ->
        try {
          removeSessionObserver = owner.observe { snapshot ->
            if (observedSession === owner && snapshot.closed) {
              if ((requestedSession as? HybridScanSession)?.state === owner) clearCapturePreview()
              captureAdmission.current?.takeIf { it.source.session === owner }?.let { rejectPromise(it.promise, ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)) }
              updateCaptureState()
            }
          }.unsubscribe
        } catch (_: ScannerException) { /* Closed sessions remain unavailable. */ }
      }
    }
    captureAdmission.current?.let {
      if (!captureCurrent(it)) rejectPromise(it.promise, ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED))
    }
    updateCaptureState()
  }

  private fun captureCurrent(capture: GatedCapture): Boolean = synchronized(this) {
    capture.generation == sessionGeneration &&
      (requestedSession as? HybridScanSession)?.state === capture.source.session &&
      !capture.source.session.snapshot().closed
  }
  override var onCaptureStage: ((stage: CaptureStage, waitReason: CaptureWaitReason?) -> Unit)? = null

  // Raw React props; afterUpdate resolves them into [props] once per batch.
  override var cornerDetectionModel: String? = null
  private var requestedModel: String? = null
  /** The guide or auto-crop needs the model; toggling one while the other stays on keeps the current request. */
  private var requestedNeedsModel = true
  private var modelChecked = false
  private var modelReady = false
  private var modelLoadPending = false
  private var modelGeneration = 0L
  /** The loader's own error; each consumer translates it with `model/load-failed` as the fallback. */
  private var modelFailure: Throwable? = null
  override var onError: ((ScannerFailure) -> Unit)? = null
    set(value) {
      field = value
      mainHandler.post { if (!disposed) modelFailure?.let { value?.invoke(scannerFailure(it, ScannerErrorCode.MODEL_LOAD_FAILED)) } }
    }
  override var active: Boolean? = null
  override var guideLiveCorners: Boolean? = null
  override var autoCrop: Boolean? = null
  override var inferenceThrottling: Boolean? = null
  override var minCornerScore: Double? = null
  override var lowQualityControl: LowQualityControl = LowQualityControl(true, null, null)
  override var tapToFocus: Boolean? = null
  override var focusLock: Boolean? = null
  override var exposureCompensation: Double? = null
  override var minFrameRate: Double? = null
  override var torch: TorchMode? = null
  override var lowLightBoost: Boolean? = null
  override var captureMode: CaptureMode? = null
  override var darkLatency: Boolean? = null
  override var highResolutionPhoto: Boolean? = null
  override var guideTransitionDuration: Double? = null
  override var analysisFrameWidth: Double? = null
  override var squareAspectRatio: Boolean? = null
  override var showStats: Boolean? = null
  override var showFocusIndicator: Boolean? = null
  override var guideColor: Double? = null
  override var captureFeedback: Boolean? = null
  override var captureColor: Double? = null
  /** Applied prop values with defaults, written on main. A capture admits with one read, so it keeps one whole batch. */
  @Volatile private var props = resolveProps()

  /** Main thread, after each React prop batch; tests that set props directly call it themselves. */
  override fun afterUpdate() {
    if (disposed) return
    updateSession()
    updateModel()
    val old = props
    // Resolved whole before publishing, so an invalid batch changes nothing.
    val next = resolveProps()
    if (next == old) return
    synchronized(this) { props = next }
    liveDetector.inferenceThrottling = next.inferenceThrottling
    liveDetector.minCornerScore = next.minCornerScore
    liveDetector.flowMotionEnabled = flowMotionEnabled || next.guide
    liveOverlay.transitionNs = next.guideTransitionNs
    liveOverlay.color = next.guideColor
    captureFeedbackView.color = next.captureColor
    if (!next.captureFeedback) captureFeedbackView.clear()
    if (old.exposureCompensation != next.exposureCompensation) {
      // A capture keeps the exposure it was admitted with; the last capture to end applies the latest value.
      if (hasPendingCapture()) exposureDeferred = true else applyExposureCompensation()
    }
    if (old.torch != next.torch) {
      synchronized(metadataLock) {
        torchPolicy.reset()
        torchAuto = next.torch == TorchMode.AUTO
      }
      applyLight()
    } else if (old.lowLightBoost != next.lowLightBoost) {
      applyLight()
    }
    // In a dark scene, the torch, the boost and `darkLatency` decide whether `zsl` binds `latency`.
    val lightChangesMode = bound?.let { captureModeFor(next, it).first != it.captureMode } == true
    liveDetector.statsListener = if (next.showStats) ::renderStats else null
    focusBoxOverlay.visibility = if (next.showFocusIndicator) View.VISIBLE else View.GONE
    if (old.showStats != next.showStats) {
      statsFigures = null
      statsScroll.visibility = if (next.showStats) View.VISIBLE else View.GONE
      renderStatus()
      layoutStats()
    }
    if (old.squareAspectRatio != next.squareAspectRatio && !hasPendingCapture()) layoutStats()
    // A rebind applies the guide when it binds; a deferred one leaves the bound camera, which applies it below.
    var rebinding = false
    // The frame rate belongs to the bound session, so it rebinds too.
    if (old.analysisFrameWidth != next.analysisFrameWidth || old.captureMode != next.captureMode || lightChangesMode ||
      old.squareAspectRatio != next.squareAspectRatio || old.minFrameRate != next.minFrameRate ||
      old.highResolutionPhoto != next.highResolutionPhoto) {
      logLifecycle("use cases changed analysisFrameWidth=${next.analysisFrameWidth} captureMode=${next.captureMode} " +
        "squareAspectRatio=${next.squareAspectRatio} minFrameRate=${next.minFrameRate} " +
        "highResolutionPhoto=${next.highResolutionPhoto}")
      if (hasPendingCapture()) {
        logLifecycle("use-case rebind deferred until capture settles")
        requestRebind(RebindReason.CONFIGURATION)
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
      updateDocumentState(boundDocumentState())
    }
    // A rebind sets the scanner's lens when it binds.
    if (!rebinding) syncScannerLens()
    updateGyroscope()
    updateCaptureState()
  }



  /**
   * Main thread: source changes invalidate only this view's completion; in-flight captures settle first. The guide and
   * auto-crop need a model: without a source they follow the packaged one, and without that the camera stays off with
   * `input/invalid`. Meanwhile the document state reports `loading`, then `unavailable` on failure; leaving for photo
   * mode is the app's choice.
   */
  private fun updateModel() {
    val needsModel = (guideLiveCorners ?: true) || (autoCrop ?: true)
    if (modelChecked && requestedModel == cornerDetectionModel &&
      (!cornerDetectionModel.isNullOrEmpty() || requestedNeedsModel == needsModel)) return
    modelChecked = true
    requestedModel = cornerDetectionModel
    requestedNeedsModel = needsModel
    modelGeneration++
    modelReady = false
    modelFailure = null
    modelLoadPending = true
    if (!hasPendingCapture()) loadRequestedModel()
  }

  private fun loadRequestedModel() {
    if (!modelLoadPending || disposed) return
    modelLoadPending = false
    val generation = modelGeneration
    val uri = requestedModel
    fun failed(error: Throwable) {
      if (disposed || generation != modelGeneration) return
      modelFailure = error
      modelReady = false
      updateCameraState()
      updateDocumentState(DocumentState.UNAVAILABLE)
      onError?.invoke(scannerFailure(error, ScannerErrorCode.MODEL_LOAD_FAILED))
      ScannerLog.e("DocumentScanner", "Could not prepare corners tensor model", error)
    }
    fun ready() {
      if (disposed || generation != modelGeneration) return
      modelReady = true
      updateDocumentState(boundDocumentState())
      onHostViewVisible()
    }
    // Photo mode: captures use any active shared model, else keep the full frame. Ready before the stop, which
    // would otherwise report loading.
    val photo = uri.isNullOrEmpty() && !requestedNeedsModel
    modelReady = photo
    stopCamera("model-loading")
    if (photo) return ready()
    CornerTensorModelLoader.shared.loadCameraSource(themedContext.applicationContext, uri.orEmpty()) { error ->
      mainHandler.post { if (error == null) ready() else failed(error) }
    }
  }

  private fun resolveProps(): ResolvedProps {
    val minimumScore = minCornerScore ?: 0.95
    requireInput(minimumScore.isFinite() && minimumScore in 0.0..1.0) { "minCornerScore must be between 0 and 1." }
    val config = lowQualityControl
    val gated = config.enabled ?: true
    val maxCaptureBlur = if (!gated) 0.0
      else (config.captureBlurPercent ?: DEFAULT_CAPTURE_BLUR_PERCENT).coerceAtLeast(0.0) / 100
    val detection = guideLiveCorners ?: true
    return ResolvedProps(
      active = active ?: true,
      requireFocus = gated && (config.requireFocus ?: true),
      guide = detection,
      inferenceThrottling = inferenceThrottling ?: false,
      minCornerScore = minimumScore,
      lowQualityControl = gated,
      maxCaptureBlur = maxCaptureBlur,
      documentFocus = detection,
      autoCrop = autoCrop ?: true,
      renderOptions = resolveRenderOptions(),
      tapToFocus = tapToFocus ?: true,
      focusLock = focusLock ?: false,
      exposureCompensation = exposureCompensation?.takeIf { it.isFinite() } ?: 0.0,
      minFrameRate = minFrameRate?.takeIf { it.isFinite() }?.toInt()?.coerceAtLeast(0) ?: 0,
      torch = torch ?: TorchMode.OFF,
      lowLightBoost = lowLightBoost ?: true,
      guideTransitionNs = guideTransitionDuration?.let { (it * 1_000_000).toLong() } ?: GuideTransition.DEFAULT_DURATION_NS,
      squareAspectRatio = squareAspectRatio ?: false,
      analysisFrameWidth = analysisFrameWidth?.toInt()?.coerceIn(160, 1920) ?: DEFAULT_ANALYSIS_WIDTH,
      captureMode = captureMode ?: CaptureMode.ZSL,
      darkLatency = darkLatency ?: false,
      highResolutionPhoto = highResolutionPhoto ?: true,
      showStats = showStats ?: false,
      showFocusIndicator = showFocusIndicator ?: true,
      // processColor yields a signed or unsigned 32-bit ARGB number.
      guideColor = guideColor?.toLong()?.toInt() ?: LiveGuideOverlay.DEFAULT_COLOR,
      captureFeedback = captureFeedback ?: true,
      captureColor = captureColor?.toLong()?.toInt() ?: CaptureFeedbackView.DEFAULT_COLOR,
    )
  }

  init {
    logLifecycle("view created permission=${readPermissionStatus()}")
    previewView.installHierarchyFitter()
    previewView.previewStreamState.observeForever(streamObserver)
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
      focusBoxOverlay,
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
      statsScroll,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
    )
  }

  override fun takePhotoResult(options: TakePhotoOptions?): Promise<Variant_ScannerFailure_CameraCaptureResult> =
    takePhoto(options).scannerResult(ScannerErrorCode.CAPTURE_FAILED,
      { Variant_ScannerFailure_CameraCaptureResult.create(it) }, { Variant_ScannerFailure_CameraCaptureResult.create(it) })

  internal fun takePhoto(options: TakePhotoOptions? = null): Promise<CameraCaptureResult> {
    val promise = Promise<CameraCaptureResult>()
    try {
      val capture = captureAdmission.admit {
        if (disposed) throw ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE)
        val owner = (requestedSession as? HybridScanSession)?.state
          ?: throw ScannerException(ScannerErrorCode.CAPTURE_NOT_READY)
        val config = props
        val source = SessionCapture.reserve(owner, config.renderOptions)
        pendingPromises[promise] = Unit
        GatedCapture(promise, SystemClock.uptimeMillis(), options?.skipLowQualityControl == true,
          source, config, sessionGeneration)
      }
      mainHandler.post { takePhotoOnMain(capture) }
    } catch (error: Throwable) { promise.reject(error) }
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
    onHostViewVisible()
  }

  private fun onPermissionChange(status: CameraPermissionStatus) {
    logLifecycle("permission changed=$status")
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
      standaloneTrace?.let(ScannerDiagnostics::detach)
      pendingPromises.keys.toList().also { pendingPromises.clear() }
    }
    promises.forEach { it.reject(ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE)) }
    removeSessionObserver?.invoke()
    removeSessionObserver = null
    removePermissionListener?.invoke()
    removePermissionListener = null
    removeActivityLifecycleObserver()
    unregisterDisplayListener()
    stopCamera("view-dropped")
    clearCapturePreview()
    liveDetector.close()
    focus.close()
    metadataExecutor.shutdown()
    previewView.previewStreamState.removeObserver(streamObserver)
  }

  /** Main thread: registers [capture] on its first call, rechecks that it can still shoot on every call, and evaluates readiness.
   * It runs again only on an event: new frames, a focus or torch change, or the capture's deadline.
   */
  private fun takePhotoOnMain(capture: GatedCapture) {
    val promise = capture.promise
    if (!isPending(promise)) return
    if (!captureCurrent(capture)) {
      rejectPromise(promise, ScannerException(if (capture.source.session.snapshot().closed) ScannerErrorCode.PROCESSOR_DISPOSED else ScannerErrorCode.PROCESSOR_SUPERSEDED))
      return
    }
    if (!modelReady && !capture.registered) {
      // The capture promise falls back to `capture/failed`, so an untyped load failure carries its model code here.
      rejectPromise(promise, modelFailure?.let { failure ->
        if (failure is ScannerException || failure is InvalidInputException) failure
        else ScannerException(ScannerErrorCode.MODEL_LOAD_FAILED, failure)
      } ?: ScannerException(ScannerErrorCode.CAPTURE_NOT_READY))
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
    if (captureUseCase == null || !capture.registered && previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) {
      logLifecycle("capture rejected: camera preview/capture is not ready")
      rejectPromise(capture.promise, ScannerException(ScannerErrorCode.CAPTURE_NOT_READY))
      return
    }
    if (!capture.registered) {
      capture.registered = true
      // Judgment starts from the frames and focus that follow the press.
      capture.frameCursor = liveDetector.frameHistory.cursor()
      capture.focus = focus.snapshot
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
    val waiting = listOfNotNull(captureAdmission.current?.takeIf { it.registered && it.stage == CaptureStage.WAITING })
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
   * skipped. Document detection continues while autofocus runs, but the four-frame motion and blur check starts from
   * scratch once focus is accepted, so a refocus is revalidated. Motion only delays the shot or rejects it at the steady
   * deadline; it never starts another scan. The readiness state then goes to the capture's [CaptureGate], whose decision
   * alone waits, shoots or rejects.
   */
  private fun evaluateCapture(capture: GatedCapture, captureUseCase: ImageCapture) {
    if (!isPending(capture.promise) || capture.stage != CaptureStage.WAITING) return
    val config = capture.config
    val snapshot = focus.snapshot
    if (snapshot.generation != capture.focus.generation) {
      capture.focus = snapshot
      capture.stability.reset()
      capture.latest = null
      capture.inferenceFinished = false
    }
    // The gyroscope measures smear; without it the smear check is off rather than holding every capture.
    val blurLimit = if (gyroscope.isRunning) config.maxCaptureBlur else 0.0
    val motionCheckEnabled = config.lowQualityControl && blurLimit > 0
    // Live corners as they are now: a detector turned off mid-press reports no page to wait for. Output and processing
    // keep the press's own settings.
    val documentRequired = config.lowQualityControl && config.documentFocus && props.guide
    val nowMs = SystemClock.uptimeMillis()
    // The page and focus checks; with `requireFocus` off, focus itself is not waited for.
    val focusCheckEnabled = config.lowQualityControl && (hasAutofocus || documentRequired)
    val waitsForFocus = focusCheckEnabled && hasAutofocus && config.requireFocus
    // A frame's own autofocus state: a scan's lock, or continuous autofocus settled on focus. While a scan's successful
    // lock holds, the camera can reset its state to inactive, as some requests do on the SM-G998U; single-shot autofocus
    // then leaves the lens where the lock put it, so the lock still counts.
    val lockHolds = snapshot.owner != null && snapshot.locked == true
    fun frameFocused(afState: Int?) = !hasAutofocus || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
      afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
      (lockHolds && afState == CaptureResult.CONTROL_AF_STATE_INACTIVE)
    // Continuous autofocus may still report a focus it reached before the page arrived, on the background or a hand; each
    // frame carries when its focus was reached, so readiness and steadiness judge the same frame. A tap's lock is
    // exempt: the user chose that focus, and it holds until the tap ends even if the page arrived after it.
    val pageArrived = pageArrivedSensorNs
    val pageFocusApplies = documentRequired && hasAutofocus && snapshot.owner == null && pageArrived != null
    fun predatesPage(state: CameraFrameState) =
      pageFocusApplies && (state.focusReachedSensorNs ?: Long.MIN_VALUE) < pageArrived
    val (frames, missed) = liveDetector.frameHistory.since(capture.frameCursor)
    // Whether this evaluation read a record analysis just published, for the log: shooting on one an earlier evaluation
    // read, after a focus or torch change, means analysis had not produced a newer frame.
    var newFrame = false
    if (missed && motionCheckEnabled) capture.stability.interrupt()
    for (frame in frames) {
      capture.frameCursor = frame.sequence + 1
      if (!frame.after(snapshot.changedRealtimeNs, snapshot.changedNs)) continue
      capture.latest = frame
      if (frame.inferenceFinished) capture.inferenceFinished = true
      newFrame = true
      if (!motionCheckEnabled) continue
      // A frame exposed while focus was lost does not continue the sequence; one without its result cannot tell.
      val state = frame.frame
      if (waitsForFocus && !capture.gate.focusSkipped && state != null && (!frameFocused(state.afState) || predatesPage(state))) {
        capture.stability.interrupt(StabilityBreak.UNFOCUSED)
        continue
      }
      capture.stability.observe(frame, documentRequired && !capture.gate.focusSkipped, blurLimit, motionSensorsRunning)
    }
    val latest = capture.latest
    val binding = bound
    val afState = latest?.frame?.afState
    val skip = capture.skipLowQualityControl
    val scanningFrame = afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
    val staleFocus = latest?.frame?.let(::predatesPage) == true
    // Quality mode scans for each photo: at the page once one is found, or the preview's centre, since a dim, blurred
    // page may never be detected before its scan. Zero shutter lag and latency use continuous autofocus's focus, and
    // scan only at a page whose focus is stale and not scanning already; continuous autofocus may never rescan a page
    // it was already focused on.
    val qualityScan = binding?.captureMode == CaptureMode.QUALITY && (!documentRequired || latest != null)
    val staleScan = staleFocus && !scanningFrame && (!documentRequired || latest.guide != null)
    // In a dark scene with no lit torch and no active low-light boost, zero shutter lag and latency scan too, at the page
    // or the preview's centre: there continuous autofocus held blurred pages while reporting focus on the SM-G998U, and
    // the blur hid the page from detection. The shot then waits for frames exposed after the lock, four steady ones
    // with motion checking, and zero shutter lag saves only a buffered frame that reports the lock.
    val dark = (exposureIsoMs ?: 0.0) >= TorchPolicy.DARK_ISO_MS && !torchIsLit &&
      boundCamera?.info?.lowLightBoostState?.value != LowLightBoostState.ACTIVE
    val darkScan = dark && !scanningFrame && (!documentRequired || latest != null)
    // In `latency` bound for the light, a press with no page found scans at the preview's centre: under the torch on the
    // SM-G998U continuous autofocus held a page too blurred to detect while reporting focus, so the capture waited for
    // the page until it was rejected; the torch kept the scene from counting as dark.
    val unseenPageScan = binding?.lightFallback != null && documentRequired && latest != null && latest.guide == null &&
      !scanningFrame
    val ownScan = if (snapshot.owner != FocusOwner.CAPTURE) OwnScan.NONE else when (snapshot.locked) {
      null -> OwnScan.SCANNING
      true -> OwnScan.LOCKED
      false -> OwnScan.FAILED
    }
    // With `focusLock` and live corners, the scanner's focus cycle owns every focus attempt for the press, its final
    // camera scan included, within one budget ([ScannerPress]); the shot starts no scan of its own and reads the cycle's
    // result. The live setting decides, so turning it off hands a waiting press to the checks below.
    val managed = props.focusLock && props.guide && hasAutofocus && waitsForFocus && !skip
    val pressFocus = if (!managed) null else {
      val press = capture.press ?: ScannerPress(nowMs).also { started ->
        capture.press = started
        // The budget runs without frames: these wake the press when the final scan is due and when it runs out.
        mainHandler.postAtTime({ evaluateCaptures() }, capture.pressToken, started.finalScanAtMs)
        mainHandler.postAtTime({ evaluateCaptures() }, capture.pressToken, started.deadlineMs)
      }
      val locked = snapshot.locked == true
      val lockAccepted = when (snapshot.owner) {
        FocusOwner.PAGE -> locked && snapshot.generation != pageRelockGeneration
        // A guide lock is progress toward a page, not the page's focus: a page found under it keeps or takes the lock
        // over, and one still missing leaves the cycle its next step and the press its final scan.
        FocusOwner.GUIDE -> locked && !documentRequired
        FocusOwner.TAP, FocusOwner.CAPTURE -> locked
        // Continuous autofocus reports focus on a new scene before it reaches the page, and holds a nearer object as
        // focused, so a page's press waits for the lock it starts. Without a page to require, continuous autofocus
        // serves outside the dark.
        null -> !documentRequired && !darkForAutofocus()
      }
      val lensFree = boundCamera != null && !hasShotInFlight() &&
        !(snapshot.owner == FocusOwner.TAP && snapshot.scanHolds(nowMs))
      press.next(nowMs, lockAccepted, cycle.gaveUp, ownScan, lensFree)
    }
    if (pressFocus == PressFocus.START_FINAL_SCAN) {
      val camera = boundCamera ?: return
      val point = focusCandidate?.let(::meteringPoint)
        ?: scanPoint(FocusAim.CENTRE, FocusAim.CENTRE, MeteringPointFactory.getDefaultPointSize())
      logLifecycle("capture final focus scan gaveUp=${cycle.gaveUp} lighting=${cycle.lighting} page=${latest?.guide != null}")
      // The new focus generation re-evaluates this capture.
      focus.capture(camera, point)
      return
    }
    val scanTrigger = if (managed) null else when {
      qualityScan -> ScanTrigger.QUALITY
      staleScan -> ScanTrigger.STALE_FOCUS
      darkScan -> ScanTrigger.DARK
      unseenPageScan -> ScanTrigger.UNSEEN_PAGE
      else -> null
    }
    val focused = when {
      !hasAutofocus || !config.requireFocus -> true
      // A skipped gate waits only for a tap's scan in progress.
      skip -> !snapshot.scanning(nowMs)
      // The cycle's accepted lock, judged on a frame exposed after it.
      pressFocus != null -> pressFocus == PressFocus.ACCEPTED && frameFocused(afState)
      // A tap's or capture's scan in progress is waited for; a focus older than the page is not the page's; otherwise the
      // frame's own autofocus state decides.
      snapshot.scanning(nowMs) || staleFocus -> false
      else -> frameFocused(afState)
    }
    // Frames judged before focus was accepted do not count toward steadiness: the sequence starts with the first frame of
    // accepted focus.
    if (waitsForFocus && !skip && !capture.gate.focusSkipped && !focused) capture.stability.reset()
    // Zero shutter lag saves the newest buffered frame CameraX found focused, exposed and balanced, however old, so a gated
    // shot waits for the newest frame to qualify, whether or not focus is required. A fixed-focus camera never reports
    // focus, so its buffer stays empty and CameraX takes a new frame.
    val waitForZsl = config.lowQualityControl && binding?.zsl == true && hasAutofocus
    // One age for the newest frame, so the focus and buffer checks judge it at the same instant.
    val latestAgeNs = latest?.takeIf { scanningFrame || waitForZsl }?.ageNanos(System.nanoTime(), SystemClock.elapsedRealtimeNanos())
    // Autofocus is still moving, so a focus wait outlasts its limit rather than fail or shoot halfway. An owned scan
    // still pending counts by itself; a frame's scanning state counts only while that frame is recent, so a stalled
    // analysis cannot hold the shot on an old result.
    val frameScanningUntilMs = latestAgeNs?.takeIf { scanningFrame }?.let { nowMs + (FRESH_FOCUS_FRAME_NS - it) / 1_000_000 }
    val focusingUntilMs = when {
      !hasAutofocus || managed -> null
      snapshot.scanning(nowMs) -> Long.MAX_VALUE
      else -> frameScanningUntilMs?.takeIf { it > nowMs }
    }
    val lightPending = light.pending
    val postFocusInferenceFinished = if (documentRequired && ownScan == OwnScan.LOCKED) {
      capture.inferenceFinished || liveDetector.hasFailed() || !props.guide
    } else null
    val captureState = CaptureState(
      nowMs, if (skip) hasAutofocus && config.requireFocus else focusCheckEnabled, documentRequired, config.requireFocus,
      FOCUS_TIMEOUT_MS,
      documentFound = latest?.guide != null,
      focused = focused,
      zslCheckEnabled = waitForZsl,
      zslReady = latest?.zslReady == true,
      zslStatesKnown = waitForZsl && latest?.zslStatesKnown == true && latestAgeNs != null && latestAgeNs < FRESH_FOCUS_FRAME_NS,
      lightReady = !lightPending,
      motionCheckEnabled = motionCheckEnabled,
      // The sequence must reach the newest frame, so a sequence that stopped short of it is not steady.
      steady = capture.stability.stable && latest != null && latest.sensorNs == capture.stability.latestSensorNs,
      focusingUntilMs = focusingUntilMs,
      scanAvailable = !managed && !skip && waitsForFocus && boundCamera != null &&
        !(snapshot.owner == FocusOwner.TAP && snapshot.scanHolds(nowMs)),
      scanTrigger = scanTrigger,
      // The cycle's final scan is the press's, not the gate's: the press's budget bounds it.
      ownScan = if (managed) OwnScan.NONE else ownScan,
      cycleHoldsUntilMs = if (managed) capture.press?.holdsUntilMs() else null,
      frameSinceFocusChange = latest != null,
      postFocusInferenceFinished = postFocusInferenceFinished,
    )
    fun traceGate(fired: Boolean) = liveDetector.traceObserver?.onCaptureGate(LiveCaptureGateSample(
      SystemClock.elapsedRealtimeNanos(), fired, latest?.realtimeSensorNs, latest?.frame?.exposureNs, latest?.motion?.blur, latest?.motion?.source,
      latest?.flow, null, latest?.ageNanos(System.nanoTime(), SystemClock.elapsedRealtimeNanos()),
    ))
    val decision = capture.gate.next(captureState)
    liveDetector.captureInferenceFrame =
      if (decision is CaptureDecision.Wait && decision.reason == WaitReason.DOCUMENT && postFocusInferenceFinished == false) {
        { sensorNs, arrivalNs, realtime ->
          if (realtime) sensorNs >= snapshot.changedRealtimeNs else arrivalNs >= snapshot.changedNs
        }
      } else null
    when (decision) {
      is CaptureDecision.StartFocusScan -> {
        val camera = boundCamera ?: return
        val point = focusCandidate?.let(::meteringPoint)
          ?: scanPoint(FocusAim.CENTRE, FocusAim.CENTRE, MeteringPointFactory.getDefaultPointSize())
        logLifecycle("capture focus scan ${decision.attempt} trigger=${decision.trigger} stale=$staleFocus dark=$dark " +
          "page=${latest?.guide != null}")
        // The new focus generation re-evaluates this capture.
        focus.capture(camera, point)
      }
      is CaptureDecision.Wait -> {
        if (capture.reason != decision.reason) {
          logLifecycle("capture waiting for ${decision.reason}")
          capture.reason = decision.reason
          onCaptureStage?.invoke(CaptureStage.WAITING, decision.reason.toCaptureWaitReason())
        }
        mainHandler.removeCallbacksAndMessages(capture.deadlineToken)
        if (decision.untilMs != Long.MAX_VALUE) {
          mainHandler.postAtTime({ evaluateCaptures() }, capture.deadlineToken, decision.untilMs)
        }
      }
      is CaptureDecision.Reject -> {
        logLifecycle("capture rejected: ${decision.code} waiting for ${decision.reason} focusGeneration=${snapshot.generation} " +
          "document=${captureState.documentFound} focused=$focused lightReady=${captureState.lightReady} zsl=${latest?.zslReady} ae/awb=$aeAwbStates " +
          "stableFrames=${capture.stability.count} break=${capture.stability.lastBreak}")
        traceGate(fired = false)
        // Exposure or white balance kept adjusting, as under flicker or moving shadows. The capture still holds its
        // slot, so the rebind to `latency` waits for its completion, where a deactivation or model change goes first.
        if (decision.zslStarved) {
          light.bufferStarved()
          rebindForLight()
        }
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
    mainHandler.removeCallbacksAndMessages(capture.pressToken)
    capture.reason = null
    val file = capture.source.original.file
    // Stage times in ms since the request, for comparing capture settings in the lifecycle log.
    val elapsed = { SystemClock.uptimeMillis() - requestedMs }
    logLifecycle("capture timing steady=${elapsed()}ms mode=${bound?.captureMode}")
    previewView.bitmap?.let {
      capture.preview = it
      frozenFrame.setImageBitmap(it)
      frozenFrame.visibility = View.VISIBLE
    }
    capture.liveCandidate = trackedGuide
    capture.frame = CompletableFuture<LiveFrame>().also { frame -> liveDetector.requestFrame { frame.complete(it) } }
    capture.sample = SampleCollection.beginCapture(themedContext)
    capture.shutterRealtimeNs = SystemClock.elapsedRealtimeNanos()
    advanceCapture(capture, CaptureStage.SHOOTING)
    try {
      capture.physicalInFlight = true
      saveShot(captureUseCase, file, capture, elapsed)
    } catch (error: Throwable) {
      capture.physicalInFlight = false
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
            capture.physicalInFlight = false
            endCapture(promise)
            return
          }
          val owner = capture.source.session.resources.workOwner
          owner.queue.submit(owner, ScannerWorkQueue.Kind.CAPTURE, capture.source.pageId,
            operation = { describePhoto(capture) },
            onSuccess = { photo -> mainHandler.post { finishSavedCapture(capture, photo, elapsed) } },
            onFailure = { failure ->
              mainHandler.post {
                capture.physicalInFlight = false
                val error = if (failure is ScannerException) failure else ScannerException(ScannerErrorCode.CAPTURE_METADATA_FAILED, failure)
                rejectPromise(promise, error)
              }
            })
        }

        override fun onError(exception: ImageCaptureException) {
          capture.physicalInFlight = false
          ScannerLog.e(LOG_TAG, "CameraX photo capture failed with error ${exception.imageCaptureError}.", exception)
          rejectPromise(promise, ScannerException(ScannerErrorCode.CAPTURE_FAILED, exception))
        }
      },
    )
  }

  /** Main owner: claims publication only for the captured session/generation, then settles unlocked. */
  private fun finishSavedCapture(capture: GatedCapture, photo: SavedCapture, elapsed: () -> Long) {
    val promise = capture.promise
    capture.physicalInFlight = false
    if (isPending(promise) && captureCurrent(capture)) advanceCapture(capture, CaptureStage.CAPTURED)
    val kept = try {
      val id = synchronized(this) {
        if (!isPending(promise) || !captureCurrent(capture)) null else {
          capture.sample?.let { SampleCollection.remember(capture.source.original.destination.name, it) }
          val id = capture.source.publish(photo.frame, capture.liveCandidate?.corners, autoCrop = capture.config.autoCrop)
          if (settle(promise)) id else null
        }
      }
      if (id != null) {
        capture.preview?.let { capture.source.session.retainCapturePlaceholder(id, it) }
        promise.resolve(CameraCaptureResult(id, capture.source.session.snapshot()))
      }
      id != null
    } catch (error: Throwable) {
      rejectPromise(promise, error)
      false
    }
    capture.delivered = kept
    if (!kept && isPending(promise)) rejectPromise(promise, ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED))
    logLifecycle("capture complete total=${elapsed()}ms resolution=${photo.width}x${photo.height} orientation=${photo.orientation}")
    endCapture(promise)
  }
  /**
   * Main thread: the newest page, where a capture's scan aims, and when it arrived. Any detected outline counts, provisional
   * or confirmed, because continuous autofocus starts on a page as soon as it enters the view.
   */
  private fun onFocusCandidate(hint: LiveFocusCandidate) {
    if (!props.active || !props.guide || !hasAutofocus) return
    focusCandidate = hint.takeIf { it.corners != null }
    // A `focusLock` change that waited for a shot being taken applies once it settles.
    if (scannerLensDeferred && !hasShotInFlight()) syncScannerLens()
    updateSteadiness()
    if (hint.corners == null) {
      if (noPageSinceNs == null) noPageSinceNs = hint.sensorNs
      followCycleRestart()
      updateFocusLock(hint)
      // A page gone for PAGE_LOST_NS loses its print spot and returns continuous autofocus to the centre.
      if (hint.sensorNs - pageSeenSensorNs >= PAGE_LOST_NS) {
        printSpot = null
        if (!focusAim.centred && focusAim.observe(FocusAim.CENTRE, FocusAim.CENTRE, FocusAim.AREA)) aimContinuousFocus()
      }
      return
    }
    noPageSinceNs = null
    if (pageArrivedSensorNs == null || hint.sensorNs - pageSeenSensorNs >= PAGE_LOST_NS) {
      pageArrivedSensorNs = hint.sensorNs
      // Lighting the torch can restart the camera, and the same page then arrives again; its lock still waits.
      cycle.reset()
      printSpot = null
      logLifecycle("page arrived sensorNs=${hint.sensorNs}")
    }
    followCycleRestart()
    pageSeenSensorNs = maxOf(pageSeenSensorNs, hint.sensorNs)
    trackPrintSpot(hint)
    updateFocusLock(hint)
    // A page with no supported box, too small or narrow or mapping outside the preview, returns continuous autofocus to
    // the centre rather than leave it on the page's last box.
    val aim = focusBox(hint) ?: run {
      if (focus.snapshot.owner == FocusOwner.PAGE) focusBoxOverlay.show(null, true)
      if (!focusAim.centred && focusAim.observe(FocusAim.CENTRE, FocusAim.CENTRE, FocusAim.AREA)) aimContinuousFocus()
      return
    }
    // The dot follows the print every frame while continuous autofocus or the page lock focuses there.
    val owner = focus.snapshot.owner
    if (owner == FocusOwner.PAGE || owner == null) {
      focusBoxOverlay.show(floatArrayOf(aim[0], aim[1], aim[2] * previewImageScale[0], aim[2] * previewImageScale[1]), false)
    }
    if (focusAim.observe(aim[0], aim[1], aim[2])) aimContinuousFocus()
  }

  /**
   * Main thread: the page's print spot, picked once per page on its first steady, confirmed frame and kept in page
   * coordinates, so the tracked outline carries it with the page: the focus aim and a lock's point stay on the same
   * print as the hand drifts. A page's print does not change, so it is picked again only for a page arriving anew, a
   * restarted focus cycle, or a spot that left the frame.
   */
  private fun trackPrintSpot(hint: LiveFocusCandidate) {
    val corners = hint.corners ?: return
    if (printSpot == null) {
      val picked = hint.spot?.takeIf { hint.confirmed }
      if (picked != null) {
        printSpot = picked
        logLifecycle("print spot picked at %.2f, %.2f of the page".format(picked.x, picked.y))
      } else if (hint.confirmed && steadyHold) {
        liveDetector.requestSpot(++spotRequests)
      }
    }
    val spot = printSpot
    val inFrame = spot?.let { pagePoint(corners, it.x, it.y) }
    if (inFrame != null && (inFrame.x !in SPOT_FRAME_MARGIN..1 - SPOT_FRAME_MARGIN ||
        inFrame.y !in SPOT_FRAME_MARGIN..1 - SPOT_FRAME_MARGIN)) {
      printSpot = null
      logLifecycle("print spot left the frame")
    }
  }

  /** Main thread: a capture scan's point in [hint]'s focus box, no larger than CameraX's default point. */
  private fun meteringPoint(hint: LiveFocusCandidate): MeteringPoint? = focusBox(hint)?.let {
    scanPoint(it[0], it[1], minOf(it[2], MeteringPointFactory.getDefaultPointSize()))
  }

  /** Main thread: a scan's metering point at preview fractions ([x], [y]) with side [size], a fraction of the camera
   * image; the debug overlay shows it as the region that now holds focus. */
  private fun scanPoint(x: Float, y: Float, size: Float): MeteringPoint {
    focusBoxOverlay.show(floatArrayOf(x, y, size * previewImageScale[0], size * previewImageScale[1]), false)
    return previewView.meteringPointFactory.createPoint(x * previewView.width, y * previewView.height, size)
  }

  /**
   * Main thread: [hint]'s focus box as preview fractions (x, y, size): a [FocusAim.AREA] box on the page's print spot,
   * or its centre before one is picked, kept inside the page's outline; null without a page, when the target maps
   * outside the preview, or when not even the smallest box fits the page.
   */
  private fun focusBox(hint: LiveFocusCandidate): FloatArray? {
    val transform = previewView.outputTransform ?: return null
    val corners = hint.corners ?: return null
    val width = previewView.width.toFloat()
    val height = previewView.height.toFloat()
    if (width == 0f || height == 0f) return null
    val outline = listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
    val centreX = outline.sumOf { it.x } / 4
    val centreY = outline.sumOf { it.y } / 4
    val target = printSpot?.let { pagePoint(corners, it.x, it.y) } ?: DocumentPoint(centreX, centreY)
    val points = floatArrayOf(
      (target.x * hint.width).toFloat(), (target.y * hint.height).toFloat(),
      (centreX * hint.width).toFloat(), (centreY * hint.height).toFloat(),
    ) + outline.flatMap { listOf((it.x * hint.width).toFloat(), (it.y * hint.height).toFloat()) } +
      floatArrayOf(0f, 0f, hint.width.toFloat(), hint.height.toFloat())
    try { CoordinateTransform(hint.source, transform).mapPoints(points) } catch (_: IllegalArgumentException) { return null }
    if (!points.all(Float::isFinite) || points[0] !in 0f..width || points[1] !in 0f..height) return null
    for (index in points.indices) points[index] /= if (index % 2 == 0) width else height
    // The analysis frame shares the preview's viewport, so its corners give the camera image within the view.
    previewImageScale = floatArrayOf(abs(points[14] - points[12]), abs(points[15] - points[13]))
    return fitInside(points.copyOfRange(4, 12), points[0], points[1], points[2], points[3], FocusAim.AREA,
      previewImageScale[0], previewImageScale[1])
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
    if (cameraController != null && imageCapture != null) {
      logLifecycle("camera start skipped: already bound")
      return
    }
    if (cameraStartRequestInFlight) {
      logLifecycle("camera start skipped: provider request in flight")
      return
    }

    val lifecycleOwner = themedContext.currentActivity as? LifecycleOwner
    if (lifecycleOwner == null) {
      onError?.invoke(scannerFailure(ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE), ScannerErrorCode.CAMERA_UNAVAILABLE))
      return
    }
    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

    val generation = ++providerRequestGeneration
    cameraStartRequestInFlight = true
    updateCameraState()
    logLifecycle("camera provider requested generation=$generation size=${container.width}x${container.height} rotation=$targetRotation")
    val controller: LifecycleCameraController
    val future = try {
      controller = LifecycleCameraController(themedContext)
      controller.initializationFuture
    } catch (_: Throwable) {
      cameraStartRequestInFlight = false
      updateCameraState()
      logLifecycle("camera provider request failed")
      onError?.invoke(scannerFailure(ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE), ScannerErrorCode.CAMERA_UNAVAILABLE))
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
        return@addListener
      }
      try {
        future.get()
        layoutStats()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val cameraInfo = ProcessCameraProvider.getInstance(themedContext).get().getCameraInfo(selector)
        val lowLightBoostSupported = cameraInfo.isLowLightBoostSupported
        val hasFlash = cameraInfo.hasFlashUnit()
        val slowSizes = cameraInfo.cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
          ?.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toSet()
        val (captureMode, lightFallback) = captureModeFor(props, lowLightBoostSupported, hasFlash, atBind = true)
        if (lightFallback != null) logLifecycle("capture mode=$captureMode for $lightFallback, requested ${props.captureMode}")
        // The controller attaches the preview to previewView.
        val preview = Preview.Builder()
          .setTargetRotation(targetRotation)
          .build()
        val capture = ImageCapture.Builder()
          .setCaptureMode(
            when (captureMode) {
              CaptureMode.QUALITY -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
              CaptureMode.LATENCY -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
              // CameraX falls back to MINIMIZE_LATENCY where zero shutter lag is unsupported.
              CaptureMode.ZSL -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            },
          )
          .also { observeStillFrames(it, generation) }
          .setResolutionSelector(
            ResolutionSelector.Builder()
              // A high-resolution photo, the size for text, may also use the sizes a camera lists as slower
              // high-resolution ones, which limited phones hold their full-size 4:3 stills in; the ranking takes one
              // only when no normal size suits text. Otherwise the photo is the standard 1440x1080-class one.
              .also {
                if (props.highResolutionPhoto) {
                  it.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                }
              }
              // Reorder all CameraX-advertised sizes across aspect ratios.
              // Keep fallbacks so the three bound use cases can negotiate a supported combination.
              .setResolutionFilter { supportedSizes, _ ->
                (if (props.highResolutionPhoto) CaptureResolutionPolicy.rank(supportedSizes, { it.width }, { it.height }, { it in slowSizes })
                else CaptureResolutionPolicy.rankStandard(supportedSizes, { it.width }, { it.height })).also { ranked ->
                  logLifecycle("capture candidates=${ranked.joinToString()} mode=$captureMode " +
                    "highResolution=${props.highResolutionPhoto} slow=${slowSizes.joinToString()}")
                }
              }
              .build(),
          )
          .setTargetRotation(targetRotation)
          .build()
        // Page thumbnails match the live frame, so a capture's latest frame and its renders share one size.
        PageThumbnails.longSide = props.analysisFrameWidth
        val analysis = ImageAnalysis.Builder()
          .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(props.analysisFrameWidth.let { Size(it, it * 3 / 4) }, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
          ).build())
          .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setTargetRotation(targetRotation)
          .build()
        check(previewView.width > 0 && previewView.height > 0) { "The camera viewport is not measured yet." }
        val useCases = listOf(preview, capture, analysis)
        val range = cameraInfo
          .getSupportedFrameRateRanges(SessionConfig(useCases)).toList()
          .let { CameraExposurePolicy.targetFpsRange(it, props.minFrameRate, { range -> range.lower }, { range -> range.upper }) }
        logLifecycle("session fpsRange=$range")
        // The controller replaces the session's viewport with previewView's. FIT keeps the whole frame in every use
        // case; on a square preview FILL_CENTER crops all three to the frame's centered square, so the saved JPEG and
        // the analysis frames match what the preview shows.
        previewView.scaleType = if (props.squareAspectRatio) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        liveDetector.begin(props.guide)
        analysis.setAnalyzer(liveDetector.executor, liveDetector.boundAnalyzer())
        val camera = try {
          // Display rotation sets the use cases' target rotation, and a rotation rebinds; the controller must not
          // follow the device sensor instead.
          controller.isAutoRotationEnabled = false
          controller.isPinchToZoomEnabled = false
          // Taps go to [onPreviewTap].
          controller.isTapToFocusEnabled = false
          controller.setSessionConfig(
            if (range != null) SessionConfig(useCases, frameRateRange = range) else SessionConfig(useCases), selector)
          previewView.controller = controller
          controller.bindToLifecycle(lifecycleOwner)
          val control = controller.cameraControl
          val info = controller.cameraInfo
          check(control != null && info != null) { "The camera controller did not bind." }
          BoundCamera(control, info)
        } catch (error: Throwable) {
          previewView.controller = null
          analysis.clearAnalyzer()
          liveDetector.stop()
          throw error
        }
        cameraController = controller
        boundCamera = camera
        cameraFeatures = CameraFeatureSupport(lowLightBoostSupported, camera.info.isZslSupported)
        observeFrameState(camera, generation)
        // OFF and EDOF do not scan; minimum focus distance may be missing, so it cannot decide.
        val characteristics = camera.info.cameraCharacteristics
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        hasAutofocus = hasAdjustableAutofocus(afModes)
        hasSingleShotAutofocus = afModes?.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) == true
        logLifecycle("autofocus adjustable=$hasAutofocus modes=${afModes?.joinToString()} " +
          "pipelineMaxDepth=${characteristics.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)}")
        liveDetector.cameraOptics = backCameraOptics(camera.info)
        depthOfField = try { DepthOfField.read(characteristics) } catch (_: Exception) { null }
        logLifecycle("depth of field $depthOfField")
        // The ImageCapture above was built from these props in this same call; a later change waits for a rebind.
        bound = BoundCapture(captureMode,
          zsl = captureMode == CaptureMode.ZSL && camera.info.isZslSupported, lowLightBoostSupported = lowLightBoostSupported,
          hasFlash = hasFlash, lightFallback = lightFallback)
        if (captureMode == CaptureMode.ZSL) logLifecycle("zero shutter lag supported=${camera.info.isZslSupported}")
        applyExposureCompensation()
        scannerLensOn = props.focusLock && props.guide
        scannerLensDeferred = false
        aimContinuousFocus()
        applyLight()
        updateGyroscope()
        imageCapture = capture
        imageAnalysis = analysis
        if (debugLoggingEnabled) {
          val stateLiveData = camera.info.cameraState
          val observer = Observer<CameraState> { state ->
            logLifecycle("CameraX state=$state")
          }
          stateLiveData.observe(lifecycleOwner, observer)
          observedCameraState = stateLiveData
          cameraStateObserver = observer
        }
        logLifecycle("CameraX bound generation=$generation container=${container.width}x${container.height} preview=${preview.resolutionInfo?.resolution} capture=${capture.resolutionInfo?.resolution} analysis=${analysis.resolutionInfo?.resolution} rotation=$targetRotation shown=${previewView.isShown}")
        updateCameraState()
        updateDocumentState(boundDocumentState())
      } catch (error: Throwable) {
        updateCameraState()
        logLifecycle("camera bind failed generation=$generation message=${error.message}")
        ScannerLog.e(LOG_TAG, "CameraX preview binding failed", error)
        onError?.invoke(scannerFailure(ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE), ScannerErrorCode.CAMERA_UNAVAILABLE))
      }
    }, ContextCompat.getMainExecutor(themedContext))
  }

  /** [keepLightDecision]: the stop is a rebind for the light, whose readings still hold; see [RebindReason.LIGHT]. */
  private fun stopCamera(reason: String, keepLightDecision: Boolean = false) {
    captureAdmission.current?.let { rejectPromise(it.promise, ScannerException(ScannerErrorCode.CAMERA_UNAVAILABLE)) }
    pendingRebind = null
    providerRequestGeneration += 1L
    cameraStartRequestInFlight = false
    val controller = cameraController
    imageAnalysis?.clearAnalyzer()
    liveDetector.stop()
    liveDetector.cameraOptics = null
    synchronized(metadataLock) {
      metadataGeneration = providerRequestGeneration
      liveDetector.frames.clear()
      autofocusState = AutofocusState.INACTIVE
      focusReachedSensorNs = null
      // A new binding re-evaluates the room lighting; CameraX turns the torch off with the camera. A rebind for the
      // light keeps the auto torch's decision, so the torch relights at once rather than after another dark second.
      if (!keepLightDecision) torchPolicy.reset()
      // The room's readings outlive the binding, but a flip still under way must not count the time without frames.
      darkScene.interrupt()
      whiteBalance.interrupt()
      exposureIsoMs = null
    }
    light.unbind()
    // The camera keeps its interop options across bindings; drop this binding's capture callback with it.
    boundCamera?.let { stopFrameState(it) }
    boundCamera = null
    statsFigures = null
    cameraFeatures = null
    bound = null
    hasAutofocus = false
    focusCandidate = null
    focusAim.reset()
    focusBoxOverlay.show(null, true)
    pageArrivedSensorNs = null
    steadiness.reset()
    steadyHold = false
    noPageSinceNs = null
    pageSize.reset()
    liveDetector.cancelSpots()
    guideSpot.cancel()
    printSpot = null
    cycle.reset()
    focus.unbind()
    steadinessWait = null
    gyroscope.stop()
    liveOverlay.clear()
    trackedGuide = null
    latestModelScore = null
    updateDocumentState(modelDocumentState() ?: DocumentState.OFF)
    emitAutofocus()
    logLifecycle("camera stop reason=$reason bound=${controller != null} generation=$providerRequestGeneration")
    val observer = cameraStateObserver
    val stateLiveData = observedCameraState
    if (observer != null && stateLiveData != null) stateLiveData.removeObserver(observer)
    cameraStateObserver = null
    observedCameraState = null
    if (controller != null) {
      try {
        // Detaching unbinds only this controller's session, unlike unbind(), which unbinds every use case.
        previewView.controller = null
      } catch (_: Throwable) {
        logLifecycle("camera unbind failed reason=$reason")
        // A provider can already be shutting down as its owning Activity stops.
      }
    }
    cameraController = null
    imageCapture = null
    imageAnalysis = null
    // PreviewView may never have left IDLE, so its observer would not report the stop.
    updateCameraState()
  }

  private fun renderStats(stats: LiveDetectionStats) {
    statsFigures = stats
    renderStatus()
  }

  /** Main thread: every row is present even before the first measurement. */
  private fun renderStatus() {
    if (!props.showStats) return
    val stats = statsFigures
    val missing = "-"
    fun number(value: Double?, pattern: String = "%.1f") =
      value?.let { String.format(Locale.US, pattern, it) } ?: missing
    fun ms(value: Double?) = value?.let { "${number(it)} ms" } ?: missing
    val frame = stats?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}x${it.height}" } ?: missing
    fun pacing(value: String?) = when (value) {
      "acquiring" -> "searching"
      "unconfirmed" -> "no page"
      "revalidating" -> "recheck"
      "tracking degraded" -> "degraded"
      null -> missing
      else -> value
    }
    val capture = when (boundCaptureLabel()) {
      "zsl" -> "ZSL"
      "latency" -> "latency"
      "quality" -> "quality"
      else -> missing
    }
    val torch = if (torchIsLit) "on" else if (props.torch == TorchMode.AUTO) "auto (idle)" else "off"
    val entries = listOf(
      "Camera: ${emittedCameraState?.name?.lowercase() ?: "suspended"}",
      "AF: ${emittedAutofocus?.name?.lowercase() ?: "inactive"}",
      "Page: ${documentState.name.lowercase()}",
      "Score: ${number(latestModelScore, "%.4f")}",
      "Frame: $frame",
      "Runs/s: ${number(stats?.rateHz)}",
      "Prep: ${ms(stats?.preprocessingMs)}",
      "Model: ${ms(stats?.inferenceMs)}",
      "Analysis: ${ms(stats?.analysisMs)}",
      "Frame age: ${ms(stats?.cameraToAnalysisMs)}",
      "Pacing: ${pacing(stats?.adaptiveStatus)}",
      "Wake: ${pacing(stats?.adaptiveReset)}",
      "Checks: ${ms(stats?.checksMsPerFrame)}",
      "Gated: ${stats?.gatedFrames ?: missing}",
      "Mode: $capture",
      "Torch: $torch",
      "Boost: ${lowLightBoostLabel()}",
      "Exp x ISO: ${number(exposureIsoMs, "%.0f")}",
      "AE / AWB: ${if (stats == null) missing else aeAwbStates}",
      "Peak blur: ${stats?.peakBlurPercent?.let { "${number(it, "%.2f")}%" } ?: missing}",
      "No meta: ${stats?.unmatchedFrames ?: missing}",
    )
    for ((cell, entry) in statsCells.zip(entries)) {
      if (cell.text != entry) cell.text = entry
    }
    layoutStatsStrip()
  }

  private fun statsStripHeight(): Int =
    if (props.showStats) minOf(container.height / 2,
      statsCells.first().lineHeight * statsGrid.childCount + statsGrid.paddingTop + statsGrid.paddingBottom)
    else 0

  private fun layoutStatsStrip() {
    if (!props.showStats || container.width <= 0) return
    val width = container.width
    val height = statsStripHeight()
    statsScroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    statsScroll.layout(0, 0, width, height)
  }

  /** Main thread: places the stats strip above the preview; React Native's layout never measures native children. */
  private fun layoutStats() {
    val width = container.width
    val height = container.height
    if (width <= 0 || height <= 0) return
    // Explicitly lay out native children: RN does not guarantee another FrameLayout pass after requestLayout.
    // Reserve the same font-scaled height even before the first statistics arrive.
    val top = statsStripHeight()
    fun place(view: View, x: Int, y: Int, childWidth: Int, childHeight: Int) {
      view.measure(
        View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(childHeight, View.MeasureSpec.EXACTLY),
      )
      view.layout(x, y, x + childWidth, y + childHeight)
    }
    layoutStatsStrip()
    // FIT_CENTER scales the viewport crop but does not hide the surrounding camera texture inside a
    // rectangular PreviewView. Make the view itself square so it clips to the same area as analysis.
    if (!hasPendingCapture()) previewSquareFrame = props.squareAspectRatio
    liveOverlay.squareAspectRatio = previewSquareFrame
    val cameraWidth = if (previewSquareFrame) minOf(width, height - top) else width
    val cameraHeight = if (previewSquareFrame) cameraWidth else height - top
    val cameraLeft = (width - cameraWidth) / 2
    val cameraTop = top + (height - top - cameraHeight) / 2
    for (view in listOf(previewView, liveOverlay, focusBoxOverlay, frozenFrame, captureFeedbackView)) {
      place(view, cameraLeft, cameraTop, cameraWidth, cameraHeight)
    }
  }

  /** Main thread: a tap at ([x], [y]) in [previewView] focuses there, metering the way CameraX's own tap does. A shot
   * in flight refuses it, since that photo's focus must not change. */
  private fun onPreviewTap(x: Float, y: Float) {
    val camera = boundCamera
    if (!props.tapToFocus || camera == null) return
    val factory = previewView.meteringPointFactory
    if (previewView.width > 0 && previewView.height > 0) {
      focusBoxOverlay.show(floatArrayOf(x / previewView.width, y / previewView.height,
        FocusRules.TAP_AREA * previewImageScale[0], FocusRules.TAP_AREA * previewImageScale[1]), false)
    }
    focus.tap(camera, factory.createPoint(x, y, FocusRules.TAP_AREA), factory.createPoint(x, y, TAP_METERING_AREA))
  }

  /** Main thread: sets the bound camera's torch and low-light boost from the props and the auto decision. Changes wait
   * while a capture is pending, so the light does not change under its judgment or shot; the capture's end applies
   * them. A failed torch change can be retried by the next call. */
  /** Main thread: applies the torch and boost, as [CameraLightController.apply] defers them around a capture. */
  private fun applyLight() {
    val autoHeld = hasShotInFlight() || bound?.zsl == true && hasPendingCapture()
    light.apply(boundCamera, props.torch, props.lowLightBoost, hasPendingCapture(), autoHeld) {
      synchronized(metadataLock) { torchPolicy.lit }
    }
  }

  private val torchIsLit: Boolean get() = boundCamera?.info?.torchState?.value == TorchState.ON

  /** The bound still-capture mode for the stats strip; `zsl` without camera support shoots as `latency`. */
  private fun boundCaptureLabel(): String {
    val binding = bound ?: return "n/a"
    return if (binding.captureMode == CaptureMode.ZSL && !binding.zsl) "latency" else binding.captureMode.name.lowercase()
  }

  /** Low-light boost for the stats strip: unsupported, off, inactive or active. */
  private fun lowLightBoostLabel(): String {
    val info = boundCamera?.info ?: return "n/a"
    if (!info.isLowLightBoostSupported) return "unsupported"
    return when (info.lowLightBoostState.value) {
      LowLightBoostState.ACTIVE -> "active"
      LowLightBoostState.INACTIVE -> "auto (idle)"
      else -> "off"
    }
  }

  /** Main thread: sets the bound camera's AE compensation index from the current exposureCompensation, so after a
   * deferral only the latest value applies; a newer call cancels it. */
  private fun applyExposureCompensation() {
    exposureDeferred = false
    val camera = boundCamera ?: return
    val state = camera.info.exposureState
    if (!state.isExposureCompensationSupported) return
    val range = state.exposureCompensationRange
    val index = CameraExposurePolicy.compensationIndex(
      props.exposureCompensation, state.exposureCompensationStep.toDouble(), range.lower, range.upper,
    )
    logLifecycle("exposure compensation index=$index")
    camera.control.setExposureCompensationIndex(index)
  }

  /**
   * Main thread: while no scan holds the lens, points continuous autofocus at [focusAim]'s box: on the page's print
   * spot, which has the detail focus needs, inside its outline, or the frame's centre without a page, so it does not
   * settle on the background. The action locks nothing, so continuous autofocus keeps running; a scan's own action
   * replaces its region, and the scan's release restores it.
   */
  private fun aimContinuousFocus() {
    val camera = boundCamera ?: return
    // A pending cancellation aims it once it completes.
    if (!hasAutofocus || focus.snapshot.owner != null || focus.resuming) return
    val point = if (focusAim.centred || previewView.width == 0 || previewView.height == 0) {
      SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(FocusAim.CENTRE, FocusAim.CENTRE, FocusAim.AREA)
    } else {
      previewView.meteringPointFactory.createPoint(focusAim.x * previewView.width, focusAim.y * previewView.height, focusAim.size)
    }
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).setLockingMode(0).disableAutoCancel().build()
    logLifecycle(if (focusAim.centred) "continuous focus aimed at the centre"
      else "continuous focus aimed at x=%.2f y=%.2f size=%.2f".format(focusAim.x, focusAim.y, focusAim.size))
    if (focusAim.centred) focusBoxOverlay.show(null, true)
    try { camera.control.startFocusAndMetering(action) } catch (error: Exception) {
      logLifecycle("continuous focus region failed ${error.javaClass.simpleName}")
    }
  }

  private fun updateCaptureState() {
    if (disposed) return
    val owner = (requestedSession as? HybridScanSession)?.state
    val capture = captureAdmission.current
    val available = props.active && modelReady && owner != null && !owner.snapshot().closed &&
      container.isAttachedToWindow && readPermissionStatus() == CameraPermissionStatus.GRANTED &&
      imageCapture != null && previewView.previewStreamState.value == PreviewView.StreamState.STREAMING &&
      (capture == null || captureCurrent(capture))
    val state = cameraCaptureState(available, capture?.stage, capture != null)
    if (state == emittedCaptureState) return
    emittedCaptureState = state
    onCaptureStateChange?.invoke(state)
  }
  private fun updateCameraState() {
    if (disposed) return
    updateCaptureState()
    val state = when {
      cameraController == null -> if (cameraStartRequestInFlight) ScannerCameraState.STARTING else ScannerCameraState.SUSPENDED
      previewView.previewStreamState.value != PreviewView.StreamState.STREAMING -> ScannerCameraState.STARTING
      hasPendingCapture() -> ScannerCameraState.CAPTURING
      else -> ScannerCameraState.READY
    }
    if (state == emittedCameraState) return
    emittedCameraState = state
    renderStatus()
    onCameraStateChange?.invoke(state)
  }

  private fun emitAutofocus() {
    if (disposed) return
    // A held lock the camera reports as `inactive` is reported `locked`.
    val camera = autofocusState
    val held = focus.snapshot.let { it.owner != null && it.locked == true }
    val state = if (held && camera == AutofocusState.INACTIVE) AutofocusState.LOCKED else camera
    focusBoxOverlay.state(state)
    if (state == emittedAutofocus) return
    emittedAutofocus = state
    renderStatus()
    onAutofocusChange?.invoke(state)
  }

  /** Main thread: feeds the analysis frames since the last candidate to [steadiness]. */
  private fun updateSteadiness() {
    val (frames, missed) = liveDetector.frameHistory.since(steadinessCursor)
    if (missed) steadiness.interrupt()
    // The motion sensors say the phone is still: a soft or dark frame, before focus is found, has flow that reads
    // motion a still hand does not make. [steadiness] stands in without them.
    var sensed: HandMotion.Reading? = null
    for (frame in frames) {
      steadinessCursor = frame.sequence + 1
      steadiness.observe(frame, false, 0.0, motionSensorsRunning)
      sensed = sensedMotion(frame.sensorNs)
      // Every frame's steadiness, so a move between two candidates still restarts the cycle.
      if (props.focusLock) cycle.motion(frame.sensorNs, sensed?.quiet ?: steadiness.stable)
      traceSteadiness(frame, sensed)
    }
    if (frames.isNotEmpty()) steadyHold = sensed?.quiet ?: steadiness.stable
  }

  /** The hand's motion at [sensorNs] by the motion sensors ([HandMotion.read]); null without them, and the focus cycle
   * then falls back to [steadiness]. */
  private fun sensedMotion(sensorNs: Long): HandMotion.Reading? =
    if (liveDetector.cameraOptics == null || !gyroscope.isRunning) null else hand.read(sensorNs)

  /** Main thread: the gyroscope and accelerometer both run, so a frame without either reading has unknown motion. */
  private val motionSensorsRunning: Boolean get() = gyroscope.isRunning && gyroscope.hasAcceleration

  /** Main thread: while `focusLock` waits for a steady hold, a once-a-second trace line of why frames broke it. */
  private fun traceSteadiness(frame: FrameRecord, sensed: HandMotion.Reading?) {
    // Nothing reads the report without the debug log or the standalone trace.
    if (!debugLoggingEnabled && standaloneTrace == null) return
    if (!props.focusLock || focus.snapshot.owner != null) return
    val waiting = steadinessWait ?: SteadinessWait(frame.sensorNs).also { steadinessWait = it }
    waiting.frames++
    if (steadiness.count == 0) waiting.breaks.merge(steadiness.lastBreak?.name ?: "none", 1, Int::plus)
    frame.flow?.let { flow -> waiting.statuses.merge(flow.status.name, 1, Int::plus)
      flow.speedPxPerSecond?.let { waiting.flowSpeeds += it / flow.sourceWidth } }
    if (sensed != null) {
      waiting.sensed++
      if (sensed.quiet) waiting.quiet++
      waiting.turns += sensed.turn
      waiting.accelerations += sensed.acceleration
    }
    if (frame.sensorNs - waiting.startNs < 1_000_000_000L) return
    steadinessWait = null
    if (steadyHold && (waiting.sensed > 0 || waiting.breaks.isEmpty())) return
    fun median(values: List<Double>) = values.sorted().getOrNull(values.size / 2)?.let { "%.3f".format(it) } ?: "-"
    fun max(values: List<Double>) = values.maxOrNull()?.let { "%.3f".format(it) } ?: "-"
    logLifecycle("focus lock steadiness frames=${waiting.frames} breaks=${waiting.breaks} flow=${waiting.statuses} " +
      "speed median=${median(waiting.flowSpeeds)} max=${max(waiting.flowSpeeds)} sensed=${waiting.sensed} " +
      "quiet=${waiting.quiet} turn median=${median(waiting.turns)} max=${max(waiting.turns)} " +
      "accel median=${median(waiting.accelerations)} max=${max(waiting.accelerations)} page=${focusCandidate != null}")
  }

  private class SteadinessWait(val startNs: Long) {
    var frames = 0
    val breaks = mutableMapOf<String, Int>()
    val statuses = mutableMapOf<String, Int>()
    val flowSpeeds = mutableListOf<Double>()
    var sensed = 0
    var quiet = 0
    val turns = mutableListOf<Double>()
    val accelerations = mutableListOf<Double>()
  }

  /**
   * Main thread, `focusLock`: the document camera's side of [FocusCycle], which decides when the camera's own locking
   * scan runs. The target is the confirmed page's print spot, a [FocusOwner.PAGE] lock, or without a page the guide
   * area's most textured spot, a [FocusOwner.GUIDE] lock, since a page too blurred to find usually lies under the guide
   * and is found once the lock sharpens it; a page unseen for [PAGE_DROPOUT_NS] leaves the guide as the target. A page
   * found under a guide lock whose point lies on it keeps the lock as the page's; one whose point lies off it locks again
   * at its print. A guide lock found nothing when no page appears within [RECOVERY_NS] of its settling. A page lock
   * follows the page's size and holds until the page is unseen for [PAGE_LOST_NS]; a guide lock follows no size, so a
   * deliberate move releases it. The model's outline jitters and drops out on a still page, so its dropout never ends a
   * lock by itself. The steady hold is [steadyHold]: the motion sensors, or the capture's motion rule without them.
   */
  private fun updateFocusLock(hint: LiveFocusCandidate) {
    if (!props.focusLock) return
    val owner = focus.snapshot.owner
    // A tap's or a capture's own scan holds the lens for its own time; the guide asks for its spot again after it.
    if (owner != null && !owner.scanners) {
      cancelGuideSpot()
      return
    }
    val page = hint.corners != null
    if (owner == FocusOwner.GUIDE && focus.snapshot.locked != null) {
      // The guide lock sharpened the frame, so the page it hid can be found now rather than at the search's next retry.
      if (guideSettledGeneration != focus.snapshot.generation) {
        guideSettledGeneration = focus.snapshot.generation
        guideSettledSensorNs = hint.sensorNs
        liveDetector.inferenceWanted = true
      }
    }
    // The guide's spot is picked on a steady frame without a page, so the guide lock aims at what the phone holds.
    if (!page) {
      if (!steadyHold && owner == null) cancelGuideSpot()
      else guideSpot.update(hint.sampled, liveDetector.spotRequests, hint.sensorNs, steadyHold && owner == null) {
        (++spotRequests).also(liveDetector::requestSpot)
      }
    } else {
      // A page frame takes any request as the page's; the guide asks again once the page is gone.
      guideSpot.cancel()
    }
    if (owner == FocusOwner.PAGE && !page && hint.sensorNs - pageSeenSensorNs >= PAGE_LOST_NS) {
      releaseScannerLock("page lost")
      return
    }
    val held = focus.snapshot
    val lens = when {
      held.owner == null -> FocusCycle.Lens.FREE
      held.locked == null -> FocusCycle.Lens.SCANNING
      else -> FocusCycle.Lens.LOCKED
    }
    // A page lock's relock aims at the confirmed page's print, never the guide. The guide's target waits for the
    // detector's answer to its spot request; an answer without a spot, as sampling failed, takes the centre.
    val pageTarget = page && hint.confirmed && printSpot != null
    val guideTarget = !page && noPageSinceNs?.let { hint.sensorNs - it >= PAGE_DROPOUT_NS } == true && guideSpot.ready
    val target = if (held.owner == FocusOwner.PAGE || page) pageTarget else guideTarget
    // A page confirmed under a settled guide lock: the lock focused the page's plane when its point lies on the page.
    val corners = hint.corners
    val aim = if (held.owner == FocusOwner.GUIDE && held.locked == true && corners != null && hint.confirmed) {
      val point = guideLockPoint
      if (point != null && insidePage(corners, point)) FocusCycle.Aim.ON_NEW_TARGET else FocusCycle.Aim.OFF_NEW_TARGET
    } else FocusCycle.Aim.TARGET
    val found = if (held.owner == FocusOwner.GUIDE && held.locked == true && !page &&
      guideSettledGeneration == held.generation && hint.sensorNs - guideSettledSensorNs >= RECOVERY_NS) false else null
    // The torch changes exposure and white balance under a scan, which then fails, so a lit lock waits for both.
    val latest = liveDetector.frameHistory.latest()?.frame
    val lit = latest?.torchFired == true && latest.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
      latest.awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
    val frame = FocusCycle.Frame(steadyHold, deviceTurned(hint.sensorNs), lens, target,
      rescaled = held.owner == FocusOwner.PAGE && page && hint.confirmed && pageSizeChanged(hint), found = found,
      motionReleases = held.owner == FocusOwner.GUIDE,
      canLight = props.torch == TorchMode.AUTO && bound?.hasFlash == true && !torchIsLit, dark = darkForAutofocus(),
      lightWanted = props.torch == TorchMode.AUTO && synchronized(metadataLock) { torchPolicy.lit }, lit = lit, aim = aim)
    follow(cycle.next(frame), hint)
  }

  /** Main thread, `focusLock`: carries out the focus cycle's [step] on the page or the guide of [hint]. */
  private fun follow(step: FocusCycle.Step, hint: LiveFocusCandidate?) {
    when (step) {
      FocusCycle.Step.None -> Unit
      is FocusCycle.Step.Lock -> hint?.let { if (it.corners != null) lockPage(it, step.why) else lockGuide(it, step.why) }
      // The cycle asks for a relock only with its target ready: a page lock's on a confirmed page.
      is FocusCycle.Step.Relock -> hint?.let {
        if (focus.snapshot.owner == FocusOwner.PAGE) relockPage(it, step.why) else relockGuide(it, step.why)
      }
      FocusCycle.Step.Keep -> hint?.let { keepGuideLockForPage(it) }
      is FocusCycle.Step.Replace -> hint?.let {
        releaseScannerLock("guide lock: ${step.why}")
        if (it.corners != null) lockPage(it, step.why)
      }
      is FocusCycle.Step.Release -> releaseScannerLock(step.why)
      is FocusCycle.Step.Light -> {
        synchronized(metadataLock) { torchPolicy.light() }
        logLifecycle("focus lock cycle lighting the torch after ${step.why}")
        // A `zsl` binding still active while its switch to `latency` waits for a press rebinds for the light instead,
        // which keeps this latched request through the restart; the next lock waits for the lit torch and a steady camera.
        if (!rebindForLight()) applyLight()
      }
      is FocusCycle.Step.GiveUp -> {
        logLifecycle("focus lock cycle gave up: ${step.why}")
        scheduleCaptureEvaluation()
      }
    }
  }

  /**
   * Main thread, `focusLock`: the settled guide lock's point lies on the page confirmed under it, so it focused the
   * page's plane: the lock becomes the page's without another scan, and its point becomes the page's print spot, which
   * flow carries. A point off the page focused the background, and the cycle replaces that lock with the page's own.
   */
  private fun keepGuideLockForPage(hint: LiveFocusCandidate) {
    val corners = hint.corners ?: return
    val point = guideLockPoint ?: return
    val spot = pageCoordinates(corners, point)
    if (!focus.adoptGuideLock()) return
    if (spot != null) printSpot = spot
    logLifecycle("guide focus lock kept for the page: its point lies on the page")
  }

  /** Main thread: forgets the guide spot and withdraws its own request from the detector, not a page's after it. */
  private fun cancelGuideSpot() {
    (guideSpot.state as? GuideSpot.State.Requested)?.let { liveDetector.cancelSpot(it.id) }
    guideSpot.cancel()
  }

  /** Main thread, `focusLock`: a cycle that started over picks its targets afresh. Called once per candidate, before
   * the print spot is tracked, so a page's arrival clears the old spots before its own is picked or asked for. */
  private fun followCycleRestart() {
    if (cycle.restarts == cycleRestarts) return
    cycleRestarts = cycle.restarts
    printSpot = null
    cancelGuideSpot()
  }

  /**
   * Main thread, `focusLock`: the page has grown or shrunk past the depth of field around the distance the page lock
   * focused at, as moving the phone nearer or farther does ([SizeWatch]); the limit comes from the lock frame's focus
   * distance and the lens ([sizeLimit]). A press does not accept the lock until it relocks.
   */
  private fun pageSizeChanged(hint: LiveFocusCandidate): Boolean {
    val corners = hint.corners ?: return false
    val snapshot = focus.snapshot
    if (snapshot.locked != true || pageRelockGeneration == snapshot.generation) return false
    val scale = pageSize.observe(snapshot.generation, corners.area()) {
      val diopters = liveDetector.frames.at(hint.sensorNs)?.focusDiopters
      depthOfField.sizeLimit(diopters).also { logLifecycle("page lock size limit $it at ${diopters ?: "unknown"} diopters") }
    } ?: return false
    pageRelockGeneration = snapshot.generation
    logLifecycle("page size changed %.3fx under the lock; relocking once steady".format(scale))
    return true
  }

  /** Main thread, `focusLock`: the page lock scans again at the page's print without a release, so the lens starts the
   * sweep where the lock left it and continuous autofocus never takes it between. */
  private fun relockPage(hint: LiveFocusCandidate, why: String) {
    val camera = boundCamera ?: return
    if (hint.corners == null) return
    val point = meteringPoint(hint) ?: scanPoint(FocusAim.CENTRE, FocusAim.CENTRE, MeteringPointFactory.getDefaultPointSize())
    focus.relock(camera, point) {
      cycle.started()
      logLifecycle("page focus relock for $why, after ${steadiness.count} steady frames")
    }
  }

  /** Main thread: a scene too dark for continuous autofocus, as the auto torch judges darkness, with no torch lit and
   * no low-light boost active brightening what autofocus sees. */
  private fun darkForAutofocus(): Boolean = synchronized(metadataLock) { darkScene.dark } && !torchIsLit &&
    boundCamera?.info?.lowLightBoostState?.value != LowLightBoostState.ACTIVE

  /** Main thread: [point], in [hint]'s upright frame fractions, as preview fractions; null when it maps outside. */
  private fun previewFraction(hint: LiveFocusCandidate, point: DocumentPoint): FloatArray? {
    val transform = previewView.outputTransform ?: return null
    val width = previewView.width.toFloat()
    val height = previewView.height.toFloat()
    if (width == 0f || height == 0f) return null
    val mapped = floatArrayOf((point.x * hint.width).toFloat(), (point.y * hint.height).toFloat())
    try { CoordinateTransform(hint.source, transform).mapPoints(mapped) } catch (_: IllegalArgumentException) { return null }
    if (!mapped.all(Float::isFinite) || mapped[0] !in 0f..width || mapped[1] !in 0f..height) return null
    return floatArrayOf(mapped[0] / width, mapped[1] / height)
  }

  /** Main thread, `focusLock`: a page lock at [hint]'s print, counted by the cycle once the camera takes it. */
  private fun lockPage(hint: LiveFocusCandidate, why: String) {
    val camera = boundCamera ?: return
    // A page too small or narrow for the smallest focus box locks at the centre, where such a page usually is.
    val point = meteringPoint(hint) ?: run {
      if (previewView.width == 0 || previewView.height == 0) return
      logLifecycle("page focus lock at the centre: no focus box fits the page")
      scanPoint(FocusAim.CENTRE, FocusAim.CENTRE, MeteringPointFactory.getDefaultPointSize())
    }
    focus.lock(camera, point, FocusOwner.PAGE) {
      cycle.started()
      logLifecycle("page focus lock for $why, after ${steadiness.count} steady frames")
    }
  }

  /** Main thread, `focusLock`: a guide lock at [guideSpot], or at the frame's centre before one is picked. */
  private fun lockGuide(hint: LiveFocusCandidate, why: String) {
    val camera = boundCamera ?: return
    val (point, at) = guidePoint(hint) ?: return
    focus.lock(camera, point, FocusOwner.GUIDE) { guideScanAccepted(at, "lock", why) }
  }

  /** Main thread, `focusLock`: the guide lock scans again without a release, at the guide's spot now. */
  private fun relockGuide(hint: LiveFocusCandidate, why: String) {
    val camera = boundCamera ?: return
    val (point, at) = guidePoint(hint) ?: return
    focus.relock(camera, point) { guideScanAccepted(at, "relock", why) }
  }

  /** Main thread, `focusLock`: the camera took a guide scan at [at]. The lock's point changes only with a scan it took,
   * so a refused relock leaves the held lock paired with the point it focused, which page takeover reads. */
  private fun guideScanAccepted(at: DocumentPoint, scan: String, why: String) {
    cycle.started()
    guideLockPoint = at
    logLifecycle("guide focus $scan at %.2f, %.2f for $why".format(at.x, at.y))
  }

  /** Main thread, `focusLock`: the guide scan's metering point at [guideSpot], or at the frame's centre before one is
   * picked, with that point in upright frame fractions; null before the preview is measured. */
  private fun guidePoint(hint: LiveFocusCandidate): Pair<MeteringPoint, DocumentPoint>? {
    if (previewView.width == 0 || previewView.height == 0) return null
    val spot = guideSpot.point
    val mapped = spot?.let { previewFraction(hint, it) }
    val at = mapped ?: floatArrayOf(FocusAim.CENTRE, FocusAim.CENTRE)
    return scanPoint(at[0], at[1], MeteringPointFactory.getDefaultPointSize()) to
      (spot?.takeIf { mapped != null } ?: DocumentPoint(.5, .5))
  }

  private fun releaseScannerLock(why: String): FocusController.Release {
    if (focus.snapshot.owner?.scanners != true) return FocusController.Release.NONE
    val release = focus.releaseScannerLock()
    logLifecycle(if (release == FocusController.Release.DEFERRED) "focus lock release for $why waits for the shot"
      else "focus lock released $why")
    return release
  }

  /** Whether the gyroscope shows the phone turned ([HandMotion.turned]); false without it or its samples. */
  private fun deviceTurned(sensorNs: Long): Boolean =
    liveDetector.cameraOptics != null && gyroscope.isRunning && hand.turned(sensorNs)

  /** `loading` or `unavailable` while the camera waits for, or lost, its model; null once it has what it needs. */
  private fun modelDocumentState(): DocumentState? = when {
    modelFailure != null -> DocumentState.UNAVAILABLE
    !modelReady -> DocumentState.LOADING
    else -> null
  }

  /** The document state right after binding or toggling the guide, before any detector result. */
  private fun boundDocumentState(): DocumentState = modelDocumentState() ?: when {
    cameraController == null || !props.guide -> DocumentState.OFF
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

  /**
   * Receives the bound session's repeating capture results on the camera thread. The controller rebuilds the session
   * config without its interop options, so the callback is set on the camera control, which keeps it across the
   * controller's own rebinds.
   */
  private fun observeFrameState(camera: BoundCamera, generation: Long) {
    metadataGeneration = generation
    camera.control.applyCamera2InteropAsync { repeatingCaptureCallback = frameStateCallback(generation) }
  }

  private fun stopFrameState(camera: BoundCamera) {
    try { camera.control.applyCamera2InteropAsync { repeatingCaptureCallback = null } } catch (_: Exception) { }
  }

  /**
   * Logs which frame each photo saved: a still request's SENSOR_TIMESTAMP, which a zero-shutter-lag reprocess shares
   * with its buffered input, against the shot's request. CameraX chooses that frame; this only reports it.
   */
  private fun observeStillFrames(builder: ImageCapture.Builder, generation: Long) {
    builder.camera2Interop { stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
        if (intent != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE &&
          intent != CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG) return
        val sensorNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val reprocess = request.isReprocess
        mainHandler.post {
          if (generation != metadataGeneration) return@post
          val shutterNs = synchronized(this@HybridDocumentScannerCameraView) {
            captureAdmission.current?.takeIf { it.shutterRealtimeNs != 0L }?.shutterRealtimeNs
          }
          logLifecycle("capture still frame reprocess=$reprocess intent=$intent " +
            "exposedVsShutter=${shutterNs?.let { (sensorNs - it) / 1_000_000 } ?: "-"}ms")
        }
      }
    } }
  }

  /** Back-camera optics when its timestamps share the gyroscope clock; otherwise motion compensation stays off. */
  private fun backCameraOptics(info: CameraInfo): LiveCameraOptics? {
    val characteristics = info.cameraCharacteristics
    val optics = if (CameraOpticsReader.sharesGyroClock(characteristics)) CameraOpticsReader.read(characteristics) else null
    logLifecycle("camera optics=$optics")
    return optics
  }

  private fun logLifecycle(message: String) {
    standaloneTrace?.let { trace ->
      if (message.startsWith("camera stop") || message.startsWith("CameraX bound") ||
        message.startsWith("continuous focus") || message.startsWith("capture mode") ||
        message.contains("focus scan") || message.startsWith("focus trigger") || message.contains("focus lock") || message.startsWith("auto torch") || message.startsWith("torch ") || message.startsWith("print spot") || message.startsWith("lens ") || message.contains("focus settled") || message.startsWith("manual lens") ||
        message.startsWith("capture ")) {
        trace.record("command", null, message)
      }
    }
    if (debugLoggingEnabled) ScannerLog.d(LOG_TAG, "view=$instanceId $message")
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

  private data class SavedCapture(val width: Int, val height: Int, val orientation: Int, val frame: SessionResources.Resource?)

  private fun describePhoto(capture: GatedCapture): SavedCapture {
    val output = capture.source.original
    val exif = ExifInterface(output.file.absolutePath)
    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(output.file.absolutePath, bounds)
    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, bounds.outWidth)
    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, bounds.outHeight)
    check(width > 0 && height > 0) { "Captured JPEG has no valid dimensions." }
    output.commit()
    val frame = capture.frame?.let { pending ->
      runCatching {
        // Without auto-crop the page keeps the whole photo, so its retained frame stays unstraightened too.
        val straighten = capture.liveCandidate?.corners?.takeIf { capture.config.autoCrop }
        LatestFrameCapture.write(pending.get(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), straighten, capture.source.frame)
      }.onFailure { logLifecycle("latest frame capture unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    }
    return SavedCapture(width, height, orientation, frame)
  }
  private fun isPending(promise: Promise<*>): Boolean = synchronized(this) {
    pendingPromises.containsKey(promise)
  }

  /** Main thread: reports [stage] once per capture, and shows it unless feedback is off. Failure follows any stage. */
  private fun advanceCapture(capture: GatedCapture, stage: CaptureStage) {
    if (disposed || stage != CaptureStage.FAILED && (!isPending(capture.promise) || !captureCurrent(capture))) return
    val current = capture.stage
    if (current == stage || current == CaptureStage.FAILED) return
    if (stage == CaptureStage.CAPTURED && current != CaptureStage.SHOOTING) return
    capture.stage = stage
    updateCaptureState()
    if (props.captureFeedback) captureFeedbackView.show(stage)
    onCaptureStage?.invoke(stage, if (stage == CaptureStage.WAITING) capture.reason?.toCaptureWaitReason() else null)
  }

  /** Main thread. Also ends a capture: a capture rejects only while its shot is not in flight. */
  private fun <T> rejectPromise(promise: Promise<T>, error: Throwable) {
    val capture = captureAdmission.current?.takeIf { it.promise === promise }
    capture?.source?.invalidate()
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
   * capture to end releases the camera preview and applies a deferred rebind.
   */
  private fun endCapture(promise: Promise<*>) {
    val ended = captureAdmission.current?.takeIf { it.promise === promise } ?: return
    if (ended.physicalInFlight || !captureAdmission.finish(ended)) return
    liveDetector.captureInferenceFrame = null
    ended.source.close()
    // A rejected capture leaves no scheduled deadline callback.
    mainHandler.removeCallbacksAndMessages(ended.deadlineToken)
    mainHandler.removeCallbacksAndMessages(ended.pressToken)
    mainHandler.post {
      // Focus held still for the shots; a capture still waiting does not hold it.
      if (!hasShotInFlight()) focus.afterCapture()
      if (scannerLensDeferred && !hasShotInFlight()) syncScannerLens()
      if (!hasPendingCapture() && modelLoadPending) loadRequestedModel()
      if (!hasPendingCapture()) {
        clearCapturePreview()
        if (exposureDeferred) applyExposureCompensation()
        applyLight()
      }
      if (!disposed && !props.active && !hasPendingCapture()) {
        stopCamera("inactive-after-capture")
      } else {
        updateCameraState()
        applyPendingRebind()
      }
    }
  }

  private fun hasPendingCapture(): Boolean = captureAdmission.current != null

  /** A capture has requested its shot, whose focus and exposure must not change until it settles. */
  private fun hasShotInFlight(): Boolean = captureAdmission.current?.physicalInFlight == true

  /** The still-capture mode to bind for [props], and the light fallback that chose `latency` for `zsl`, if one did;
   * see [CameraLightController.zslFallback]. */
  private fun captureModeFor(props: ResolvedProps, lowLightBoostSupported: Boolean, hasFlash: Boolean,
    atBind: Boolean = false): Pair<CaptureMode, LightFallback?> {
    val (autoLit, dark, unsettled) = synchronized(metadataLock) {
      Triple(torchPolicy.lit, darkScene.dark, whiteBalance.unsettled)
    }
    val fallback = light.zslFallback(props.captureMode, props.torch, autoLit, hasFlash, props.lowLightBoost,
      lowLightBoostSupported, dark, unsettled, atBind, props.darkLatency)
    return (if (fallback != null) CaptureMode.LATENCY else props.captureMode) to fallback
  }

  /** Main thread: rebinds when the room's light, a light prop or a refused boost changes the mode [captureModeFor]
   * picks, after any capture in flight settles. */
  private fun rebindForLight(): Boolean {
    val binding = bound ?: return false
    val (next, fallback) = captureModeFor(props, binding)
    if (next == binding.captureMode) return false
    logLifecycle("capture mode ${binding.captureMode} -> $next for ${fallback ?: "the light"}")
    requestRebind(RebindReason.LIGHT)
    return true
  }

  private fun captureModeFor(props: ResolvedProps, binding: BoundCapture) =
    captureModeFor(props, binding.lowLightBoostSupported, binding.hasFlash)

  /** Main thread: rebinds now, or once the last capture settles; binding reads the current props. */
  private fun requestRebind(reason: RebindReason) {
    // A light rebind's readings stay valid whatever else rebinds with it.
    pendingRebind = if (reason == RebindReason.LIGHT || pendingRebind == RebindReason.LIGHT) RebindReason.LIGHT else reason
    applyPendingRebind()
  }

  /** Main thread: rebinds for a change deferred by a capture once the last capture settles. */
  private fun applyPendingRebind() {
    val reason = pendingRebind
    if (reason == null || disposed || hasPendingCapture()) return
    stopCamera("deferred-rebind reason=$reason", keepLightDecision = reason == RebindReason.LIGHT)
    startCamera()
  }

  private fun dp(value: Int): Int = (value * themedContext.resources.displayMetrics.density).toInt()

  /** The capture configuration the bound use cases were built with; [zsl] means zero shutter lag is both asked for and
   * supported, so CameraX takes the photo from its buffer. [lightFallback]: why this binding is `latency` for a `zsl`
   * request, or null when it binds what was requested.
   */
  private data class BoundCapture(val captureMode: CaptureMode, val zsl: Boolean,
    val lowLightBoostSupported: Boolean, val hasFlash: Boolean, val lightFallback: LightFallback?)

  /** Why a stop rebinds. [LIGHT] comes from sustained readings that outlive the stop, so the auto torch keeps its
   * decision and relights at once instead of after another dark second; [CONFIGURATION] re-evaluates the room. */
  private enum class RebindReason { CONFIGURATION, LIGHT }

  /** One takePhoto's progress through its gate; main thread. */
  private class GatedCapture(val promise: Promise<CameraCaptureResult>, val requestedMs: Long, val skipLowQualityControl: Boolean,
    val source: SessionCapture, val config: ResolvedProps, val generation: Long) {
    var physicalInFlight = false
    var registered = false
    /** Owns the capture's deadlines and own focus scans, and decides each evaluation. */
    val gate = CaptureGate(STEADY_TIMEOUT_MS, skipLowQualityControl, MAX_CAPTURE_SCANS, FRESH_FOCUS_FRAME_NS / 1_000_000)
    /** What the capture is waiting for, shown over the guide while it pulses; null once it shoots. */
    var reason: WaitReason? = null
    /** The next frame results to consume, and the newest consumed frame exposed since the last focus change. */
    var frameCursor = 0L
    var latest: FrameRecord? = null
    var inferenceFinished = false
    /** The focus snapshot the consumed frames were evaluated against; a newer generation restarts the sequence. */
    var focus = FocusSnapshot(0, null, null, 0, 0, 0)
    /** Framing stability over consecutive frames since the press and the latest focus change. */
    val stability = CaptureStability()
    /** Identifies this capture's pending deadline on the main handler. */
    val deadlineToken = Any()
    /** The scanner focus cycle's budget for this press while it owns focus, and its wakeups on the main handler. */
    var press: ScannerPress? = null
    val pressToken = Any()
    /** elapsedRealtimeNanos() when the shot was requested, for the still frame's timestamp in the lifecycle log. */
    var shutterRealtimeNs = 0L
    /** The last stage reported; null before the capture waits. */
    var stage: CaptureStage? = null
    var delivered = false
    /** The guide at the shutter, to seed authoritative photo analysis. */
    var preview: android.graphics.Bitmap? = null
    var liveCandidate: DocumentCandidate? = null
    /** The first analysis frame after the shutter, for the session placeholder. */
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
    const val STEADY_TIMEOUT_MS = 1_000L
    /** How long a capture waits for its page and for focus; autofocus still moving extends the focus wait. */
    const val FOCUS_TIMEOUT_MS = 4_000L
    /** A capture's first focus scan and its one retry. */
    const val MAX_CAPTURE_SCANS = 2
    /** How recent a frame must be for its scanning autofocus state to extend a focus wait; also how long a settled
     * capture scan waits for a frame exposed after it. */
    const val FRESH_FOCUS_FRAME_NS = 500_000_000L
    /** A page unseen this long arrives anew, so a focus reached before it no longer counts. */
    const val PAGE_LOST_NS = 1_000_000_000L
    /** A print spot this close to the frame's edge, as a fraction of it, has left the frame for focus. */
    const val SPOT_FRAME_MARGIN = .05
    /** A guide lock found nothing when no page appears this long after it settled. */
    const val RECOVERY_NS = 1_000_000_000L
    /** `focusLock`'s first guide lock waits for analysis to have found no page for this long: past a dropped frame. */
    const val PAGE_DROPOUT_NS = 200_000_000L
    /** The side of a tap's exposure area, as a fraction of the preview: CameraX's own tap-to-meter size. */
    const val TAP_METERING_AREA = FocusRules.TAP_AREA * 1.5f



  }
}

private data class ResolvedProps(
  val active: Boolean,
  /** `guideLiveCorners`: live inference and the guide. */
  val guide: Boolean,
  val inferenceThrottling: Boolean,
  val minCornerScore: Double,
  val lowQualityControl: Boolean,
  val maxCaptureBlur: Double,
  /** A capture waits for autofocus to finish and rejects when it did not focus; off, focus is not waited for. */
  val requireFocus: Boolean,
  /** `guideLiveCorners`: autofocus aims at the guide area, and a capture waits for a page and counts only focus reached
   * after it arrived. */
  val documentFocus: Boolean,
  /** New photos crop to the page their analysis finds. */
  val autoCrop: Boolean,
  /** `imageCorrection` and `outputOptions` for new pages. */
  val renderOptions: DocumentRenderOptions,
  val tapToFocus: Boolean,
  val focusLock: Boolean,
  val exposureCompensation: Double,
  val minFrameRate: Int,
  val torch: TorchMode,
  val lowLightBoost: Boolean,
  val guideTransitionNs: Long,
  val analysisFrameWidth: Int,
  val squareAspectRatio: Boolean,
  val captureMode: CaptureMode,
  /** The experimental `darkLatency` prop: a dark scene binds `latency` for `zsl`. */
  val darkLatency: Boolean,
  /** The photo takes the size for text, from the camera's high-resolution sizes too; otherwise the standard
   * 1440x1080-class photo. */
  val highResolutionPhoto: Boolean,
  val showStats: Boolean,
  val showFocusIndicator: Boolean,
  val guideColor: Int,
  val captureFeedback: Boolean,
  val captureColor: Int,
)

