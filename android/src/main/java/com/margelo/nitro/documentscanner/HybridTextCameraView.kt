package com.margelo.nitro.documentscanner

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.camera.camera2.interop.applyCamera2InteropAsync
import androidx.camera.camera2.interop.cameraCharacteristics
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.processing.CameraOpticsReader
import com.margelo.nitro.documentscanner.processing.FlowMotionEstimator
import com.margelo.nitro.documentscanner.processing.FlowMotionStatus
import com.margelo.nitro.documentscanner.processing.GyroDocumentMotion
import com.margelo.nitro.documentscanner.processing.GyroHistory
import com.margelo.nitro.documentscanner.processing.LiveCameraOptics
import com.margelo.nitro.documentscanner.processing.LiveFrameGeometry
import com.margelo.nitro.documentscanner.processing.GyroscopeSource
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tan
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.margelo.nitro.documentscanner.CameraState as ScannerCameraState

private const val LOG_TAG = "RNDocumentScannerText"

/**
 * Back-camera preview that reads one line inside [textRegion] with bundled ML Kit Latin text recognition. The camera
 * runs while `active`, attached, visible, measured and permitted; CameraX follows the Activity's start and stop. Each
 * analysis frame is cropped to the region in its buffer, recognized on the analysis thread, filtered and voted on
 * there, and the agreed reading is published on main. Props, callbacks, the reading and pending reads live on main.
 */
