package com.margelo.nitro.documentscanner.processing

import android.content.Context
import java.io.File

/** Cache root only; all page deletion belongs to registered session resources. */
internal object CachedImageFiles {
  const val DIRECTORY_NAME = "react-native-document-scanner"
  fun directory(context: Context) = File(context.cacheDir, DIRECTORY_NAME)
}
