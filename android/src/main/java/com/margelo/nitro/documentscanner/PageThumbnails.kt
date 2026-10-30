package com.margelo.nitro.documentscanner

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import com.margelo.nitro.NitroModules
import com.margelo.nitro.documentscanner.processing.SourceImageLoader
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live-frame-size copies of page images, shared across the library and found by the page's uri. A render and a
 * capture's latest frame add theirs as they are written, so a pager shows them without decoding; a pager makes the rest.
 * Thread-safe. Cached bitmaps are never recycled, since views may still draw them.
 */
internal object PageThumbnails {
  /** The live analysis frame's longer side, which the camera sets as it binds; no thumbnail is larger. */
  @Volatile var longSide = DEFAULT_LONG_SIDE

  private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
  }

  /** Thumbnail files by page uri, so an evicted thumbnail decodes from its small file instead of the page. */
  private val files = ConcurrentHashMap<String, File>()

  fun cached(uri: String): Bitmap? = memory.get(uri)

  /** Adds [bitmap] as the thumbnail of [uri], and [file] as its source once evicted. */
  fun put(uri: String, bitmap: Bitmap, file: File? = null) {
    NitroModules.applicationContext?.let(::watchMemory)
    memory.put(uri, bitmap)
    if (file != null) files[uri] = file
  }

  /** The thumbnail of [uri]: cached, from its thumbnail file, or decoded small from the page itself. Worker threads. */
  fun load(uri: String): Bitmap {
    memory.get(uri)?.let { return it }
    val source = files[uri]?.takeIf(File::isFile)?.let { Uri.fromFile(it).toString() } ?: uri
    return SourceImageLoader.loadReduced(source, longSide).also { memory.put(uri, it) }
  }

  /**
   * Forgets thumbnails of deleted [uris], whether pages or thumbnail files. Views still drawing a forgotten bitmap keep
   * it, since cached bitmaps are never recycled.
   */
  fun forget(uris: Collection<String>) {
    uris.forEach {
      memory.remove(it)
      files.remove(it)
    }
    val paths = uris.mapNotNullTo(HashSet()) { Uri.parse(it).path }
    files.values.removeIf { it.path in paths }
  }

  /** Drops the cached bitmaps; files stay. */
  fun trim() = memory.evictAll()

  /**
   * Empties the cache once the app is in the background, where a smaller process is less likely to be killed; thumbnails
   * come back from their small files. Since Android 14 only UI_HIDDEN and BACKGROUND are delivered, and no level
   * warns before a kill. One callback serves the whole process.
   */
  fun watchMemory(context: Context) {
    if (!watching.compareAndSet(false, true)) return
    context.applicationContext.registerComponentCallbacks(memoryCallbacks)
  }

  /** Releases [loader]'s full image whenever the app's UI is hidden; held weakly. Main thread only. */
  fun onUiHidden(loader: ImageLoader) {
    loaders += loader
  }

  /** The application callback, on the main thread; internal so tests can deliver a trim level. */
  internal val memoryCallbacks = object : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
      if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) loaders.toList().forEach(ImageLoader::release)
      if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) trim()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Superseded by onTrimMemory.")
    override fun onLowMemory() = Unit
  }

  private val watching = AtomicBoolean()
  private val loaders: MutableSet<ImageLoader> = Collections.newSetFromMap(WeakHashMap())

  private const val DEFAULT_LONG_SIDE = 640
  /** About ten 640 x 480 thumbnails. */
  private const val MEMORY_BYTES = 12 * 1024 * 1024
}