@DoNotStrip
class HybridTextCameraView(private val themedContext: ThemedReactContext) : HybridTextCameraViewSpec() {
  /** Debug builds log; any build does once the app turns diagnostics on before the view is created. */
  private val debugLogging = themedContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 || ScannerDiagnostics.enabled
  private val mainHandler = Handler(Looper.getMainLooper())
  private val container = object : FrameLayout(themedContext) {
    init { setBackgroundColor(Color.BLACK) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); onAttached() }
    override fun onDetachedFromWindow() {
      stopCamera("detached")
      removePermissionListener()
      previewView.previewStreamState.removeObserver(streamObserver)
      super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
      super.onWindowVisibilityChanged(visibility)
      if (visibility == View.VISIBLE) startCamera() else stopCamera("hidden")
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
      super.onSizeChanged(width, height, oldWidth, oldHeight)
      layoutChildren()
      if (oldWidth > 0 && oldHeight > 0) stopCamera("resized")
      startCamera()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = layoutChildren()
    // React Native never runs a layout pass for native children, so PreviewView adding its surface view would stay
    // unmeasured at 0x0 and never receive frames; lay them out on the next frame instead.
    override fun requestLayout() {
      super.requestLayout()
      if (!layoutPosted) {
        layoutPosted = true
        post { layoutPosted = false; layoutChildren() }
      }
    }
  }
  private var layoutPosted = false
  private val previewView = PreviewView(themedContext).apply {
    // The bound viewport is the view's, so the analysis crop rect is exactly what the view shows.
    scaleType = PreviewView.ScaleType.FILL_CENTER
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    // Each binding gives PreviewView a new surface view. Its layout request stops at the container, whose own
    // request React Native never clears, so only the first one would arrive; lay the new view out directly.
    setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
      override fun onChildViewAdded(parent: View?, child: View?) { post { layoutChildren() } }
      override fun onChildViewRemoved(parent: View?, child: View?) = Unit
    })
    setOnTouchListener { _, event ->
      if (event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_UP &&
        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) onTap(event.x, event.y)
      false
    }
  }
  private val overlay = TextRegionOverlay(themedContext)
  override val view: View = container

  init {
    container.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    container.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
  }

  override var active: Boolean? = null
  override var textRegion = TextRegion(0.1, 0.44, 0.8, 0.12)
  override var textGuide: TextGuide? = null
  override var textHighlight: Boolean? = null
  override var guideColor: Double? = null
  override var allowedCharacters: String? = null
  override var textPattern: String? = null
  override var minLength: Double? = null
  override var tapToFocus: Boolean? = null
  override var torch: TextTorchMode? = null
  override var recognitionRate: Double? = null
  override var analysisFrameWidth: Double? = null
  override var focusLock: Boolean? = null
  override var zoom: Double? = null
  override var onCameraStateChange: ((state: ScannerCameraState) -> Unit)? = null
    set(value) { field = value; emittedState = null; updateCameraState() }
  override var onAutofocusChange: ((state: AutofocusState) -> Unit)? = null
    set(value) { field = value; emittedAutofocus = null; updateAutofocus() }
  override var onTextChange: ((reading: TextReading) -> Unit)? = null
    set(value) { field = value; emittedReading = null; emitReading() }
  override var onError: ((error: ScannerFailure) -> Unit)? = null

  // Main thread.
  private var disposed = false
  private var controller: LifecycleCameraController? = null
  private var cameraControl: CameraControl? = null
  private var analysis: ImageAnalysis? = null
  private var startInFlight = false
  private var generation = 0L
  private var boundWidth = 0
  private var removePermission: (() -> Unit)? = null
  private var emittedState: ScannerCameraState? = null
  private var emittedAutofocus: AutofocusState? = null
  private var reading: TextReading? = null
  private var emittedReading: TextReading? = null
  private var appliedFilterKey: Triple<String, String, Int>? = null
  private val pendingReads = mutableListOf<PendingRead>()
  /** Main thread, `focusLock`: when the camera's own locking scan runs, or null while continuous autofocus alone
   * follows the text. */
  private var textCycle: FocusCycle? = null
  /** Main thread: the bound camera, as [focus] holds it. */
  private var boundCamera: BoundCamera? = null
  /** Main thread: whether the bound camera offers single-shot autofocus, which locks are confirmed in. */
  private var hasSingleShotAutofocus = false
  /** Main thread: who holds the lens: the text lock, a tap, or nobody while continuous autofocus runs. */
  private val focus = FocusController(object : FocusController.Host {
    override val camera: BoundCamera? get() = boundCamera
    override val mainExecutor get() = ContextCompat.getMainExecutor(themedContext)
    override fun shotInFlight() = false
    override fun resumeContinuousFocus() = aimFocus()
    override fun postDelayed(delayMs: Long, block: () -> Unit) { mainHandler.postDelayed(block, delayMs) }
    override fun log(message: String) = this@HybridTextCameraView.log("text $message")
    override fun focusChanged(snapshot: FocusSnapshot) = updateAutofocus()
    override fun scannerLockFailed(owner: FocusOwner) { textCycle?.let { follow(it.failed()) } }
    override val confirmsLockFrames get() = hasSingleShotAutofocus
  })
  private val zoomRequests = TextZoom()
  /** Logging only: when the pending zoom was requested, on the sensor clock. */
  private var zoomRequestedNs = 0L
  /** Whether the previous focus frame saw a turn, so each turn wakes recognition and is logged once. */
  private var wasTurned = false
  /** Main: bumped to return recognition to its full rate, as after a turn or a lens search. */
  private var recognitionWake = 0L
  private var wasSearching = false
  /** How the bound camera's capture results show its zoom; read on the first zoom request of a binding. */
  @Volatile private var zoomMetadata: ZoomMetadata? = null
  /** Counts [textCycle] runs, so a frame posted for an earlier one, in this binding or another, drops. */
  private var focusSessions = 0L
  /** Set when the motion sensors gave no samples for a running [textCycle]; continuous autofocus until the next start. */
  private var focusSensorsFailed = false
  /** The sensor time of the first frame since [textCycle] started without motion samples, or 0. */
  private var sensorlessSinceNs = 0L
  private val gyroscope = GyroscopeSource(themedContext, GyroHistory())
  private val hand = HandMotion(gyroscope.history, gyroscope.acceleration)
  /** The newest capture result's sensor time; written on a camera thread. */
  @Volatile private var latestCaptureNs = 0L
  /** The newest capture result's zoom ratio, and whether any result of this binding showed one; camera thread. */
  @Volatile private var latestZoomRatio: Float? = null
  /** Camera thread: the newest frame's `LENS_FOCUS_DISTANCE` in diopters; null when unreported. */
  @Volatile private var latestFocusDiopters: Float? = null
  /** Main thread: the bound camera's depth of field; null without the lens and sensor sizes or calibrated focus. */
  @Volatile private var depthOfField: DepthOfField? = null
  @Volatile private var zoomMeasured = false
  /** The zoom request whose first capture result at its ratio the camera thread watches for, then clears. */
  private val zoomWatch = AtomicReference<TextZoom.Request?>(null)
  /** The newest capture result's autofocus state, for the camera's own autofocus; written on a camera thread. */
  @Volatile private var cameraAfState: Int? = null
  private val streamObserver = Observer<PreviewView.StreamState> { updateCameraState() }
  /** Set when the bound camera closed or failed to open; reads stay unavailable until the first fresh frame. */
  private var interrupted = false
  /** The bound camera's state, observed for the current start only. */
  private var observedCameraState: LiveData<CameraState>? = null
  private var cameraStateObserver: Observer<CameraState>? = null

  private class PendingRead(val promise: Promise<Variant_ScannerFailure_TextReading>, val timeout: Runnable)

  // Analysis thread, except the volatile hand-offs from main.
  private val executor = Executors.newSingleThreadExecutor()
  /** Created on first use on the analysis thread; a recognizer that fails to start reports once and reads nothing. */
  private var recognizer: TextRecognizer? = null
  private var recognizerFailed = false
  private val votes = TextVotes()
  /** Analysis thread, `focusLock`: the managed OCR state: the text map the aim reads from, and what it publishes. */
  private val managed = ManagedTextReading()
  /** Main thread: the lock whose first reading ended last, and whether it found text; null before one ends. */
  private var lockReadResult: Pair<Long, Boolean>? = null
  /** Main thread: what the text map may read, which [snapshot] hands the analyzer. */
  private var permission = ReadPermission()
  /** Written by main only, as a whole; the analyzer takes one per frame and reads and publishes by it. */
  // Analysis thread, logging only: what one report's frames cost and how many reached the analyzer.
  private var statFrames = 0
  private var statFirstNs = 0L
  private var statLastNs = 0L
  private var statRecognitions = 0
  private var statRecognitionNs = 0L
  private var statSettled = 0
  /** Logging only: capture results counted on a camera thread, and the count when the analysis report began, so the
   * report shows how many camera frames never reached the analyzer, as while a recognition holds it. */
  private val capturedFrames = java.util.concurrent.atomic.AtomicLong()
  private var statCapturedFrom = 0L
  /** Analysis thread, logging only: the longest gap between analyzed frames in the report. */
  private var statLongestGapNs = 0L
  /** Analysis thread: slows recognition once the reading has settled. */
  private val pace = RecognitionPace()
  @Volatile private var snapshot = AnalysisSnapshot(clampedRegion(textRegion), TextLineFilter("", "", 1), 0L, Long.MIN_VALUE, 0L, 0L)
  /** Main sets it from the camera state; frames still queued while the camera is not open do not count. */
  @Volatile private var cameraOpen = false
  /** Bumped by each unavailable transition, so a first-frame report queued before one cannot restore readiness. */
  @Volatile private var availability = 0L
  /** Cleared by each unavailable transition and on reopening; the next counted frame reports itself to main. */
  private val frameSeen = AtomicBoolean(false)
  private var countedRevision = -1L
  private var lastFrameMs = 0L
  /** Main thread: the text under the lock changed size; the next focus frame tells the cycle. */
  private var rescaled = false
  @Volatile private var frameIntervalMs = 1000L / DEFAULT_RATE
  private var cropBytes: ByteArray? = null
  private var cropBuffer: ByteBuffer? = null
  private val uprightMatrix = Matrix()
  /** Optical flow over every frame, so the outline follows the text between recognitions; null once it fails. */
  private var flow: FlowMotionEstimator? = null
  private var flowFailed = false
  /** The bound camera's optics when its timestamps share the gyroscope clock, so the gyroscope can move the outline. */
  @Volatile private var cameraOptics: LiveCameraOptics? = null
  /** Analysis thread, logging only: how the report's frames moved the outline. */
  private val statMotion = java.util.TreeMap<String, Int>()
  /** Analysis thread, paced OCR only: the outline of the agreed text, if a frame has boxed it. */
  private var outline: TrackedOutline? = null
  /** Analysis thread: the view area flow crops around the outline's text; it changes only when the text nears its edge. */
  private var textArea: TextRegion? = null

  /** Main thread: fills the container with the preview and overlay; React Native's layout never measures them. */
  private fun layoutChildren() {
    val width = container.width
    val height = container.height
    if (width <= 0 || height <= 0) return
    for (child in listOf(previewView, overlay)) {
      child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
      child.layout(0, 0, width, height)
    }
  }

  override fun afterUpdate() {
    super.afterUpdate()
    val region = clampedRegion(textRegion)
    if (region != snapshot.region) {
      snapshot = snapshot.copy(region = region, revision = snapshot.revision + 1, regionRevision = snapshot.regionRevision + 1)
      clearReading()
      // Other text, maybe at another distance: continuous autofocus follows it again.
      val release = textCycle?.let {
        it.reset()
        focus.releaseScannerLock()
      } ?: FocusController.Release.NONE
      // A released lock's cancellation re-aims continuous autofocus at the new region once it completes.
      if (release == FocusController.Release.NONE) aimFocus()
      updateAutofocus()
    }
    val filterKey = Triple(allowedCharacters.orEmpty(), textPattern.orEmpty(), (minLength ?: 1.0).toInt())
    if (filterKey != appliedFilterKey) {
      appliedFilterKey = filterKey
      val filter = TextLineFilter(filterKey.first, filterKey.second, filterKey.third)
      // The same text read another way: the votes restart, the lock stays.
      snapshot = snapshot.copy(filter = filter, revision = snapshot.revision + 1)
      clearReading()
      if (filter.patternInvalid) onError?.invoke(ScannerFailure(ScannerErrorCode.INPUT_INVALID))
    }
    frameIntervalMs = (1000 / (recognitionRate ?: DEFAULT_RATE.toDouble()).coerceIn(1.0, 15.0)).toLong()
    overlay.update(region, textGuide ?: TextGuide.NONE, textHighlight == true, guideColor?.toLong()?.toInt() ?: DEFAULT_COLOR)
    val width = requestedWidth()
    if (controller != null && width != boundWidth) stopCamera("analysis width")
    if (active == false) stopCamera("inactive") else startCamera()
    applyTorch()
    applyZoom()
    syncTextFocus()
  }

  override fun readTextResult(timeoutMs: Double?): Promise<Variant_ScannerFailure_TextReading> {
    val promise = Promise<Variant_ScannerFailure_TextReading>()
    mainHandler.post {
      val current = reading
      when {
        controller == null || disposed || interrupted -> promise.resolve(Variant_ScannerFailure_TextReading.create(ScannerFailure(ScannerErrorCode.CAMERA_UNAVAILABLE)))
        current != null -> promise.resolve(Variant_ScannerFailure_TextReading.create(current))
        else -> {
          lateinit var pending: PendingRead
          val timeout = Runnable {
            if (pendingReads.remove(pending)) promise.resolve(Variant_ScannerFailure_TextReading.create(emptyReading()))
          }
          pending = PendingRead(promise, timeout)
          pendingReads.add(pending)
          mainHandler.postDelayed(timeout, (timeoutMs ?: 1500.0).toLong().coerceIn(0, 30_000))
        }
      }
    }
    return promise
  }

  override fun onDropView() {
    disposed = true
    stopCamera("dropped")
    focus.close()
    removePermissionListener()
    gyroscope.stop()
    executor.execute { recognizer?.close(); flow?.close(); flow = null }
    executor.shutdown()
  }

  private fun onAttached() {
    previewView.previewStreamState.observeForever(streamObserver)
    if (removePermission == null) removePermission = CameraPermissions.addListener { status ->
      if (status == CameraPermissionStatus.GRANTED) startCamera() else stopCamera("permission")
    }
    startCamera()
  }

  private fun removePermissionListener() {
    removePermission?.invoke()
    removePermission = null
  }

  private fun requestedWidth() = analysisFrameWidth?.toInt()?.coerceIn(320, 3840) ?: DEFAULT_ANALYSIS_WIDTH

  private fun startCamera() {
    if (disposed || active == false || controller != null || startInFlight) return
    if (!container.isAttachedToWindow || container.windowVisibility != View.VISIBLE || container.width <= 0 || container.height <= 0) return
    if (CameraPermissions.status() != CameraPermissionStatus.GRANTED) return
    val owner = themedContext.currentActivity as? LifecycleOwner
    if (owner == null || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
      onError?.invoke(ScannerFailure(ScannerErrorCode.CAMERA_UNAVAILABLE))
      return
    }
    val bindGeneration = ++generation
    startInFlight = true
    updateCameraState()
    val next = try { LifecycleCameraController(themedContext) } catch (error: Throwable) {
      startInFlight = false
      failStart(error)
      return
    }
    next.initializationFuture.addListener({
      if (bindGeneration != generation || disposed) return@addListener
      startInFlight = false
      try {
        next.initializationFuture.get()
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val width = requestedWidth()
        // 4:3 like the analysis frames, so both streams see the same field of view.
        val preview = Preview.Builder()
          .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
          .setTargetRotation(rotation)
          .build()
        val useCase = ImageAnalysis.Builder()
          .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(width, width * 3 / 4), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
          ).build())
          .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setTargetRotation(rotation)
          .build()
        snapshot = snapshot.copy(revision = snapshot.revision + 1)
        useCase.setAnalyzer(executor) { image -> analyze(image, bindGeneration) }
        next.isAutoRotationEnabled = false
        next.isPinchToZoomEnabled = false
        next.isTapToFocusEnabled = false
        // The preview's viewport crops every use case to what the view shows, so the region maps through cropRect.
        val viewPort = previewView.viewPort
        if (viewPort == null) log("no viewport; analysis crop is the full frame")
        next.setSessionConfig(SessionConfig(listOf(preview, useCase), viewPort), CameraSelector.DEFAULT_BACK_CAMERA)
        previewView.controller = next
        next.bindToLifecycle(owner)
        controller = next
        analysis = useCase
        cameraControl = next.cameraControl
        val info = next.cameraInfo
        boundCamera = next.cameraControl?.let { control -> info?.let { BoundCamera(control, it) } }
        cameraOptics = try {
          info?.cameraCharacteristics?.let { if (CameraOpticsReader.sharesGyroClock(it)) CameraOpticsReader.read(it) else null }
        } catch (_: Exception) { null }
        log("camera optics=$cameraOptics")
        depthOfField = try { info?.cameraCharacteristics?.let { DepthOfField.read(it) } } catch (_: Exception) { null }
        log("depth of field $depthOfField")
        hasSingleShotAutofocus = try {
          info?.cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) == true
        } catch (_: Exception) { false }
        observeCameraState(next, bindGeneration)
        boundWidth = width
        layoutChildren()
        log("bound analysis=${useCase.resolutionInfo?.resolution} rotation=$rotation")
        try {
          cameraControl?.applyCamera2InteropAsync { repeatingCaptureCallback = frameStateCallback(bindGeneration) }
        } catch (error: Exception) {
          log("camera option failed ${error.javaClass.simpleName}")
        }
        aimFocus()
        applyTorch()
        applyZoom()
        syncTextFocus()
      } catch (error: Throwable) {
        previewView.controller = null
        failStart(error)
      }
      updateCameraState()
    }, ContextCompat.getMainExecutor(themedContext))
  }

  private fun failStart(error: Throwable) {
    ScannerLog.e(LOG_TAG, "Text camera binding failed", error)
    updateCameraState()
    onError?.invoke(ScannerFailure(ScannerErrorCode.CAMERA_UNAVAILABLE))
  }

  /** Main: watches the camera bound for [bindGeneration]; a state queued for an earlier start finds a newer generation. */
  private fun observeCameraState(bound: LifecycleCameraController, bindGeneration: Long) {
    val data = bound.cameraInfo?.cameraState ?: run {
      // Without a camera state to follow, frames count as they arrive.
      cameraOpen = true
      return
    }
    val observer = Observer<CameraState> { state -> onCameraState(state, bindGeneration) }
    observedCameraState = data
    cameraStateObserver = observer
    data.observeForever(observer)
  }

  private fun stopObservingCameraState() {
    val observer = cameraStateObserver
    if (observer != null) observedCameraState?.removeObserver(observer)
    observedCameraState = null
    cameraStateObserver = null
  }

  /**
   * Main: a critical camera error stops the camera and reports it. Closing, such as another app taking the camera or
   * the activity stopping, and a recoverable error while opening make reads unavailable; CameraX reopens the camera
   * itself, and the first fresh frame after that restores readiness.
   */
  private fun onCameraState(state: CameraState, bindGeneration: Long) {
    if (bindGeneration != generation || controller == null) return
    if (state.error?.type == CameraState.ErrorType.CRITICAL) {
      ScannerLog.e(LOG_TAG, "Text camera failed: ${state.error?.code}")
      stopCamera("camera error")
      onError?.invoke(ScannerFailure(ScannerErrorCode.CAMERA_UNAVAILABLE))
      return
    }
    val open = state.type == CameraState.Type.OPEN
    val wasOpen = cameraOpen
    cameraOpen = open
    when {
      open -> if (!wasOpen) frameSeen.set(false)
      // Opening, without an error, is still startup, which reads wait out; a repeat while unavailable is a duplicate.
      wasOpen || (state.error != null && !interrupted) -> {
        interrupted = true
        makeUnavailable()
      }
    }
  }

  /** Main: a frame of this start, counted after the last unavailable transition, arrived. */
  private fun onFreshFrame(bindGeneration: Long, token: Long) {
    if (bindGeneration != generation || token != availability || !cameraOpen) return
    interrupted = false
    updateCameraState()
  }

  /**
   * Main: the one transition that makes reads unavailable, for a stop, a camera error or the camera closing. Frames
   * already queued no longer count, the reading clears, and pending reads fail with `camera/unavailable`.
   */
  private fun makeUnavailable() {
    availability++
    snapshot = snapshot.copy(revision = snapshot.revision + 1)
    frameSeen.set(false)
    clearReading()
    for (pending in pendingReads.toList()) {
      mainHandler.removeCallbacks(pending.timeout)
      pending.promise.resolve(Variant_ScannerFailure_TextReading.create(ScannerFailure(ScannerErrorCode.CAMERA_UNAVAILABLE)))
    }
    pendingReads.clear()
    updateCameraState()
  }

  private fun stopCamera(reason: String) {
    generation++
    startInFlight = false
    stopObservingCameraState()
    cameraOpen = false
    interrupted = false
    stopTextFocus(bound = false)
    focus.unbind()
    boundCamera = null
    zoomRequests.reset()
    zoomWatch.set(null)
    zoomMetadata = null
    zoomMeasured = false
    latestZoomRatio = null
    cameraOptics = null
    focusSensorsFailed = false
    cameraAfState = null
    analysis?.clearAnalyzer()
    analysis = null
    cameraControl = null
    if (controller != null) {
      log("stop reason=$reason")
      try { previewView.controller = null } catch (_: Throwable) { }
    }
    controller = null
    makeUnavailable()
    updateAutofocus()
  }

  /** Analysis thread: crops the region, recognizes it and votes. Frames arriving faster than the recognizer are dropped. */
  private fun analyze(image: ImageProxy, bindGeneration: Long) {
    try {
      // A frame delivered while the camera is not open belongs to a closed stream.
      if (!cameraOpen) return
      if (!frameSeen.getAndSet(true)) {
        val token = availability
        mainHandler.post { onFreshFrame(bindGeneration, token) }
      }
      val frame = snapshot
      val revision = frame.revision
      if (revision != countedRevision) {
        votes.clear()
        outline = null
        flow?.reset()
        pace.reset()
        managed.reset()
        textArea = null
        countedRevision = revision
        log("frames ${image.width}x${image.height} crop=${image.cropRect} rotation=${image.imageInfo.rotationDegrees}")
      }
      val sensorNs = image.imageInfo.timestamp
      // A frame exposed before the zoom requested applied shows the view before it, for focus and reading alike.
      val current = sensorNs >= frame.readFromNs
      if (current && frame.focusSession != 0L) {
        mainHandler.post { onFocusFrame(frame.focusSession, frame.regionRevision, sensorNs) }
      }
      if (debugLogging) reportAnalysis(sensorNs)
      val readLock = frame.readLock
      if (readLock != null) {
        analyzeMapped(image, frame, readLock, current, sensorNs, bindGeneration)
        return
      }
      val moved = followText(image, sensorNs, frame.region)
      // A highlight flow lost has no known position: it goes at once, and recognition returns to its full rate to
      // place it again.
      if (moved && outline == null) {
        pace.lost()
        postOutline(bindGeneration, revision)
      }
      pace.wake(frame.wake)
      outline?.let { if (moved) pace.moved(it.corners.boundingRegion()) }
      val now = SystemClock.uptimeMillis()
      // Votes age out on each analyzed frame as well as on new recognitions, so a reading, and its highlight, ends about
      // the votes' age after the last agreeing recognition when recognitions slow down or fail; later only while no
      // frame reaches the analyzer, as during a running recognition.
      if (votes.expire(now)) {
        val winner = votes.winner()
        outline = outlineAfter(outline, null, winner?.text) { it.text }
        val region = frame.region
        val shown = outline?.corners
        mainHandler.post {
          if (bindGeneration == generation && revision == snapshot.revision && controller != null) publish(winner, region, shown)
        }
      }
      if (now - lastFrameMs < pace.intervalMs(frameIntervalMs)) {
        if (moved && outline != null) postOutline(bindGeneration, revision)
        return
      }
      lastFrameMs = now
      if (pace.settled) statSettled++
      if (!current) return
      val region = frame.region
      val recognized = recognize(image, region) ?: return
      val accepted = frame.filter.select(recognized.lines, recognized.width.toFloat(), recognized.height.toFloat())
      if (revision != snapshot.revision) return
      val read = accepted?.let {
        val corners = textBoundsInView(it, region, recognized.width.toFloat(), recognized.height.toFloat()).corners()
        TrackedOutline(it.text, corners, sensorNs, onText(corners, region.x + region.width / 2, region.y + region.height / 2))
      }
      val winner = votes.add(accepted?.text, accepted?.confidence ?: 0f, now)
      outline = outlineAfter(outline, read, winner?.text) { it.text }
      pace.recognized(accepted?.text, winner?.text, outline?.corners?.boundingRegion())
      val shown = outline?.corners
      mainHandler.post {
        // Main's own check: a result still recognizing when the votes restarted cannot restore a cleared reading.
        if (bindGeneration == generation && revision == snapshot.revision && controller != null) publish(winner, region, shown)
      }
    } catch (error: Throwable) {
      log("recognition failed ${error.javaClass.simpleName}")
    } finally {
      image.close()
    }
  }

  /**
   * Analysis thread, `focusLock`: the region's centre selects from the text map, the text read around it, and the
   * region is the tile each recognition reads. The map moves with the page, so the aim moving onto a line already read
   * shows it at once. The first lock permits reading, and after it every frame but a lock's scan, which sweeps the lens;
   * a new lock reads afresh. The region is read when [TextMap.needsRead] says so, at most every [MOVING_READ_MS] while
   * the phone moves and at `recognitionRate` while it holds still, and a lock's first reads run until they show whether
   * its focus found text. Only frames exposed after the lock settled read under it, and a recognition the permission
   * changed during, as a scan starting, is dropped; so is any publication queued before such a change.
   */
  private fun analyzeMapped(image: ImageProxy, frame: AnalysisSnapshot, readLock: Long, current: Boolean, sensorNs: Long,
    bindGeneration: Long) {
    val region = frame.region
    val cx = region.x + region.width / 2
    val cy = region.y + region.height / 2
    managed.underLock(readLock)
    followMap(image, sensorNs, region, frame.steady)
    val keep = clampedRegion(TextRegion(0.0, region.y - region.height * MAP_REACH, 1.0, region.height * (1 + 2 * MAP_REACH)))
    managed.map.prune(keep, sensorNs - TextMap.LINE_LIFETIME_NS)
    val selection = showAimed(cx, cy, region, frame, afterRead = false, bindGeneration)
    checkDistance(frame, selection.line)
    if (readLock == 0L || !current || sensorNs < frame.readLockFromNs) return
    if (managed.lockReads.reported && !managed.map.needsRead(region, selection.aimed, sensorNs)) return
    val now = SystemClock.uptimeMillis()
    // `recognitionRate` is the most recognitions per second, moving or not.
    val interval = pace.intervalMs(frameIntervalMs)
    if (now - lastFrameMs < if (frame.steady) interval else maxOf(interval, MOVING_READ_MS)) return
    lastFrameMs = now
    val recognized = recognize(image, region) ?: return
    // A permission change while ML Kit ran, as a scan starting, makes the read the old permission's.
    if (frame.revision != snapshot.revision || frame.readAdmission != snapshot.readAdmission) return
    val reads = frame.filter.candidatesIn(recognized.lines).map {
      TextMap.Read(it.text, it.confidence, textBoundsInView(it, region, recognized.width.toFloat(), recognized.height.toFloat()).corners())
    }
    managed.map.merge(region, reads, sensorNs)
    managed.carried(sensorNs)
    showAimed(cx, cy, region, frame, afterRead = true, bindGeneration)
    // The lock's reads tell the focus cycle whether its focus found readable text.
    val found = managed.lockReads.recognized(managed.map.at(cx, cy)?.agreed() != null) ?: return
    if (debugLogging) log("text lock reading: ${if (found) "the aimed line agreed" else "no agreed text"}")
    mainHandler.post {
      if (!admits(bindGeneration, frame)) return@post
      if (lockReadResult?.first != readLock) lockReadResult = readLock to found
    }
  }

  /** Main thread: a result from the analyzer's [frame] is still current: same binding, revision and read admission. */
  private fun admits(bindGeneration: Long, frame: AnalysisSnapshot): Boolean =
    bindGeneration == generation && controller != null && frame.revision == snapshot.revision &&
      frame.readAdmission == snapshot.readAdmission

  /**
   * Analysis thread: moves the map by image flow on the line nearest the aim. When flow fails during motion, the map
   * loses its position and OCR places it again. The gyroscope alone cannot place text when the phone slides.
   */
  private fun followMap(image: ImageProxy, sensorNs: Long, region: TextRegion, steady: Boolean) {
    val map = managed.map
    val target = map.nearest(region.x + region.width / 2, region.y + region.height / 2)
    if (target == null) {
      if (!steady) map.clear()
      return
    }
    val current = TrackedOutline("", target.corners, managed.mapNs, aimed = false)
    val move = flowMove(image, sensorNs, region, current)
    if (debugLogging) statMotion.merge(if (move != null) "flow" else "unknown", 1, Int::plus)
    if (move == null) {
      if (steady) managed.carried(sensorNs) else map.clear()
      return
    }
    if (map.move(move)) managed.carried(sensorNs)
  }

  /**
   * Analysis thread: publishes the agreed line the aim is on as the reading, with its highlight; the aim over text not
   * yet agreed, or none, clears it, so what the reading says is what the centre is on. A recognition [afterRead]
   * republishes the aimed line, whose bounds, confidence and votes it renewed; between recognitions flow moves only the
   * native highlight. Main publishes only what [admits] the frame, and the selection republishes once under each read
   * admission, so what main dropped is repaired on the next frame. Returns the selection.
   */
  private fun showAimed(cx: Double, cy: Double, region: TextRegion, frame: AnalysisSnapshot, afterRead: Boolean,
    bindGeneration: Long): ManagedTextReading.Selection {
    val selection = managed.select(cx, cy, afterRead, frame.readAdmission)
    val agreed = selection.agreed
    val shown = selection.line?.corners
    if (selection.publish) {
      if (debugLogging && selection.changed && agreed != null) log("text aimed: ${agreed.votes} agreeing")
      mainHandler.post { if (admits(bindGeneration, frame)) publish(agreed, region, shown) }
    } else if (shown != null) {
      mainHandler.post { if (admits(bindGeneration, frame) && reading != null) overlay.showText(shown) }
    }
    return selection
  }

  /** One recognition's lines, in an upright crop of [width] by [height] showing the region. */
  private class Recognized(val lines: List<RecognizedLine>, val width: Int, val height: Int)

  /** Analysis thread: recognizes [region] of [image]; null when it is too small or the recognizer is unavailable. */
  private fun recognize(image: ImageProxy, region: TextRegion): Recognized? {
    val rotation = image.imageInfo.rotationDegrees
    val crop = image.cropRect
    val box = regionInBuffer(region, rotation, crop.left, crop.top, crop.width(), crop.height())
    val left = box[0].coerceIn(0, image.width)
    val top = box[1].coerceIn(0, image.height)
    val width = box[2].coerceIn(left, image.width) - left
    val height = box[3].coerceIn(top, image.height) - top
    if (width < MIN_CROP_PX || height < MIN_CROP_PX) return null
    val client = recognizer() ?: return null
    // Upright before ML Kit: its own rotation shifts the boxes along the rotated axis.
    val bitmap = upright(cropRgba(image, left, top, width, height), rotation)
    val recognizing = System.nanoTime()
    val text = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)))
    statRecognitions++
    statRecognitionNs += System.nanoTime() - recognizing
    val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
      val bounds = line.boundingBox ?: return@mapNotNull null
      // Element boxes place each word, so a reading can be the piece of the line under the region's centre.
      val words = line.elements.mapNotNull { element ->
        element.boundingBox?.let { box ->
          // Symbol boxes place each character, so a piece split from a word starts and ends where its characters do.
          val chars = ArrayList<Float>()
          for (symbol in element.symbols) {
            val bounds = symbol.boundingBox ?: continue
            val count = symbol.text.length
            if (count == 0) continue
            val width = bounds.width().toFloat() / count
            for (i in 0 until count) {
              chars.add(bounds.left + i * width)
              chars.add(bounds.left + (i + 1) * width)
            }
          }
          // Missing symbol boxes leave the list short or empty, and the filter then shares the word's width evenly.
          RecognizedWord(element.text, box.left.toFloat(), box.right.toFloat(), chars.takeIf { it.isNotEmpty() })
        }
      }.ifEmpty { listOf(RecognizedWord(line.text, bounds.left.toFloat(), bounds.right.toFloat())) }
      RecognizedLine(words, bounds.top.toFloat(), bounds.bottom.toFloat(), line.confidence)
    }
    return Recognized(lines, if (rotation == 90 || rotation == 270) height else width,
      if (rotation == 90 || rotation == 270) width else height)
  }

  /**
   * Analysis thread: moves [outline] by the image motion since the previous frame and returns whether it changed. Flow
   * tracks the outline's own text on every frame while there is one, so each frame is the next one's reference. The
   * gyroscope bridges a frame flow cannot measure, as a new crop or a smeared frame, for [GYRO_BRIDGE_NS] since the image
   * last placed the outline: at reading distance the phone sliding moves the image as much as turning, and the gyroscope
   * sees only the turn. Past that the outline goes, since drawn where it was it would stay fixed over a moving frame. So
   * does text the region's centre was on when read and has left, which the aim no longer reads. The next recognition
   * that boxes the text places it again.
   */
  private fun followText(image: ImageProxy, sensorNs: Long, region: TextRegion): Boolean {
    val current = outline ?: return false
    val flowed = flowMove(image, sensorNs, region, current)
    val move = flowed ?: gyroMove(image, current, sensorNs)?.takeIf { sensorNs - current.placedNs <= GYRO_BRIDGE_NS }
    if (debugLogging) statMotion.merge(if (flowed != null) "flow" else if (move != null) "gyro" else "unknown", 1, Int::plus)
    val corners = move?.invoke(current.corners)
    if (corners == null) {
      outline = null
      return true
    }
    val moved = current.copy(corners = corners, sensorNs = sensorNs, placedNs = if (flowed != null) sensorNs else current.placedNs)
    outline = moved.takeUnless { it.aimed && !onText(it.corners, region.x + region.width / 2, region.y + region.height / 2) }
    return true
  }

  /**
   * Analysis thread: tells the focus cycle when the aimed [line] has grown or shrunk past the depth of field around the
   * distance the lock focused at since it was first measured under the held lock: moving the phone nearer or farther
   * scales the text and blurs it, though a slow move straight back neither turns nor shakes the phone. The limit comes
   * from the locked lens's focus distance, the lens and the applied zoom ([sizeLimit]), and the change must hold for a
   * few frames ([SizeWatch]). The baseline is the mapped line itself, so moving onto another line, even the same
   * text at another size, measures afresh.
   */
  private fun checkDistance(frame: AnalysisSnapshot, line: TextMap.Line?) {
    val lock = frame.readLock ?: return
    val scale = managed.distanceChange(frame.locked, lock, line) {
      depthOfField.sizeLimit(latestFocusDiopters, (latestZoomRatio ?: 1f).toDouble()).also {
        if (debugLogging) log("text lock size limit $it at ${latestFocusDiopters ?: "unknown"} diopters")
      }
    } ?: return
    if (debugLogging) log("text size changed %.3fx under the lock".format(scale))
    val session = frame.focusSession
    val regionRevision = frame.regionRevision
    val admission = frame.readAdmission
    mainHandler.post {
      if (session == snapshot.focusSession && regionRevision == snapshot.regionRevision && snapshot.locked &&
        snapshot.readAdmission == admission) {
        rescaled = true
      }
    }
  }

  /**
   * Analysis thread: runs flow over [current]'s text, or a stretch of a long line around the aim, cropped with a margin
   * from the full frame before flow downscales it so the strokes stay sharp, and returns the motion it measured since
   * [current]'s frame, which moves any corners of the page; null when flow cannot measure it. Flow finds features and fits the motion on the text alone, which its fit needs to
   * cover the area it watches.
   */
  private fun flowMove(image: ImageProxy, sensorNs: Long, region: TextRegion, current: TrackedOutline):
    ((DocumentCorners) -> DocumentCorners?)? {
    if (flowFailed) return null
    val crop = image.cropRect
    val rotation = image.imageInfo.rotationDegrees
    val pixelAspect = if (rotation % 180 == 0) crop.width().toDouble() / crop.height() else crop.height().toDouble() / crop.width()
    val text = textFlowWindow(current.corners.boundingRegion(), region.x + region.width / 2, pixelAspect)
    val view = textArea?.takeIf { textFlowAreaHolds(it, text) } ?: textFlowArea(text).also { textArea = it }
    val area = flowCrop(view, crop.left, crop.top, crop.right, crop.bottom, rotation) ?: return null
    val estimator = flow ?: try {
      FlowMotionEstimator(maxEdge = TEXT_FLOW_EDGE).also { flow = it }
    } catch (error: Throwable) {
      flowFailed = true
      ScannerLog.e(LOG_TAG, "Text outline motion is unavailable", error)
      return null
    }
    val plane = image.planes[0]
    val estimate = try {
      // A new crop starts flow from a new reference; the text within it moves freely.
      estimator.update(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height,
        area.left, area.top, area.right, area.bottom, rotation, sensorNs,
        area.toCrop(text.corners()), fitTransform = true)
    } catch (error: Throwable) {
      flowFailed = true
      estimator.close()
      flow = null
      ScannerLog.e(LOG_TAG, "Text outline motion failed", error)
      return null
    } finally {
      plane.buffer.rewind()
    }
    if (debugLogging) {
      val failure = when {
        estimate.status != FlowMotionStatus.MEASURED -> estimate.status.name
        estimate.transform == null -> "NO_FIT"
        estimate.fromSensorNs != current.sensorNs -> "OTHER_REFERENCE"
        else -> null
      }
      if (failure != null) statMotion.merge(failure, 1, Int::plus)
    }
    val transform = estimate.transform?.takeIf { estimate.status == FlowMotionStatus.MEASURED && estimate.fromSensorNs == current.sensorNs }
      ?: return null
    val move = { corners: DocumentCorners -> transform.warp(area.toCrop(corners))?.let(area::toView) }
    return move.takeIf { it(current.corners) != null }
  }

  /**
   * Analysis thread: the image motion the phone's rotation made since [current]'s frame, as the gyroscope measured it,
   * which moves any corners of the page; null without the gyroscope, the camera's optics, or samples over the interval. Turning moves every
   * image point the same way whatever the scene's depth; the phone sliding or the page moving it does not see.
   */
  private fun gyroMove(image: ImageProxy, current: TrackedOutline, sensorNs: Long): ((DocumentCorners) -> DocumentCorners?)? {
    val optics = cameraOptics ?: return null
    if (!gyroscope.isRunning) return null
    val crop = image.cropRect
    val frame = LiveFrameGeometry.of(optics, image.width, image.height, crop.left, crop.top, crop.right, crop.bottom,
      image.imageInfo.rotationDegrees)
    // Zoom crops the sensor around its centre, so the frame sees it through a longer focal length.
    val zoom = (latestZoomRatio ?: 1f).toDouble()
    val zoomed = frame.copy(focalPx = frame.focalPx * zoom, centerX = frame.width / 2.0 + (frame.centerX - frame.width / 2.0) * zoom,
      centerY = frame.height / 2.0 + (frame.centerY - frame.height / 2.0) * zoom)
    val motion = GyroDocumentMotion(gyroscope.history, zoomed)
    val fromNs = current.sensorNs
    val move = { corners: DocumentCorners -> motion.warp(corners, fromNs, sensorNs) }
    return move.takeIf { it(current.corners) != null }
  }

  /**
   * Analysis thread, logging only: every [STAT_FRAMES] analyzed frames, the rate they reached the analyzer, which a
   * recognition holding it lowers below the camera's, and what recognition costs.
   */
  private fun reportAnalysis(sensorNs: Long) {
    // A gap, as across a camera stop, starts a new report, so its rate covers frames that were running.
    if (sensorNs - statLastNs > STAT_GAP_NS) resetAnalysisReport()
    else if (statFrames > 0) statLongestGapNs = maxOf(statLongestGapNs, sensorNs - statLastNs)
    statLastNs = sensorNs
    if (statFrames++ == 0) statFirstNs = sensorNs
    if (statFrames <= STAT_FRAMES) return
    val spanMs = (sensorNs - statFirstNs) / 1e6
    val captured = capturedFrames.get() - statCapturedFrom
    log("analysis: %.1f frames/s, %d of %d camera frames, longest gap %.0f ms; recognition %.1f ms x %d, %d settled; outline %s".format(
      STAT_FRAMES * 1000 / spanMs, statFrames, captured, statLongestGapNs / 1e6,
      statRecognitionNs / 1e6 / statRecognitions.coerceAtLeast(1), statRecognitions, statSettled, statMotion))
    resetAnalysisReport()
  }

  private fun resetAnalysisReport() {
    statCapturedFrom = capturedFrames.get()
    statLongestGapNs = 0L
    statMotion.clear()
    statSettled = 0
    statFrames = 0
    statRecognitions = 0
    statRecognitionNs = 0L
  }

  /**
   * Main thread: one frame for [textCycle], with the motion sensors' view of it. A frame posted for another run or an
   * earlier region drops, and a tap's focus holds the lens for its own time.
   */
  private fun onFocusFrame(focusSession: Long, regionRevision: Long, sensorNs: Long) {
    val current = snapshot
    if (focusSession == 0L || focusSession != current.focusSession || regionRevision != current.regionRevision) return
    val cycle = textCycle ?: return
    val motion = hand.read(sensorNs)
    // Without motion samples the hand never reads steady, and no lock would ever run.
    if (motion == null) {
      if (sensorlessSinceNs == 0L) sensorlessSinceNs = sensorNs
      if (sensorNs - sensorlessSinceNs > SENSOR_GRACE_NS) {
        log("text focus lock: no motion samples; camera autofocus")
        focusSensorsFailed = true
        stopTextFocus(bound = true)
        return
      }
    } else {
      sensorlessSinceNs = 0L
    }
    val quiet = motion?.quiet == true
    val turned = hand.turned(sensorNs)
    if (turned && !wasTurned) wakeRecognition()
    if (turned && !wasTurned && debugLogging) {
      log("text lens: turned %.1f deg over %d ms; turn rate %.2f rad/s, acceleration %.2f m/s2".format(
        Math.toDegrees(hand.turnAngle(sensorNs) ?: 0.0), FocusRules.TURN_NS / 1_000_000, motion?.turn ?: Double.NaN,
        motion?.acceleration ?: Double.NaN))
    }
    wasTurned = turned
    if (quiet != snapshot.steady) snapshot = snapshot.copy(steady = quiet)
    val held = focus.snapshot
    if (held.owner == FocusOwner.TAP) return
    val lens = when {
      held.owner == null -> FocusCycle.Lens.FREE
      held.locked == null -> FocusCycle.Lens.SCANNING
      else -> FocusCycle.Lens.LOCKED
    }
    // The region is the target; a lock found what it focused for when its first reads agree on the aimed line.
    val found = lockReadResult?.takeIf { lens == FocusCycle.Lens.LOCKED && it.first == current.readLock }?.second
    val distanceChanged = rescaled
    rescaled = false
    cycle.motion(sensorNs, quiet)
    follow(cycle.next(FocusCycle.Frame(quiet, turned, lens, rescaled = distanceChanged, found = found)))
  }

  /** Main thread: carries out the focus cycle's [step]. The text camera has no auto torch, so it never lights one. */
  private fun follow(step: FocusCycle.Step) {
    when (step) {
      // The region is always the target, so no lock aims elsewhere and the text camera never keeps or replaces one.
      FocusCycle.Step.None, FocusCycle.Step.Keep, is FocusCycle.Step.Light, is FocusCycle.Step.Replace -> Unit
      is FocusCycle.Step.Release -> {
        log("text focus lock released: ${step.why}")
        focus.releaseScannerLock()
      }
      is FocusCycle.Step.Lock -> lockText(step.why)
      is FocusCycle.Step.Relock -> lockText(step.why)
      is FocusCycle.Step.GiveUp -> log("text focus cycle gave up: ${step.why}")
    }
  }

  /** Main thread: the camera's own locking scan at the region, focusing and metering there. A settled lock scans again
   * without a release, so the lens starts the sweep where it was; anything else holding the lens is released first. */
  private fun lockText(why: String) {
    val camera = boundCamera ?: return
    val point = regionPoint() ?: return
    val accepted = {
      textCycle?.started()
      log("text focus lock for $why")
      overlay.pulse()
    }
    if (focus.relock(camera, point, meterExposure = true, accepted = accepted)) return
    if (focus.snapshot.owner != null) focus.releaseScannerLock()
    focus.lock(camera, point, FocusOwner.PAGE, meterExposure = true, accepted = accepted)
  }

  /**
   * Main thread: starts or stops [textCycle] to match `focusLock` on the bound camera. It needs single-shot autofocus
   * and motion sensors on the camera's clock, which tell a steady hand and a turned phone apart; without them the
   * camera's continuous autofocus alone follows the region. It runs only at a settled zoom: a zoom request stops it and
   * its settling starts it again, since zoom can switch the camera to another lens; a new binding starts it anew too.
   */
  private fun syncTextFocus() {
    val want = focusLock == true && controller != null && boundCamera != null && !focusSensorsFailed &&
      zoomRequests.settled != null
    if (want && textCycle != null) return
    if (textCycle != null) stopTextFocus(bound = true)
    if (!want) return
    val info = controller?.cameraInfo ?: return
    val camera2 = try { info.cameraCharacteristics } catch (_: Exception) { return }
    val realtime = try {
      camera2.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    } catch (_: Exception) { false }
    if (!hasSingleShotAutofocus || !realtime) {
      log("text focus lock unavailable: singleShot=$hasSingleShotAutofocus realtime=$realtime; camera autofocus")
      return
    }
    if (!gyroscope.hasAcceleration) {
      log("text focus lock unavailable: no linear acceleration sensor; camera autofocus")
      return
    }
    gyroscope.start()
    if (!gyroscope.isRunning) {
      log("text focus lock unavailable: no gyroscope; camera autofocus")
      return
    }
    sensorlessSinceNs = 0L
    textCycle = FocusCycle()
    // Managed OCR takes over from the paced path: a new revision drops a paced recognition still running and its queued
    // highlights, and starts the analyzer's state afresh.
    snapshot = snapshot.copy(focusSession = ++focusSessions, revision = snapshot.revision + 1)
    clearReading()
    aimFocus()
    updateAutofocus()
  }

  /** Main thread: ends [textCycle]; with the camera still [bound], continuous autofocus follows the region again. */
  private fun stopTextFocus(bound: Boolean) {
    if (textCycle == null) return
    textCycle = null
    // Reading returns to its own pace under continuous autofocus at once, even while the camera stops.
    permission = permission.next(cycle = false, scanning = false, settled = null, latestCaptureNs = latestCaptureNs)
    rescaled = false
    // The paced path takes over: a new revision drops managed results in flight and the map, so a quick toggle back
    // cannot keep it.
    snapshot = snapshot.copy(focusSession = 0L, revision = snapshot.revision + 1, readLock = permission.lock,
      readAdmission = permission.admission, readLockFromNs = permission.fromNs, locked = false, steady = false)
    clearReading()
    gyroscope.stop()
    if (!bound) return
    // A released lock's cancellation re-aims continuous autofocus once it completes.
    if (focus.releaseScannerLock() == FocusController.Release.NONE) aimFocus()
    updateAutofocus()
  }

  /**
   * Main thread: the `zoom` ratio, clamped to the camera's range, on the bound camera, asked once per ratio. Each request,
   * the first of a binding included, shows other text, so the reading clears and no frame is read until the
   * request settles: the camera has answered it, and the view's own capture results have shown its ratio, from whose
   * first frame on frames count. The votes then restart and the focus lock restarts for the ratio. A
   * request the camera refuses keeps the ratio its capture results show, or else the last one it confirmed, and is not
   * asked again until `zoom` changes.
   */
  private fun applyZoom() {
    val bound = controller ?: return
    val range = bound.cameraInfo?.zoomState?.value
    val ratio = (zoom ?: 1.0).toFloat().let { if (range != null) it.coerceIn(range.minZoomRatio, range.maxZoomRatio) else it }
    if (zoomMetadata == null) zoomMetadata = readZoomMetadata(bound)
    val request = zoomRequests.request(ratio) ?: return
    zoomWatch.set(request)
    zoomRequestedNs = SystemClock.elapsedRealtimeNanos()
    log("zoom %.2f requested".format(request.ratio))
    onZoomChanged()
    val bindGeneration = generation
    fun refused(error: Throwable) {
      log("zoom failed ${error.javaClass.simpleName}")
      zoomWatch.compareAndSet(request, null)
      // The view did not change, so frames from the next capture on count.
      if (zoomRequests.refuse(request, latestZoomRatio.takeIf { zoomMeasured }, latestCaptureNs + 1)) onZoomChanged()
    }
    val future = try { bound.setZoomRatio(request.ratio) } catch (error: Exception) { return refused(error) }
    future.addListener({
      try {
        future.get()
      } catch (error: java.util.concurrent.ExecutionException) {
        return@addListener refused(error.cause ?: error)
      } catch (error: Exception) {
        return@addListener refused(error)
      }
      // Without zoom in the capture results, frames after the newest one seen count, which capture results trail.
      if (!zoomRequests.settle(request, if (zoomMeasured) null else latestCaptureNs + 1)) return@addListener
      log("zoom %.2f applied after %d ms%s".format(request.ratio, (SystemClock.elapsedRealtimeNanos() - zoomRequestedNs) / 1_000_000,
        if (zoomMeasured) "" else "; capture results show no zoom, reading from the next frame"))
      onZoomChanged()
      // A ratio the capture results never match, as after rounding the camera's own, does not stop reading for good.
      mainHandler.postDelayed({
        if (bindGeneration == generation && zoomRequests.seen(request.id, latestCaptureNs + 1)) {
          log("zoom ${request.ratio} not seen in capture results; reading from the next frame")
          zoomWatch.compareAndSet(request, null)
          onZoomChanged()
        }
      }, ZOOM_SEEN_GRACE_MS)
    }, ContextCompat.getMainExecutor(themedContext))
  }

  /** Main thread: publishes the zoom's read admission, restarting the votes and reading, and syncs the focus lock. */
  private fun onZoomChanged() {
    snapshot = snapshot.copy(revision = snapshot.revision + 1, readFromNs = zoomRequests.readFromNs)
    clearReading()
    syncTextFocus()
  }

  /** How [bound]'s capture results show its zoom, as CameraX applies it; null when the camera cannot be read yet. */
  private fun readZoomMetadata(bound: LifecycleCameraController): ZoomMetadata? {
    val info = bound.cameraInfo ?: return null
    return try {
      val camera2 = info.cameraCharacteristics
      val ratioRange = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        camera2.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
      val active = camera2.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
      ZoomMetadata(ratioRange, active?.width() ?: 0)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Each frame's autofocus state for `onAutofocusChange`, a lock's trigger and lock frames, and the zoom it shows; runs
   * on a camera thread. Results of an older binding drop.
   */
  private fun frameStateCallback(bindGeneration: Long) = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      if (bindGeneration != generation) return
      val afState = result.get(CaptureResult.CONTROL_AF_STATE)
      focus.observeFrame(result.frameNumber, request.get(CaptureRequest.CONTROL_AF_TRIGGER),
        result.get(CaptureResult.CONTROL_AF_MODE), afState)
      if (afState != cameraAfState) {
        cameraAfState = afState
        mainHandler.post { if (bindGeneration == generation) updateAutofocus() }
      }
      latestFocusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
      val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
      if (timestampNs > latestCaptureNs) latestCaptureNs = timestampNs
      if (debugLogging) capturedFrames.incrementAndGet()
      zoomMetadata?.ratio(result)?.let { applied ->
        zoomMeasured = true
        latestZoomRatio = applied
        val watch = zoomWatch.get()
        if (watch != null && abs(applied - watch.ratio) <= watch.ratio * ZOOM_TOLERANCE && zoomWatch.compareAndSet(watch, null)) {
          mainHandler.post {
            if (bindGeneration != generation || !zoomRequests.seen(watch.id, timestampNs)) return@post
            log("zoom %.2f first shown by the frame %d ms after the request; reading from it".format(
              watch.ratio, (timestampNs - zoomRequestedNs) / 1_000_000))
            onZoomChanged()
          }
        }
      }
    }
  }

  private fun postOutline(bindGeneration: Long, revision: Long) {
    val shown = outline?.corners
    mainHandler.post {
      if (bindGeneration != generation || revision != snapshot.revision || controller == null) return@post
      if (reading == null) return@post
      overlay.showText(shown)
    }
  }

  /** Analysis thread. */
  private fun recognizer(): TextRecognizer? {
    recognizer?.let { return it }
    if (recognizerFailed) return null
    return try {
      TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    } catch (error: Throwable) {
      recognizerFailed = true
      ScannerLog.e(LOG_TAG, "ML Kit text recognition is unavailable", error)
      mainHandler.post { if (!disposed) onError?.invoke(ScannerFailure(ScannerErrorCode.FEATURE_UNSUPPORTED)) }
      null
    }
  }

  private fun upright(sideways: Bitmap, rotation: Int): Bitmap {
    if (rotation == 0) return sideways
    return Bitmap.createBitmap(sideways, 0, 0, sideways.width, sideways.height, uprightMatrix.apply { setRotate(rotation.toFloat()) }, false)
      .also { if (it !== sideways) sideways.recycle() }
  }

  private fun cropRgba(image: ImageProxy, left: Int, top: Int, width: Int, height: Int): Bitmap {
    val plane = image.planes[0]
    val source = plane.buffer
    val rowBytes = width * 4
    val size = rowBytes * height
    val bytes = cropBytes?.takeIf { it.size == size } ?: ByteArray(size).also { cropBytes = it; cropBuffer = ByteBuffer.wrap(it) }
    for (row in 0 until height) {
      source.position((top + row) * plane.rowStride + left * plane.pixelStride)
      source.get(bytes, row * rowBytes, rowBytes)
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(cropBuffer!!.apply { rewind() })
    return bitmap
  }

  private fun publish(winner: TextVotes.Winner?, region: TextRegion, outline: DocumentCorners?) {
    reading = winner?.let { TextReading(it.text, it.confidence, it.votes.toDouble(), region, outline?.boundingRegion()) }
    overlay.showText(if (winner != null) outline else null)
    val current = reading ?: return emitReading()
    for (pending in pendingReads) {
      mainHandler.removeCallbacks(pending.timeout)
      pending.promise.resolve(Variant_ScannerFailure_TextReading.create(current))
    }
    pendingReads.clear()
    emitReading()
  }

  private fun clearReading() {
    reading = null
    emitReading()
  }

  private fun emptyReading() = TextReading("", 0.0, 0.0, snapshot.region, null)

  private fun emitReading() {
    if (disposed) return
    val next = reading ?: emptyReading()
    if (reading == null) overlay.showText(null)
    val last = emittedReading
    if (last != null && last.text == next.text && last.votes == next.votes && last.bounds == next.bounds) return
    emittedReading = next
    onTextChange?.invoke(next)
  }

  /** Main thread: continuous autofocus and exposure aimed at the region, unlocked, while nothing holds the lens. */
  private fun aimFocus() {
    val control = cameraControl ?: return
    // A pending cancellation aims it once it completes.
    if (focus.snapshot.owner != null || focus.resuming) return
    val point = regionPoint() ?: return
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .setLockingMode(0).disableAutoCancel().build()
    try { control.startFocusAndMetering(action) } catch (error: Exception) { log("region focus failed ${error.javaClass.simpleName}") }
  }

  /** Main thread: the region's centre, sized to it, on the preview; null before the preview is measured. */
  private fun regionPoint(): MeteringPoint? {
    if (previewView.width <= 0 || previewView.height <= 0) return null
    val region = snapshot.region
    return previewView.meteringPointFactory.createPoint(
      ((region.x + region.width / 2) * previewView.width).toFloat(),
      ((region.y + region.height / 2) * previewView.height).toFloat(),
      maxOf(region.width, region.height).toFloat().coerceIn(0.05f, 0.5f),
    )
  }

  /** Main thread: a tap's own scan focuses and meters there for its hold, then continuous autofocus resumes on the
   * region and the focus cycle starts over. */
  private fun onTap(x: Float, y: Float) {
    val camera = boundCamera ?: return
    if (tapToFocus == false) return
    val point = previewView.meteringPointFactory.createPoint(x, y, FocusRules.TAP_AREA)
    textCycle?.reset()
    focus.tap(camera, point, point)
  }

  private fun applyTorch() {
    val control = cameraControl ?: return
    try { control.enableTorch(torch == TextTorchMode.ON) } catch (_: Exception) { }
  }

  /**
   * Main thread: the focus state for `onAutofocusChange` and the built-in guide. A lock or a tap reports its scan as
   * `scan` and its confirmed lock as `locked`; continuous autofocus reports its capture results' state.
   */
  private fun updateAutofocus() {
    if (disposed) return
    val held = focus.snapshot
    val state = when {
      controller == null -> AutofocusState.INACTIVE
      held.owner != null && held.locked == null -> AutofocusState.SCAN
      held.owner != null && held.locked == true -> AutofocusState.LOCKED
      else -> when (cameraAfState) {
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> AutofocusState.SCAN
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> AutofocusState.FOCUSED
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> AutofocusState.UNFOCUSED
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> AutofocusState.LOCKED
        else -> AutofocusState.INACTIVE
      }
    }
    overlay.focus(state)
    // Under the focus cycle, reading waits for the first settled lock, its own or a tap's, since unconfirmed continuous
    // autofocus misreads at close range; after it, the hand corrects the distance, and only a lock's scan, which sweeps
    // the lens, pauses reading. A new lock starts a new reading. Without the cycle reading keeps its pace.
    val locked = textCycle != null && held.owner != null && held.locked == true
    permission = permission.next(cycle = textCycle != null, scanning = held.owner != null && held.locked == null,
      settled = held.generation.takeIf { locked }, latestCaptureNs = latestCaptureNs)
    if (permission.admission != snapshot.readAdmission || locked != snapshot.locked) {
      snapshot = snapshot.copy(readLock = permission.lock, readAdmission = permission.admission,
        readLockFromNs = permission.fromNs, locked = locked)
    }
    val searching = held.owner != null && held.locked == null
    // The focus a scan found changes what the frames show, so the reading confirms it at the full rate.
    if (wasSearching && !searching) wakeRecognition()
    wasSearching = searching
    if (state == emittedAutofocus) return
    emittedAutofocus = state
    onAutofocusChange?.invoke(state)
  }

  /** Main: returns recognition to its full rate on the next analyzed frame. */
  private fun wakeRecognition() {
    snapshot = snapshot.copy(wake = ++recognitionWake)
  }

  private fun updateCameraState() {
    if (disposed) return
    val state = when {
      controller == null -> if (startInFlight) ScannerCameraState.STARTING else ScannerCameraState.SUSPENDED
      interrupted || previewView.previewStreamState.value != PreviewView.StreamState.STREAMING -> ScannerCameraState.STARTING
      else -> ScannerCameraState.READY
    }
    if (state == emittedState) return
    emittedState = state
    onCameraStateChange?.invoke(state)
  }

  private fun log(message: String) {
    if (debugLogging) ScannerLog.d(LOG_TAG, message)
  }

  private companion object {
    const val DEFAULT_ANALYSIS_WIDTH = 1280
    /** Flow's longest edge for the region's crop: twice the whole-frame default, so text strokes stay trackable. */
    const val TEXT_FLOW_EDGE = 448
    /** How long the gyroscope alone may carry the outline: a few frames, over a new crop or a smeared frame. */
    const val GYRO_BRIDGE_NS = 300_000_000L
    /** How often, at most, the region is read while the phone moves, as new text enters it; a lower `recognitionRate` is slower. */
    const val MOVING_READ_MS = 500L
    /** How far above and below the region, in region heights, the map keeps lines. */
    const val MAP_REACH = 1.0
    const val DEFAULT_COLOR = 0xFF61E1CF.toInt()
    const val DEFAULT_RATE = 5
    const val MIN_CROP_PX = 16
    /** How long a running focus cycle waits for motion samples before leaving focus to continuous autofocus. */
    const val SENSOR_GRACE_NS = 1_000_000_000L
    /** A capture result's zoom within this fraction of a request's ratio shows it, past the crop region's rounding. */
    const val ZOOM_TOLERANCE = .01f
    /** How long after the camera applies a zoom its capture results may take to show it before frames count anyway. */
    const val ZOOM_SEEN_GRACE_MS = 1_000L
    /** Analyzed frames between the analysis cost reports of a debug build, about 5 s. */
    const val STAT_FRAMES = 150
    /** A gap between analyzed frames this long starts a new analysis report. */
    const val STAT_GAP_NS = 1_000_000_000L
  }
}

