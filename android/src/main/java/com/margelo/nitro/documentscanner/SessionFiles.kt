package com.margelo.nitro.documentscanner

import java.io.File
import java.io.IOException
import java.util.UUID

/** Filesystem boundary, injected into the registry so lease/state tests require no Android filesystem. */
internal interface SessionFiles {
  data class Output(val directory: File, val temporary: File, val destination: File)

  fun directory(cache: File, sessionId: String): File
  fun createDirectory(directory: File)
  fun sessions(cache: File): List<File>
  fun removeLegacyImages(cache: File) {}
  fun output(directory: File, id: String, kind: SessionResources.Kind, extension: String): Output
  fun publish(output: Output)
  fun remove(output: Output)
  fun removeDirectory(cache: File, directory: File)
}

/** Only generated session directories and generated direct-child files can reach deletion. */
internal object LocalSessionFiles : SessionFiles {
  const val NAMESPACE = "sessions"
  private const val PREFIX = "session-"

  private fun namespace(cache: File): File {
    val root = File(cache.canonicalFile, NAMESPACE)
    requireInput(!ScannerFiles.isSymbolicLink(root) && root.canonicalFile == root) { "Session namespace must not be a symbolic link." }
    return root
  }

  override fun directory(cache: File, sessionId: String): File {
    requireInput(validId(sessionId)) { "Invalid native session ID." }
    return checkedDirectory(File(namespace(cache), PREFIX + sessionId))
  }

  private fun validId(id: String): Boolean =
    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

  /** Identifies the reserved direct-file shape, also through a legacy caller's path alias. */
  fun isSessionFile(file: File): Boolean {
    val parent = file.canonicalFile.parentFile ?: return false
    return parent.parentFile?.name == NAMESPACE && parent.name.startsWith(PREFIX) &&
      validId(parent.name.removePrefix(PREFIX))
  }

  private fun checkedDirectory(directory: File): File {
    val absolute = directory.absoluteFile.normalize()
    requireInput(directory == absolute && directory.parentFile?.name == NAMESPACE &&
      directory.name.startsWith(PREFIX) && validId(directory.name.removePrefix(PREFIX))) {
      "Not a native session directory."
    }
    requireInput(!ScannerFiles.isSymbolicLink(directory) && directory.canonicalFile == directory) { "Session directory must not traverse symbolic links." }
    return directory
  }

  override fun createDirectory(directory: File) {
    checkedDirectory(directory)
    if (!directory.mkdirs()) throw IOException("Could not create a new session directory.")
    checkedDirectory(directory)
  }

  override fun sessions(cache: File): List<File> {
    val root = namespace(cache)
    if (!root.exists()) return emptyList()
    val children = root.listFiles() ?: throw IOException("Could not list session directories.")
    return children.filter {
      it.name.startsWith(PREFIX) && validId(it.name.removePrefix(PREFIX)) &&
        (!ScannerFiles.isSymbolicLink(it) && it.isDirectory) && it.canonicalFile == it
    }
  }

  private val legacyImage = Regex("""(document-scan|frame-document-scan|corrected|thumbnail-corrected)-[0-9]+-([0-9a-f-]{36})\.(jpg|png)""")

  /** Upgrade cleanup: only exact pre-session output names, never directories or links. */
  override fun removeLegacyImages(cache: File) {
    val root = cache.canonicalFile
    if (!root.exists()) return
    val children = root.listFiles() ?: throw IOException("Could not list legacy scanner cache.")
    for (file in children) {
      val match = legacyImage.matchEntire(file.name) ?: continue
      if (!validId(match.groupValues[2]) || (match.groupValues[1] != "corrected" && match.groupValues[3] != "jpg")) continue
      if (!ScannerFiles.isSymbolicLink(file) && file.isFile && file.canonicalFile == file) ScannerFiles.delete(file)
    }
  }

  override fun output(directory: File, id: String, kind: SessionResources.Kind, extension: String): SessionFiles.Output {
    checkedDirectory(directory)
    requireInput(validId(id) && extension in setOf("jpg", "png")) { "Invalid native output name or format." }
    val stem = "${kind.name.lowercase()}-$id"
    return SessionFiles.Output(directory, File(directory, ".$stem.tmp.$extension"), File(directory, "$stem.$extension"))
      .also(::checkOutput)
  }

  private fun checkOutput(output: SessionFiles.Output) {
    checkedDirectory(output.directory)
    for (file in listOf(output.temporary, output.destination)) {
      requireInput(file.parentFile == output.directory && file.absoluteFile.normalize() == file &&
        !ScannerFiles.isSymbolicLink(file) && file.canonicalFile == file && !file.isDirectory) { "Output must be a direct session file, without symbolic links." }
    }
  }

  override fun publish(output: SessionFiles.Output) {
    checkOutput(output)
    if (output.destination.exists()) throw IOException("Session originals and outputs are immutable.")
    ScannerFiles.move(output.temporary, output.destination)
  }

  override fun remove(output: SessionFiles.Output) {
    checkOutput(output)
    ScannerFiles.delete(output.temporary)
    ScannerFiles.delete(output.destination)
  }

  override fun removeDirectory(cache: File, directory: File) {
    requireInput(directory.parentFile == namespace(cache)) { "Cleanup must stay inside the session namespace." }
    checkedDirectory(directory)
    fun removeTree(file: File) {
      // Inspect the link itself before consulting isDirectory; never traverse a directory link.
      if (!ScannerFiles.isSymbolicLink(file) && file.isDirectory) {
        requireInput(file.canonicalFile == file) { "Session cleanup cannot traverse symbolic links." }
        val children = file.listFiles() ?: throw IOException("Could not list session directory.")
        children.forEach(::removeTree)
      }
      ScannerFiles.delete(file)
    }
    removeTree(directory)
  }
}
