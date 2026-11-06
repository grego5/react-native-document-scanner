package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.processing.PdfWriter
import com.margelo.nitro.documentscanner.processing.CornerModelStore
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic source pixels and controlled FIFO; no camera or model inference. */
@RunWith(AndroidJUnit4::class)
class ScanSessionExportTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val automatic = DocumentCorners(DocumentPoint(.1, .1), DocumentPoint(.9, .1), DocumentPoint(.9, .9), DocumentPoint(.1, .9))
  private val later = DocumentCorners(DocumentPoint(.2, .2), DocumentPoint(.8, .2), DocumentPoint(.8, .8), DocumentPoint(.2, .8))

  private inner class Fixture : AutoCloseable {
    val root = File(context.cacheDir, "session-export-test-${UUID.randomUUID()}").apply { mkdirs() }
    val output = File(root, "exports").apply { mkdirs() }
    private val tasks = ArrayDeque<Runnable>()
    val worker = Executor { tasks.addLast(it) }
    val queue = ScannerWorkQueue(worker) { throw AssertionError(it) }
    val registry = ScanSessionRegistry(LocalSessionFiles, queue, Executor { it.run() }, {}, { throw AssertionError(it) })
    val resources = registry.open(root)
    val processing = SessionPageProcessing(context, resources)
    var analysis: DocumentAnalysis = DocumentAnalysis(160.0, 120.0, 0.0, DocumentCandidate(automatic, 1.0))
    var failAnalysis = false
    var failRender = false
    val renders = mutableListOf<ScanPage.RenderKey>()
    val state = ScanSessionState(resources, object : ScanSessionState.Actions {
      override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?) =
        { if (failAnalysis) throw IllegalStateException("analysis failed") else analysis }
      override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact {
        check(!failRender)
        return processing.render(original, key)
      }
      override fun renderSnapshot(source: SessionResources.Lease, key: ScanPage.RenderKey): ScanPage.Artifact {
        check(!failRender)
        renders.add(key)
        return processing.renderSnapshot(source, key)
      }
      override fun close() = processing.close()
    }, dispatcher = Executor { it.run() })
    val exports = SessionExports(state)
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    fun admit(id: String, color: Int = Color.WHITE, options: DocumentRenderOptions? = null, model: CornerModelStore.Selection? = null) {
      val original = resources.output(SessionResources.Kind.ORIGINAL).use { out ->
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { out.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
        finally { bitmap.recycle() }
        out.commit()
      }
      state.admit(id, original, null, null, options, model)
    }
    fun pdf(name: String = "scan.pdf") = SessionPdfOptions(File(output, name).path, null, null, null, null, null, null, null)
    override fun close() {
      val closed = state.close(); drain(); closed.get(10, TimeUnit.SECONDS)
      check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
      check(root.name.startsWith("session-export-test-")); root.deleteRecursively()
    }
  }

  @Test fun defaultExportFreezesSessionOrderBeforeReorderAndDeletion() = Fixture().use { f ->
    f.admit("red", Color.RED); f.admit("blue", Color.BLUE)
    f.state.setPageOrder(listOf("blue", "red"))
    val exported = f.exports.exportImages(null, f.output.path)
    f.state.setPageOrder(listOf("red", "blue"))
    f.state.removePage("blue")
    f.drain()
    val colors = exported.get().map { uri ->
      val bitmap = android.graphics.BitmapFactory.decodeFile(Uri.parse(uri).path)
      try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) } finally { bitmap.recycle() }
    }
    assertEquals(2, colors.size)
    assertTrue(Color.blue(colors[0]) > Color.red(colors[0]))
    assertTrue(Color.red(colors[1]) > Color.blue(colors[1]))
  }

  @Test fun pendingAutomaticAnalysisAndSettingsAreFrozenAcrossLaterEditsAndDeletion() = Fixture().use { f ->
    f.admit("page")
    val exported = f.exports.createPdf(listOf("page"), f.pdf())
    f.state.setDocumentCrop("page", later)
    f.state.setFilter("page", DocumentFilter.GRAYSCALE)
    f.state.removePage("page")
    f.drain()
    val file = File(Uri.parse(exported.get()).path!!)
    assertTrue(file.readText(Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
    assertEquals(automatic, f.renders.single().corners)
    assertEquals(DocumentFilter.NONE, f.renders.single().options.filter)
    f.state.close().get(10, TimeUnit.SECONDS)
    assertTrue("external PDF survives session cleanup", file.isFile)
  }

  @Test fun explicitFullFrameDuringAnalysisOverridesItsCandidateAndLaterDraft() = Fixture().use { f ->
    f.admit("page")
    f.state.setDocumentCrop("page", ScanPage.fullFrame())
    val exported = f.exports.exportImages(listOf("page"), f.output.path)
    f.state.setDocumentCrop("page", later)
    f.drain(); exported.get()
    assertEquals(ScanPage.fullFrame(), f.renders.single().corners)
  }

  @Test fun pendingEditsExportRequestedArtifactsRatherThanAnOlderDisplay() = Fixture().use { f ->
    f.admit("page"); f.drain()
    f.state.setFilter("page", DocumentFilter.GRAYSCALE)
    val exported = f.exports.exportImages(listOf("page"), f.output.path)
    f.state.setFilter("page", DocumentFilter.BLACKWHITE)
    f.state.removePage("page")
    f.drain(); exported.get()
    assertEquals(DocumentFilter.GRAYSCALE, f.renders.single().options.filter)
  }

  @Test fun duplicateIdsKeepInputOrderAndImmutableCopiesSurviveClose() = Fixture().use { f ->
    f.admit("a", Color.RED); f.admit("b", Color.BLUE); f.drain()
    val expectedA = f.state.exportSnapshot(listOf("a")).use { it.files().single().readBytes() }
    val expectedB = f.state.exportSnapshot(listOf("b")).use { it.files().single().readBytes() }
    val result = f.exports.exportImages(listOf("b", "a", "b"), f.output.path)
    f.state.removePage("a"); f.state.removePage("b"); f.drain()
    val files = result.get().map { File(Uri.parse(it).path!!) }
    assertEquals(3, files.map { it.name }.toSet().size)
    f.state.close().get(10, TimeUnit.SECONDS)
    assertArrayEquals(expectedB, files[0].readBytes())
    assertArrayEquals(expectedA, files[1].readBytes())
    assertArrayEquals(expectedB, files[2].readBytes())
    assertTrue("matching ready artifacts were reused", f.renders.isEmpty())
  }

  @Test fun unknownIdsCurrentFailuresAndPrivateDestinationsCreateNoOutput() = Fixture().use { f ->
    f.admit("page"); f.drain()
    assertThrows(InvalidInputException::class.java) { f.exports.createPdf(listOf("page", "missing"), f.pdf()) }
    val privatePath = File(f.resources.directory, "export.pdf")
    assertTrue(f.exports.createPdf(listOf("page"), f.pdf().copy(outputPath = privatePath.path)).isCompletedExceptionally)
    f.failRender = true
    f.state.setFilter("page", DocumentFilter.GRAYSCALE); f.drain()
    assertThrows(ScannerException::class.java) { f.exports.createPdf(listOf("page"), f.pdf()) }
    assertTrue(f.output.listFiles()!!.isEmpty())
  }

  @Test fun requiredAutomaticAnalysisFailureRejectsButExplicitGeometryDoesNotRequireIt() = Fixture().use { f ->
    f.failAnalysis = true; f.admit("page")
    val failed = f.exports.createPdf(listOf("page"), f.pdf())
    f.drain()
    assertTrue(failed.isCompletedExceptionally)
    assertTrue(f.output.listFiles()!!.isEmpty())
    f.state.setDocumentCrop("page", later)
    val explicit = f.exports.createPdf(listOf("page"), f.pdf())
    f.drain(); assertTrue(File(Uri.parse(explicit.get()).path!!).isFile)
  }

  @Test fun queuedCloseCancelsAndRunningCloseDiscardsStagedPdfBeforeReplacement() = Fixture().use { f ->
    f.admit("page"); f.drain()
    val pending = f.exports.createPdf(listOf("page"), f.pdf("queued.pdf"))
    f.state.close(); f.drain()
    assertTrue(pending.isCompletedExceptionally)
    assertTrue(f.output.listFiles()!!.isEmpty())
  }

  @Test fun runningCloseLeavesExistingDestinationUntouchedAndNoTemporaryOutput() = Fixture().use { f ->
    f.admit("page"); f.drain()
    val output = File(f.output, "scan.pdf").apply { writeText("existing") }
    val exports = SessionExports(f.state, writePdf = { options -> PdfWriter.write(options).also { f.state.close() } })
    val result = exports.createPdf(listOf("page"), f.pdf())
    f.drain()
    assertTrue(result.isCompletedExceptionally)
    assertEquals("existing", output.readText())
    assertEquals(listOf("scan.pdf"), f.output.listFiles()!!.map { it.name })
  }

  @Test fun partialImageCopiesRollBackWithoutPublishingAnyUris() = Fixture().use { f ->
    f.admit("page"); f.drain()
    var copied = 0
    val exports = SessionExports(f.state, copyImage = { source, target ->
      source.copyTo(target)
      if (++copied == 2) throw java.io.IOException("synthetic copy failure")
    })
    val result = exports.exportImages(listOf("page", "page", "page"), f.output.path)
    f.drain()
    assertTrue(result.isCompletedExceptionally)
    assertTrue(f.output.listFiles()!!.isEmpty())
  }

  @Test fun imagePublicationFailureRollsBackAlreadyMovedCopies() = Fixture().use { f ->
    f.admit("page"); f.drain()
    var copies = 0
    val exports = SessionExports(f.state, copyImage = { source, target ->
      source.copyTo(target)
      if (++copies == 2) File(target.parentFile, target.name.removePrefix(".").removeSuffix(".tmp")).mkdir()
    })
    val result = exports.exportImages(listOf("page", "page"), f.output.path)
    f.drain()
    assertTrue(result.isCompletedExceptionally)
    assertTrue("no moved images or staging files survive failure", f.output.listFiles()!!.none { it.isFile })
  }

  @Test fun closeDuringImageCopyRollsBackAllStaging() = Fixture().use { f ->
    f.admit("page"); f.drain()
    val exports = SessionExports(f.state, copyImage = { source, target -> source.copyTo(target); f.state.close() })
    val result = exports.exportImages(listOf("page", "page"), f.output.path)
    f.drain()
    assertTrue(result.isCompletedExceptionally)
    assertTrue(f.output.listFiles()!!.isEmpty())
  }

  @Test fun sampleAdmissionKeepsPageAnalysisAndModelAfterGlobalModelSwapAndDeletion(): Unit = Fixture().use { f ->
    val captured = CornerModelStore.Selection(byteArrayOf(1))
    val replacement = CornerModelStore.Selection(byteArrayOf(2))
    val previous = CornerModelStore.selected
    try {
      f.admit("page", model = captured); f.drain()
      CornerModelStore.activate(replacement)
      val sample = f.state.sampleSource("page")
      try {
        f.state.removePage("page")
        assertSame(captured, sample.model)
        assertEquals(f.analysis, sample.analysis)
        assertTrue(sample.original.file.isFile)
        val closing = f.state.close()
        assertFalse(closing.isDone)
        sample.original.close()
        closing.get(10, TimeUnit.SECONDS)
      } finally { sample.original.close() }
    } finally { CornerModelStore.activate(previous) }
  }

  @Test fun sampleQueuePinsTheOriginalAndTheInvocationFolderAcrossRemovalAndClose() = Fixture().use { f ->
    com.facebook.soloader.SoLoader.init(context, false)
    com.margelo.nitro.JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
    val collection = com.margelo.nitro.documentscanner.processing.SampleCollection
    val previous = collection.current(context)
    val folder = File(f.root, "samples-a").apply { mkdirs() }
    val laterFolder = File(f.root, "samples-b").apply { mkdirs() }
    val storage = ArrayDeque<Runnable>()
    try {
      collection.configure(context, Uri.fromFile(folder).toString(), true)
      // A validated model, so the sample can record its input size.
      val model = CornerModelStore.prepare(context, "asset:///doccornernet/model.tflite")
      f.admit("page", model = model)
      f.drain()
      val source = f.state.sampleSource("page")
      var name: String? = null
      var failure: Throwable? = null
      val saved = collection.saveCapture(context, source.original, Executor { storage.addLast(it) },
        analysis = source.analysis, model = source.model, liveCorners = later)
      saved.then { name = it }.catch { failure = it }
      collection.configure(context, Uri.fromFile(laterFolder).toString(), true)
      f.state.removePage("page")
      val closing = f.state.close()
      f.drain()
      assertFalse("storage admission retains the original", closing.isDone)
      while (storage.isNotEmpty()) storage.removeFirst().run()
      closing.get(10, TimeUnit.SECONDS)
      assertNull(failure)
      assertTrue(requireNotNull(name).matches(Regex("\\d+-\\d+\\.png")))
      val image = android.graphics.BitmapFactory.decodeFile(File(folder, name!!).path)
      assertTrue(image.width < 160 && image.height < 120)
      image.recycle()
      assertTrue(folder.listFiles()!!.none { it.extension == "jpg" })
      val info = org.json.JSONObject(File(folder, name!!.removeSuffix(".png") + ".json").readText())
      // The model's prediction starts the outline to correct; no page edit is ever recorded as a label.
      assertEquals("unlabeled", info.getString("status"))
      assertEquals(info.optJSONObject("prediction")?.get("corners")?.toString() ?: "null", info.get("corners").toString())
      assertEquals("photo-input", info.getString("source"))
      assertFalse(info.isNull("model"))
      assertTrue(laterFolder.listFiles()!!.isEmpty())
    } finally {
      while (storage.isNotEmpty()) storage.removeFirst().run()
      if (previous.uri == null) collection.clear(context)
      else collection.configure(context, previous.uri, previous.collecting)
    }
  }

  @Test fun noCandidateUsesFullFrameAndJpegPdfEmbedsUnchangedArtifactBytes() = Fixture().use { f ->
    f.analysis = DocumentAnalysis(160.0, 120.0, 0.0, null)
    f.admit("page")
    val original = f.state.leaseOriginal("page").use { it.file.readBytes() }
    val images = f.exports.exportImages(listOf("page"), f.output.path)
    var embeddedSource: ByteArray? = null
    val writer = SessionExports(f.state, writePdf = { options ->
      embeddedSource = File(Uri.parse(options.pages.single()).path!!).readBytes()
      PdfWriter.write(options)
    })
    val pdf = writer.createPdf(listOf("page"), f.pdf())
    f.drain()
    assertTrue(f.renders.all { it.corners == ScanPage.fullFrame() })
    assertEquals("jpg", File(Uri.parse(images.get().single()).path!!).extension)
    val jpeg = requireNotNull(embeddedSource)
    val bytes = File(Uri.parse(pdf.get()).path!!).readBytes()
    assertTrue(bytes.indices.any { offset -> offset + jpeg.size <= bytes.size && jpeg.indices.all { bytes[offset + it] == jpeg[it] } })
    f.state.leaseOriginal("page").use { assertArrayEquals(original, it.file.readBytes()) }
  }

  @Test fun autoFormatFollowsSavedFilterAndPreservesCapturedJpegQuality() = Fixture().use { f ->
    f.admit("page", options = DocumentRenderOptions(false, .81, DocumentFilter.NONE,
      DocumentImageFormat.AUTO, null, null, false))
    for (filter in listOf(DocumentFilter.NONE, DocumentFilter.BLACKWHITE, DocumentFilter.GRAYSCALE, DocumentFilter.NONE)) {
      f.state.setFilter("page", filter)
      val exported = f.exports.exportImages(null, f.output.path)
      f.drain()
      val file = File(Uri.parse(exported.get().single()).path!!)
      assertEquals(if (filter == DocumentFilter.BLACKWHITE) "png" else "jpg", file.extension)
      if (filter == DocumentFilter.BLACKWHITE) assertEquals(4, file.readBytes()[24].toInt())
      assertEquals(DocumentImageFormat.AUTO, f.state.snapshot().pages.getValue("page").options.format)
      assertEquals(.81, f.state.snapshot().pages.getValue("page").options.jpegQuality!!, .001)
    }
  }

  @Test fun grayscalePngCopiesAndPdfKeepFourBitEncoding() = Fixture().use { f ->
    f.admit("page", options = DocumentRenderOptions(false, null, DocumentFilter.GRAYSCALE,
      DocumentImageFormat.PNG, null, null, false))
    val images = f.exports.exportImages(listOf("page"), f.output.path)
    val pdf = f.exports.createPdf(listOf("page"), f.pdf())
    f.drain()
    val png = File(Uri.parse(images.get().single()).path!!)
    assertEquals("png", png.extension)
    assertEquals(4, png.readBytes()[24].toInt())
    val text = File(Uri.parse(pdf.get()).path!!).readText(Charsets.ISO_8859_1)
    assertTrue(text.contains("/BitsPerComponent 4"))
    assertTrue(text.contains("/FlateDecode"))
  }
}
