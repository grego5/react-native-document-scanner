package com.margelo.nitro.documentscanner

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Android filesystem primitives available since API 21. */
internal object ScannerFiles {
  private fun stat(file: File): android.system.StructStat? = try {
    Os.lstat(file.path)
  } catch (error: ErrnoException) {
    if (error.errno == OsConstants.ENOENT) null else throw error
  }

  fun isSymbolicLink(file: File): Boolean = stat(file)?.let { OsConstants.S_ISLNK(it.st_mode) } ?: false

  fun delete(file: File) {
    try {
      Os.remove(file.path)
    } catch (error: ErrnoException) {
      if (error.errno != OsConstants.ENOENT) throw error
    }
  }

  /** Same-filesystem POSIX rename atomically replaces the destination; failures are explicit. */
  fun move(source: File, destination: File) = Os.rename(source.path, destination.path)
}
