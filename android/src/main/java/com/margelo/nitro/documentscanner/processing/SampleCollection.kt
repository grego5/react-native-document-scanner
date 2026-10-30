package com.margelo.nitro.documentscanner.processing

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.DocumentQuad
import com.margelo.nitro.documentscanner.InvalidInputException
import com.margelo.nitro.documentscanner.SampleFolder
import com.margelo.nitro.documentscanner.SampleFolders
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/** An `analyzePhoto` result on a pending photo, made by [model]; the size is upright. */
private class PhotoAnalysis(
  val width: Int,
  val height: Int,
  val durationMs: Double,
  val candidate: DocumentCandidate?,
  val model: QuadModelStore.Selection,
)

/** A photo the Camera took while a folder was picked, kept until it is forgotten or pushed out by newer ones. */
private class PendingCapture(val shutterMs: Long, val shutterNs: Long, val frame: LiveFrameSnapshot?) {
  @Volatile var analysis: PhotoAnalysis? = null
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

/**
 * A photo opened before its save is queued, so deleting the file meanwhile cannot cut the save short. A cached photo
 * is a plain file, so each [read] starts over from its first byte; reads must not overlap.
 */
private class OpenPhoto(private val descriptor: ParcelFileDescriptor) : Closeable {
  fun read(): InputStream {
    val copy = descriptor.dup()
    Os.lseek(copy.fileDescriptor, 0, OsConstants.SEEK_SET)
    return ParcelFileDescriptor.AutoCloseInputStream(copy)
  }

  override fun close() = descriptor.close()

