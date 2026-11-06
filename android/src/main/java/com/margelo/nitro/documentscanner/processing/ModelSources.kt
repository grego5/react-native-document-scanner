package com.margelo.nitro.documentscanner.processing

import android.content.Context
import android.net.Uri
import com.margelo.nitro.documentscanner.requireInput
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Reads model sources on the runtime's I/O worker. Models are limited to 128 MiB. */
internal object ModelSources {
  private const val MAX_BYTES = 128 * 1024 * 1024

  fun local(context: Context, source: String): ByteArray {
    val uri = Uri.parse(source).normalizeScheme()
    val stream = when {
      uri.scheme == "asset" -> context.assets.open((uri.authority.orEmpty() + uri.path.orEmpty()).trimStart('/'))
      uri.scheme == "file" && uri.pathSegments.firstOrNull() == "android_asset" ->
        context.assets.open(uri.pathSegments.drop(1).joinToString("/"))
      uri.scheme == "file" && uri.pathSegments.firstOrNull() == "android_res" -> {
        val parts = uri.pathSegments
        requireInput(parts.size == 3) { "Expected a packaged resource type and name." }
        resource(context, parts[1].substringBefore('-'), parts[2].substringBeforeLast('.'))
      }
      uri.scheme == "content" || uri.scheme == "android.resource" ->
        context.contentResolver.openInputStream(uri) ?: error("Could not open the model source.")
      uri.scheme == null && !File(source).isAbsolute -> resource(context, "raw", source)
      uri.scheme == null || uri.scheme == "file" -> {
        requireInput(uri.authority.isNullOrEmpty()) { "File URI must be local." }
        val file = File(if (uri.scheme == "file") uri.path.orEmpty() else source)
        requireInput(file.isAbsolute && file.isFile) { "Model must be an existing absolute file." }
        file.inputStream()
      }
      else -> {
        requireInput(false) { "Model source must be local; download HTTP(S) models in the host app first." }
        error("Unsupported model source scheme.")
      }
    }
    return stream.use { read(it) }
  }

  private fun resource(context: Context, type: String, name: String): InputStream {
    val id = context.resources.getIdentifier(name, type, context.packageName)
    requireInput(id != 0) { "Packaged model resource not found." }
    return context.resources.openRawResource(id)
  }

  private fun read(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      check(output.size().toLong() + count <= MAX_BYTES) { "Model exceeds 128 MiB." }
      output.write(buffer, 0, count)
    }
    check(output.size() > 0) { "Model is empty." }
    return output.toByteArray()
  }
}
