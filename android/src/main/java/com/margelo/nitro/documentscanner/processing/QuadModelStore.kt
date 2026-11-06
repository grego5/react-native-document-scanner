package com.margelo.nitro.documentscanner.processing

import android.content.Context
import java.security.MessageDigest

/** Process-wide immutable model bytes, consumed by the shared inference worker. */
internal object QuadModelStore {
  class Selection internal constructor(private val bytes: ByteArray) {
    @Volatile private var dimensions: CornerModelSize? = null
    /** Available once this selection has been opened or validated. */
    val size: CornerModelSize get() = checkNotNull(dimensions) { "Model dimensions have not been read yet." }
    @Volatile private var hash: String? = null

    fun open(context: Context) = DocCornerSession(context, modelBytes = bytes).also { dimensions = it.size }

    /** The model file's SHA-256 in hex, read once. */
    fun sha256(context: Context): String = hash ?: MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }.also { hash = it }
  }

  @Volatile var selected: Selection? = null
    private set
  val current: Selection get() = selected ?: throw com.margelo.nitro.documentscanner.ScannerException(
    com.margelo.nitro.documentscanner.ScannerErrorCode.MODEL_LOAD_FAILED)

  /** Caller runs this on a worker. No published state changes until validation and warm-up succeed. */
  fun prepare(context: Context, path: String): Selection {
    val bytes = ModelSources.local(context, path)
    return Selection(bytes).also { CornerInference.validate(context, it) }
  }

  fun activate(selection: Selection?) { selected = selection }
}
