package com.margelo.nitro.documentscanner.processing

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.DocumentAnalysis
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.SessionResources
import com.margelo.nitro.documentscanner.requireInput
import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.InvalidInputException
import com.margelo.nitro.documentscanner.SampleFolder
import com.margelo.nitro.documentscanner.SampleFolders
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONObject

/** An analysis result on a pending photo, made by [model]. */
private class SamplePhotoAnalysis(
  val durationMs: Double,
  val candidate: DocumentCandidate?,
  val model: CornerModelStore.Selection,
)

/** A photo the Camera took while a folder was picked, kept until it is forgotten or pushed out by newer ones. */
private class PendingCapture(val shutterMs: Long) {
  // Storage thread only.
  private var savedTree: Uri? = null
  private val saved = HashMap<String, Uri>()

  /** Files already saved for this photo into [tree], by name, so saving it there again replaces them. */
  fun savedIn(tree: Uri): MutableMap<String, Uri> {
    if (tree != savedTree) {
      saved.clear()
      savedTree = tree
    }
    return saved
  }
}

/** The picked [tree]'s top folder; [write] creates a file there, or replaces one [saved] holds. */
private class FolderWriter(private val resolver: ContentResolver, val tree: Uri) {
  private val root = if (tree.scheme == "file") tree else
    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

  fun write(name: String, mimeType: String, saved: MutableMap<String, Uri>?, body: (OutputStream) -> Unit) {
    if (tree.scheme == "file") {
      val directory = File(tree.path!!)
      if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create the sample directory.")
      File(directory, name).outputStream().use(body)
      return
    }
    val earlier = saved?.get(name)?.let { uri ->
      try {
        resolver.openOutputStream(uri, "wt")
      } catch (_: FileNotFoundException) {
        null
      }
    }
    val output = earlier ?: run {
      val created = DocumentsContract.createDocument(resolver, root, mimeType, name)
        ?: throw IOException("Could not create $name in the sample folder.")
      saved?.put(name, created)
      resolver.openOutputStream(created, "wt") ?: throw IOException("Could not open $name.")
    }
    output.use(body)
  }
}

/**
 * What a photo carries from its shutter to [SampleCollection.remember]: its shutter time, and which collection it
 * belongs to, so a stop in between voids it.
 */
internal class SampleTicket internal constructor(internal val generation: Long, internal val shutterMs: Long)

/**
 * Sample collection: whether it is on, and the samples written only on request into the top of the picked
 * [SampleFolders] tree. Every change of state goes through [transition]: turning collection off, by a stop, forgetting
 * the folder or finding its grant revoked, forgets pending photos and voids those in flight. Each image is written as
 * `<shutter ms>-<long edge>.png` beside its `.json`, one `tools/label-samples` entry whose cornerSets are normalized to the
 * image, with the model's prediction and how it was made. The last [REMEMBERED] photos taken while collection is on
 * keep their shutter time. Storage work runs in order on one thread, since a picked tree can belong to a slow or
 * remote provider.
 */
