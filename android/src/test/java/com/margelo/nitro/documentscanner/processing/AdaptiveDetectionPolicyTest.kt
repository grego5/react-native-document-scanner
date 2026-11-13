package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import org.junit.Assert.*
import org.junit.Test

class AdaptiveDetectionPolicyTest {
  @Test fun cadenceRearmsAfterMovementAndLimitsUnknownMotion() {
    val cadence = AdaptiveInferenceCadence()
    fun gap(ms: Long, moving: Boolean = false, measured: Boolean = true, searching: Boolean = true, trackingGap: Long = 0L) =
      cadence.gap(ms * 1_000_000L, searching, moving, measured, trackingGap)

    assertEquals(null, gap(0))
    assertEquals(null, gap(30))
    assertEquals(null, gap(60))
    assertEquals(0L, gap(90))
    cadence.didAttempt(90_000_000L)
    assertEquals(null, gap(200, measured = false))
    assertEquals(null, gap(250))
    assertEquals(null, gap(280))
    assertEquals(null, gap(310))
    assertEquals(null, gap(400))
    assertEquals(null, gap(500, moving = true))
    assertEquals(null, gap(600, moving = true))
    assertEquals(null, gap(650))
    assertEquals(null, gap(680))
    assertEquals(null, gap(710))
    assertEquals(0L, gap(740))
    cadence.didAttempt(740_000_000L)
    assertEquals(null, gap(900, measured = false))
    assertEquals(null, gap(1739, measured = false))
    assertEquals(0L, gap(1740, measured = false))
    cadence.didAttempt(1_740_000_000L)
    assertEquals(null, gap(2739, measured = false))
    assertEquals(null, gap(3000, searching = false, trackingGap = Long.MAX_VALUE))
    assertEquals(null, gap(3030, searching = false, trackingGap = Long.MAX_VALUE))
    assertEquals(null, gap(3060, searching = false, trackingGap = Long.MAX_VALUE))
    assertEquals(null, gap(3090, searching = false, trackingGap = Long.MAX_VALUE))
    assertEquals(60_000_000L, gap(3120, searching = false, trackingGap = 60_000_000L))
  }

  @Test fun cadenceRetriesAnUnconfirmedSteadyScene() {
    val cadence = AdaptiveInferenceCadence()
    fun gap(ms: Long, searching: Boolean = true) =
      cadence.gap(ms * 1_000_000L, searching, moving = false, measured = true, trackingGap = Long.MAX_VALUE)

    for (ms in listOf(0L, 30L, 60L)) assertNull(gap(ms))
    assertEquals(0L, gap(90))
    cadence.didAttempt(90_000_000L)
    assertNull(gap(1089))
    assertEquals(0L, gap(1090))
    cadence.didAttempt(1_090_000_000L)
    assertNull(gap(2089))
    assertEquals(0L, gap(2090))
    assertNull(gap(2100, searching = false))
    assertNull(gap(5000, searching = false))
  }

  @Test fun cadenceMakesProgressWhenMotionNeverSettles() {
    val cadence = AdaptiveInferenceCadence()
    fun gap(ms: Long, moving: Boolean = false, measured: Boolean = true, searching: Boolean = true, trackingGap: Long = 0L) =
      cadence.gap(ms * 1_000_000L, searching, moving, measured, trackingGap)

    for (ms in 0L until 1000L step 30L) {
      assertNull(gap(ms, moving = ms % 90L == 0L, measured = ms % 90L != 60L))
    }
    assertEquals(0L, gap(1000, moving = true))
    cadence.didAttempt(1_000_000_000L)
    assertNull(gap(1999, moving = true))
    assertEquals(0L, gap(2000, moving = true))
    cadence.didAttempt(2_000_000_000L)
    assertNull(gap(2010, searching = false, trackingGap = Long.MAX_VALUE))
    assertNull(gap(3500, searching = false, trackingGap = Long.MAX_VALUE))
    val revalidation = AdaptiveDetectionPolicy.REVALIDATE_GAP_NS
    assertNull(gap(3600, moving = true, searching = false, trackingGap = revalidation))
    assertNull(gap(4599, moving = true, searching = false, trackingGap = revalidation))
    assertEquals(0L, gap(4600, moving = true, searching = false, trackingGap = revalidation))
  }

  @Test fun cadenceUsesLowBlurOnlyToStartEarly() {
    val cadence = AdaptiveInferenceCadence()
    fun gap(ms: Long, blur: Double?, measured: Boolean = true, moving: Boolean = true) =
      cadence.gap(ms * 1_000_000L, searching = true, moving, measured, trackingGap = 0L, blurFraction = blur)

    assertNull(gap(0, null))
    assertNull(gap(30, .0019, measured = false))
    assertNull(gap(60, .0021))
    assertEquals(0L, gap(90, .0019))
    cadence.didAttempt(90_000_000L)
    for (ms in 120L..840L step 30L) assertNull(gap(ms, .0019, moving = false))
    assertNull(gap(1089, .0019, moving = false))
    assertEquals(0L, gap(1090, .0019, moving = false))
    cadence.didAttempt(1_090_000_000L)

    assertNull(cadence.gap(1_120_000_000L, searching = false, moving = false,
      measured = true, trackingGap = Long.MAX_VALUE, blurFraction = .0019))
    assertEquals(0L, gap(1150, .0019))
    cadence.didAttempt(1_150_000_000L)
    assertNull(gap(1180, .0019))
  }

  @Test fun sharedPlatformSequences() {
    val stream = javaClass.classLoader!!.getResourceAsStream("parity/adaptive.tsv")!!
    val rows = stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
    assertTrue(rows.isNotEmpty())
    var case: String? = null
    var policy = AdaptiveDetectionPolicy()
    for (line in rows) {
      val v = line.split('\t')
      assertEquals(line, 12, v.size)
      if (v[0] != case) { case = v[0]; policy = AdaptiveDetectionPolicy() }
      when (v[1]) {
        "reference" -> policy.reference(corners(v[5]))
        "confirmed" -> policy.confirmed()
        "reset" -> policy.reset()
        "next" -> {
          val gap = policy.next(v[2].toBooleanStrict(), v[3].toBooleanStrict(), v[4].toBooleanStrict(), corners(v[5]),
            v[6].toInt(), v[7].toInt())
          assertEquals(line, if (v[8] == "max") Long.MAX_VALUE else v[8].toLong(), gap)
          assertEquals(line, v[9], policy.status)
          assertEquals(line, v[10], policy.lastReset)
          assertEquals(line, v[11].takeUnless { it == "-" }, policy.doubt)
          assertEquals(line, v[9] == "paused", policy.paused)
        }
        else -> fail("Unknown step: $line")
      }
    }
  }

  @Test fun resetDropsTheReferenceAndKeepsTheLastWakeReason() {
    val policy = AdaptiveDetectionPolicy()
    val page = corners("0.2,0.2,0.8,0.2,0.8,0.8,0.2,0.8")
    policy.reference(page)
    policy.next(true, false, false, null, 240, 320)
    policy.reset()
    assertFalse(policy.hasReference)
    assertEquals("acquiring", policy.status)
    assertEquals("tracking lost", policy.lastReset)
  }

  private fun corners(text: String): DocumentCorners? {
    if (text == "-") return null
    val v = text.split(',').map(String::toDouble)
    assertEquals(8, v.size)
    return DocumentCorners(DocumentPoint(v[0], v[1]), DocumentPoint(v[2], v[3]), DocumentPoint(v[4], v[5]), DocumentPoint(v[6], v[7]))
  }
}
