package com.margelo.nitro.documentscanner

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
import android.widget.FrameLayout
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
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
  override var guideColor: Double? = null
  override var allowedCharacters: String? = null
  override var textPattern: String? = null
  override var minLength: Double? = null
  override var tapToFocus: Boolean? = null
  override var torch: TextTorchMode? = null
  override var recognitionRate: Double? = null
  override var analysisFrameWidth: Double? = null
  override var onCameraStateChange: ((state: ScannerCameraState) -> Unit)? = null
    set(value) { field = value; emittedState = null; updateCameraState() }
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
  private var reading: TextReading? = null
  private var emittedReading: TextReading? = null
  private var appliedFilterKey: Triple<String, String, Int>? = null
  private val pendingReads = mutableListOf<PendingRead>()
  private val reaimFocus = Runnable { aimFocus() }
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
  @Volatile private var analysisRegion = clampedRegion(textRegion)
  @Volatile private var analysisFilter = TextLineFilter("", "", 1)
  /** Bumped by main whenever votes must restart: a new region, filter or binding. */
  @Volatile private var voteRevision = 0L
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
    if (region != analysisRegion) {
      analysisRegion = region
      voteRevision++
      clearReading()
      aimFocus()
    }
    val filterKey = Triple(allowedCharacters.orEmpty(), textPattern.orEmpty(), (minLength ?: 1.0).toInt())
    if (filterKey != appliedFilterKey) {
      appliedFilterKey = filterKey
      val filter = TextLineFilter(filterKey.first, filterKey.second, filterKey.third)
      analysisFilter = filter
      voteRevision++
      clearReading()
      if (filter.patternInvalid) onError?.invoke(ScannerFailure(ScannerErrorCode.INPUT_INVALID))
    }
    frameIntervalMs = (1000 / (recognitionRate ?: DEFAULT_RATE.toDouble()).coerceIn(1.0, 15.0)).toLong()
    overlay.update(region, textGuide ?: TextGuide.NONE, guideColor?.toLong()?.toInt() ?: DEFAULT_COLOR)
    val width = requestedWidth()
    if (controller != null && width != boundWidth) stopCamera("analysis width")
    if (active == false) stopCamera("inactive") else startCamera()
    applyTorch()
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
        voteRevision++
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
        aimFocus()
        applyTorch()
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
    voteRevision++
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
    analysis?.clearAnalyzer()
    analysis = null
    cameraControl = null
    if (controller != null) {
      log("stop reason=$reason")
      try { previewView.controller = null } catch (_: Throwable) { }
    }
    controller = null
    makeUnavailable()
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
      val revision = voteRevision
      if (revision != countedRevision) {
        votes.clear()
        outline = null
        flow?.reset()
        countedRevision = revision
        log("frames ${image.width}x${image.height} crop=${image.cropRect} rotation=${image.imageInfo.rotationDegrees}")
      }
      val rotation = image.imageInfo.rotationDegrees
      val crop = image.cropRect
      val sensorNs = image.imageInfo.timestamp
      val moved = followText(image, sensorNs)
      val now = SystemClock.uptimeMillis()
      if (now - lastFrameMs < frameIntervalMs) {
        if (moved) postOutline(bindGeneration, revision)
        return
      }
      lastFrameMs = now
      val box = regionInBuffer(analysisRegion, rotation, crop.left, crop.top, crop.width(), crop.height())
      val left = box[0].coerceIn(0, image.width)
      val top = box[1].coerceIn(0, image.height)
      val width = box[2].coerceIn(left, image.width) - left
      val height = box[3].coerceIn(top, image.height) - top
      if (width < MIN_CROP_PX || height < MIN_CROP_PX) return
      val client = recognizer() ?: return
      // Upright before ML Kit: its own rotation shifts the boxes along the rotated axis.
      val bitmap = upright(cropRgba(image, left, top, width, height), rotation)
      val text = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)))
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
      val accepted = analysisFilter.select(lines, uprightWidth.toFloat(), uprightHeight.toFloat())
      if (revision != voteRevision) return
      val region = analysisRegion
      val winner = votes.add(accepted?.text, accepted?.confidence ?: 0f, now)
      val read = accepted?.let {
        TrackedOutline(it.text, textBoundsInView(it, region, uprightWidth.toFloat(), uprightHeight.toFloat()).corners(), sensorNs)
      }
      outline = outlineAfter(outline, read, winner?.text) { it.text }
      val shown = outline?.corners
      mainHandler.post {
        if (bindGeneration == generation && revision == voteRevision && controller != null) publish(winner, region, shown)
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
  private fun followText(image: ImageProxy, sensorNs: Long): Boolean {
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
        flowArea(analysisRegion), fitTransform = true)
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

  private fun postOutline(bindGeneration: Long, revision: Long) {
    val shown = outline?.corners
    mainHandler.post {
      if (bindGeneration != generation || revision != voteRevision || controller == null) return@post
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

  private fun emptyReading() = TextReading("", 0.0, 0.0, analysisRegion, null)

  private fun emitReading() {
    if (disposed) return
    val next = reading ?: emptyReading()
    if (reading == null) overlay.showText(null)
    val last = emittedReading
    if (last != null && last.text == next.text && last.votes == next.votes && last.bounds == next.bounds) return
    emittedReading = next
    onTextChange?.invoke(next)
  }

  /** Main thread: continuous autofocus and exposure aimed at the region, unlocked, until a tap replaces it. */
  private fun aimFocus() {
    val control = cameraControl ?: return
    if (previewView.width <= 0 || previewView.height <= 0) return
    val region = analysisRegion
    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(
      ((region.x + region.width / 2) * previewView.width).toFloat(),
      ((region.y + region.height / 2) * previewView.height).toFloat(),
      maxOf(region.width, region.height).toFloat().coerceIn(0.05f, 0.5f),
    )
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .setLockingMode(0).disableAutoCancel().build()
    try { control.startFocusAndMetering(action) } catch (error: Exception) { log("region focus failed ${error.javaClass.simpleName}") }
  }

  private fun onTap(x: Float, y: Float) {
    val control = cameraControl ?: return
    if (tapToFocus == false) return
    val factory = previewView.meteringPointFactory
    val action = FocusMeteringAction.Builder(factory.createPoint(x, y, TAP_AREA), FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .disableAutoCancel().build()
    try { control.startFocusAndMetering(action) } catch (error: Exception) { log("tap focus failed ${error.javaClass.simpleName}") }
    mainHandler.removeCallbacks(reaimFocus)
    mainHandler.postDelayed(reaimFocus, TAP_HOLD_MS)
  }

  private fun applyTorch() {
    val control = cameraControl ?: return
    try { control.enableTorch(torch == TextTorchMode.ON) } catch (_: Exception) { }
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
  }
}

/** The outline of [text], as view fractions, where it appeared in the frame at sensor time [sensorNs]. */
private data class TrackedOutline(val text: String, val corners: DocumentCorners, val sensorNs: Long)

/** Dims the preview outside the region, outlines it and the text being read; draws nothing when hidden. */
private class TextRegionOverlay(context: Context) : View(context) {
  private var region: TextRegion? = null
  private var text: DocumentCorners? = null
  private var guide = TextGuide.NONE
  private val density = context.resources.displayMetrics.density
  /** A soft highlight over the text, its corners rounded by the path effect. */
  private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = CornerPathEffect(HIGHLIGHT_RADIUS_DP * density) }
  private val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.5f * density
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val brackets = Path()
  private val textPath = Path()
  private val rect = RectF()

  fun update(region: TextRegion, guide: TextGuide, color: Int) {
    if (region == this.region && guide == this.guide && color == guideStroke.color) return
    this.region = region
    this.guide = guide
    textFill.color = (color and 0x00FFFFFF) or 0x59000000
    guideStroke.color = color
    invalidate()
  }

  private companion object {
    const val CROSSHAIR_ARM_DP = 8f
    const val HIGHLIGHT_PAD_DP = 3f
    const val HIGHLIGHT_RADIUS_DP = 4f
  }

  /** The outline of the text being read, as fractions of the view, or null for none. */
  fun showText(outline: DocumentCorners?) {
    if (outline == text) return
    text = outline
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    val region = region ?: return
    if (guide == TextGuide.NONE || width == 0 || height == 0) return
    rect.set((region.x * width).toFloat(), (region.y * height).toFloat(),
      ((region.x + region.width) * width).toFloat(), ((region.y + region.height) * height).toFloat())
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
    val text = text ?: return
    // Each corner moves out from the centre by the padding, so the highlight clears the glyphs.
    val pad = HIGHLIGHT_PAD_DP * density
    val cx = (text.topLeft.x + text.topRight.x + text.bottomRight.x + text.bottomLeft.x) / 4 * width
    val cy = (text.topLeft.y + text.topRight.y + text.bottomRight.y + text.bottomLeft.y) / 4 * height
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