internal object SampleCollection {
  private const val REMEMBERED = 8
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "DocumentScannerSampleCollection").apply { isDaemon = true }
  }
  /** By photo file name; the oldest are forgotten. */
  private val pending = object : LinkedHashMap<String, PendingCapture>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingCapture>) = size > REMEMBERED
  }
  /** Serializes stored configuration reads and writes through publication; never taken while holding [pending]. */
  private val configurationLock = Any()
  // Guarded by [pending].
  /** The tree samples go into, null while collection is off; looked up once, then on each change. */
  private var tree: Uri? = null
  private var looked = false
  /** Counts turns off, so a photo from before one cannot come back. */
  private var generation = 0L

  /** Any thread: the tree samples go into, or null while collection is off. */
  fun active(context: Context): Uri? {
    synchronized(pending) { if (looked) return tree }
    return refresh(context)
  }

  /** Any thread: the picked folder while its grant holds, else an empty value; notices a revoked grant. */
  fun current(context: Context): SampleFolder = synchronized(configurationLock) {
    val folder = SampleFolders.granted(context)
    val active = transition(folder?.takeUnless { SampleFolders.stopped(context) })
    if (folder == null) SampleFolder(null, null, false)
    else SampleFolder(folder.toString(), SampleFolders.displayName(context, folder), active != null)
  }

  /** Main thread: opens the folder picker; a picked folder turns collection on. Resolves with the folder in use. */
  fun pick(context: Context, onResult: (Result<SampleFolder>) -> Unit) {
    SampleFolders.pick { result ->
      val selected = result.mapCatching { folder ->
        synchronized(configurationLock) {
          if (folder != null) SampleFolders.remember(context, folder)
          current(context)
        }
      }
      onResult(selected)
    }
  }

  /** Patches folder and enabled state under one configuration lock, without briefly enabling a stopped collection. */
  fun configure(context: Context, folderUri: String?, enabled: Boolean?) {
    synchronized(configurationLock) {
      val wasOn = active(context) != null
      if (folderUri != null) SampleFolders.remember(context, Uri.parse(folderUri))
      SampleFolders.setStopped(context, !(enabled ?: wasOn))
      refresh(context)
    }
  }

  /** Releases the picked folder, which turns collection off. */
  fun clear(context: Context) {
    synchronized(configurationLock) {
      SampleFolders.clear(context)
      refresh(context)
    }
  }

  /** Reads the stored folder and moves to it; returns the tree samples go into. */
  private fun refresh(context: Context): Uri? = synchronized(configurationLock) {
    transition(SampleFolders.granted(context)?.takeUnless { SampleFolders.stopped(context) })
  }

  /** The one change of state; turning off forgets pending photos and voids those in flight. */
  private fun transition(next: Uri?): Uri? = synchronized(pending) {
    val stopping = next == null && tree != null
    tree = next
    looked = true
    if (stopping) {
      generation++
      pending.clear()
    }
    next
  }

  /**
   * Any thread: runs [keep] while collection is on and has not turned off since [generation], or since now when null,
   * atomically with turning off; returns the generation it ran in, or null.
   */
  internal fun whileOn(context: Context, generation: Long? = null, keep: () -> Unit): Long? {
    active(context) ?: return null
    synchronized(pending) {
      if (tree == null || generation != null && generation != this.generation) return null
      keep()
      return this.generation
    }
  }

  /** Any thread: at a shutter, the ticket its photo passes to [remember], or null while collection is off. */
  fun beginCapture(context: Context): SampleTicket? {
    var ticket: SampleTicket? = null
    whileOn(context) { ticket = SampleTicket(generation, System.currentTimeMillis()) }
    return ticket
  }

  /** Any thread: keeps [ticket]'s shutter time for the photo named [photoName], unless collection turned off since. */
  fun remember(photoName: String, ticket: SampleTicket) {
    synchronized(pending) {
      if (tree != null && ticket.generation == generation) {
        pending[photoName] = PendingCapture(ticket.shutterMs)
      }
    }
  }

  fun forget(photoNames: Collection<String>) {
    synchronized(pending) { photoNames.forEach(pending::remove) }
  }

  /** ID admission has already pinned this original under the session state lock. */
  fun saveCapture(context: Context, lease: SessionResources.Lease, worker: Executor = executor,
    analysis: DocumentAnalysis? = null, model: CornerModelStore.Selection? = null, liveCorners: DocumentCorners? = null): Promise<String> =
    try {
      requireInput(analysis != null && model != null) { "Photo analysis is not available for this sample." }
      val provenance = SamplePhotoAnalysis(analysis!!.duration, analysis.candidate, model!!)
      val capture = synchronized(pending) { pending[lease.file.name] }
      val id = capture?.shutterMs ?: System.currentTimeMillis()
      submit(context, Closeable { lease.close() }, worker) { folder ->
        writePhotoInput(context, folder, Uri.fromFile(lease.file).toString(), id, liveCorners, provenance,
          capture?.savedIn(folder.tree))
      }
    }
    catch (error: Throwable) { lease.close(); Promise<String>().also { it.reject(error) } }

  /** Recreate the lossless upright bitmap passed to photo inference, with the model's prediction on it. */
  private fun writePhotoInput(context: Context, folder: FolderWriter, uri: String, id: Long, seed: DocumentCorners?,
    analysis: SamplePhotoAnalysis, saved: MutableMap<String, Uri>?): String =
    SourceImageLoader.inspect(uri).let { header ->
      val input = PhotoAnalysis.loadInput(header, seed)
      val crop = input.region
      val region = input.bitmap
      try {
        val png = ByteArrayOutputStream().also { check(region.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val stem = "$id-${maxOf(region.width, region.height)}"
        folder.write("$stem.png", "image/png", saved) { it.write(png) }
        val prediction = analysis.candidate
        val mappedPrediction = prediction?.corners?.let { crop.fromPhoto(it, header.uprightWidth, header.uprightHeight) }
        val info = entry(context, "$stem.png", hex(MessageDigest.getInstance("SHA-256").digest(png)),
          region.width, region.height, mappedPrediction, analysis.model)
          .put("source", "photo-input")
          .put("prediction", if (mappedPrediction != null) JSONObject().put("quad", corners(mappedPrediction))
            .put("score", prediction!!.score) else JSONObject.NULL)
          .put("analysisMs", analysis.durationMs)
        writeJson(folder, "$stem.json", info, saved)
        "$stem.png"
      } finally {
        region.recycle()
      }
    }

  /** Queues [write] into the picked folder, then closes [held] whether or not it ran. */
  private fun submit(context: Context, held: Closeable? = null, worker: Executor = executor, write: (FolderWriter) -> String): Promise<String> {
    val promise = Promise<String>()
    val tree = try { active(context) } catch (error: Throwable) {
      held?.close()
      promise.reject(error)
      return promise
    }
    if (tree == null) {
      held?.close()
      promise.reject(InvalidInputException("Sample collection is off."))
      return promise
    }
    try {
      worker.execute {
        try {
          promise.resolve(write(FolderWriter(context.contentResolver, tree)))
        } catch (error: Throwable) {
          // A grant revoked in settings shows up as a failed write; noticing it turns collection off.
          try { refresh(context) } finally { promise.reject(error) }
        } finally {
          held?.close()
        }
      }
    } catch (error: Throwable) {
      held?.close()
      promise.reject(error)
    }
    return promise
  }

  /**
   * An unlabeled `tools/label-samples` entry; the model's [corners] starts the outline to correct. [model] made the
   * prediction; null when there is none.
   */
  private fun entry(context: Context, image: String, sha256: String, width: Int, height: Int, corners: DocumentCorners?,
    model: CornerModelStore.Selection?): JSONObject {
    return JSONObject()
      .put("version", 2)
      .put("coordinateSpace", "exifUprightNormalized")
      .put("image", image)
      .put("sha256", sha256)
      .put("sourceWidth", width)
      .put("sourceHeight", height)
      .put("status", "unlabeled")
      .put("quad", corners?.let(::corners) ?: JSONObject.NULL)
      .put("savedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
      .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
      .put("model", model?.let {
        JSONObject()
          .put("sha256", it.sha256(context))
          .put("inputWidth", it.size.width)
          .put("inputHeight", it.size.height)
      } ?: JSONObject.NULL)
  }

  private fun writeJson(folder: FolderWriter, name: String, info: JSONObject, saved: MutableMap<String, Uri>?) =
    folder.write(name, "application/json", saved) { it.write((info.toString(2) + "\n").toByteArray()) }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun corners(corners: DocumentCorners) = JSONObject()
    .put("topLeft", JSONObject().put("x", corners.topLeft.x).put("y", corners.topLeft.y))
    .put("topRight", JSONObject().put("x", corners.topRight.x).put("y", corners.topRight.y))
    .put("bottomRight", JSONObject().put("x", corners.bottomRight.x).put("y", corners.bottomRight.y))
    .put("bottomLeft", JSONObject().put("x", corners.bottomLeft.x).put("y", corners.bottomLeft.y))
}
