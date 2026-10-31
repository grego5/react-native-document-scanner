package com.margelo.nitro.documentscanner.processing

/** Process-wide Android diopters; metadata writes and main-thread reads share one volatile value. */
internal object LearnedFocusPosition {
  @Volatile private var position: Double? = null
  fun learn(value: Double?) { position = value?.takeIf { it.isFinite() } }
  fun value(): Double? = position
}
