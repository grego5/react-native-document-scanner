package com.margelo.nitro.documentscanner.processing

internal object OpenCvRuntime {
  init { System.loadLibrary("opencv_java4") }
  fun ensureLoaded() = Unit
}
