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
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.applyCamera2InteropAsync
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import com.margelo.nitro.documentscanner.processing.FlowMotionEstimator
import com.margelo.nitro.documentscanner.processing.FlowMotionStatus
import com.margelo.nitro.documentscanner.processing.CameraFrameHistory
import com.margelo.nitro.documentscanner.processing.CameraFrameState
import com.margelo.nitro.documentscanner.processing.GyroHistory
import com.margelo.nitro.documentscanner.processing.GyroscopeSource
import com.margelo.nitro.documentscanner.processing.RegionDetail
import com.margelo.nitro.documentscanner.processing.regionDetail
import kotlin.math.abs
import kotlin.math.acos
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
  private val debugLogging = themedContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
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
  private val reaimFocus = Runnable { aimFocus() }
  /** Main thread, `focusLock`: the lens hold, or null while the camera's own autofocus runs. */
  private var textFocus: TextLensFocus? = null
  /** Main thread: the lens memory key of the bound camera at its applied zoom, while [textFocus] runs. */
  private var focusKey: String? = null
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
  /** Counts lens holds, so a sharpness sample scored for an earlier one, in this binding or another, drops. */
  private var focusSessions = 0L
  /** Set when the motion sensors gave no samples for a running [textFocus]; continuous autofocus until the next start. */
  private var focusSensorsFailed = false
  /** The sensor time of the first frame since [textFocus] started without motion samples, or 0. */
  private var sensorlessSinceNs = 0L
  private val gyroscope = GyroscopeSource(themedContext, GyroHistory())
  /** Capture results by sensor timestamp, written on a camera thread for [textFocus]. */
  private val frameStates = CameraFrameHistory()
  /** Main thread: the newest capture result's lens reading, where a tap's autofocus left the lens. */
  @Volatile private var latestDiopters: Float? = null
  /** The newest capture result's sensor time; written on a camera thread. */
  @Volatile private var latestCaptureNs = 0L
  /** The newest capture result's zoom ratio, and whether any result of this binding showed one; camera thread. */
  @Volatile private var latestZoomRatio: Float? = null
  @Volatile private var zoomMeasured = false
  /** The zoom request whose first capture result at its ratio the camera thread watches for, then clears. */
  private val zoomWatch = AtomicReference<TextZoom.Request?>(null)
  /** The newest capture result's autofocus state, for the camera's own autofocus; written on a camera thread. */
  @Volatile private var cameraAfState: Int? = null
  /** Each camera's lens distance at its last text lock, by camera id and zoom, kept across launches. */
  private val lensMemory by lazy { themedContext.getSharedPreferences(TEXT_LENS_PREFERENCES, Context.MODE_PRIVATE) }
  private val resumeAfterTap = Runnable {
    textFocus?.resume(latestDiopters)
    aimFocus()
    updateAutofocus()
  }
  private val focusHost = object : TextLensFocus.Host {
    override fun moveLens(diopters: Float): Boolean = interop {
      setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
    }.also { if (it && textFocus?.stage == TextLensFocus.Stage.SEARCHING) overlay.pulse() }
    override fun lockExposure(locked: Boolean) {
      interop {
        if (locked) setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        else clearCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK)
      }
    }
    override fun cameraAutofocus() {
      clearManualLens()
      aimFocus()
    }
    override fun remember(diopters: Float) {
      val key = focusKey ?: return
      if (lensMemory.getFloat(key, Float.NaN) != diopters) lensMemory.edit().putFloat(key, diopters).apply()
    }
    override val logging get() = debugLogging
    override fun log(message: String) = this@HybridTextCameraView.log(message)
  }
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
  /** Written by main only, as a whole; the analyzer takes one per frame and reads and publishes by it. */
  // Analysis thread, logging only: what one report's frames cost and how many reached the analyzer.
  private var statFrames = 0
  private var statFirstNs = 0L
  private var statLastNs = 0L
  private var statScored = 0
  private var statScoreNs = 0L
  private var statScorePx = 0L
  private var statRecognitions = 0
  private var statRecognitionNs = 0L
  private var statSettled = 0
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
  @Volatile private var frameIntervalMs = 1000L / DEFAULT_RATE
  private var cropBytes: ByteArray? = null
  private var cropBuffer: ByteBuffer? = null
  private val uprightMatrix = Matrix()
  /** Optical flow over every frame, so the outline follows the text between recognitions; null once it fails. */
  private var flow: FlowMotionEstimator? = null
  private var flowFailed = false
  /** The outline of the agreed text, if a frame has boxed it. */
  private var outline: TrackedOutline? = null

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
      // Other text, maybe at another distance: the lens hold searches again from where the lens is.
      textFocus?.regionChanged(latestDiopters)
      aimFocus()
      updateAutofocus()
    }
    val filterKey = Triple(allowedCharacters.orEmpty(), textPattern.orEmpty(), (minLength ?: 1.0).toInt())
    if (filterKey != appliedFilterKey) {
      appliedFilterKey = filterKey
      val filter = TextLineFilter(filterKey.first, filterKey.second, filterKey.third)
      // The same text read another way: the votes restart, the lens hold stays.
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
        observeCameraState(next, bindGeneration)
        boundWidth = width
        layoutChildren()
        log("bound analysis=${useCase.resolutionInfo?.resolution} rotation=$rotation")
        interop { repeatingCaptureCallback = frameStateCallback(bindGeneration) }
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
    Log.e(LOG_TAG, "Text camera binding failed", error)
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
      Log.e(LOG_TAG, "Text camera failed: ${state.error?.code}")
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
    mainHandler.removeCallbacks(reaimFocus)
    stopTextFocus(bound = false)
    zoomRequests.reset()
    zoomWatch.set(null)
    zoomMetadata = null
    zoomMeasured = false
    latestZoomRatio = null
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
        countedRevision = revision
        log("frames ${image.width}x${image.height} crop=${image.cropRect} rotation=${image.imageInfo.rotationDegrees}")
      }
      val rotation = image.imageInfo.rotationDegrees
      val crop = image.cropRect
      val sensorNs = image.imageInfo.timestamp
      val moved = followText(image, sensorNs, frame.region)
      // A frame exposed before the zoom requested applied shows the view before it, for focus and reading alike.
      val current = sensorNs >= frame.readFromNs
      if (current && frame.focusSession != 0L) scoreRegion(image, sensorNs, frame)
      if (debugLogging) reportAnalysis(sensorNs)
      pace.wake(frame.wake)
      if (moved) outline?.let { pace.moved(it.corners.boundingRegion()) }
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
        if (moved) postOutline(bindGeneration, revision)
        return
      }
      lastFrameMs = now
      if (pace.settled) statSettled++
      if (!current) return
      val box = regionInBuffer(frame.region, rotation, crop.left, crop.top, crop.width(), crop.height())
      val left = box[0].coerceIn(0, image.width)
      val top = box[1].coerceIn(0, image.height)
      val width = box[2].coerceIn(left, image.width) - left
      val height = box[3].coerceIn(top, image.height) - top
      if (width < MIN_CROP_PX || height < MIN_CROP_PX) return
      val client = recognizer() ?: return
      // Upright before ML Kit: its own rotation shifts the boxes along the rotated axis.
      val bitmap = upright(cropRgba(image, left, top, width, height), rotation)
      val recognizing = System.nanoTime()
      val text = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)))
      statRecognitions++
      statRecognitionNs += System.nanoTime() - recognizing
      val uprightWidth = if (rotation == 90 || rotation == 270) height else width
      val uprightHeight = if (rotation == 90 || rotation == 270) width else height
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
      val accepted = frame.filter.select(lines, uprightWidth.toFloat(), uprightHeight.toFloat())
      if (revision != snapshot.revision) return
      val region = frame.region
      val winner = votes.add(accepted?.text, accepted?.confidence ?: 0f, now)
      val read = accepted?.let {
        TrackedOutline(it.text, textBoundsInView(it, region, uprightWidth.toFloat(), uprightHeight.toFloat()).corners(), sensorNs)
      }
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
   * Analysis thread: moves [outline] by the image motion since the previous frame and returns whether it moved. Flow
   * runs on every frame so each one is the next one's reference. Unknown motion leaves the outline where it is.
   */
  private fun followText(image: ImageProxy, sensorNs: Long, region: TextRegion): Boolean {
    if (flowFailed) return false
    val estimator = flow ?: try {
      FlowMotionEstimator().also { flow = it }
    } catch (error: Throwable) {
      flowFailed = true
      Log.e(LOG_TAG, "Text outline motion is unavailable", error)
      return false
    }
    val plane = image.planes[0]
    val crop = image.cropRect
    val estimate = try {
      estimator.update(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height,
        crop.left, crop.top, crop.right, crop.bottom, image.imageInfo.rotationDegrees, sensorNs,
        flowArea(region), fitTransform = true)
    } catch (error: Throwable) {
      flowFailed = true
      estimator.close()
      flow = null
      Log.e(LOG_TAG, "Text outline motion failed", error)
      return false
    } finally {
      plane.buffer.rewind()
    }
    val current = outline ?: return false
    val transform = estimate.transform?.takeIf { estimate.status == FlowMotionStatus.MEASURED && estimate.fromSensorNs == current.sensorNs }
    val moved = transform?.warp(current.corners) ?: return false
    outline = current.copy(corners = moved, sensorNs = sensorNs)
    return true
  }

  /**
   * Analysis thread, `focusLock`: the sharpness of [frame]'s region for [textFocus], posted to main with its sensor time
   * and tagged with the lens hold and region it was scored for.
   */
  private fun scoreRegion(image: ImageProxy, sensorNs: Long, frame: AnalysisSnapshot) {
    val crop = image.cropRect
    val box = regionInBuffer(frame.region, image.imageInfo.rotationDegrees, crop.left, crop.top, crop.width(), crop.height())
    val plane = image.planes[0]
    val scoring = System.nanoTime()
    val detail = regionDetail(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height,
      box[0], box[1], box[2], box[3], logQuarter = debugLogging)
    statScored++
    statScoreNs += System.nanoTime() - scoring
    statScorePx += (box[2] - box[0]).toLong() * (box[3] - box[1])
    mainHandler.post { onFocusFrame(frame.focusSession, frame.regionRevision, sensorNs, detail) }
  }

  /**
   * Analysis thread, logging only: every [STAT_FRAMES] analyzed frames, the rate they reached the analyzer, which a
   * recognition holding it lowers below the camera's, and what scoring and recognition cost, for tuning the lens hold.
   */
  private fun reportAnalysis(sensorNs: Long) {
    // A gap, as across a camera stop, starts a new report, so its rate covers frames that were running.
    if (sensorNs - statLastNs > STAT_GAP_NS) resetAnalysisReport()
    statLastNs = sensorNs
    if (statFrames++ == 0) statFirstNs = sensorNs
    if (statFrames <= STAT_FRAMES) return
    val spanMs = (sensorNs - statFirstNs) / 1e6
    log("analysis: %.1f frames/s; sharpness %.2f ms over %d px on %d frames; recognition %.1f ms x %d, %d settled".format(
      STAT_FRAMES * 1000 / spanMs, statScoreNs / 1e6 / statScored.coerceAtLeast(1),
      statScorePx / statScored.coerceAtLeast(1), statScored,
      statRecognitionNs / 1e6 / statRecognitions.coerceAtLeast(1), statRecognitions, statSettled))
    resetAnalysisReport()
  }

  private fun resetAnalysisReport() {
    statSettled = 0
    statFrames = 0
    statScored = 0
    statScoreNs = 0L
    statScorePx = 0L
    statRecognitions = 0
    statRecognitionNs = 0L
  }

  /**
   * Main thread: one frame for [textFocus], with the motion sensors' and the frame's own capture result's view of it. A
   * sample scored for another lens hold or an earlier region drops.
   */
  private fun onFocusFrame(focusSession: Long, regionRevision: Long, sensorNs: Long, detail: RegionDetail?) {
    val current = snapshot
    if (focusSession == 0L || focusSession != current.focusSession || regionRevision != current.regionRevision) return
    val focus = textFocus ?: return
    val state = frameStates.at(sensorNs)
    val history = gyroscope.history
    val from = sensorNs - QUIET_HAND_NS
    val turn = history.peakRate(from, sensorNs)
    val acceleration = gyroscope.acceleration.peak(from, sensorNs)
    // Without motion samples the hand never reads steady and the lens would stay where it opened.
    if (turn == null || acceleration == null) {
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
    val quiet = turn != null && acceleration != null && history.covers(from, sensorNs) &&
      turn <= QUIET_TURN_RATE && acceleration <= QUIET_ACCELERATION
    val angle = history.rotation(sensorNs - TURN_NS, sensorNs)?.let { r -> acos(((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0)) }
    val turned = angle != null && angle > TURN_RADIANS
    if (turned && !wasTurned) wakeRecognition()
    if (turned && !wasTurned && debugLogging) {
      log("text lens: turned %.1f deg over %d ms; turn rate %.2f rad/s, acceleration %.2f m/s2".format(
        Math.toDegrees(angle), TURN_NS / 1_000_000, turn ?: Double.NaN, acceleration ?: Double.NaN))
    }
    wasTurned = turned
    focus.observe(TextLensFocus.Frame(sensorNs, detail?.fine, quiet, turned, state?.manualFocus == true,
      state?.focusDiopters, state?.outOfFocus == true,
      state?.aeState == CaptureResult.CONTROL_AE_STATE_LOCKED || state?.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED,
      detail?.focus, detail?.coarse, detail?.quarterFocus, state?.requestedDiopters, state?.lensStateReported == true))
    updateAutofocus()
  }

  /**
   * Main thread: starts or stops [textFocus] to match `focusLock` on the bound camera. It needs a camera that can set its
   * lens distance and motion sensors on the camera's clock, which tell a steady hand and a turned phone apart; without
   * them the camera's continuous autofocus stays on the region. A new zoom starts it again, since zoom can switch the
   * camera to another lens with its own distances.
   */
  private fun syncTextFocus() {
    val want = focusLock == true && controller != null && cameraControl != null && !focusSensorsFailed
    val key = if (want) lensKey() else null
    if (want && key != null && key == focusKey && textFocus != null) return
    if (textFocus != null) stopTextFocus(bound = true)
    if (!want || key == null) return
    val info = controller?.cameraInfo ?: return
    val camera2 = try { Camera2CameraInfo.from(info) } catch (_: Exception) { return }
    val nearest = try { camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) } catch (_: Exception) { null }
    val realtime = try {
      camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    } catch (_: Exception) { false }
    if (nearest == null || nearest <= 0f || !realtime) {
      log("text focus lock unavailable: nearest=$nearest realtime=$realtime; camera autofocus")
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
    frameStates.clear()
    sensorlessSinceNs = 0L
    focusKey = key
    val focus = TextLensFocus(focusHost)
    textFocus = focus
    snapshot = snapshot.copy(focusSession = ++focusSessions)
    focus.start(nearest, lensMemory.getFloat(key, Float.NaN).takeUnless { it.isNaN() })
    aimFocus()
    updateAutofocus()
  }

  /** Main thread: ends [textFocus]; with the camera still [bound], it returns to continuous autofocus on the region. */
  private fun stopTextFocus(bound: Boolean) {
    if (textFocus == null) return
    textFocus = null
    focusKey = null
    snapshot = snapshot.copy(focusSession = 0L)
    mainHandler.removeCallbacks(resumeAfterTap)
    gyroscope.stop()
    frameStates.clear()
    if (!bound) return
    interop { clearCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK) }
    clearManualLens()
    aimFocus()
    updateAutofocus()
  }

  private fun clearManualLens() {
    interop {
      clearCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE)
      clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
    }
  }

  /** Main thread: applies Camera2 options to the bound camera, keeping its others; false without one or on failure. */
  private fun interop(block: androidx.camera.camera2.interop.CameraControlCamera2Interop.() -> Unit): Boolean {
    val control = cameraControl ?: return false
    return try {
      control.applyCamera2InteropAsync(block)
      true
    } catch (error: Exception) {
      log("camera option failed ${error.javaClass.simpleName}")
      false
    }
  }

  /** The bound camera's lens memory key at its confirmed zoom; null while a zoom request is pending. */
  private fun lensKey(): String? {
    val info = controller?.cameraInfo ?: return null
    val zoom = zoomRequests.settled ?: return null
    val id = try { Camera2CameraInfo.from(info).cameraId } catch (_: Exception) { return null }
    return "%s@%.1f".format(java.util.Locale.ROOT, id, zoom)
  }

  /**
   * Main thread: the `zoom` ratio, clamped to the camera's range, on the bound camera, asked once per ratio. Each request,
   * the first of a binding included, shows other text, so the reading clears and no frame is read or scored until the
   * request settles: the camera has answered it, and the view's own capture results have shown its ratio, from whose
   * first frame on frames count. The votes then restart and the lens hold restarts under the ratio's memory key. A
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

  /** Main thread: publishes the zoom's read admission, restarting the votes and reading, and syncs the lens hold. */
  private fun onZoomChanged() {
    snapshot = snapshot.copy(revision = snapshot.revision + 1, readFromNs = zoomRequests.readFromNs)
    clearReading()
    syncTextFocus()
  }

  /** How [bound]'s capture results show its zoom, as CameraX applies it; null when the camera cannot be read yet. */
  private fun readZoomMetadata(bound: LifecycleCameraController): ZoomMetadata? {
    val info = bound.cameraInfo ?: return null
    return try {
      val camera2 = Camera2CameraInfo.from(info)
      val ratioRange = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
      val active = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
      ZoomMetadata(ratioRange, active?.width() ?: 0)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Each frame's lens and exposure state for [textFocus], and the camera autofocus's state for `onAutofocusChange`; runs
   * on a camera thread. Results of an older binding drop.
   */
  private fun frameStateCallback(bindGeneration: Long) = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      if (bindGeneration != generation) return
      val afState = result.get(CaptureResult.CONTROL_AF_STATE)
      if (afState != cameraAfState) {
        cameraAfState = afState
        mainHandler.post { if (bindGeneration == generation) updateAutofocus() }
      }
      val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
      if (timestampNs > latestCaptureNs) latestCaptureNs = timestampNs
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
      if (snapshot.focusSession == 0L) return
      val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
      latestDiopters = diopters
      val manual = result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF
      frameStates.add(timestampNs, CameraFrameState(
        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
        rollingShutterSkewNs = 0L,
        outOfFocus = result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING,
        afState = afState,
        focusDiopters = diopters,
        manualFocus = manual,
        requestedDiopters = if (manual) request.get(CaptureRequest.LENS_FOCUS_DISTANCE) else null,
        lensStateReported = result.get(CaptureResult.LENS_STATE) != null,
        aeState = result.get(CaptureResult.CONTROL_AE_STATE),
      ))
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
      Log.e(LOG_TAG, "ML Kit text recognition is unavailable", error)
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

  /** Main thread: continuous autofocus and exposure aimed at the region, unlocked, until a tap replaces it. While
   * [textFocus] holds the lens, only exposure is metered there. */
  private fun aimFocus() {
    val control = cameraControl ?: return
    if (previewView.width <= 0 || previewView.height <= 0) return
    val region = snapshot.region
    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(
      ((region.x + region.width / 2) * previewView.width).toFloat(),
      ((region.y + region.height / 2) * previewView.height).toFloat(),
      maxOf(region.width, region.height).toFloat().coerceIn(0.05f, 0.5f),
    )
    val stage = textFocus?.stage
    val lensHeld = stage == TextLensFocus.Stage.HELD || stage == TextLensFocus.Stage.SEARCHING
    val flags = if (lensHeld) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    val action = FocusMeteringAction.Builder(point, flags).setLockingMode(0).disableAutoCancel().build()
    try { control.startFocusAndMetering(action) } catch (error: Exception) { log("region focus failed ${error.javaClass.simpleName}") }
  }

  private fun onTap(x: Float, y: Float) {
    val control = cameraControl ?: return
    if (tapToFocus == false) return
    // A tap's own autofocus takes the lens for its hold; the text lens resumes where it leaves it.
    textFocus?.let {
      it.suspend()
      clearManualLens()
      mainHandler.removeCallbacks(resumeAfterTap)
      mainHandler.postDelayed(resumeAfterTap, TAP_HOLD_MS)
      updateAutofocus()
    }
    val factory = previewView.meteringPointFactory
    val action = FocusMeteringAction.Builder(factory.createPoint(x, y, TAP_AREA), FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .disableAutoCancel().build()
    try { control.startFocusAndMetering(action) } catch (error: Exception) { log("tap focus failed ${error.javaClass.simpleName}") }
    if (textFocus == null) {
      mainHandler.removeCallbacks(reaimFocus)
      mainHandler.postDelayed(reaimFocus, TAP_HOLD_MS)
    }
  }

  private fun applyTorch() {
    val control = cameraControl ?: return
    try { control.enableTorch(torch == TextTorchMode.ON) } catch (_: Exception) { }
  }

  /**
   * Main thread: the focus state for `onAutofocusChange` and the built-in guide. While [textFocus] holds the lens it
   * reports its search as `scan`, a settled search as `locked`, and a hold no search has settled as `inactive`; the
   * camera's own autofocus, in a fallback, during a tap's hold or without `focusLock`, reports its capture results' state.
   */
  private fun updateAutofocus() {
    if (disposed) return
    val focus = textFocus
    val state = when {
      controller == null -> AutofocusState.INACTIVE
      focus?.stage == TextLensFocus.Stage.SEARCHING -> AutofocusState.SCAN
      focus?.stage == TextLensFocus.Stage.HELD -> if (focus.locked) AutofocusState.LOCKED else AutofocusState.INACTIVE
      else -> when (cameraAfState) {
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> AutofocusState.SCAN
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> AutofocusState.FOCUSED
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> AutofocusState.UNFOCUSED
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> AutofocusState.LOCKED
        else -> AutofocusState.INACTIVE
      }
    }
    overlay.focus(state)
    val searching = focus?.stage == TextLensFocus.Stage.SEARCHING
    // The focus a search found changes what the frames show, so the reading confirms it at the full rate.
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
    if (debugLogging) Log.d(LOG_TAG, message)
  }

  private companion object {
    const val DEFAULT_ANALYSIS_WIDTH = 1280
    const val DEFAULT_COLOR = 0xFF61E1CF.toInt()
    const val DEFAULT_RATE = 5
    const val MIN_CROP_PX = 16
    const val TAP_AREA = 1f / 6f
    const val TAP_HOLD_MS = 5_000L
    const val TEXT_LENS_PREFERENCES = "com.margelo.nitro.documentscanner.textlens"
    /** The hand holds steady when the gyroscope peaks under [QUIET_TURN_RATE] rad/s and the accelerometer under
     * [QUIET_ACCELERATION] m/s² over this span, as the scanner's focus cycle judges it. */
    const val QUIET_HAND_NS = 300_000_000L
    const val QUIET_TURN_RATE = .15
    const val QUIET_ACCELERATION = .5
    /** A turn of about 4 degrees over [TURN_NS] brings other text, so the lens hold checks its distance. A hand held
     * steady drifts about 2 degrees over that time, which is not a turn. */
    const val TURN_RADIANS = .07
    const val TURN_NS = 500_000_000L
    /** How long a running lens hold waits for motion samples before leaving focus to the camera's autofocus. */
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
 * must still match on main for a result to publish. Frames exposed before [readFromNs] are neither read nor scored,
 * which [Long.MAX_VALUE] makes all of them while a zoom settles. A nonzero [focusSession] has each frame's sharpness scored,
 * tagged with it and [regionRevision], which changes with [region] alone. A new [wake] returns recognition to its full
 * rate.
 */
private data class AnalysisSnapshot(
  val region: TextRegion, val filter: TextLineFilter, val revision: Long, val readFromNs: Long,
  val focusSession: Long, val regionRevision: Long, val wake: Long = 0L,
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
private data class TrackedOutline(val text: String, val corners: DocumentCorners, val sensorNs: Long)

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
  private val brackets = Path()
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
  /** How much wider the guide's stroke draws, fading from 1 to 0 after each step of a lens search. */
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

  /** A swell of the guide's stroke, as the lens moves one step of a search. */
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
    if (guide == TextGuide.BRACKETS) {
      val arm = minOf(14 * density, rect.width() / 4, rect.height() / 3)
      brackets.reset()
      brackets.moveTo(rect.left, rect.top + arm)
      brackets.lineTo(rect.left, rect.top)
      brackets.lineTo(rect.left + arm, rect.top)
      brackets.moveTo(rect.right - arm, rect.top)
      brackets.lineTo(rect.right, rect.top)
      brackets.lineTo(rect.right, rect.top + arm)
      brackets.moveTo(rect.right, rect.bottom - arm)
      brackets.lineTo(rect.right, rect.bottom)
      brackets.lineTo(rect.right - arm, rect.bottom)
      brackets.moveTo(rect.left + arm, rect.bottom)
      brackets.lineTo(rect.left, rect.bottom)
      brackets.lineTo(rect.left, rect.bottom - arm)
      canvas.drawPath(brackets, guideStroke)
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
