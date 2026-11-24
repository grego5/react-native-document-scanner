package com.margelo.nitro.documentscanner

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import com.margelo.nitro.documentscanner.processing.pagePoint
import com.margelo.nitro.documentscanner.processing.pickPrintSpot
import com.margelo.nitro.documentscanner.processing.EdgeWidth
import com.margelo.nitro.documentscanner.processing.regionEdgeWidth
import com.margelo.nitro.documentscanner.processing.regionSharpness
import com.margelo.nitro.documentscanner.processing.regionThumbnail
import com.margelo.nitro.documentscanner.processing.GyroHistory
import com.margelo.nitro.documentscanner.processing.GyroscopeSource
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "RNDocumentScannerText"

/**
 * Back-camera preview with a box marking [textRegion], for picking text in two steps. Live, the camera focuses on the
 * region by the scanner's focus rules, keeps the region's sharpest recent frames, and reads the sharpest of the first
 * few frames under each focus in the background with bundled ML Kit Latin text recognition; nothing shows over the
 * preview but the box. A capture returns the best frame with that read's words, or reads its frame then. The camera
 * runs while `active`, attached, visible, measured and permitted; CameraX follows the Activity's start and stop. Props,
 * callbacks and focus live on main; frames, reads and captures on the analysis thread.
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
      postLayout(this)
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
  private val overlay = TextGuideOverlay(themedContext, { postLayout(container) }) { box, label -> guideLaidOut(box, label) }
  override val view: View = container

  init {
    container.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    container.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
  }

  /** Main thread: the session as last set, and the one this view shows, which another view may hold instead. */
  private var requestedSession: HybridTextSessionSpec? = null
  private var attachedSession: HybridTextSession? = null
  private var checkedSession: HybridTextSessionSpec? = null
  override var session: HybridTextSessionSpec
    get() = requestedSession ?: throw InvalidInputException("TextCamera requires a session.")
    set(value) { requestedSession = value }
  override var active: Boolean? = null
  override var textRegion = TextRegion(0.1, 0.44, 0.8, 0.12)
  override var guide: TextGuide? = null
  override var tapToFocus: Boolean? = null
  override var torch: TextTorchMode? = null
  override var analysisFrameWidth: Double? = null
  override var focusLock: Boolean? = null
  override var zoom: Double? = null
  override var onGuideLayout: ((layout: TextGuideLayout) -> Unit)? = null
    set(value) { field = value; emittedGuideLayout = null; emitGuideLayout() }
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
  /** Logging only: the autofocus state last logged. */
  private var loggedAutofocus: AutofocusState? = null
  /** Main thread: what the scan does, which the guide shows and the attached session publishes. */
  private var status = TextScanStatus.OFF
  /** Main thread: the guide as last laid out, in view points, and as last reported. */
  private var guideLayout: TextGuideLayout? = null
  private var emittedGuideLayout: TextGuideLayout? = null
  /** Main thread: captures not yet resolved, which the guide shows as `capturing`. */
  private var capturesWaiting = 0
  /** Main thread: the region's print is blurred ([BlurWatch]). */
  private var blurred = false
  /** Main thread, `focusLock`: when the camera's own locking scan runs, or null while continuous autofocus alone
   * follows the region. */
  private var textCycle: TextFocusCycle? = null
  /** Main thread: the nearest distance the bound camera's lens focuses, in diopters; null when it reports none. */
  private var nearestDiopters: Float? = null
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
    override fun scannerLockFailed(owner: FocusOwner) { textCycle?.failed() }
    override val confirmsLockFrames get() = hasSingleShotAutofocus
  })
  private val zoomRequests = TextZoom()
  /** Logging only: when the pending zoom was requested, on the sensor clock. */
  private var zoomRequestedNs = 0L
  /** Whether the previous focus frame saw a turn, so each turn is logged once. */
  private var wasTurned = false
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
  /** Main thread: what holds focus now, a settled lock's generation or continuous autofocus, and the focus counter
   * that numbers each new one for the analyzer. */
  private var focusHolder: Any? = null
  private var focusCount = 0L
  /** The newest capture result's sensor time; written on a camera thread. */
  @Volatile private var latestCaptureNs = 0L
  /** Analysis thread, `focusLock`: where the next text lock aims, picked on each analysed frame; null before one, and
   * for a new region, which locks at its centre. */
  @Volatile private var lockAim: TextLockAim? = null
  /** The newest capture result's zoom ratio, and whether any result of this binding showed one; camera thread. */
  @Volatile private var latestZoomRatio: Float? = null
  @Volatile private var zoomMeasured = false
  /** The zoom request whose first capture result at its ratio the camera thread watches for, then clears. */
  private val zoomWatch = AtomicReference<TextZoom.Request?>(null)
  /** The newest capture result's autofocus state, for the camera's own autofocus; written on a camera thread. */
  @Volatile private var cameraAfState: Int? = null
  private val streamObserver = Observer<PreviewView.StreamState> { updateStatus() }
  /** Set when the bound camera closed or failed to open; captures stay unavailable until the first fresh frame. */
  private var interrupted = false
  /** The bound camera's state, observed for the current start only. */
  private var observedCameraState: LiveData<CameraState>? = null
  private var cameraStateObserver: Observer<CameraState>? = null

  /** A capture waiting for a read frame until [deadline], in uptime milliseconds; [done] takes the capture or the failure, once,
   * and [settled] follows it. */
  private class PendingCapture(val deadline: Long, private val done: (TextCapture?, ScannerErrorCode?) -> Unit,
    private val settled: () -> Unit) {
    fun resolve(capture: TextCapture) {
      done(capture, null)
      settled()
    }

    fun fail(code: ScannerErrorCode) {
      done(null, code)
      settled()
    }
  }

  /** What this view does for its session. */
  private val sessionCamera = object : HybridTextSession.Camera {
    override fun capture(timeout: Long, done: (TextCapture?, ScannerErrorCode?) -> Unit) {
      mainHandler.post { startCapture(timeout, done) }
    }
  }
  /** A read frame: [crop] of [region], exposed at [sensorNs] in [focus], 0 for none, the [lines] recognized in it, and
   * its [edges] width in pixels, null when unmeasured. */
  private class TextShot(val crop: LumaCrop, val lines: List<RecognizedLine>, val focus: Long, val region: TextRegion,
    val sharpness: Double, val sensorNs: Long, val edges: Double?) {
    /** Text found in a frame sharp enough to trust what it says. */
    val readable get() = lines.isNotEmpty() && BlurWatch.sharp(edges)
  }

  // Analysis thread, except the volatile hand-offs from main.
  private val executor = Executors.newSingleThreadExecutor()
  /** Created on first use on the analysis thread; a recognizer that fails to start reports once and reads nothing. */
  private var recognizer: TextRecognizer? = null
  private var recognizerFailed = false
  /** Analysis thread: the sharpest recent crops of the region, which a capture takes from. */
  private val frames = FrameRing<LumaCrop>()
  /** Analysis thread: when the current focus's background read is due. */
  private val focusedRead = FocusedRead()
  /** Analysis thread: the current focus's background read, which a capture returns without reading again. */
  private var shot: TextShot? = null
  /** Analysis thread: captures waiting for a read frame. */
  private val captures = mutableListOf<PendingCapture>()
  /** Analysis thread: whether the waiting captures requested a lock of their own, and the focus whose read they
   * turned down for finding no text, so the lock's read, whatever it finds, is the one returned. */
  private var captureLockRequested = false
  private var captureRejected: Long? = null
  /** Analysis thread: tells when the view moves or changes, which the motion sensors cannot see. */
  private val viewWatch = ViewWatch()
  /** Analysis thread, logging only: frames since the edge width was last logged. */
  private var edgeFrames = 0
  /** Analysis thread: whether the region's print is blurred, by its edge width. */
  private val blurWatch = BlurWatch()
  /** Camera thread: continuous autofocus is sweeping the lens, which blurs the view on its way to focus. */
  @Volatile private var passiveScanning = false
  /** Analysis thread: the newest frame's sensor time, which a shot's age is measured against. */
  private var newestNs = 0L
  /** Camera thread: the lens's latest focus distance in diopters, which tells a lock at its near limit. */
  @Volatile private var lensDiopters: Float? = null
  /** Written by main only, as a whole; the analyzer takes one per frame. */
  @Volatile private var snapshot = AnalysisSnapshot(clampedRegion(textRegion), 0L, Long.MIN_VALUE, 0L, 0L)
  /** Main sets it from the camera state; frames still queued while the camera is not open do not count. */
  @Volatile private var cameraOpen = false
  /** Bumped by each unavailable transition, so a first-frame report queued before one cannot restore readiness. */
  @Volatile private var availability = 0L
  /** Cleared by each unavailable transition and on reopening; the next counted frame reports itself to main. */
  private val frameSeen = AtomicBoolean(false)
  /** Analysis thread: the snapshot revision the kept frames and the shot belong to, and its region, which a capture
   * read from them shows. */
  private var countedRevision = -1L
  private var keptRegion = snapshot.region
  // Analysis thread, logging only: what one report's frames cost and how many reached the analyzer.
  private var statFrames = 0
  private var statFirstNs = 0L
  private var statLastNs = 0L
  private var statRecognitions = 0
  private var statRecognitionNs = 0L
  /** Logging only: capture results counted on a camera thread, and the count when the analysis report began, so the
   * report shows how many camera frames never reached the analyzer, as while a recognition holds it. */
  private val capturedFrames = java.util.concurrent.atomic.AtomicLong()
  private var statCapturedFrom = 0L
  /** Analysis thread, logging only: the longest gap between analyzed frames in the report. */
  private var statLongestGapNs = 0L

  /** Main thread: lays the children out on the next frame of [host], the container, once however often requested;
   * it is passed in because the container requests it while it is still being built. */
  private fun postLayout(host: View) {
    if (layoutPosted) return
    layoutPosted = true
    host.post { layoutPosted = false; layoutChildren() }
  }

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
    syncSession()
    val region = clampedRegion(textRegion)
    if (region != snapshot.region) {
      snapshot = snapshot.copy(region = region, revision = snapshot.revision + 1, regionRevision = snapshot.regionRevision + 1)
      // Other text, maybe at another distance: continuous autofocus follows it again.
      val release = textCycle?.let {
        it.reset()
        focus.releaseScannerLock()
      } ?: FocusController.Release.NONE
      // A released lock's cancellation re-aims continuous autofocus at the new region once it completes.
      if (release == FocusController.Release.NONE) aimFocus()
      updateAutofocus()
    }
    overlay.update(region, guide)
    val width = requestedWidth()
    if (controller != null && width != boundWidth) stopCamera("analysis width")
    if (active == false) stopCamera("inactive") else startCamera()
    applyTorch()
    applyZoom()
    syncTextFocus()
  }

  /** Main thread: the session's capture, by the workflow of [serveCaptures]. */
  private fun startCapture(timeout: Long, done: (TextCapture?, ScannerErrorCode?) -> Unit) {
    if (controller == null || disposed || interrupted) {
      done(null, ScannerErrorCode.CAMERA_UNAVAILABLE)
      return
    }
    val pending = PendingCapture(SystemClock.uptimeMillis() + timeout, done) {
      mainHandler.post { capturesWaiting--; updateStatus() }
    }
    val queued = runCatching { executor.execute { captures += pending; serveCurrentCaptures() } }.isSuccess
    if (!queued) {
      done(null, ScannerErrorCode.CAMERA_UNAVAILABLE)
      return
    }
    // Its settlement's decrement is posted behind this block, so it always follows.
    capturesWaiting++
    updateStatus()
    // Past its deadline a capture reads the best frame so far, even when no frame arrives to serve it.
    mainHandler.postDelayed({ runCatching { executor.execute { serveCurrentCaptures() } } }, timeout)
  }

  /** Main thread: shows [requestedSession] when it changed; one another view shows is refused once. */
  private fun syncSession() {
    val next = requestedSession
    if (next === checkedSession) return
    checkedSession = next
    attachedSession?.let { previous ->
      previous.detach(sessionCamera)
      // Its waiting captures, all of them its own until the next session asks, settle now from what the camera kept
      // while it showed them, rather than from frames of the next session's scan.
      runCatching { executor.execute { settleCaptures() } }
    }
    attachedSession = null
    val session = next as? HybridTextSession ?: return
    if (!session.attach(sessionCamera)) {
      log("text session shown by another camera")
      onError?.invoke(ScannerFailure(ScannerErrorCode.INPUT_INVALID))
      return
    }
    attachedSession = session
    session.show(sessionCamera, status)
  }

  override fun onDropView() {
    disposed = true
    stopCamera("dropped")
    attachedSession?.detach(sessionCamera)
    attachedSession = null
    focus.close()
    removePermissionListener()
    gyroscope.stop()
    // stopCamera queued the settlement of waiting captures and the frames' release ahead of this.
    executor.execute { recognizer?.close() }
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
    updateStatus()
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
          // YUV, read by its luma plane alone: OCR, sharpness and the lock's aim need no colour, and only the region is read.
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
      updateStatus()
    }, ContextCompat.getMainExecutor(themedContext))
  }

  private fun failStart(error: Throwable) {
    ScannerLog.e(LOG_TAG, "Text camera binding failed", error)
    updateStatus()
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
   * the activity stopping, and a recoverable error while opening make captures unavailable; CameraX reopens the camera
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
      // Opening, without an error, is still startup, which captures wait out; a repeat while unavailable is a duplicate.
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
    updateStatus()
  }

  /**
   * Main: the one transition that makes captures unavailable, for a stop, a camera error or the camera closing. Frames
   * already queued no longer count, and waiting captures read the sharpest kept frame before the kept frames go.
   */
  private fun makeUnavailable() {
    availability++
    snapshot = snapshot.copy(revision = snapshot.revision + 1)
    frameSeen.set(false)
    runCatching { executor.execute { settleCaptures(); clearFrames() } }
    updateStatus()
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
    hasSingleShotAutofocus = false
    zoomRequests.reset()
    zoomWatch.set(null)
    zoomMetadata = null
    zoomMeasured = false
    latestZoomRatio = null
    focusSensorsFailed = false
    cameraAfState = null
    lensDiopters = null
    passiveScanning = false
    onBlurred(false, null)
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

  /**
   * Analysis thread: keeps the region's sharpest recent frames, each tagged with the focus it was exposed in, and
   * once the current focus has [FocusedRead.SAMPLES] frames reads the sharpest of them in the background, so a
   * capture returns at once. Frames of a lock's scan, which sweeps the lens, are skipped. Then serves waiting captures.
   */
  private fun analyze(image: ImageProxy, bindGeneration: Long) {
    try {
      // A frame delivered while the camera is not open belongs to a closed stream.
      if (!cameraOpen) return
      if (!frameSeen.getAndSet(true)) {
        val token = availability
        mainHandler.post { onFreshFrame(bindGeneration, token) }
      }
      val frame = snapshot
      if (keepFor(frame)) {
        log("frames ${image.width}x${image.height} crop=${image.cropRect} rotation=${image.imageInfo.rotationDegrees}")
      }
      val sensorNs = image.imageInfo.timestamp
      // A frame exposed before the zoom requested applied shows the view before it.
      if (sensorNs < frame.readFromNs) return
      if (debugLogging) reportAnalysis(sensorNs)
      val region = frame.region
      val box = regionBox(image, region)
      val plane = image.planes[0]
      val thumb = box?.let { regionThumbnail(plane.buffer, plane.rowStride, plane.pixelStride, it[0], it[1], it[2], it[3]) }
      // A page moved under a steady phone is motion too; a lock's scan sweeping the lens blurs the view, which is not.
      val still = viewWatch.still(thumb) || frame.scanning
      // Continuous autofocus sweeping the lens blurs the view on its way to focus, which says nothing of the distance,
      // and so does a lock's scan.
      val sweeping = frame.scanning || passiveScanning
      val edges = if (box == null || frame.scanning) null
      else regionEdgeWidth(plane.buffer, plane.rowStride, plane.pixelStride, box[0], box[1], box[2], box[3])
      if (frame.focusSession != 0L) {
        val judged = edges?.median.takeUnless { sweeping }
        mainHandler.post { onFocusFrame(frame.focusSession, frame.regionRevision, sensorNs, still, judged) }
      }
      if (frame.scanning) return
      if (frame.focusSession != 0L) lockAim = textureAim(image, region)
      // Frames queued while a focus began were exposed before it.
      val inFocus = if (sensorNs >= frame.focusFromNs) frame.focus else 0L
      // A read belongs to the focus it was taken in: once that focus ends, a capture waits for the next one's.
      if (shot?.let { it.focus != frame.focus } == true) shot = null
      if (box == null) return
      val score = regionSharpness(plane.buffer, plane.rowStride, plane.pixelStride, box[0], box[1], box[2], box[3]) ?: 0.0
      if (debugLogging && ++edgeFrames >= EDGE_LOG_FRAMES) {
        edgeFrames = 0
        log("text edges ${describe(edges)}, sharpness ${"%.0f".format(score)}${lensNote()}")
      }
      if (!passiveScanning) blurWatch.observe(edges?.median)?.let { now ->
        mainHandler.post { if (bindGeneration == generation) onBlurred(now, edges?.median) }
      }
      newestNs = sensorNs
      if (frames.wants(sensorNs, score, inFocus)) frames.add(FrameRing.Entry(sensorNs, score, inFocus, cropUpright(image, box)))
      if (viewWatch.changed(inFocus, thumb, score)) {
        // The page moved, or the lens no longer suits the view, as after moving nearer or farther: the read no longer
        // shows it. Whether the lens refocuses is the print's blur to tell.
        if (debugLogging) log("text view changed under the focus: sharpness %.0f, read at %.0f".format(score, shot?.sharpness ?: 0.0))
        shot = null
      }
      if (focusedRead.sampled(inFocus)) readFocused(inFocus, region, thumb)
    } catch (error: Throwable) {
      log("text frame failed ${error.javaClass.simpleName}")
    } finally {
      image.close()
      if (captures.isNotEmpty()) serveCaptures()
    }
  }

  /** Analysis thread: reads the sharpest kept frame of [focus] as its shot, which later frames, [thumb] showing the
   * view now, are compared with; nothing without such a frame or a recognizer. */
  private fun readFocused(focus: Long, region: TextRegion, thumb: FloatArray?) {
    val entry = frames.sharpest(focus) ?: return
    val lines = recognize(entry.image) ?: return
    val edges = edgesOf(entry.image)
    if (debugLogging) {
      log("text focused read: ${lines.size} lines, sharpness ${"%.0f".format(entry.sharpness)}, edges " +
        "${describe(edges)}${lensNote()}")
    }
    viewWatch.reference(focus, thumb, entry.sharpness)
    shot = TextShot(entry.image, lines, focus, region, entry.sharpness, entry.sensorNs, edges?.median)
  }

  /** Analysis thread: forgets what was kept for an earlier revision of [frame], as for another region or zoom; true
   * when it did. */
  private fun keepFor(frame: AnalysisSnapshot): Boolean {
    if (frame.revision == countedRevision) return false
    clearFrames()
    lockAim = null
    countedRevision = frame.revision
    keptRegion = frame.region
    return true
  }

  /** Analysis thread: serves waiting captures from what is kept for the current revision only, so a press right after
   * a new region or zoom, before a frame of it arrives, never reads the old crop. */
  private fun serveCurrentCaptures() {
    keepFor(snapshot)
    serveCaptures()
  }

  /** Analysis thread: forgets the kept frames, the shot and the read count, as for a new region or binding. */
  private fun clearFrames() {
    frames.clear()
    focusedRead.reset()
    viewWatch.reset()
    shot = null
    blurWatch.reset()?.let { now -> mainHandler.post { onBlurred(now, null) } }
  }

  /** Analysis thread: the lock's aim: the region's most textured third ([pickPrintSpot]), which prefers the centre
   * when the region is blank; null when it cannot be sampled, and the lock then aims at the region's centre. */
  private fun textureAim(image: ImageProxy, region: TextRegion): TextLockAim? {
    val plane = image.planes[0]
    val crop = image.cropRect
    val corners = region.corners()
    val spot = try {
      pickPrintSpot(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.right,
        crop.bottom, image.imageInfo.rotationDegrees, corners)
    } finally {
      plane.buffer.rewind()
    } ?: return null
    val at = pagePoint(corners, spot.x, spot.y)
    return TextLockAim(at.x, at.y, maxOf(region.width, region.height) / 3)
  }

  /** Analysis thread: [region]'s box in [image]'s buffer, clamped to it; null when too small to read. */
  private fun regionBox(image: ImageProxy, region: TextRegion): IntArray? {
    val crop = image.cropRect
    val box = regionInBuffer(region, image.imageInfo.rotationDegrees, crop.left, crop.top, crop.width(), crop.height())
    val left = box[0].coerceIn(0, image.width)
    val top = box[1].coerceIn(0, image.height)
    val right = box[2].coerceIn(left, image.width)
    val bottom = box[3].coerceIn(top, image.height)
    if (right - left < MIN_CROP_PX || bottom - top < MIN_CROP_PX) return null
    return intArrayOf(left, top, right, bottom)
  }

  /** Analysis thread: [box] of [image]'s luma plane, upright: ML Kit's own rotation shifts the boxes along the rotated
   * axis. Only the region's rows are read. */
  private fun cropUpright(image: ImageProxy, box: IntArray): LumaCrop {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val width = box[2] - box[0]
    val height = box[3] - box[1]
    val bytes = ByteArray(width * height)
    for (row in 0 until height) {
      val start = (box[1] + row) * plane.rowStride + box[0] * plane.pixelStride
      if (plane.pixelStride == 1) {
        buffer.position(start)
        buffer.get(bytes, row * width, width)
      } else {
        for (x in 0 until width) bytes[row * width + x] = buffer.get(start + x * plane.pixelStride)
      }
    }
    buffer.rewind()
    return uprightLuma(bytes, width, height, image.imageInfo.rotationDegrees)
  }

  /** [crop] as an opaque grey bitmap, which ML Kit and the JPEG encoder take. */
  private fun bitmapOf(crop: LumaCrop): Bitmap {
    val pixels = IntArray(crop.bytes.size) { val g = crop.bytes[it].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    return Bitmap.createBitmap(pixels, crop.width, crop.height, Bitmap.Config.ARGB_8888)
  }

  /**
   * Analysis thread: the capture workflow. A press first looks at what it already has. The current focus's read
   * serves it at once when it is readable, text found with edges no wider than [BlurWatch.CUT], is under a second
   * old, and no kept frame of the last second is clearly sharper. Otherwise the sharpest kept frame is read now when
   * its edges are that sharp, which serves it when it finds text. Without either, the press locks the lens once, from
   * where it is, and waits for a readable read of that lock or of the focus cycle's next; a scan already running is
   * that lock. Past a capture's deadline the sharpest kept frame is read then, sharp or not; without a frame the
   * capture fails.
   */
  private fun serveCaptures() {
    if (captures.isEmpty()) {
      resetCaptureLock()
      return
    }
    val now = SystemClock.uptimeMillis()
    var how = "the focus's read"
    var result: TextShot? = null
    if (!captureLockRequested) {
      result = firstLook()
      if (result == null) {
        captureLockRequested = true
        captureRejected = snapshot.focus
        if (debugLogging) log("text capture: nothing readable kept; locking")
        mainHandler.post { lockForCapture() }
      } else if (result !== shot) {
        how = "the sharpest kept frame, read now"
      }
    } else {
      result = lockRead()
      how = "the capture's lock"
    }
    if (result == null) {
      if (captures.none { now >= it.deadline }) return
      result = sharpestRead()
      if (result == null) {
        val expired = captures.filter { now >= it.deadline }
        captures.removeAll(expired)
        val code = if (frames.sharpest() == null) ScannerErrorCode.CAPTURE_NOT_READY else ScannerErrorCode.CAPTURE_FAILED
        expired.forEach { it.fail(code) }
        if (captures.isEmpty()) resetCaptureLock()
        return
      }
      how = "the sharpest kept frame, past the deadline"
    }
    deliver(result, how)
  }

  /** Analysis thread: the camera stopped, or the session they came from left it, so waiting captures take what is
   * kept now, as a press past its deadline does: the capture's lock's read when it came, else the sharpest kept frame,
   * sharp or not. Without a frame they fail with `camera/unavailable`, and with `capture/failed` when it reads
   * nothing. */
  private fun settleCaptures() {
    if (captures.isEmpty()) return
    val result = lockRead() ?: sharpestRead()
    if (result == null) {
      failCaptures(if (frames.sharpest() == null) ScannerErrorCode.CAMERA_UNAVAILABLE else ScannerErrorCode.CAPTURE_FAILED)
      return
    }
    deliver(result, "what was kept, on stop")
  }

  /** Analysis thread: the current focus's read, when readable and not the one the waiting captures turned down. */
  private fun lockRead(): TextShot? = shot?.takeIf { it.focus != captureRejected && it.focus != 0L && it.readable }

  /** Analysis thread: the sharpest kept frame, read now; null without a frame, or when the recognizer reads nothing. */
  private fun sharpestRead(): TextShot? {
    val entry = frames.sharpest() ?: return null
    val lines = runCatching { recognize(entry.image) }.getOrNull() ?: return null
    return TextShot(entry.image, lines, entry.focus, keptRegion, entry.sharpness, entry.sensorNs, edgesOf(entry.image)?.median)
  }

  /** Analysis thread: resolves every waiting capture with [result], got by [how], encoded once. */
  private fun deliver(result: TextShot, how: String) {
    val waiting = captures.toList()
    captures.clear()
    resetCaptureLock()
    val crop = result.crop
    val bitmap = bitmapOf(crop)
    val uri = try { captureUri(bitmap) } finally { bitmap.recycle() }
    if (debugLogging) log("text capture: ${result.lines.size} lines from $how, sharpness %.0f, ${if (result.focus != 0L) "in focus" else "out of focus"}, %d ms old".format(
      result.sharpness, (newestNs - result.sensorNs) / 1_000_000) + ", edges ${describe(edgesOf(crop))}")
    if (uri == null) {
      waiting.forEach { it.fail(ScannerErrorCode.CAPTURE_FAILED) }
      return
    }
    val capture = TextCapture(uri, crop.width.toDouble(), crop.height.toDouble(), result.region,
      captureWords(result.lines, crop.width.toFloat(), crop.height.toFloat()))
    waiting.forEach { it.resolve(capture) }
  }

  /** Analysis thread: no capture waits, so the next press looks first again. */
  private fun resetCaptureLock() {
    captureLockRequested = false
    captureRejected = null
  }

  /** Analysis thread: a press's first look: the current focus's read when it still stands, else the sharpest kept
   * frame read now when it is sharp and finds text; null when neither does, or while a lock's scan runs, whose read is
   * coming. */
  private fun firstLook(): TextShot? {
    if (snapshot.scanning) return null
    val best = frames.sharpest() ?: return null
    val current = shot?.takeIf { it.focus != 0L }
    if (current != null && current.readable && newestNs - current.sensorNs <= FrameRing.KEEP_NS &&
      best.sharpness <= current.sharpness * SHARPER) return current
    if (current != null && best.sensorNs == current.sensorNs) return current.takeIf { it.readable }
    val edges = edgesOf(best.image)?.median
    if (!BlurWatch.sharp(edges)) return null
    val lines = runCatching { recognize(best.image) }.getOrNull()
    if (lines.isNullOrEmpty()) return null
    return TextShot(best.image, lines, best.focus, keptRegion, best.sharpness, best.sensorNs, edges)
  }

  /** Analysis thread: resolves every waiting capture with [code]. */
  private fun failCaptures(code: ScannerErrorCode) {
    val waiting = captures.toList()
    captures.clear()
    resetCaptureLock()
    waiting.forEach { it.fail(code) }
  }

  /** Analysis thread: [bitmap] as a JPEG data URI, for the selection's preview: nothing is written, so nothing needs
   * cleaning up; null when it cannot be encoded. */
  private fun captureUri(bitmap: Bitmap): String? {
    val out = java.io.ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, CAPTURE_QUALITY, out)) return null
    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
  }

  /** Analysis thread: the lines recognized in [crop], read through a grey bitmap released once read; null when the
   * recognizer is unavailable. */
  private fun recognize(crop: LumaCrop): List<RecognizedLine>? {
    val client = recognizer() ?: return null
    val bitmap = bitmapOf(crop)
    val recognizing = System.nanoTime()
    val text = try { Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0))) } finally { bitmap.recycle() }
    statRecognitions++
    statRecognitionNs += System.nanoTime() - recognizing
    return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
      val bounds = line.boundingBox ?: return@mapNotNull null
      // Corner points follow the text's slant, where the bounding boxes only frame it upright.
      val words = line.elements.mapNotNull { element ->
        element.boundingBox?.let { RecognizedWord(element.text, it.left.toFloat(), it.right.toFloat(), quad(element.cornerPoints)) }
      }.ifEmpty { listOf(RecognizedWord(line.text, bounds.left.toFloat(), bounds.right.toFloat(), quad(line.cornerPoints))) }
      RecognizedLine(words, bounds.top.toFloat(), bounds.bottom.toFloat(), line.confidence, quad(line.cornerPoints))
    }
  }

  /** The recognizer's four corner points as x, y pairs; null unless it gave all four. */
  private fun quad(points: Array<android.graphics.Point>?): FloatArray? =
    points?.takeIf { it.size == 4 }?.let { p -> FloatArray(8) { i -> (if (i % 2 == 0) p[i / 2].x else p[i / 2].y).toFloat() } }

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
    log("analysis: %.1f frames/s, %d of %d camera frames, longest gap %.0f ms; recognition %.1f ms x %d".format(
      STAT_FRAMES * 1000 / spanMs, statFrames, captured, statLongestGapNs / 1e6,
      statRecognitionNs / 1e6 / statRecognitions.coerceAtLeast(1), statRecognitions))
    resetAnalysisReport()
  }

  private fun resetAnalysisReport() {
    statCapturedFrom = capturedFrames.get()
    statLongestGapNs = 0L
    statFrames = 0
    statRecognitions = 0
    statRecognitionNs = 0L
  }

  /**
   * Main thread: one frame for [textCycle], with the motion sensors' view of it and the region's [edges] width, null
   * when unmeasured or while the lens sweeps. A frame posted for another run or an earlier region drops, and a tap's
   * focus holds the lens for its own time.
   */
  private fun onFocusFrame(focusSession: Long, regionRevision: Long, sensorNs: Long, viewStill: Boolean, edges: Double?) {
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
    // A steady phone over a page being moved is not a steady hold.
    val quiet = motion?.quiet == true && viewStill
    val turned = hand.turned(sensorNs)
    if (turned && !wasTurned && debugLogging) {
      log("text lens: turned %.1f deg over %d ms; turn rate %.2f rad/s, acceleration %.2f m/s2".format(
        Math.toDegrees(hand.turnAngle(sensorNs) ?: 0.0), FocusRules.TURN_NS / 1_000_000, motion?.turn ?: Double.NaN,
        motion?.acceleration ?: Double.NaN))
    }
    wasTurned = turned
    val held = focus.snapshot
    if (held.owner == FocusOwner.TAP) return
    val lens = when {
      held.owner == null -> TextFocusCycle.Lens.FREE
      held.locked == null -> TextFocusCycle.Lens.SCANNING
      else -> TextFocusCycle.Lens.LOCKED
    }
    // The lens distance pins at its near limit for anything nearer, which is exactly when a blurred lock stays.
    val near = nearestDiopters
    val atNearLimit = near != null && (lensDiopters ?: 0f) >= near - TextFocusCycle.NEAR_LIMIT_DIOPTERS
    cycle.motion(sensorNs, quiet)
    follow(cycle.next(TextFocusCycle.Frame(sensorNs, quiet, turned, lens, edges, atNearLimit)))
  }

  /** Main thread: carries out the focus cycle's [step]. */
  private fun follow(step: TextFocusCycle.Step) {
    when (step) {
      TextFocusCycle.Step.None -> Unit
      is TextFocusCycle.Step.Release -> {
        // Continuous autofocus follows the region again once the cancellation completes.
        log("text focus lock released: ${step.why}${lensNote()}")
        focus.releaseScannerLock()
      }
      is TextFocusCycle.Step.Lock -> lockText(step.why)
    }
  }

  /** Main thread: a capture without a usable read locks the lens, from where it is when a lock holds it; a scan already
   * running is the one it waits for, and a tap's hold is left alone. Only the focus cycle locks, since it alone
   * releases a lock again; without it continuous autofocus's next focus serves the capture. */
  private fun lockForCapture() {
    if (disposed || controller == null || textCycle == null) return
    val held = focus.snapshot
    if (held.owner == FocusOwner.TAP || (held.owner != null && held.locked == null)) return
    lockText("a capture")
  }

  /** Main thread: the camera's own locking scan at [lockAim], focusing and metering there: the region's most textured
   * spot, else its centre. A settled lock scans again without a release, so the lens starts the sweep where it was;
   * anything else holding the lens is released first. */
  private fun lockText(why: String) {
    val camera = boundCamera ?: return
    val aim = lockAim
    val point = aim?.let(::aimPoint) ?: regionPoint() ?: return
    val accepted = {
      textCycle?.started()
      log("text focus lock at ${if (aim != null) "texture" else "the centre"} for $why")
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
    nearestDiopters = try {
      camera2.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0f }
    } catch (_: Exception) { null }
    textCycle = TextFocusCycle()
    snapshot = snapshot.copy(focusSession = ++focusSessions)
    aimFocus()
    updateAutofocus()
  }

  /** Main thread: ends [textCycle]; with the camera still [bound], continuous autofocus follows the region again. */
  private fun stopTextFocus(bound: Boolean) {
    if (textCycle == null) return
    textCycle = null
    snapshot = snapshot.copy(focusSession = 0L, scanning = false)
    gyroscope.stop()
    if (bound && focus.releaseScannerLock() == FocusController.Release.NONE) aimFocus()
    updateAutofocus()
  }

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

  /** Main thread: publishes the zoom's read admission, restarting the kept frames, and syncs the focus lock. */
  private fun onZoomChanged() {
    snapshot = snapshot.copy(revision = snapshot.revision + 1, readFromNs = zoomRequests.readFromNs)
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
   * Each frame's autofocus state for the status and the background read, a lock's trigger and lock frames,
   * and the zoom it shows; runs on a camera thread. Results of an older binding drop.
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
      lensDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
      passiveScanning = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
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

  /** Main thread: [aim] as a metering point; null before the preview is measured. */
  private fun aimPoint(aim: TextLockAim): MeteringPoint? {
    if (previewView.width <= 0 || previewView.height <= 0) return null
    return previewView.meteringPointFactory.createPoint((aim.x * previewView.width).toFloat(),
      (aim.y * previewView.height).toFloat(), aim.size.toFloat().coerceIn(0.05f, 0.5f))
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
   * Main thread: the focus the frames are read in, and whether a lock's or a tap's scan runs, which the status shows as
   * `focusing`. The autofocus state, logged on change, reads a lock or a tap as `scan` while its scan runs and `locked`
   * once confirmed, and continuous autofocus by its capture results' state.
   * Frames count as in focus under a settled lock, the cycle's or a tap's, and while continuous autofocus reports
   * focus; at close range it reports focus it has not reached, which the print's edge width tells, so a read counts
   * only when sharp ([TextShot.readable]). Each new focus gets a new number, so its frames are ranked and read apart.
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
    val scanning = controller != null && held.owner != null && held.locked == null
    val holder: Any? = when {
      controller == null -> null
      held.owner != null && held.locked == true -> held.generation
      held.owner == null && state == AutofocusState.FOCUSED -> ContinuousFocus
      else -> null
    }
    if (holder != focusHolder) {
      focusHolder = holder
      snapshot = snapshot.copy(focus = if (holder == null) 0L else ++focusCount, focusFromNs = latestCaptureNs + 1)
    }
    if (scanning != snapshot.scanning) snapshot = snapshot.copy(scanning = scanning)
    updateStatus()
    if (state == loggedAutofocus) return
    loggedAutofocus = state
    log("text autofocus $state")
  }

  /** Logging only: [edges] as a log fragment. */
  private fun describe(edges: EdgeWidth?): String = edges?.let {
    "%.2f px (rows %.2f, columns %.2f, %d edges)".format(it.median, it.rows, it.columns, it.edges)
  } ?: "unmeasured"

  /** Logging only: the edge width of an upright crop. */
  private fun edgesOf(crop: LumaCrop): EdgeWidth? =
    regionEdgeWidth(java.nio.ByteBuffer.wrap(crop.bytes), crop.width, 1, 0, 0, crop.width, crop.height)

  /** Logging only: where the lens last focused. */
  private fun lensNote(): String = lensDiopters?.let {
    if (it > 0f) ", lens %.2f D (%.1f cm)".format(it, 100 / it) else ", lens at infinity"
  }.orEmpty()

  /** Main thread: the region's print became blurred, at [edges] pixels, or sharp again, which the status shows, so
   * the user moves back or holds still. */
  private fun onBlurred(now: Boolean, edges: Double?) {
    if (now == blurred) return
    blurred = now
    log("text print ${if (now) "blurred" else "sharp"}${edges?.let { ": edges %.2f px".format(it) }.orEmpty()}")
    updateStatus()
  }

  /**
   * Main thread: what the scan does, `TextScanStatus`, highest first: the camera suspended or starting, a waiting
   * capture, blurred print, a lock's or a tap's scan. The guide shows it, and the attached session publishes it.
   */
  private fun updateStatus() {
    if (disposed) return
    val next = when {
      controller == null -> if (startInFlight) TextScanStatus.STARTING else TextScanStatus.OFF
      interrupted || previewView.previewStreamState.value != PreviewView.StreamState.STREAMING -> TextScanStatus.STARTING
      capturesWaiting > 0 -> TextScanStatus.CAPTURING
      blurred -> TextScanStatus.BLURRED
      snapshot.scanning -> TextScanStatus.FOCUSING
      else -> TextScanStatus.READY
    }
    overlay.show(next)
    if (next == status) return
    status = next
    attachedSession?.show(sessionCamera, next)
  }

  /** Main thread: the overlay laid the guide out at [box] and [label], in pixels. */
  private fun guideLaidOut(box: RectF, label: RectF?) {
    val density = themedContext.resources.displayMetrics.density
    fun RectF.points() = TextGuideFrame((left / density).toDouble(), (top / density).toDouble(),
      (width() / density).toDouble(), (height() / density).toDouble())
    guideLayout = TextGuideLayout(box.points(), label?.points())
    emitGuideLayout()
  }

  private fun emitGuideLayout() {
    val layout = guideLayout
    if (disposed || layout == null || layout == emittedGuideLayout) return
    emittedGuideLayout = layout
    onGuideLayout?.invoke(layout)
  }

  private fun log(message: String) {
    if (debugLogging) ScannerLog.d(LOG_TAG, message)
  }

  /** Continuous autofocus reporting focus, as what holds focus where no lock does. */
  private object ContinuousFocus

  private companion object {
    const val DEFAULT_ANALYSIS_WIDTH = 1280
    const val MIN_CROP_PX = 16
    /** The capture preview's JPEG quality: enough to see the print, small enough to pass as a data URI. */
    const val CAPTURE_QUALITY = 85
    /** A kept frame this much sharper than the focus's read shows the view better, so a capture reads it instead. */
    const val SHARPER = 1.25
    /** Logging only: the edge width is logged every this many analysed frames, about three times a second. */
    const val EDGE_LOG_FRAMES = 10
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
 * What the analyzer reads for one frame, published by main as a whole. A new [revision] drops the kept frames and the
 * shot, as for a new [region] or zoom. Frames exposed before [readFromNs] are not kept, which [Long.MAX_VALUE] makes
 * all of them while a zoom settles. A nonzero [focusSession] has each frame handed to main's focus cycle, tagged with
 * it and [regionRevision], which changes with [region] alone. [focus] numbers the focus frames are exposed in, 0 for
 * none, from [focusFromNs] on, since frames already queued when it began were exposed before it; frames of a lock's
 * scan, [scanning], are not kept.
 */
private data class AnalysisSnapshot(
  val region: TextRegion, val revision: Long, val readFromNs: Long, val focusSession: Long, val regionRevision: Long,
  val focus: Long = 0L, val focusFromNs: Long = 0L, val scanning: Boolean = false,
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
