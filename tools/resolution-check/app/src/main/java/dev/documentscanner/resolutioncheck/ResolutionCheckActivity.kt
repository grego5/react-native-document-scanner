package dev.documentscanner.resolutioncheck

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import com.margelo.nitro.documentscanner.CaptureResolutionPolicy
import com.margelo.nitro.documentscanner.CameraExposurePolicy
import java.io.File

/**
 * Binds Preview, ImageCapture and ImageAnalysis the way HybridDocumentScannerCameraView does, for each capture mode,
 * and shows what the camera offers, how CaptureResolutionPolicy ranks it, and what CameraX negotiated and cropped.
 * Capture saves the JPEG with the analysis buffer and preview of that moment; share sends them with the report.
 * Keep the use-case setup in step with the scanner's camera view.
 */
class ResolutionCheckActivity : ComponentActivity() {
  private lateinit var previewView: PreviewView
  private lateinit var report: TextView
  private lateinit var captureButton: Button
  private lateinit var shareButton: Button
  private lateinit var timingsButton: Button
  private val modeButtons = mutableListOf<Button>()
  private var provider: ProcessCameraProvider? = null
  private var controller: LifecycleCameraController? = null
  private var imageCapture: ImageCapture? = null
  private var capturing = false
  private var mode = Mode.ZSL
  /** One line per finished capture this session, the short report to share. */
  private val timings = mutableListOf<String>()
  private var candidates = ""
  private var bindReport = ""
  private var frameReport = "waiting for an analysis frame"
  private var captureReport = "no capture yet"
  @Volatile private var grabFrame = false
  private val shared by lazy { File(cacheDir, "shared").apply { mkdirs() } }

