package com.margelo.nitro.documentscanner.processing

/**
 * Whether the scene is dark, from the exposure time and ISO auto-exposure chooses: it turns dark after [SUSTAIN_NS] at
 * or above [TorchPolicy.DARK_ISO_MS] and bright again after as long under it, so a passing shadow does not flip it. A
 * flip rebinds the camera, so it must be rare. A lower threshold for light held the dark state: a lit room on the
 * SM-G998U read about 15,000, under the dark threshold but above half of it. Confined by its caller.
 */
internal class DarkScenePolicy {
  var dark = false
    private set
  private var changingSince: Long? = null

  /** Observation stopped: [dark] stands, but a flip under way restarts its second from the next frame, so time without
   * frames never counts. */
  fun interrupt() {
    changingSince = null
  }

  /** Returns true on the observation that flips [dark]. [iso] is the frame's sensitivity, null when unreported. */
  fun observe(observationNs: Long, exposureNs: Long, iso: Int?): Boolean {
    if (iso == null || iso <= 0 || exposureNs <= 0) {
      changingSince = null
      return false
    }
    val exposureIsoMs = exposureNs / 1e6 * iso
    val toward = (exposureIsoMs >= TorchPolicy.DARK_ISO_MS) != dark
    if (!toward) {
      changingSince = null
      return false
    }
    val since = changingSince ?: observationNs.also { changingSince = it }
    if (observationNs - since < SUSTAIN_NS) return false
    dark = !dark
    changingSince = null
    return true
  }

  companion object {
    const val SUSTAIN_NS = 1_000_000_000L
  }
}
