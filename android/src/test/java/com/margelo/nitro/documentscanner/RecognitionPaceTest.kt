package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionPaceTest {
  private val pace = RecognitionPace()
  private val box = TextRegion(.2, .45, .6, .05)

  private fun settle() = repeat(RecognitionPace.SETTLED_AGREEMENT) { pace.recognized("AB12", "AB12", box) }

  @Test fun agreedRecognitionsSlowToOnceASecond() {
    repeat(RecognitionPace.SETTLED_AGREEMENT - 1) { pace.recognized("AB12", "AB12", box) }
    assertEquals(200L, pace.intervalMs(200))
    pace.recognized("AB12", "AB12", box)
    assertTrue(pace.settled)
    assertEquals(1_000L, pace.intervalMs(200))
    // A rate already slower than settled keeps its own.
    assertEquals(2_000L, pace.intervalMs(2_000))
  }

  @Test fun aDisagreeingOrEmptyRecognitionReturnsToTheFullRate() {
    settle()
    pace.recognized("AB13", "AB12", box)
    assertFalse(pace.settled)
    settle()
    pace.recognized(null, "AB12", null)
    assertFalse(pace.settled)
  }

  @Test fun motionPastAFractionOfTheTextReturnsToTheFullRate() {
    settle()
    pace.moved(TextRegion(.2, .46, .6, .05))
    pace.moved(TextRegion(.23, .45, .6, .05))
    assertTrue(pace.settled)
    pace.moved(TextRegion(.2, .48, .6, .05))
    assertFalse(pace.settled)
    settle()
    pace.moved(TextRegion(.28, .45, .6, .05))
    assertFalse(pace.settled)
  }

  @Test fun aWakeOrResetReturnsToTheFullRateOncePerToken() {
    settle()
    pace.wake(1)
    assertFalse(pace.settled)
    settle()
    pace.wake(1)
    assertTrue(pace.settled)
    pace.reset()
    assertFalse(pace.settled)
  }

  @Test fun aLostOutlineReturnsToTheFullRate() {
    val pace = RecognitionPace()
    repeat(RecognitionPace.SETTLED_AGREEMENT) { pace.recognized("A", "A", null) }
    assertTrue(pace.settled)
    pace.lost()
    assertFalse(pace.settled)
  }
}
