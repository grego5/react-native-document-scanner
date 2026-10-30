package com.margelo.nitro.documentscanner

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.WindowManager
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.margelo.nitro.NitroModules
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.uimanager.ThemedReactContext

/** Instrumentation-only host for the actual native view, without loading a JS application. */
class CameraValidationActivity : ComponentActivity() {
  lateinit var camera: HybridDocumentScannerCameraView
    private set
  private lateinit var captureButton: Button
  private lateinit var traceBar: LinearLayout
  private lateinit var reactContext: BridgeReactContext
  private var previousNitroContext: ReactApplicationContext? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // Used only as an uninitialized platform Context in this test APK.
    reactContext = BridgeReactContext(applicationContext)
    previousNitroContext = NitroModules.applicationContext
    NitroModules.applicationContext = reactContext
    reactContext.onHostResume(this)
    camera = HybridDocumentScannerCameraView(ThemedReactContext(reactContext, this, "CameraValidation", -1))
    // Tests that exercise the quality gate turn it on; the rest capture and analyze ungated.
    camera.quadTensorModel = intent.getStringExtra("quadTensorModel") ?: "asset:///doccornernet/model.tflite"
    camera.qualityGate = false
    camera.mode = if (intent.getBooleanExtra("cameraActive", true)) CameraMode.SCANNER else CameraMode.OFF
    camera.afterUpdate()
    val root = FrameLayout(this)
    root.addView(camera.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val spacing = (16 * resources.displayMetrics.density).toInt()
    captureButton = Button(this).apply {
      text = "Preparing camera..."
      isEnabled = false
      isAllCaps = false
      textSize = 16f
      minHeight = (56 * resources.displayMetrics.density).toInt()
      maxLines = 2
      setTextColor(Color.WHITE)
      backgroundTintList = ColorStateList.valueOf(Color.rgb(26, 94, 80))
      setPadding(spacing, spacing, spacing, spacing)
    }
    root.addView(captureButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    traceBar = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL
      setBackgroundColor(0xCC101820.toInt())
      setPadding(spacing, spacing / 2, spacing / 2, spacing / 2)
      visibility = android.view.View.GONE
    }
    root.addView(traceBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      captureButton.layoutParams = (captureButton.layoutParams as FrameLayout.LayoutParams).apply {
        leftMargin = safe.left + spacing
        rightMargin = safe.right + spacing
        bottomMargin = safe.bottom + spacing
      }
      traceBar.layoutParams = (traceBar.layoutParams as FrameLayout.LayoutParams).apply {
        leftMargin = safe.left
        rightMargin = safe.right
        topMargin = safe.top
      }
      insets
    }
    setContentView(root)
    ViewCompat.requestApplyInsets(root)
  }

  /** Main-thread-only one-shot shutter. The test cannot trigger capture until the user taps. */
  fun requestManualCapture(label: String, action: () -> Unit) {
    captureButton.text = label
    captureButton.isEnabled = true
    captureButton.setOnClickListener {
      captureButton.isEnabled = false
      captureButton.text = "Processing photo..."
      captureButton.setOnClickListener(null)
      action()
    }
  }

  /** Main-thread-only status on the disabled shutter, for flows that offer no capture or have finished one. */
  fun showCaptureStatus(text: String) {
    captureButton.setOnClickListener(null)
    captureButton.isEnabled = false
    captureButton.text = text
  }

  /** Main-thread-only trace label and one-shot Stop control for live quad recordings. */
  fun showTraceControls(label: String, onStop: () -> Unit) {
    traceBar.removeAllViews()
    traceBar.addView(TextView(this).apply {
      text = label
      setTextColor(Color.WHITE)
      textSize = 16f
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    traceBar.addView(Button(this).apply {
      text = "Stop recording"
      isAllCaps = false
      setTextColor(Color.WHITE)
      backgroundTintList = ColorStateList.valueOf(Color.rgb(178, 34, 34))
      setOnClickListener {
        isEnabled = false
        setOnClickListener(null)
        onStop()
      }
    })
    traceBar.visibility = android.view.View.VISIBLE
  }

  override fun onDestroy() {
    captureButton.setOnClickListener(null)
    camera.onDropView()
    reactContext.onHostDestroy()
    if (NitroModules.applicationContext === reactContext) NitroModules.applicationContext = previousNitroContext
    super.onDestroy()
  }
}
