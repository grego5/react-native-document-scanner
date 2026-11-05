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
  private lateinit var modes: LinearLayout
  private var provider: ProcessCameraProvider? = null
  private var controller: LifecycleCameraController? = null
  private var imageCapture: ImageCapture? = null
  private var capturing = false
  private var mode = "zsl"
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
    modes = row("zsl" to { mode = "zsl"; bind() }, "latency" to { mode = "latency"; bind() },
      "quality" to { mode = "quality"; bind() })
    val actions = row("capture" to { capture() }, "share diagnostics" to { share() })
    captureButton = actions.getChildAt(0) as Button
    shareButton = actions.getChildAt(1) as Button
    captureButton.isEnabled = false
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.WHITE)
      addView(modes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
    bindReport = "binding $mode"
    frameReport = "waiting for an analysis frame"
    show()
    val rotation = previewView.display?.rotation ?: 0
    val preview = Preview.Builder().setTargetRotation(rotation).build()
    val capture = ImageCapture.Builder()
      .setCaptureMode(when (mode) {
        "quality" -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        "latency" -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        else -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
      })
      .setResolutionSelector(ResolutionSelector.Builder()
        .also { if (mode == "quality") it.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
        .setResolutionFilter { sizes, _ ->
          CaptureResolutionPolicy.rank(sizes, { it.width }, { it.height }).also { ranked ->
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
        "bound mode=$mode zsl supported=${info.isZslSupported}",
        "minimum frame rate=$MIN_FRAME_RATE selected range=${range ?: "camera default"} supported=$ranges",
        "preview  ${info(preview.resolutionInfo)}",
        "capture  ${info(capture.resolutionInfo)}",
        "analysis ${info(analysis.resolutionInfo)}",
      ).joinToString("\n")
    } catch (error: Throwable) {
      previewView.controller = null
      analysis.clearAnalyzer()
      "bind failed for $mode: ${error.message}"
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
    captureReport = "capturing ($captureMode)…"
    show()
    val started = System.currentTimeMillis()
    val file = File(shared, "photo.jpg")
    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeFile(file.path, bounds)
          val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
          val turned = orientation in 5..8
          val upright = if (turned) "${bounds.outHeight}x${bounds.outWidth}" else "${bounds.outWidth}x${bounds.outHeight}"
          captureReport = "capture ($captureMode) in ${System.currentTimeMillis() - started} ms: JPEG ${bounds.outWidth}x" +
            "${bounds.outHeight}, EXIF orientation $orientation, upright $upright, ${file.length() / 1024} KB\n" +
            "saved photo.jpg, analysis.png and preview.png for sharing"
          setCapturing(false)
          show()
        }

        override fun onError(error: ImageCaptureException) {
          captureReport = "capture failed ($captureMode): ${error.imageCaptureError} ${error.message}"
          setCapturing(false)
          show()
        }
      })
  }

  private fun setCapturing(value: Boolean) {
    capturing = value
    captureButton.isEnabled = !value && imageCapture != null
    shareButton.isEnabled = !value
    for (index in 0 until modes.childCount) modes.getChildAt(index).isEnabled = !value
  }

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
    val text = listOf(device(), "capture candidates (ranked, as passed to the filter):\n$candidates", bindReport,
      frameReport, captureReport).joinToString("\n\n")
    report.text = text
    Log.i(TAG, text)
  }

  private var deviceText: String? = null
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
    fun ranked(list: List<Size>) = CaptureResolutionPolicy.rank(list, { it.width }, { it.height }).firstOrNull()
    listOf(
      "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
      "back camera id=$id level=$level active=${rect(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))}",
      "JPEG sizes: ${jpeg.joinToString()}",
      "JPEG high-resolution sizes: ${highRes.joinToString().ifEmpty { "none" }}",
      "policy pick: zsl/latency ${ranked(jpeg)}, quality ${ranked(jpeg + highRes)} (before CameraX combination checks)",
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

  companion object {
    private const val TAG = "ResolutionCheck"
    private const val ANALYSIS_WIDTH = 640
    // Match the scanner's default minFrameRate; its exposure policy chooses the range.
    private const val MIN_FRAME_RATE = 0
  }
}
