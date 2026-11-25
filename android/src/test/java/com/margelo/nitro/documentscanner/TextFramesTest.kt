package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.regionEdgeWidth
import com.margelo.nitro.documentscanner.processing.regionSharpness
import com.margelo.nitro.documentscanner.processing.regionThumbnail
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFramesTest {
  private val ms = 1_000_000L

  @Test fun theRingKeepsTheSharpestRecentFramesOfEachFocus() {
    val ring = FrameRing<String>(capacity = 2, keepNs = 1000 * ms)
    ring.add(FrameRing.Entry(0, 10.0, 0L, "soft"))
    ring.add(FrameRing.Entry(10 * ms, 30.0, 0L, "sharp"))
    assertFalse(ring.wants(20 * ms, 5.0, 0L))
    assertTrue(ring.wants(20 * ms, 20.0, 0L))
    ring.add(FrameRing.Entry(20 * ms, 20.0, 0L, "middle"))
    assertEquals(2, ring.size)
    assertEquals("sharp", ring.sharpest(0L)!!.image)
    // A new focus's frames are kept however sharp the view before it was.
    assertTrue(ring.wants(30 * ms, 1.0, 7L))
    ring.add(FrameRing.Entry(30 * ms, 1.0, 7L, "focused"))
    assertEquals("focused", ring.sharpest(7L)!!.image)
    // A capture looks at the sharpest kept frame of any focus.
    assertEquals("sharp", ring.sharpest()!!.image)
    // Frames older than the window behind the newest go.
    ring.add(FrameRing.Entry(1500 * ms, 2.0, 7L, "late"))
    assertNull(ring.sharpest(0L))
    assertEquals("late", ring.sharpest(7L)!!.image)
    assertEquals("late", ring.sharpest()!!.image)
    ring.clear()
    assertNull(ring.sharpest())
  }

  private val print = floatArrayOf(0f, 100f, 0f, 100f, 0f, 100f, 0f, 100f)
  private val moved = floatArrayOf(100f, 0f, 100f, 0f, 100f, 0f, 100f, 0f)

  @Test fun thumbnailsCompareByPatternAlone() {
    // Exposure changing scales and shifts the luma, not the pattern.
    assertEquals(1.0, ViewWatch.similarity(print, FloatArray(print.size) { print[it] * .5f + 40f }), 1e-9)
    assertEquals(-1.0, ViewWatch.similarity(print, moved), 1e-9)
    // Blank paper matches blank paper, and not print.
    assertEquals(1.0, ViewWatch.similarity(FloatArray(8) { 50f }, FloatArray(8) { 52f }), 1e-9)
    assertEquals(0.0, ViewWatch.similarity(FloatArray(8) { 50f }, print), 1e-9)
  }

  @Test fun aPageMovedUnderASteadyPhoneIsNotStill() {
    val watch = ViewWatch()
    assertTrue(watch.still(print))
    assertTrue(watch.still(print))
    assertFalse(watch.still(moved))
    assertTrue(watch.still(null))
  }

  @Test fun theViewChangesOnlyWhenARunOfFramesDiffersFromTheRead() {
    val watch = ViewWatch(frames = 3)
    // Nothing is compared before a read sets the reference.
    repeat(5) { assertFalse(watch.changed(4L, moved, 1.0)) }
    watch.reference(4L, print, 100.0)
    // A frame or two that differ, as a shake, do not count.
    assertFalse(watch.changed(4L, print, 90.0))
    assertFalse(watch.changed(4L, moved, 90.0))
    assertFalse(watch.changed(4L, moved, 20.0))
    assertFalse(watch.changed(4L, print, 95.0))
    // Another focus's frames are not compared with this read.
    assertFalse(watch.changed(5L, moved, 1.0))
    // The page moved.
    assertFalse(watch.changed(4L, moved, 90.0))
    assertFalse(watch.changed(4L, moved, 90.0))
    assertTrue(watch.changed(4L, moved, 90.0))
    // Reported once per focus.
    assertFalse(watch.changed(4L, moved, 90.0))
    // The view went soft, as after moving nearer or farther, though its pattern holds.
    watch.reference(6L, print, 100.0)
    repeat(2) { assertFalse(watch.changed(6L, print, 40.0)) }
    assertTrue(watch.changed(6L, print, 40.0))
  }

  @Test fun printTurnsBlurredOnARunOfWideEdgesAndSharpOnARunOfNarrowOnes() {
    val watch = BlurWatch(enter = 3, leave = 2)
    // A frame or two of shake does not count.
    assertNull(watch.observe(2.0))
    assertNull(watch.observe(4.5))
    assertNull(watch.observe(4.5))
    assertNull(watch.observe(2.0))
    repeat(2) { assertNull(watch.observe(4.0)) }
    assertEquals(true, watch.observe(BlurWatch.CUT + .1))
    // Too blurred to measure, or blank paper, keeps the state.
    repeat(5) { assertNull(watch.observe(null)) }
    assertTrue(watch.blurred)
    assertNull(watch.observe(BlurWatch.CUT))
    assertEquals(false, watch.observe(2.0))
    assertNull(watch.reset())
    assertTrue(BlurWatch.sharp(BlurWatch.CUT))
    assertFalse(BlurWatch.sharp(null))
  }

  @Test fun theFocusedReadIsDueOnceAfterItsSamples() {
    val read = FocusedRead(samples = 3)
    assertFalse(read.sampled(0L))
    assertFalse(read.sampled(4L))
    assertFalse(read.sampled(4L))
    assertTrue(read.sampled(4L))
    assertFalse(read.sampled(4L))
    // A new focus counts afresh; frames out of focus never read.
    assertFalse(read.sampled(5L))
    assertFalse(read.sampled(0L))
    repeat(2) { assertFalse(read.sampled(6L)) }
    assertTrue(read.sampled(6L))
    read.reset()
    repeat(2) { assertFalse(read.sampled(6L)) }
    assertTrue(read.sampled(6L))
  }

  @Test fun captureWordsFollowReadingOrderAsImageFractions() {
    val lines = listOf(
      RecognizedLine(listOf(RecognizedWord("world", 60f, 100f), RecognizedWord("hello", 0f, 50f)), 40f, 60f, .8f),
      RecognizedLine(listOf(RecognizedWord("TITLE", 10f, 90f), RecognizedWord(" ", 95f, 96f)), 0f, 20f, .9f),
    )
    val words = captureWords(lines, 200f, 100f)
    assertEquals(listOf("TITLE", "hello", "world"), words.map { it.text })
    assertEquals(listOf(0.0, 1.0, 1.0), words.map { it.line })
    val hello = words[1].bounds
    assertEquals(0.0, hello.x, 1e-6)
    assertEquals(0.4, hello.y, 1e-6)
    assertEquals(0.25, hello.width, 1e-6)
    assertEquals(0.2, hello.height, 1e-6)
    assertEquals(0.8, words[1].confidence, 1e-6)
  }

  @Test fun slantedWordsKeepTheirCornersAndReadAlongTheSlant() {
    // Two lines slanting down to the right by 45 degrees in a 100 by 100 image; the second line starts further left
    // but lies below the first across the slant.
    fun word(text: String, x: Float, y: Float) = RecognizedWord(text, x, x + 10f, floatArrayOf(x, y, x + 10f, y + 10f, x + 8f, y + 12f, x - 2f, y + 2f))
    fun line(vararg words: RecognizedWord) = RecognizedLine(words.toList(), 0f, 0f, .9f,
      words.first().corners!!.let { a -> words.last().corners!!.let { b -> floatArrayOf(a[0], a[1], b[2], b[3], b[4], b[5], a[6], a[7]) } })
    val first = line(word("one", 20f, 10f), word("two", 32f, 22f))
    val second = line(word("three", 10f, 30f), word("four", 22f, 42f))
    val words = captureWords(listOf(second, first), 100f, 100f)
    assertEquals(listOf("one", "two", "three", "four"), words.map { it.text })
    assertEquals(listOf(0.0, 0.0, 1.0, 1.0), words.map { it.line })
    val one = words[0]
    assertEquals(0.3, one.corners.topRight.x, 1e-6)
    assertEquals(0.2, one.corners.topRight.y, 1e-6)
    // The upright box spans the turned corners.
    assertEquals(0.18, one.bounds.x, 1e-6)
    assertEquals(0.12, one.bounds.width, 1e-6)
  }

  @Test fun printScoresSharperThanTheSameEdgesSmeared() {
    val width = 64
    val height = 32
    fun image(edge: (Int) -> Int): ByteBuffer {
      val buffer = ByteBuffer.allocate(width * height)
      for (y in 0 until height) for (x in 0 until width) buffer.put(y * width + x, edge(x).toByte())
      return buffer
    }
    // Stripes four pixels wide, crisp and spread over a ramp as a smear does.
    val crisp = image { if ((it / 4) % 2 == 0) 0 else 200 }
    val smeared = image { x -> val phase = x % 8; if (phase < 4) phase * 50 else (8 - phase) * 50 }
    val sharp = regionSharpness(crisp, width, 1, 0, 0, width, height)!!
    val soft = regionSharpness(smeared, width, 1, 0, 0, width, height)!!
    assertTrue("$sharp > $soft", sharp > soft)
    assertNull(regionSharpness(crisp, width, 1, 0, 0, 4, 4))
  }

  @Test fun aThumbnailFollowsTheRegionsLongAxis() {
    val width = 96
    val height = 32
    val buffer = ByteBuffer.allocate(width * height)
    for (y in 0 until height) for (x in 0 until width) buffer.put(y * width + x, (x * 2).toByte())
    val wide = regionThumbnail(buffer, width, 1, 0, 0, width, height)!!
    assertEquals(24 * 8, wide.size)
    // Brighter to the right along every row.
    assertTrue(wide[23] > wide[0])
    assertEquals(wide[0], wide[24], 1e-3f)
    assertNull(regionThumbnail(buffer, width, 1, 0, 0, 20, 10))
  }

  @Test fun edgeWidthGrowsWithBlurWhateverTheContrastOrStrokeSize() {
    val width = 96
    val height = 48
    /** Stripes [period] pixels wide whose edges ramp over [ramp] pixels, between [dark] and [light]. */
    fun stripes(period: Int, ramp: Int, dark: Int = 20, light: Int = 220): ByteBuffer {
      val buffer = ByteBuffer.allocate(width * height)
      for (y in 0 until height) for (x in 0 until width) {
        val phase = x % (2 * period)
        val t = when {
          phase < period -> (phase.toDouble() / ramp).coerceAtMost(1.0)
          else -> 1 - ((phase - period).toDouble() / ramp).coerceAtMost(1.0)
        }
        buffer.put(y * width + x, (dark + t * (light - dark)).toInt().toByte())
      }
      return buffer
    }
    fun edges(buffer: ByteBuffer) = regionEdgeWidth(buffer, width, 1, 0, 0, width, height)!!
    val sharp = edges(stripes(period = 8, ramp = 1))
    val blurred = edges(stripes(period = 8, ramp = 6))
    assertTrue("${sharp.median} < 1.5", sharp.median < 1.5)
    assertEquals(4.8, blurred.median, .6)
    // Wider strokes, as nearer print, and fainter print keep the width.
    assertEquals(blurred.median, edges(stripes(period = 16, ramp = 6)).median, .3)
    assertEquals(blurred.median, edges(stripes(period = 8, ramp = 6, dark = 90, light = 170)).median, .3)
    // Vertical stripes cross only the rows.
    assertNull(blurred.columns)
    // A plain view has no edges.
    assertNull(regionEdgeWidth(ByteBuffer.allocate(width * height), width, 1, 0, 0, width, height))
  }

  @Test fun aLumaCropTurnsUprightAsTheRegionMapsToTheBuffer() {
    // A 3 by 2 block of the buffer: row 0 is 1 2 3, row 1 is 4 5 6.
    val block = byteArrayOf(1, 2, 3, 4, 5, 6)
    fun upright(rotation: Int) = uprightLuma(block, 3, 2, rotation).let { Triple(it.width, it.height, it.bytes.toList()) }
    assertEquals(Triple(3, 2, listOf<Byte>(1, 2, 3, 4, 5, 6)), upright(0))
    // Turned clockwise into the view: the buffer's left column becomes the view's top row, read bottom up.
    assertEquals(Triple(2, 3, listOf<Byte>(4, 1, 5, 2, 6, 3)), upright(90))
    assertEquals(Triple(3, 2, listOf<Byte>(6, 5, 4, 3, 2, 1)), upright(180))
    assertEquals(Triple(2, 3, listOf<Byte>(3, 6, 2, 5, 1, 4)), upright(270))
  }
}
