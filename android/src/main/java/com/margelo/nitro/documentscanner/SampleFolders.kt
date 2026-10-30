package com.margelo.nitro.documentscanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import com.margelo.nitro.NitroModules

/**
 * Where collected samples go, as stored: one Storage Access Framework tree the user picked, such as one in Downloads or
 * Documents, held with a persisted grant so it survives restarts, and whether collection into it is stopped. Picking
 * another releases the previous grant, and a grant the user revoked in settings counts as none. Whether collection is
 * on is decided by `SampleCollection`, which reads this. Main thread, except [granted], [stopped] and [displayName].
 */
internal object SampleFolders {
  private var launcher: ActivityResultLauncher<Uri?>? = null
  private val waiting = mutableListOf<(Result<Uri?>) -> Unit>()

  /** Any thread: the picked tree while its grant holds, else null. */
  fun granted(context: Context): Uri? =
    preferences(context).getString(KEY_TREE, null)?.let(Uri::parse)?.takeIf { it.scheme == "file" || granted(context, it) }

  /** Any thread: collection into the picked tree is stopped. */
  fun stopped(context: Context) = preferences(context).getBoolean(KEY_STOPPED, false)

  fun setStopped(context: Context, stopped: Boolean) {
    preferences(context).edit().putBoolean(KEY_STOPPED, stopped).apply()
  }

  /**
   * Opens the system folder picker and returns the picked tree, or null on cancellation. The collection owner stores
   * it with [remember] together with publishing its new state.
   */
  fun pick(onResult: (Result<Uri?>) -> Unit) {
    waiting += onResult
    if (launcher != null) return
    val activity = NitroModules.applicationContext?.currentActivity
    if (activity !is ActivityResultRegistryOwner) {
      settle(Result.failure(IllegalStateException("Picking a folder needs a foreground Android activity.")))
      return
    }
    try {
      launcher = activity.activityResultRegistry.register(REQUEST_KEY, ActivityResultContracts.OpenDocumentTree()) { tree ->
        settle(Result.success(tree))
      }
      launcher?.launch(null)
    } catch (error: Throwable) {
      settle(Result.failure(IllegalStateException("Android could not open the folder picker.", error)))
    }
  }

  /** Releases the picked folder's grant and forgets it. */
  fun clear(context: Context) {
    preferences(context).getString(KEY_TREE, null)?.let { release(context, Uri.parse(it)) }
    preferences(context).edit().remove(KEY_TREE).remove(KEY_STOPPED).apply()
  }

  fun remember(context: Context, tree: Uri) {
    requireInput(tree.scheme == "file" || (tree.scheme == "content" && DocumentsContract.isTreeUri(tree))) {
      "Sample folder must be a file directory URI or a SAF tree URI."
    }
    if (tree.scheme == "file") {
      requireInput(tree.authority.isNullOrEmpty() && !tree.path.isNullOrEmpty()) { "Invalid sample directory URI." }
      val directory = File(tree.path!!)
      requireInput(directory.isAbsolute && (directory.isDirectory || directory.mkdirs()) && directory.canWrite()) { "Sample directory is not writable." }
    } else context.contentResolver.takePersistableUriPermission(tree, FLAGS)
    val previous = preferences(context).getString(KEY_TREE, null)?.let(Uri::parse)
    if (previous != null && previous != tree) release(context, previous)
    preferences(context).edit().putString(KEY_TREE, tree.toString()).putBoolean(KEY_STOPPED, false).apply()
  }

  private fun release(context: Context, tree: Uri) {
    if (tree.scheme == "content" && granted(context, tree)) runCatching { context.contentResolver.releasePersistableUriPermission(tree, FLAGS) }
  }

  private fun granted(context: Context, tree: Uri) =
    context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

  fun displayName(context: Context, tree: Uri): String? = runCatching {
    if (tree.scheme == "file") return@runCatching File(tree.path!!).name
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
  }.getOrNull()

  private fun settle(result: Result<Uri?>) {
    launcher?.unregister()
    launcher = null
    val callers = waiting.toList()
    waiting.clear()
    callers.forEach { it(result) }
  }

  private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  // Unchanged from the earlier debug captures, so a folder picked for them stays picked.
  private const val REQUEST_KEY = "react-native-document-scanner.debug-capture-folder"
  private const val PREFERENCES_NAME = "react-native-document-scanner.debug-captures"
  private const val KEY_TREE = "tree"
  private const val KEY_STOPPED = "stopped"
  private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
