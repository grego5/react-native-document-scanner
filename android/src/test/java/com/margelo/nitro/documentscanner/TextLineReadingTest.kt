package com.margelo.nitro.documentscanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLineReadingTest {
  /** A line of [words] spaced 10 px per character with a 10 px gap, starting at [left]. */
  private fun line(top: Float, bottom: Float, vararg words: String, left: Float = 0f, confidence: Float = 0.9f): RecognizedLine {
    var x = left
    val placed = words.map { word -> RecognizedWord(word, x, x + word.length * 10f).also { x += word.length * 10f + 10f } }
    return RecognizedLine(placed, top, bottom, confidence)
  }

  private val serial = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"

  @Test
  fun unrestrictedFilterKeepsTheWholeLine() {
    assertEquals("SN 12 AB", TextLineFilter("", "", 1).accept("  SN   12\tAB "))
  }

  @Test
  fun allowedCharactersSwapLookalikes() {
    val filter = TextLineFilter(serial, "", 1)
    assertEquals("AB-L234", filter.accept("ab-l234"))
    assertEquals("105826", TextLineFilter("0123456789", "", 1).accept("IOS8Z6"))
  }

  @Test
  fun removedCharactersSplitPieces() {
    val filter = TextLineFilter(serial, "", 1)
    assertEquals(listOf("SN", "AB12", "X"), filter.candidates(line(0f, 10f, "SN:", "AB12", "x")).map { it.text })
    assertEquals(listOf("1", "2"), TextLineFilter("0123456789", "", 1).candidates(line(0f, 10f, "1#2")).map { it.text })
    // An allowed space keeps groups together but is trimmed from the ends.
    assertEquals(listOf("AB 12"), TextLineFilter("$serial ", "", 1).candidates(line(0f, 10f, "AB", "12:")).map { it.text })
  }

  @Test
  fun pieceCentresFollowTheirCharacters() {
    val pieces = TextLineFilter(serial, "", 1).candidates(line(0f, 10f, "SN:", "AB12"))
    assertEquals(10f, pieces[0].centerX, 1e-3f)
    assertEquals(60f, pieces[1].centerX, 1e-3f)
  }

  @Test
  fun pieceExtentsSpanOnlyTheirCharacters() {
    // "SN:" covers 0–30, so "SN" ends at 20; "AB12" covers 40–80 on a line 5–15 high.
    val pieces = TextLineFilter(serial, "", 1).candidates(line(5f, 15f, "SN:", "AB12"))
    assertEquals(listOf(0f, 20f, 40f, 80f), listOf(pieces[0].left, pieces[0].right, pieces[1].left, pieces[1].right))
    assertEquals(5f, pieces[1].top, 1e-3f)
    assertEquals(15f, pieces[1].bottom, 1e-3f)
  }

  @Test
  fun characterExtentsReplaceTheEvenShare() {
    // A narrow "=" glued to the serial: the even share would start the piece 10 px late.
    val word = RecognizedWord("=E81", 0f, 40f, listOf(0f, 4f, 6f, 18f, 18f, 29f, 29f, 40f))
    val piece = TextLineFilter(serial, "", 1).candidates(RecognizedLine(listOf(word), 0f, 10f, 0.9f)).single()
    assertEquals("E81", piece.text)
    assertEquals(6f, piece.left, 1e-3f)
    assertEquals(40f, piece.right, 1e-3f)
  }

  @Test
  fun boundsMapFromTheCropIntoTheView() {
    val piece = TextLineFilter.Candidate("AB12", 50f, 10f, 150f, 30f, 0.9f)
    val bounds = textBoundsInView(piece, TextRegion(0.1, 0.4, 0.8, 0.2), 200f, 40f)
    assertEquals(0.3, bounds.x, 1e-6)
    assertEquals(0.45, bounds.y, 1e-6)
    assertEquals(0.4, bounds.width, 1e-6)
    assertEquals(0.1, bounds.height, 1e-6)
    val box = bounds.corners().boundingRegion()
    assertEquals(bounds.x, box.x, 1e-9)
    assertEquals(bounds.width, box.width, 1e-9)
  }

  @Test
  fun flowAreaHoldsSceneAroundAThinRegion() {
    val area = flowArea(TextRegion(0.1, 0.45, 0.8, 0.1)).boundingRegion()
    assertEquals(0.05, area.x, 1e-6)
    assertEquals(0.3, area.y, 1e-6)
    assertEquals(0.9, area.width, 1e-6)
    assertEquals(0.4, area.height, 1e-6)
  }

  @Test
  fun patternAndLengthGateAcceptance() {
    val filter = TextLineFilter("", "[A-Z]{2}\\d{4}", 6)
    assertEquals("AB1234", filter.accept("AB1234"))
    assertNull(filter.accept("AB123"))
    assertNull(filter.accept("ABC234"))
    assertNull(TextLineFilter("", "", 5).accept("ABCD"))
  }

  @Test
  fun invalidPatternAcceptsNothing() {
    val filter = TextLineFilter("", "([", 1)
    assertTrue(filter.patternInvalid)
    assertNull(filter.select(listOf(line(40f, 60f, "ABC")), 100f, 100f))
  }

  @Test
  fun serialModeReadsTheCentrePieceNotItsNeighbours() {
    val filter = TextLineFilter(serial, "", 4)
    // "MODEL X200 SN: K7Q-4410 MADE IN" with the serial under the centre of a 300 px crop.
    val lines = listOf(line(40f, 60f, "MODEL", "X200", "SN:", "K7Q-4410", "MADE", "IN", left = 0f))
    val centre = filter.candidates(lines[0]).single { it.text == "K7Q-4410" }.centerX
    assertEquals("K7Q-4410", filter.select(lines, centre * 2, 100f)?.text)
  }

  @Test
  fun unrestrictedSelectionJoinsABaselineAndPrefersTheCentre() {
    val filter = TextLineFilter("", "", 1)
    val lines = listOf(line(0f, 20f, "TOP"), line(42f, 58f, "4567", left = 60f), line(40f, 60f, "SN-123"))
    assertEquals("SN-123 4567", filter.select(lines, 100f, 100f)?.text)
  }

  @Test
  fun selectionSkipsRejectedPieces() {
    val filter = TextLineFilter("", "\\d+", 1)
    val lines = listOf(line(40f, 60f, "LABEL"), line(70f, 90f, "9876"))
    assertEquals("9876", filter.select(lines, 100f, 100f)?.text)
  }

  @Test
  fun votesPickTheMajorityAndNewestOnTies() {
    val votes = TextVotes(window = 5, maxAgeMs = 1_000)
    votes.add("AB12", 0.8f, 0)
    votes.add("A812", 0.6f, 10)
    val winner = votes.add("AB12", 1.0f, 20)
    assertEquals("AB12", winner?.text)
    assertEquals(2, winner?.votes)
    assertEquals(0.9, winner!!.confidence, 1e-6)
    votes.clear()
    votes.add("ONE", 0.5f, 0)
    assertEquals("TWO", votes.add("TWO", 0.5f, 10)?.text)
  }

  @Test
  fun outlineDropsWhenANewWinnerHasNoBoxThisFrame() {
    val votes = TextVotes(window = 4, maxAgeMs = 1_000)
    val text: (Pair<String, Int>) -> String = { it.first }
    var outline: Pair<String, Int>? = null
    fun frame(read: String?, box: Int, at: Long) {
      val winner = votes.add(read, 0.9f, at)?.text
      outline = outlineAfter(outline, read?.let { it to box }, winner, text)
    }
    frame("A", 1, 0)
    frame("A", 2, 10)
    assertEquals("A" to 2, outline)
    // B ties A's two votes only once the oldest A leaves the window; the frame that tips it boxed nothing.
    frame("B", 3, 20)
    assertEquals("A" to 2, outline)
    frame(null, 0, 30)
    frame(null, 0, 40)
    assertEquals("B", votes.winner()?.text)
    assertNull(outline)
    // The next frame that boxes the winner restores the outline.
    frame("B", 5, 50)
    assertEquals("B" to 5, outline)
  }

  @Test
  fun votesExpireByWindowAndAge() {
    val votes = TextVotes(window = 3, maxAgeMs = 1_000)
    votes.add("AB12", 0.9f, 0)
    votes.add(null, 0f, 10)
    votes.add(null, 0f, 20)
    assertNull(votes.add(null, 0f, 30))
    votes.add("CD34", 0.9f, 40)
    assertNull(votes.add(null, 0f, 2_000))
  }

  @Test
  fun votesAgeOutWithoutNewRecognitions() {
    val votes = TextVotes(window = 8, maxAgeMs = 1_000)
    votes.add("AB12", 0.9f, 0)
    votes.add("AB12", 0.9f, 300)
    assertFalse(votes.expire(900))
    assertEquals("AB12", votes.winner()?.text)
    assertTrue(votes.expire(1_100))
    assertEquals("AB12", votes.winner()?.text)
    assertTrue(votes.expire(1_400))
    assertNull(votes.winner())
    assertFalse(votes.expire(5_000))
  }

  @Test
  fun regionMapsIntoTheBufferForEachRotation() {
    val region = TextRegion(0.1, 0.4, 0.8, 0.2)
    assertArrayEquals(intArrayOf(110, 440, 190, 460), regionInBuffer(region, 0, 100, 400, 100, 100))
    // Portrait over a landscape buffer: the view's horizontal strip is a vertical one in the buffer.
    assertArrayEquals(intArrayOf(40, 10, 60, 90), regionInBuffer(region, 90, 0, 0, 100, 100))
    assertArrayEquals(intArrayOf(10, 40, 90, 60), regionInBuffer(region, 180, 0, 0, 100, 100))
    assertArrayEquals(intArrayOf(40, 10, 60, 90), regionInBuffer(region, 270, 0, 0, 100, 100))
  }

  @Test
  fun regionClampsToTheView() {
    assertEquals(TextRegion(0.5, 0.0, 0.5, 1.0), clampedRegion(TextRegion(0.5, -0.2, 0.9, 1.5)))
  }
}
