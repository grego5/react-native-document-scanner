package com.margelo.nitro.documentscanner

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Any thread: the app-wide diagnostics an app turns on in any build, so testers without ADB can send what the camera
 * did. While [enabled], the module's log lines are kept in memory, the newest [MAX_LINES] within [MAX_CHARS], and
 * camera views created afterwards also log in release builds and keep their last 15 s camera trace. [export] writes
 * both, under a device header, to a text file in the app's cache. Nothing holds image pixels; text camera lines can
 * hold recognized text.
 */
internal object ScannerDiagnostics {
  @Volatile var enabled = false
  private val lines = ArrayDeque<String>()
  private var chars = 0
  private val traces = LinkedHashSet<StandaloneCameraTrace>()
  private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

  fun record(tag: String, message: String, error: Throwable? = null) {
    if (!enabled) return
    val line = buildString {
      synchronized(clock) { append(clock.format(Date())) }
      append(' ').append(tag).append(' ').append(message)
      if (error != null) append(" | ").append(error.javaClass.name).append(": ").append(error.message)
    }
    synchronized(this) {
      lines.addLast(line)
      chars += line.length
      while (lines.size > MAX_LINES || chars > MAX_CHARS) chars -= lines.removeFirst().length
    }
  }

  /** A camera view's trace while the view lives, so an export includes it. */
  @Synchronized fun attach(trace: StandaloneCameraTrace) { traces += trace }
  @Synchronized fun detach(trace: StandaloneCameraTrace) { traces -= trace }

  @Synchronized fun clear() {
    lines.clear()
    chars = 0
  }

  /** Writes the kept lines and each live camera's trace to a new file in the cache, and returns it. */
  fun export(context: Context): File {
    val (kept, traced) = synchronized(this) { lines.toList() to traces.map { it.snapshot() } }
    val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
    // Earlier exports a share has had time to send are removed, so the cache does not grow.
    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > RETENTION_MS }?.forEach { it.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    // A unique name per export, so two in the same second never share, or overwrite, one file.
    val file = File.createTempFile("scanner-diagnostics-$stamp-", ".txt", directory)
    file.bufferedWriter().use { out ->
      val app = try {
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${context.packageName} ${it.versionName}" }
      } catch (_: Exception) { context.packageName }
      out.appendLine("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
      out.appendLine("app $app, exported ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}")
      out.appendLine(if (enabled) "diagnostics on" else "diagnostics off: nothing new is kept")
      out.appendLine()
      out.appendLine("== log, ${kept.size} lines ==")
      kept.forEach(out::appendLine)
      traced.forEachIndexed { index, rows ->
        out.appendLine()
        out.appendLine("== camera ${index + 1} trace, last 15 s ==")
        out.appendLine("elapsed_ms\tevent\tsensor_ns\tdetails")
        rows.forEach(out::appendLine)
      }
    }
    return file
  }

  private const val MAX_LINES = 20_000
  private const val MAX_CHARS = 4_000_000
  private const val DIRECTORY = "document-scanner-diagnostics"
  private const val RETENTION_MS = 24 * 60 * 60 * 1000L
}

/** [Log]'s calls the module makes, also kept by [ScannerDiagnostics] while it is on. */
internal object ScannerLog {
  fun d(tag: String, message: String) {
    Log.d(tag, message)
    ScannerDiagnostics.record(tag, message)
  }

  fun w(tag: String, message: String, error: Throwable? = null) {
    Log.w(tag, message, error)
    ScannerDiagnostics.record(tag, "W $message", error)
  }

  fun e(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, message, error)
    ScannerDiagnostics.record(tag, "E $message", error)
  }
}
