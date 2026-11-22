package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLensFocusTest {
  private class Host : TextLensFocus.Host {
    var lens: Float? = null
    var exposureLocked = false
    var autofocus = 0
    var remembered: Float? = null
    override fun moveLens(diopters: Float): Boolean { lens = diopters; return true }
    override fun lockExposure(locked: Boolean) { exposureLocked = locked }
    override fun cameraAutofocus() { autofocus++; lens = null }
    override fun remember(diopters: Float) { remembered = diopters }
    val messages = ArrayList<String>()
    override val logging = true
    override fun log(message: String) { messages += message }
  }

  private val host = Host()
  private val focus = TextLensFocus(host)
  private var time = 0L

  /**
   * What the region shows: text with [content] detail, sharpest at [peak] diopters, or with no peak a flat field, or with
   * no content nothing scorable. Defocus takes fine detail faster than coarse, as a lens does.
   */
  private class Scene(val peak: Float?, val content: Double?) {
    fun fine(d: Float) = content?.let { c -> peak?.let { c / (1 + 4 * (d - it) * (d - it)) } ?: c }
    fun coarse(d: Float) = content?.let { c -> peak?.let { c / (1 + (d - it) * (d - it)) } ?: c }
  }

  private fun textAt(peak: Float, content: Double = 1000.0) = Scene(peak, content)
  private fun flat(content: Double) = Scene(null, content)
  private val unscorable = Scene(null, null)

  /** A frame of [scene] at the lens's requested distance. */
  private fun frame(scene: Scene, quiet: Boolean = true, turned: Boolean = false) {
    time += 33_000_000
    val lens = host.lens
    val fine = lens?.let(scene::fine)
    val coarse = lens?.let(scene::coarse)
    focus.observe(TextLensFocus.Frame(time, fine, quiet, turned, manual = lens != null,
      lensDiopters = lens, lensMoving = false, exposureSettled = true,
      focusRatio = if (fine != null && coarse != null && coarse > 0) fine / coarse else null, detail = coarse))
  }

  private fun searches() = host.messages.count { it.contains("search from") }

  @Test fun opensAtACloseUpDistanceBeforeTheFirstLock() {
    focus.start(nearest = 10f, remembered = null)
    assertEquals(7f, host.lens!!, 1e-6f)
    focus.start(nearest = 10f, remembered = 6.5f)
    assertEquals(6.5f, host.lens!!, 1e-6f)
  }

  @Test fun aSteadyHoldSearchesHoldsTheSharpestAndRemembersIt() {
    focus.start(10f, null)
    assertTrue(!focus.locked)
    val text = textAt(6f)
    frame(text)
    assertEquals(TextLensFocus.Stage.SEARCHING, focus.stage)
    repeat(59) { frame(text) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(focus.locked)
    assertEquals(6f, host.lens!!, 1e-6f)
    assertEquals(6f, host.remembered!!, 1e-6f)
    assertTrue(!host.exposureLocked)
    // Held: steady frames of the same text keep the lens.
    repeat(30) { frame(text) }
    assertEquals(6f, host.lens!!, 1e-6f)
    assertEquals(0, host.autofocus)
  }

  @Test fun framesQueuedBeforeAMoveDoNotScoreTheNewPosition() {
    // The lens reports no reading and each frame shows the request from three frames before: only the frame's request
    // says which move it shows.
    focus.start(10f, null)
    val text = textAt(6f)
    val requests = ArrayDeque<Float?>()
    repeat(150) {
      requests.addLast(host.lens)
      val shown = if (requests.size > 3) requests.removeFirst() else requests.first()
      val fine = shown?.let(text::fine)
      val coarse = shown?.let(text::coarse)
      time += 33_000_000
      focus.observe(TextLensFocus.Frame(time, fine, quiet = true, turned = false, manual = shown != null,
        lensDiopters = null, lensMoving = false, exposureSettled = true,
        focusRatio = if (fine != null && coarse != null) fine / coarse else null, detail = coarse, requestedDiopters = shown))
    }
    assertTrue(focus.locked)
    assertEquals(6f, host.lens!!, .01f)
  }

  @Test fun otherTextAtTheHeldDistanceKeepsTheLockWithoutATurn() {
    focus.start(10f, null)
    repeat(60) { frame(textAt(7f)) }
    assertTrue(focus.locked)
    val before = searches()
    // Sparser, then denser text slides into the region at the same distance: its focus is as sharp, so no search.
    repeat(60) { frame(textAt(7f, 150.0)) }
    repeat(60) { frame(textAt(7f, 4000.0)) }
    repeat(60) { frame(flat(5.0)) }
    assertEquals(before, searches())
    assertEquals(7f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
  }

  @Test fun aMovingHandNeitherSearchesNorMovesTheLens() {
    focus.start(10f, 6f)
    repeat(40) { frame(textAt(6f), quiet = false) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertEquals(6f, host.lens!!, 1e-6f)
    assertEquals(null, host.remembered)
  }

  @Test fun textComingCloserTurnsSoftAndIsFoundAgain() {
    focus.start(10f, null)
    repeat(60) { frame(textAt(5f)) }
    assertEquals(5f, host.lens!!, 1e-6f)
    repeat(80) { frame(textAt(6f)) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertEquals(6f, host.lens!!, 1e-6f)
  }

  @Test fun aTurnSearchesAgainOnceSteady() {
    focus.start(10f, null)
    repeat(60) { frame(textAt(5f)) }
    assertTrue(focus.locked)
    frame(textAt(5f), turned = true)
    // The lock stands while the new text is checked; at 0.2 of the held level it is soft, so the lens searches.
    assertTrue(focus.locked)
    repeat(60) { frame(textAt(4f)) }
    assertEquals(4f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
  }

  @Test fun aTurnOntoTextAtTheSameDistanceKeepsTheLockWithoutMovingTheLens() {
    focus.start(10f, null)
    repeat(60) { frame(textAt(7f)) }
    assertTrue(focus.locked)
    val moves = ArrayList<Float>()
    frame(textAt(7f), turned = true)
    // Fainter text at the same distance: still sharp, so the lens stays.
    val fainter = textAt(7f, 300.0)
    repeat(40) { frame(fainter); host.lens?.let(moves::add) }
    assertTrue(moves.all { it == 7f })
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(focus.locked)
    assertTrue(host.messages.any { it.contains("kept 7.00 diopters after a turn") })
  }

  @Test fun unscorableTextLeavesFocusToTheCameraUntilThePhoneTurns() {
    focus.start(10f, null)
    repeat(30) { frame(unscorable) }
    assertEquals(TextLensFocus.Stage.CAMERA, focus.stage)
    assertTrue(!focus.locked)
    assertEquals(1, host.autofocus)
    assertTrue(!host.exposureLocked)
    // The camera's autofocus runs until a turn; then the lens is held again from where autofocus put it.
    repeat(30) { frame(unscorable) }
    assertEquals(1, host.autofocus)
  }

  @Test fun aTurnDuringASearchHoldsAndSearchesAgainOnceSteady() {
    focus.start(10f, null)
    repeat(3) { frame(textAt(5f)) }
    assertEquals(TextLensFocus.Stage.SEARCHING, focus.stage)
    frame(textAt(5f), turned = true)
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(!host.exposureLocked)
    repeat(60) { frame(textAt(5f)) }
    assertEquals(5f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
  }

  @Test fun aSearchTheHandDoesNotStayStillForEndsAndUnlocksExposure() {
    focus.start(10f, null)
    frame(textAt(5f))
    assertTrue(host.exposureLocked)
    repeat(130) { frame(textAt(5f), quiet = false) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(!host.exposureLocked)
    repeat(60) { frame(textAt(5f)) }
    assertEquals(5f, host.lens!!, 1e-6f)
  }

  @Test fun fainterTextAfterARefocusHoldsWithoutSearchingAgain() {
    focus.start(10f, null)
    repeat(60) { frame(textAt(7f)) }
    assertTrue(focus.locked)
    val faint = textAt(6f, 300.0)
    repeat(80) { frame(faint) }
    assertEquals(6f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
    // Steady frames of the fainter text keep the lock instead of searching again.
    repeat(60) { frame(faint) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
  }

  @Test fun aFlatFieldHoldsWithoutALockAndSearchesWhenTextAppears() {
    focus.start(10f, null)
    repeat(30) { frame(flat(50.0)) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(!focus.locked)
    assertEquals(null, host.remembered)
    repeat(80) { frame(textAt(6f)) }
    assertEquals(6f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
    assertEquals(6f, host.remembered!!, 1e-6f)
  }

  @Test fun aFieldWithNoDetailAtAllIsNoLock() {
    focus.start(10f, null)
    repeat(30) { frame(flat(0.0)) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(!focus.locked)
    assertEquals(null, host.remembered)
    repeat(80) { frame(textAt(6f)) }
    assertEquals(6f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
  }

  @Test fun aRegionChangeDuringASearchDropsItsScoresAndSearchesTheNewText() {
    focus.start(10f, null)
    repeat(3) { frame(textAt(5f)) }
    assertEquals(TextLensFocus.Stage.SEARCHING, focus.stage)
    focus.regionChanged(host.lens)
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertTrue(!host.exposureLocked)
    repeat(80) { frame(textAt(8f)) }
    assertEquals(8f, host.lens!!, 1e-6f)
    assertTrue(focus.locked)
  }

  @Test fun aTapSuspendsTheHoldAndResumesWhereItLeftTheLens() {
    focus.start(10f, null)
    focus.suspend()
    repeat(30) { frame(textAt(6f)) }
    assertEquals(TextLensFocus.Stage.TAPPED, focus.stage)
    focus.resume(6.2f)
    assertEquals(6.2f, host.lens!!, 1e-6f)
    repeat(60) { frame(textAt(6.2f)) }
    assertEquals(TextLensFocus.Stage.HELD, focus.stage)
    assertEquals(6.2f, host.lens!!, 1e-6f)
  }
}
