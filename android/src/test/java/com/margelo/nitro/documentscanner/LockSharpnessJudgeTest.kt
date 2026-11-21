package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSharpnessJudgeTest {
  @Test fun theBestIsTheSharpestMedianOfThreeSoASpikeDoesNotCount() {
    val judge = LockSharpnessJudge()
    listOf(100.0, 900.0, 110.0).forEach { judge.observe(it) }
    assertEquals(110.0, judge.best!!, 0.0)
    listOf(400.0, 420.0).forEach { judge.observe(it) }
    assertEquals(400.0, judge.best!!, 0.0)
  }

  @Test fun aLockFarBelowTheBestMissesAndOneNearItIsAccepted() {
    val judge = LockSharpnessJudge()
    listOf(500.0, 520.0, 510.0).forEach { judge.observe(it) }
    judge.lockSettled()
    assertNull(judge.observe(200.0))
    assertNull(judge.observe(210.0))
    assertEquals(false, judge.observe(190.0))
    assertFalse(judge.pending)
    judge.lockSettled()
    listOf(400.0, 380.0).forEach { assertNull(judge.observe(it)) }
    assertEquals(true, judge.observe(390.0))
    assertEquals(510.0, judge.best!!, 0.0)
  }

  @Test fun withoutABestALockIsAcceptedAndBecomesIt() {
    val judge = LockSharpnessJudge()
    judge.lockSettled()
    assertTrue(judge.pending)
    listOf(300.0, 310.0).forEach { assertNull(judge.observe(it)) }
    assertEquals(true, judge.observe(320.0))
    assertEquals(310.0, judge.best!!, 0.0)
  }

  @Test fun resetAndCancelForgetWhatTheyShould() {
    val judge = LockSharpnessJudge()
    listOf(500.0, 500.0, 500.0).forEach { judge.observe(it) }
    judge.lockSettled()
    judge.cancel()
    assertFalse(judge.pending)
    assertEquals(500.0, judge.best!!, 0.0)
    judge.reset()
    assertNull(judge.best)
  }
}