  private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) start() else report.text = "Camera permission denied."
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    previewView = PreviewView(this).apply {
      scaleType = PreviewView.ScaleType.FIT_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    report = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); setPadding(24, 16, 24, 16) }
    fun row(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(this).apply {
      for ((name, action) in buttons) addView(Button(context).apply { text = name; setOnClickListener { action() } },
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }
    fun modes(vararg list: Mode) = row(*list.map { it.label to { mode = it; bind() } }.toTypedArray()).also { row ->
      for (index in 0 until row.childCount) modeButtons += row.getChildAt(index) as Button
    }
    val normalModes = modes(Mode.ZSL, Mode.LATENCY, Mode.QUALITY)
    val highResolutionModes = modes(Mode.LATENCY_HIGH_RESOLUTION, Mode.QUALITY_HIGH_RESOLUTION)
    val actions = row("capture" to { capture() }, "share timings" to { shareTimings() },
      "share diagnostics" to { share() })
    captureButton = actions.getChildAt(0) as Button
    timingsButton = actions.getChildAt(1) as Button
    shareButton = actions.getChildAt(2) as Button
    captureButton.isEnabled = false
    timingsButton.isEnabled = false
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.WHITE)
      addView(normalModes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      addView(highResolutionModes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      addView(previewView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f))
      addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      addView(ScrollView(context).apply { addView(report) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
    }
    // Android 15 draws apps edge to edge; keep the buttons and text clear of the system bars.
    root.setOnApplyWindowInsetsListener { view, insets ->
      val bars = WindowInsetsCompat.toWindowInsetsCompat(insets, view).getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
    setContentView(root)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
    else permission.launch(Manifest.permission.CAMERA)
  }

  private fun start() {
    val cameraController = LifecycleCameraController(this)
    controller = cameraController
    val future = cameraController.initializationFuture
    future.addListener({
      future.get()
      provider = ProcessCameraProvider.getInstance(this).get()
      previewView.post { bind() }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun bind() {
    if (capturing) return
    val provider = provider ?: return
    val controller = controller ?: return
    if (previewView.width == 0 || previewView.height == 0) { previewView.post { bind() }; return }
    previewView.controller = null
    imageCapture = null
    captureButton.isEnabled = false
    grabFrame = false
    candidates = ""
    bindReport = "binding ${mode.label}"
    frameReport = "waiting for an analysis frame"
    show()
    val rotation = previewView.display?.rotation ?: 0
    val preview = Preview.Builder().setTargetRotation(rotation).build()
    val capture = ImageCapture.Builder()
      .setCaptureMode(mode.cameraMode)
      .setResolutionSelector(ResolutionSelector.Builder()
        .also { if (mode.highResolution) it.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
        .setResolutionFilter { sizes, _ ->
          CaptureResolutionPolicy.rank(sizes, { it.width }, { it.height }, { it in slowSizes }).also { ranked ->
            candidates = ranked.joinToString()
          }
        }
        .build())
      .setTargetRotation(rotation)
      .build()
    val analysis = ImageAnalysis.Builder()
      .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
        ResolutionStrategy(Size(ANALYSIS_WIDTH, ANALYSIS_WIDTH * 3 / 4), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
      ).build())
      .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setTargetRotation(rotation)
      .build()
    var first = true
    analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
      image.use {
        if (first) {
          first = false
          frameReport = "first analysis frame: buffer ${it.width}x${it.height} crop ${rect(it.cropRect)} " +
            "rotation ${it.imageInfo.rotationDegrees}"
          show()
        }
        if (grabFrame) {
          grabFrame = false
          save(bitmap(it), "analysis.png", Bitmap.CompressFormat.PNG)
          frameReport += "\nanalysis at shutter: full buffer saved as analysis.png (crop ${rect(it.cropRect)} not applied)"
          show()
        }
      }
    }
    bindReport = try {
      val selector = CameraSelector.DEFAULT_BACK_CAMERA
      val useCases = listOf(preview, capture, analysis)
      val ranges = provider.getCameraInfo(selector).getSupportedFrameRateRanges(SessionConfig(useCases)).toList()
      val range = CameraExposurePolicy.targetFpsRange(ranges, MIN_FRAME_RATE, { it.lower }, { it.upper })
      controller.isAutoRotationEnabled = false
      controller.isPinchToZoomEnabled = false
      controller.isTapToFocusEnabled = false
      controller.setSessionConfig(
        if (range != null) SessionConfig(useCases, frameRateRange = range) else SessionConfig(useCases), selector)
      previewView.controller = controller
      controller.bindToLifecycle(this)
      val info = checkNotNull(controller.cameraInfo) { "The camera controller did not bind." }
      imageCapture = capture
      captureButton.isEnabled = true
      listOf(
        "bound mode=${mode.label} zsl supported=${info.isZslSupported}",
        "minimum frame rate=$MIN_FRAME_RATE selected range=${range ?: "camera default"} supported=$ranges",
        "preview  ${info(preview.resolutionInfo)}",
        "capture  ${info(capture.resolutionInfo)}",
        "analysis ${info(analysis.resolutionInfo)}",
      ).joinToString("\n")
    } catch (error: Throwable) {
      previewView.controller = null
      analysis.clearAnalyzer()
      "bind failed for ${mode.label}: ${error.message}"
    }
    show()
  }

  private fun capture() {
    if (capturing) return
    val capture = imageCapture ?: return
    val captureMode = mode
    setCapturing(true)
    shared.listFiles()?.forEach { it.delete() }
    previewView.bitmap?.let { save(it, "preview.png", Bitmap.CompressFormat.PNG) }
    grabFrame = true
    captureReport = "capturing (${captureMode.label})…"
    show()
    val zsl = controller?.cameraInfo?.isZslSupported == true
    val started = SystemClock.elapsedRealtime()
    var shutterMs: Long? = null
    val file = File(shared, "photo.jpg")
    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
      object : ImageCapture.OnImageSavedCallback {
        // The camera began exposing the photo's frame: the press-to-shutter delay.
        override fun onCaptureStarted() {
          shutterMs = SystemClock.elapsedRealtime() - started
        }

        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          val savedMs = SystemClock.elapsedRealtime() - started
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeFile(file.path, bounds)
          val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
          val turned = orientation in 5..8
          val upright = if (turned) "${bounds.outHeight}x${bounds.outWidth}" else "${bounds.outWidth}x${bounds.outHeight}"
          val shutter = shutterMs?.let { "$it ms" } ?: "unreported"
          captureReport = "capture (${captureMode.label}) shutter $shutter, saved $savedMs ms: JPEG ${bounds.outWidth}x" +
            "${bounds.outHeight}, EXIF orientation $orientation, upright $upright, ${file.length() / 1024} KB\n" +
            "saved photo.jpg, analysis.png and preview.png for sharing"
          timings += "${captureMode.label}${if (captureMode == Mode.ZSL && !zsl) " (no zsl)" else ""}: " +
            "${bounds.outWidth}x${bounds.outHeight}, shutter $shutter, saved $savedMs ms, ${file.length() / 1024} KB"
          setCapturing(false)
          show()
        }

        override fun onError(error: ImageCaptureException) {
          captureReport = "capture failed (${captureMode.label}): ${error.imageCaptureError} ${error.message}"
          timings += "${captureMode.label}: failed, ${error.imageCaptureError}"
          setCapturing(false)
          show()
        }
      })
  }

  private fun setCapturing(value: Boolean) {
    capturing = value
    captureButton.isEnabled = !value && imageCapture != null
    shareButton.isEnabled = !value
    timingsButton.isEnabled = !value && timings.isNotEmpty()
    for (button in modeButtons) button.isEnabled = !value
  }

  /** Shares only the device line and one line per capture, as plain text. */
  private fun shareTimings() {
    val text = timingsText()
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Scanner capture timings"), "Share timings"))
  }

  private fun timingsText() = (listOf("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
    "Android ${android.os.Build.VERSION.RELEASE}") + timings).joinToString("\n")

  private fun share() {
    File(shared, "report.txt").writeText(report.text.toString())
    val uris = ArrayList<Uri>(shared.listFiles().orEmpty().sortedBy { it.name }.map {
      FileProvider.getUriForFile(this, "$packageName.files", it)
    })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
      .putExtra(Intent.EXTRA_TEXT, report.text.toString())
      .putExtra(Intent.EXTRA_SUBJECT, "Scanner resolution check")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    startActivity(Intent.createChooser(intent, "Share diagnostics"))
  }

  private fun show() {
    val summary = if (timings.isEmpty()) null else "timings (shutter = press to exposure, saved = press to file):\n" +
      timings.joinToString("\n")
    val text = listOfNotNull(summary, device(), "capture candidates (ranked, as passed to the filter):\n$candidates", bindReport,
      frameReport, captureReport).joinToString("\n\n")
    report.text = text
    Log.i(TAG, text)
  }

  private var deviceText: String? = null
  /** The camera's high-resolution JPEG sizes, ranked as the scanner ranks them; read with the device report. */
  private var slowSizes = emptySet<Size>()
  private fun device(): String = deviceText ?: run {
    val provider = provider ?: return "no camera provider"
    val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
      ?: return "no back camera"
    val id = Camera2CameraInfo.from(info).cameraId
    val characteristics = getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val level = when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
      CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
      else -> "unknown"
    }
    fun sizes(list: Array<Size>?) = list.orEmpty().sortedByDescending { it.width.toLong() * it.height }
    val jpeg = sizes(map?.getOutputSizes(ImageFormat.JPEG))
    val highRes = sizes(map?.getHighResolutionOutputSizes(ImageFormat.JPEG))
    slowSizes = highRes.toSet()
    fun ranked(list: List<Size>) =
      CaptureResolutionPolicy.rank(list, { it.width }, { it.height }, { it in highRes }).firstOrNull()
    listOf(
      "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
      "back camera id=$id level=$level active=${rect(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))}",
      "JPEG sizes: ${jpeg.joinToString()}",
      "JPEG high-resolution sizes: ${highRes.joinToString().ifEmpty { "none" }}",
      "policy pick: normal ${ranked(jpeg)}, hi-res ${ranked(jpeg + highRes)} (before CameraX combination checks)",
    ).joinToString("\n").also { deviceText = it }
  }

  /** The whole RGBA buffer, ignoring the crop, so its field of view can be compared with the photo's. */
  private fun bitmap(image: ImageProxy): Bitmap {
    val plane = image.planes[0]
    val rowPixels = plane.rowStride / plane.pixelStride
    val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
    plane.buffer.rewind()
    padded.copyPixelsFromBuffer(plane.buffer)
    return if (rowPixels == image.width) padded
    else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
  }

  private fun save(bitmap: Bitmap, name: String, format: Bitmap.CompressFormat) {
    File(shared, name).outputStream().use { bitmap.compress(format, 100, it) }
  }

  private fun info(info: androidx.camera.core.ResolutionInfo?) =
    info?.let { "${it.resolution} crop ${rect(it.cropRect)}" } ?: "none"

  private fun rect(rect: android.graphics.Rect?) =
    rect?.let { "${it.left},${it.top} ${it.width()}x${it.height()}" } ?: "none"

  /** A CameraX capture mode, and whether the photo may use the sizes the camera lists as high-resolution, which it
   * captures below 20 fps. The scanner allows them in `document` mode, and in `photo` mode with `highResolutionPhoto`. */
  private enum class Mode(val label: String, val cameraMode: Int, val highResolution: Boolean) {
    ZSL("zsl", ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG, false),
    LATENCY("latency", ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, false),
    QUALITY("quality", ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, false),
    LATENCY_HIGH_RESOLUTION("latency hi-res", ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, true),
    QUALITY_HIGH_RESOLUTION("quality hi-res", ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, true),
  }

  companion object {
    private const val TAG = "ResolutionCheck"
    private const val ANALYSIS_WIDTH = 640
    // Match the scanner's default minFrameRate; its exposure policy chooses the range.
    private const val MIN_FRAME_RATE = 0
  }
}
