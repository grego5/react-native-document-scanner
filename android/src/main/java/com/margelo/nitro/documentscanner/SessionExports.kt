package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.PdfWriteOptions
import android.net.Uri
import com.margelo.nitro.documentscanner.processing.PdfWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** One FIFO job per invocation. All files stay staged until publication claims the open state lock. */
internal class SessionExports(
  private val state: ScanSessionState,
  private val writePdf: (PdfWriteOptions) -> String = { PdfWriter.write(it) },
  private val copyImage: (File, File) -> Unit = { source, target ->
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
  },
) {
  fun createPdf(ids: List<String>?, options: SessionPdfOptions): CompletableFuture<String> {
    val snapshot = state.exportSnapshot(ids)
    return try {
      val output = local(options.outputPath)
      validateFile(output)
      submit(snapshot) {
        val staged = File(output.parentFile, ".scan-export-${UUID.randomUUID()}.pdf")
        val prepared = Prepared(listOf(staged to output)) { Uri.fromFile(output).toString() }
        try {
          val paths = snapshot.files().map { Uri.fromFile(it).toString() }.toTypedArray()
          state.checkExportOpen()
          writePdf(PdfWriteOptions(outputPath = staged.path, pages = paths,
            imageFit = options.imageFit, autoRotateExif = options.autoRotateExif,
            jpegQuality = options.jpegQuality, targetDpi = options.targetDpi,
            pageWidth = options.pageWidth, pageHeight = options.pageHeight, pageAspectRatio = options.pageAspectRatio))
          prepared
        } catch (error: Throwable) { prepared.close(); throw error }
      }
    } catch (error: Throwable) { snapshot.close(); failed(error) }
  }

  fun exportImages(ids: List<String>?, directory: String): CompletableFuture<List<String>> {
    val snapshot = state.exportSnapshot(ids)
    return try {
      val folder = local(directory)
      state.resources.requireExternalOutput(folder)
      requireInput(folder.isDirectory && folder.canWrite()) { "Output directory must exist and be writable." }
      submit(snapshot) {
        val copies = mutableListOf<Pair<File, File>>()
        try {
          for (image in snapshot.files()) {
            state.checkExportOpen()
            val output = File(folder, "scan-${UUID.randomUUID()}.${image.extension}")
            validateFile(output)
            val staged = File(folder, ".${output.name}.tmp")
            copies.add(staged to output)
            copyImage(image, staged)
          }
          Prepared(copies) { copies.map { Uri.fromFile(it.second).toString() } }
        } catch (error: Throwable) {
          copies.forEach { (temporary, _) -> delete(temporary) }
          throw error
        }
      }
    } catch (error: Throwable) { snapshot.close(); failed(error) }
  }

  private fun <T> submit(snapshot: SessionExportSnapshot, work: () -> Prepared<T>): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    val owner = state.resources.workOwner
    owner.queue.submit(owner, ScannerWorkQueue.Kind.PDF, operation = work,
      onSuccess = { prepared ->
        try {
          val value = state.publishExport { prepared.publish() }
          result.complete(value)
        } catch (error: Throwable) { result.completeExceptionally(error) }
        finally { prepared.close(); snapshot.close() }
      },
      onFailure = { error -> snapshot.close(); result.completeExceptionally(error) },
      onDiscard = { it.close() })
    return result
  }

  private inner class Prepared<T>(private val files: List<Pair<File, File>>, private val value: () -> T) : AutoCloseable {
    private var published = false
    fun publish(): T {
      val moved = mutableListOf<File>()
      try {
        for ((temporary, output) in files) {
          validateFile(output)
          ScannerFiles.move(temporary, output)
          moved.add(output)
        }
        val result = value()
        published = true
        return result
      } catch (error: Throwable) {
        moved.forEach(::delete)
        throw error
      }
    }
    override fun close() { if (!published) files.forEach { delete(it.first) } }
  }

  private fun validateFile(output: File) {
    state.resources.requireExternalOutput(output)
    val parent = output.parentFile
    requireInput(parent != null && parent.isDirectory && parent.canWrite() && !output.isDirectory) {
      "Output must be a file in an existing writable directory."
    }
  }

  private fun local(value: String): File {
    requireInput(value.isNotBlank()) { "Output path cannot be empty." }
    val uri = Uri.parse(value)
    val path = when (uri.scheme?.lowercase()) {
      null -> value
      "file" -> uri.path ?: throw InvalidInputException("Invalid file URI.")
      else -> throw InvalidInputException("Output must be an absolute local path or file URI.")
    }
    val file = File(path)
    requireInput(file.isAbsolute) { "Output must be absolute." }
    return file.canonicalFile
  }

  private fun delete(file: File) {
    if (file.exists() && !file.delete()) ScanSessionMainOwner.report(java.io.IOException("Could not remove an export staging file."))
  }

  private fun <T> failed(error: Throwable) = CompletableFuture<T>().also { it.completeExceptionally(error) }
}