  companion object {
    fun open(context: Context, uri: Uri): OpenPhoto = OpenPhoto(
      if (uri.scheme == null) ParcelFileDescriptor.open(File(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
      else context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Could not open $uri."))
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
 * What a photo carries from its shutter to [SampleCollection.remember]: the last live frame before it, and which
 * collection it belongs to, so a stop in between voids it.
 */
internal class SampleTicket internal constructor(
  internal val generation: Long,
  internal val frame: LiveFrameSnapshot?,
  internal val shutterMs: Long,
  internal val shutterNs: Long,
)

/**
 * Sample collection: whether it is on, the frames kept for it, and the samples written only on request into the top of
 * the picked [SampleFolders] tree. Every change of state goes through [transition]: turning collection off, by a stop,
 * forgetting the folder or finding its grant revoked, drops every kept frame and voids frames in flight. Each image is
 * written as
 * `<ms>-<long edge>.png` or `.jpg` beside `<ms>-<long edge>.json`, one `tools/label-samples` entry whose quads are
 * normalized to the upright image, with the model's prediction and how it was made. A photo and its last live frame
 * before the shutter share the shutter's `<ms>`. Until then, each photo taken while collection is on keeps that frame
 * in memory, about 1.2 MB at 640 px, for the last [REMEMBERED] photos. Storage work runs in order on one thread, since
 * a picked tree can belong to a slow or remote provider.
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
  private val recorders = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<LiveFrameRecorder, Boolean>()))
  /** Serializes stored configuration reads and writes through publication; never taken while holding [pending]. */
  private val configurationLock = Any()
  // Guarded by [pending], which recorders' locks nest inside.
  /** The tree samples go into, null while collection is off; looked up once, then on each change. */
  private var tree: Uri? = null
  private var looked = false
  /** Counts turns off, so a frame from before one cannot come back. */
  private var generation = 0L

  fun register(recorder: LiveFrameRecorder) {
    recorders += recorder
  }

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

  /** The one change of state; turning off drops every kept frame and voids those in flight. */
  private fun transition(next: Uri?): Uri? = synchronized(pending) {
    val stopping = next == null && tree != null
    tree = next
    looked = true
    if (stopping) {
      generation++
      pending.clear()
      synchronized(recorders) { recorders.toList() }.forEach(LiveFrameRecorder::clear)
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
  fun beginCapture(context: Context, recorder: LiveFrameRecorder): SampleTicket? {
    var ticket: SampleTicket? = null
    whileOn(context) {
      ticket = SampleTicket(generation, recorder.latest(), System.currentTimeMillis(), System.nanoTime())
    }
    return ticket
  }

  /** Any thread: keeps [ticket]'s frame for the photo named [photoName], unless collection turned off since. */
  fun remember(photoName: String, ticket: SampleTicket) {
    synchronized(pending) {
      if (tree != null && ticket.generation == generation) {
        pending[photoName] = PendingCapture(ticket.shutterMs, ticket.shutterNs, ticket.frame)
      }
    }
  }

  /** Any thread: notes [model]'s `analyzePhoto` result on a pending photo; [width] and [height] are upright. */
  fun noteAnalysis(photoName: String, width: Double, height: Double, durationMs: Double, candidate: DocumentCandidate?,
    model: QuadModelStore.Selection) {
    synchronized(pending) { pending[photoName] }?.analysis =
      PhotoAnalysis(width.toInt(), height.toInt(), durationMs, candidate, model)
  }

  fun forget(photoNames: Collection<String>) {
    synchronized(pending) { photoNames.forEach(pending::remove) }
  }

  /** Saves [frame], the latest live inference, with the model's raw output; resolves with its name. */
  fun saveLive(context: Context, frame: LiveFrameSnapshot): Promise<String> {
    val askedNs = System.nanoTime()
    val askedMs = System.currentTimeMillis()
    return submit(context) { folder ->
      writeFrame(context, folder, askedMs, frame, null) { info ->
        info.put("msBeforeSave", (askedNs - frame.recordedNs) / 1_000_000.0)
      }
    }
  }

  /**
   * Saves the photo at [originalUri] with [label], the outline the user accepted, or unlabeled without one, and its
   * pending live frame; resolves with the photo's name.
   */
  fun saveCapture(context: Context, originalUri: String, label: DocumentQuad?): Promise<String> {
    val uri = Uri.parse(originalUri)
    val capture = uri.lastPathSegment?.let { synchronized(pending) { pending[it] } }
    val id = capture?.shutterMs ?: System.currentTimeMillis()
    val photo = try {
      OpenPhoto.open(context, uri)
    } catch (error: Exception) {
      return Promise<String>().also { it.reject(error) }
    }
    return submit(context, photo) { folder ->
      val saved = capture?.savedIn(folder.tree)
      val analysis = capture?.analysis
      val (width, height) = analysis?.let { it.width to it.height } ?: uprightSize(photo)
      val stem = "$id-${maxOf(width, height)}"
      val frameName = capture?.frame?.let { frame ->
        writeFrame(context, folder, id, frame, saved) { info ->
          info.put("msBeforeShutter", (capture.shutterNs - frame.recordedNs) / 1_000_000.0)
        }
      }
      val digest = MessageDigest.getInstance("SHA-256")
      folder.write("$stem.jpg", "image/jpeg", saved) { output ->
        DigestInputStream(photo.read(), digest).use { it.copyTo(output) }
      }
      val prediction = analysis?.candidate
      val info = entry(context, "$stem.jpg", hex(digest.digest()), width, height, label ?: prediction?.quad, label != null,
        analysis?.model)
        .put("source", "photo")
        .put("prediction", prediction?.let { JSONObject().put("quad", quad(it.quad)).put("score", it.score) } ?: JSONObject.NULL)
        .put("analysisMs", analysis?.durationMs ?: JSONObject.NULL)
        .put("liveFrame", frameName ?: JSONObject.NULL)
      writeJson(folder, "$stem.json", info, saved)
      "$stem.jpg"
    }
  }

  /** Queues [write] into the picked folder, then closes [held] whether or not it ran. */
  private fun submit(context: Context, held: Closeable? = null, write: (FolderWriter) -> String): Promise<String> {
    val promise = Promise<String>()
    val tree = active(context)
    if (tree == null) {
      held?.close()
      promise.reject(InvalidInputException("Sample collection is off."))
      return promise
    }
    executor.execute {
      try {
        promise.resolve(write(FolderWriter(context.contentResolver, tree)))
      } catch (error: Throwable) {
        // A grant revoked in settings shows up as a failed write; noticing it turns collection off.
        refresh(context)
        promise.reject(error)
      } finally {
        held?.close()
      }
    }
    return promise
  }

  /** Writes [frame] upright as `<id>-<long edge>.png` with its JSON, which [extra] adds to; returns the image's name. */
  private fun writeFrame(
    context: Context,
    folder: FolderWriter,
    id: Long,
    frame: LiveFrameSnapshot,
    saved: MutableMap<String, Uri>?,
    extra: (JSONObject) -> Unit,
  ): String {
    val turned = frame.rotation % 180 != 0
    val width = if (turned) frame.height else frame.width
    val height = if (turned) frame.width else frame.height
    val stem = "$id-${maxOf(width, height)}"
    val png = uprightPng(frame)
    folder.write("$stem.png", "image/png", saved) { it.write(png) }
    val info = entry(context, "$stem.png", hex(MessageDigest.getInstance("SHA-256").digest(png)), width, height,
      frame.candidate, false, frame.model)
      .put("source", "live")
      .put("prediction", frame.candidate?.let { JSONObject().put("quad", quad(it)).put("score", frame.score) }
        ?: JSONObject.NULL)
      .put("sensorRotation", frame.rotation)
      .put("sensorTimestampNs", frame.sensorTimestampNs)
    extra(info)
    writeJson(folder, "$stem.json", info, saved)
    return "$stem.png"
  }

  /**
   * A `tools/label-samples` entry; [quad] starts the outline, confirmed only when [labeled]. [model] made the
   * prediction; null when there is none.
   */
  private fun entry(context: Context, image: String, sha256: String, width: Int, height: Int, quad: DocumentQuad?,
    labeled: Boolean, model: QuadModelStore.Selection?): JSONObject {
    return JSONObject()
      .put("version", 2)
      .put("coordinateSpace", "exifUprightNormalized")
      .put("image", image)
      .put("sha256", sha256)
      .put("sourceWidth", width)
      .put("sourceHeight", height)
      .put("status", if (labeled) "document" else "unlabeled")
      .put("quad", quad?.let(::quad) ?: JSONObject.NULL)
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

  private fun uprightPng(frame: LiveFrameSnapshot): ByteArray {
    val sensor = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
    sensor.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
    val upright = if (frame.rotation == 0) sensor else Bitmap.createBitmap(sensor, 0, 0, frame.width, frame.height,
      Matrix().apply { postRotate(frame.rotation.toFloat()) }, false)
    try {
      return ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    } finally {
      if (upright !== sensor) upright.recycle()
      sensor.recycle()
    }
  }

  /** The photo's width and height after its EXIF orientation. */
  private fun uprightSize(photo: OpenPhoto): Pair<Int, Int> {
    val orientation = photo.read().use {
      ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    photo.read().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Could not read the photo's size.")
    val turned = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
    return if (turned) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
  }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun quad(quad: DocumentQuad) = JSONObject()
    .put("topLeft", JSONObject().put("x", quad.topLeft.x).put("y", quad.topLeft.y))
    .put("topRight", JSONObject().put("x", quad.topRight.x).put("y", quad.topRight.y))
    .put("bottomRight", JSONObject().put("x", quad.bottomRight.x).put("y", quad.bottomRight.y))
    .put("bottomLeft", JSONObject().put("x", quad.bottomLeft.x).put("y", quad.bottomLeft.y))
}