/**
 * What the analyzer reads for one frame, published by main as a whole. [revision] restarts the votes when it changes and
 * must still match on main for a result to publish. Frames exposed before [readFromNs] are not read, which
 * [Long.MAX_VALUE] makes all of them while a zoom settles. A nonzero [focusSession] has each frame handed to main's focus
 * cycle, tagged with it and [regionRevision], which changes with [region] alone. A new [wake] returns recognition to its full
 * rate. [readLock] is null without the focus cycle, which leaves reading to its pace; with it, the text map reads
 * under the lock it names, the latest settled one, kept after it is released, and 0 before the first and during a
 * lock's scan; [steady] is the motion sensors' view of the hand, and [locked] whether a lock holds the lens now. Frames
 * exposed before [readLockFromNs], queued when the lock settled, are not read under it, and a result taken under another
 * [readAdmission], which changes with every change of permission, a scan that starts and fails included, drops.
 */
private data class AnalysisSnapshot(
  val region: TextRegion, val filter: TextLineFilter, val revision: Long, val readFromNs: Long,
  val focusSession: Long, val regionRevision: Long, val wake: Long = 0L, val readLock: Long? = null,
  val steady: Boolean = false, val locked: Boolean = false, val readLockFromNs: Long = 0L, val readAdmission: Long = 0L,
)

