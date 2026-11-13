package com.margelo.nitro.documentscanner

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.margelo.nitro.NitroModules

/**
 * The app's CAMERA permission, independent of any camera view: reads it, prompts for it, and tells listeners when it
 * changes. Main thread, except [status].
 */
internal object CameraPermissions {
  private val listeners = LinkedHashSet<(CameraPermissionStatus) -> Unit>()
  /** Callers of [request] waiting on the open prompt; every one gets its result. */
  private val waiting = mutableListOf<(Result<CameraPermissionStatus>) -> Unit>()
  private var launcher: ActivityResultLauncher<String>? = null
  /** The Activity showing the prompt; destroying it ends the prompt without a result. */
  private var promptActivity: Activity? = null
  /** The status listeners last heard, read again when the first listener is added. */
  private var reported: CameraPermissionStatus? = null
  private var lifecycleRegistered = false

  /** Any thread. `denied` and `blocked` can only be told apart while an Activity is in front. */
  fun status(): CameraPermissionStatus {
    val context = context()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      return CameraPermissionStatus.GRANTED
    }
    val requested = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_PERMISSION_REQUESTED, false)
    if (!requested) return CameraPermissionStatus.NOT_DETERMINED
    val activity = NitroModules.applicationContext?.currentActivity ?: return CameraPermissionStatus.DENIED
    return if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
      CameraPermissionStatus.DENIED
    } else {
      CameraPermissionStatus.BLOCKED
    }
  }

  /** Calls [listener] on every status change until the returned function removes it. */
  fun addListener(listener: (CameraPermissionStatus) -> Unit): () -> Unit {
    observeActivities()
    if (listeners.isEmpty()) reported = status()
    listeners += listener
    return { listeners -= listener }
  }

  /**
   * Prompts for CAMERA unless it is granted or blocked, and calls [onResult] with the status after the prompt. A call
   * while the prompt is open waits for that prompt's result.
   */
  fun request(onResult: (Result<CameraPermissionStatus>) -> Unit) {
    observeActivities()
    val current = status()
    if (current == CameraPermissionStatus.GRANTED || current == CameraPermissionStatus.BLOCKED) {
      onResult(Result.success(current))
      return
    }
    waiting += onResult
    if (launcher != null) return
    val activity = NitroModules.applicationContext?.currentActivity
    if (activity !is ActivityResultRegistryOwner) {
      settle(Result.failure(IllegalStateException("Requesting camera permission needs a foreground Android activity.")))
      return
    }
    try {
      // A keyed registration may happen at any point of the Activity's lifecycle; it is removed after one result.
      launcher = activity.activityResultRegistry.register(REQUEST_KEY, ActivityResultContracts.RequestPermission()) {
        settle(Result.success(status()))
      }
      promptActivity = activity
      context().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_PERMISSION_REQUESTED, true)
        .apply()
      launcher?.launch(Manifest.permission.CAMERA)
    } catch (error: Throwable) {
      settle(Result.failure(IllegalStateException("Android could not open the camera permission prompt.", error)))
    }
  }

  /** Ends the open prompt: every waiting caller gets [result], then listeners hear of any change. */
  private fun settle(result: Result<CameraPermissionStatus>) {
    launcher?.unregister()
    launcher = null
    promptActivity = null
    val callers = waiting.toList()
    waiting.clear()
    callers.forEach { it(result) }
    refresh()
  }

  /** Reports the current status to listeners if it differs from the last one reported. */
  private fun refresh() {
    if (listeners.isEmpty()) return
    val current = status()
    if (current == reported) return
    reported = current
    listeners.toList().forEach { it(current) }
  }

  /** Access granted in system settings, and the denied or blocked outcome, show when an Activity resumes. */
  private fun observeActivities() {
    if (lifecycleRegistered) return
    lifecycleRegistered = true
    (context().applicationContext as Application).registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = refresh()
        override fun onActivityDestroyed(activity: Activity) {
          if (activity === promptActivity) {
            settle(Result.failure(IllegalStateException("The camera permission request was interrupted.")))
          }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
      },
    )
  }

  private fun context(): Context = NitroModules.applicationContext?.applicationContext
    ?: throw ScannerException(ScannerErrorCode.PERMISSION_UNAVAILABLE)

  private const val PREFERENCES_NAME = "react-native-document-scanner"
  private const val KEY_PERMISSION_REQUESTED = "camera-permission-requested"
  private const val REQUEST_KEY = "react-native-document-scanner-camera-permission"
}
