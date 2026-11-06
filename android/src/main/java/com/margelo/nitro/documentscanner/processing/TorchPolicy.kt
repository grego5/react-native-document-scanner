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
  fun observe(observationNs: Long, exposureNs: Long, iso: Int?): Boolean {
    if (lit) return false
    if (iso == null || iso <= 0 || exposureNs <= 0 || exposureNs / 1e6 * iso < DARK_ISO_MS) {
      darkSince = null
      return false
    }
    val since = darkSince ?: observationNs.also { darkSince = it }
    if (observationNs - since < SUSTAIN_NS) return false
    lit = true
    return true
  }

  companion object {
    /**
     * About 1/15 s at ISO 1600. A first setting of 20,000 (1/30 s at ISO 600) counted a lamp-lit room as dark on the
     * SM-G998U: it read 15,000 to 50,000 there and photos came out bright. Not yet measured in a room dim enough for
     * continuous autofocus to report focus on a blurred page.
     */
    const val DARK_ISO_MS = 100_000.0
    private const val SUSTAIN_NS = 1_000_000_000L
  }
}
