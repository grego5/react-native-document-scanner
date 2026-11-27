package com.margelo.nitro.documentscanner

/** Apply the scanner's one-scan-per-scene policy to measured OCR text edges. */
internal class TextFocusCycle {
  private val cycle = FocusCycle()
  private var generation = Long.MIN_VALUE
  private var sharp = 0
  private var soft = 0
  private var changed = false
  val awaitingScan: Boolean get() = cycle.needsScan
  var verified = false
    private set

  fun reset() {
    cycle.reset()
    changed = false
    clearEdges()
  }

  fun sceneChanged() { changed = true }

  fun failed() { cycle.failed("text lock failed") }

  fun started(): String {
    clearEdges()
    return cycle.started()
  }

  fun wantsScan(sensorNs: Long, quiet: Boolean, turned: Boolean, lens: FocusCycle.Lens,
    generation: Long, edges: Double?,
  ): Boolean {
    if (this.generation != generation) {
      this.generation = generation
      clearEdges()
    }
    if (lens == FocusCycle.Lens.LOCKED && quiet && edges != null) {
      if (BlurWatch.sharp(edges)) {
        sharp++
        soft = 0
      } else {
        soft++
        sharp = 0
      }
    }
    val print = when {
      sharp >= BlurWatch.LEAVE -> FocusCycle.Print.SHARP
      soft >= BlurWatch.ENTER -> FocusCycle.Print.DEFOCUSED
      else -> FocusCycle.Print.UNSURE
    }
    cycle.motion(sensorNs, quiet)
    if (cycle.observe(lens, generation, print, turned, if (changed) "text view changed" else null)) clearEdges()
    changed = false
    verified = lens == FocusCycle.Lens.LOCKED && quiet && edges != null && sharp >= BlurWatch.LEAVE && !cycle.needsScan
    return quiet && lens != FocusCycle.Lens.SCANNING && cycle.needsScan
  }

  private fun clearEdges() {
    sharp = 0
    soft = 0
    verified = false
  }
}
