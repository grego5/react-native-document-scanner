package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.InvalidInputException
import com.margelo.nitro.documentscanner.requireInput
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Deletion limited to the files this library writes under its cache folder. */
internal object CachedImageFiles {
  const val DIRECTORY_NAME = "react-native-document-scanner"

  fun directory(context: Context) = File(context.cacheDir, DIRECTORY_NAME)

  /** Validates every URI before deleting any; files already gone count as removed. */
  fun remove(directory: File, uris: List<String>) {
    val root = directory.canonicalPath + File.separator
    val files = uris.map { ownedFile(root, it) }
    val failed = files.count { it.exists() && !it.delete() }
    if (failed > 0) throw IOException("$failed cached image(s) could not be deleted.")
  }

  private fun ownedFile(root: String, value: String): File {
    val uri = Uri.parse(value)
    requireInput(uri.scheme == "file" && !uri.path.isNullOrBlank()) { "Not a local file:// URI: $value" }
    val requested = File(uri.path!!)
    requireInput(requested.isAbsolute) { "Not an absolute file path: $value" }
    // The parent is resolved and the name kept, so a final symbolic link shows up as a changed canonical path.
    val normalized = requested.normalize()
    val parent = normalized.parentFile?.canonicalFile ?: throw InvalidInputException("Not a cached image: $value")
    val file = File(parent, normalized.name)
    requireInput(file.canonicalFile == file) { "Refusing to delete a symbolic link: $value" }
    requireInput(file.path.startsWith(root) && !file.isDirectory) { "Not a document scanner cached image: $value" }
    return file
  }
}
