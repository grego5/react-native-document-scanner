package com.margelo.nitro.documentscanner.processing

/** Auto-torch decision, confined by its caller. Auto-exposure already measures the room: exposure time times ISO
 * grows as light falls, so sustained dark frames light the torch with no pixel work. Once lit, that measure reflects
 * the torch rather than the room, so the decision holds until [reset].
 */
internal class TorchPolicy {
  private var darkSince: Long? = null
  var lit = false
    private set

  fun reset() {
    darkSince = null
    lit = false
  }

  /** Returns true on the frame that lights the torch. [iso] is the frame's sensitivity, null when unreported. */
  fun observe(timestampNs: Long, exposureNs: Long, iso: Int?): Boolean {
    if (lit) return false
    if (iso == null || iso <= 0 || exposureNs <= 0 || exposureNs / 1e6 * iso < DARK_ISO_MS) {
      darkSince = null
      return false
    }
    val since = darkSince ?: timestampNs.also { darkSince = it }
    if (timestampNs - since < SUSTAIN_NS) return false
    lit = true
    return true
  }

  companion object {
    /** About 1/30 s at ISO 600, roughly 40 lux at f/1.8: dim enough for noise and motion blur. A starting setting. */
    const val DARK_ISO_MS = 20_000.0
    private const val SUSTAIN_NS = 1_000_000_000L
  }
}