/**
 * How a camera's capture results show the zoom it applies, as CameraX applies it: the zoom ratio where the camera has a
 * zoom ratio range, else the crop region against the [activeWidth] of its active array.
 */
private class ZoomMetadata(private val ratioKey: Boolean, private val activeWidth: Int) {
  fun ratio(result: CaptureResult): Float? {
    if (ratioKey && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return result.get(CaptureResult.CONTROL_ZOOM_RATIO)
    val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return null
    return if (activeWidth > 0 && crop.width() > 0) activeWidth.toFloat() / crop.width() else null
  }
}

/** The outline of [text], as view fractions, where it appeared in the frame at sensor time [sensorNs]. */
/** [aimed]: the region's centre, the aim, was on the text when it was read. */
/** [placedNs]: the last frame whose image placed it, by recognition or flow, which bounds how long the gyroscope carries it. */
private data class TrackedOutline(
  val text: String, val corners: DocumentCorners, val sensorNs: Long, val aimed: Boolean, val placedNs: Long = sensorNs,
)

/** Draws the region's guide and highlights the text being read, each when asked; nothing otherwise. */
private class TextRegionOverlay(context: Context) : View(context) {
  private var region: TextRegion? = null
  private var text: DocumentCorners? = null
  private var guide = TextGuide.NONE
  private var highlight = false
  private val density = context.resources.displayMetrics.density
  /** A soft highlight over the text, its corners rounded by the path effect. */
  private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = CornerPathEffect(HIGHLIGHT_RADIUS_DP * density) }
  private val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = STROKE_DP * density
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val textPath = Path()
  private val rect = RectF()
  private var guideColor = 0
  private var focus = AutofocusState.INACTIVE
  private var flashColor = 0
  /** How much of [flashColor] the guide shows, fading from 1 to 0 after focus lands or fails. */
  private var flash = 0f
  private val flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
    duration = FLASH_MS
    interpolator = AccelerateInterpolator()
    addUpdateListener { flash = it.animatedValue as Float; invalidate() }
  }
  /** How much wider the guide's stroke draws, fading from 1 to 0 after each focus lock starts. */
  private var swell = 0f
  private val pulseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
    duration = PULSE_MS
    interpolator = DecelerateInterpolator()
    addUpdateListener { swell = it.animatedValue as Float; invalidate() }
  }

  fun update(region: TextRegion, guide: TextGuide, highlight: Boolean, color: Int) {
    if (region == this.region && guide == this.guide && highlight == this.highlight && color == guideColor) return
    this.region = region
    this.guide = guide
    this.highlight = highlight
    textFill.color = (color and 0x00FFFFFF) or 0x59000000
    guideColor = color
    invalidate()
  }

  /** The guide turns yellow while focus scans, and flashes green when it lands or red when it fails, back to its colour. */
  fun focus(state: AutofocusState) {
    if (state == focus) return
    focus = state
    flashAnimator.cancel()
    flash = 0f
    when (state) {
      AutofocusState.FOCUSED, AutofocusState.LOCKED -> { flashColor = FOCUSED_COLOR; flashAnimator.start() }
      AutofocusState.UNFOCUSED -> { flashColor = UNFOCUSED_COLOR; flashAnimator.start() }
      AutofocusState.SCAN, AutofocusState.INACTIVE -> Unit
    }
    invalidate()
  }

  /** A swell of the guide's stroke, as a focus lock starts its sweep. */
  fun pulse() {
    pulseAnimator.cancel()
    pulseAnimator.start()
  }

  override fun onDetachedFromWindow() {
    flashAnimator.cancel()
    pulseAnimator.cancel()
    super.onDetachedFromWindow()
  }

  private companion object {
    const val CROSSHAIR_ARM_DP = 8f
    const val DOT_DP = 7f
    const val HIGHLIGHT_PAD_DP = 3f
    const val HIGHLIGHT_RADIUS_DP = 4f
    const val STROKE_DP = 1.5f
    /** The scanner focus indicator's colours. */
    const val SCAN_COLOR = 0xFFFFCC00.toInt()
    const val FOCUSED_COLOR = 0xFF4CD964.toInt()
    const val UNFOCUSED_COLOR = 0xFFFF3B30.toInt()
    const val FLASH_MS = 700L
    const val PULSE_MS = 400L
  }

  /** The outline of the text being read, as fractions of the view, or null for none. */
  fun showText(outline: DocumentCorners?) {
    if (outline == text) return
    text = outline
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    if (width == 0 || height == 0) return
    if (highlight) drawHighlight(canvas)
    drawGuide(canvas)
  }

  private fun drawGuide(canvas: Canvas) {
    val region = region ?: return
    if (guide == TextGuide.NONE) return
    rect.set((region.x * width).toFloat(), (region.y * height).toFloat(),
      ((region.x + region.width) * width).toFloat(), ((region.y + region.height) * height).toFloat())
    guideStroke.color = if (focus == AutofocusState.SCAN) SCAN_COLOR else ColorUtils.blendARGB(guideColor, flashColor, flash)
    guideStroke.strokeWidth = STROKE_DP * density * (1 + swell)
    if (guide == TextGuide.LINE) {
      val inset = rect.width() * .08f
      val y = rect.centerY() + minOf(14 * density, rect.height() / 3)
      canvas.drawLine(rect.left + inset, y, rect.right - inset, y, guideStroke)
      return
    }
    if (guide == TextGuide.DOT) {
      // A round cap draws a point as a disc as wide as the stroke.
      guideStroke.strokeWidth = DOT_DP * density * (1 + swell)
      canvas.drawPoint(rect.centerX(), rect.centerY(), guideStroke)
      return
    }
    // The point readings are chosen nearest to.
    val arm = minOf(CROSSHAIR_ARM_DP * density, rect.height() / 3, rect.width() / 3)
    canvas.drawLine(rect.centerX() - arm, rect.centerY(), rect.centerX() + arm, rect.centerY(), guideStroke)
    canvas.drawLine(rect.centerX(), rect.centerY() - arm, rect.centerX(), rect.centerY() + arm, guideStroke)
  }

  private fun drawHighlight(canvas: Canvas) {
    val text = text ?: return
    val region = region ?: return
    val u = (text.topLeft.x + text.topRight.x + text.bottomRight.x + text.bottomLeft.x) / 4
    val v = (text.topLeft.y + text.topRight.y + text.bottomRight.y + text.bottomLeft.y) / 4
    // Text whose centre the camera's motion carried out of the region is no longer what is read; the reading outlives
    // it by its votes, but the highlight goes with it.
    if (u < region.x || u > region.x + region.width || v < region.y || v > region.y + region.height) return
    // Each corner moves out from the centre by the padding, so the highlight clears the glyphs.
    val pad = HIGHLIGHT_PAD_DP * density
    val cx = u * width
    val cy = v * height
    textPath.reset()
    listOf(text.topLeft, text.topRight, text.bottomRight, text.bottomLeft).forEachIndexed { i, point ->
      val x = (point.x * width).toFloat().let { it + if (it < cx) -pad else pad }
      val y = (point.y * height).toFloat().let { it + if (it < cy) -pad else pad }
      if (i == 0) textPath.moveTo(x, y) else textPath.lineTo(x, y)
    }
    textPath.close()
    canvas.drawPath(textPath, textFill)
  }
}
