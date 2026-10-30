package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.*
import org.junit.Test

class TorchPolicyTest {
  @Test fun sustainedDarkFramesLightTheTorchOnceAndItStaysLit() {
    val policy = TorchPolicy()
    // 1/15 s at ISO 1600: a dark room.
    assertFalse(policy.observe(0, 66_000_000, 1600))
    assertFalse(policy.observe(966_000_000, 66_000_000, 1600))
    assertTrue(policy.observe(1_000_000_000, 66_000_000, 1600))
    assertTrue(policy.lit)
    // The torch now brightens the page; the decision holds until reset.
    assertFalse(policy.observe(1_033_000_000, 10_000_000, 100))
    assertTrue(policy.lit)
    policy.reset()
    assertFalse(policy.lit)
  }

  @Test fun aBrightOrUnreportedFrameRestartsTheWait() {
    val policy = TorchPolicy()
    policy.observe(0, 66_000_000, 1600)
    assertFalse(policy.observe(500_000_000, 10_000_000, 200)) // 2,000 ISO ms: an ordinary room.
    assertFalse(policy.observe(600_000_000, 66_000_000, 1600))
    assertFalse(policy.observe(1_000_000_000, 66_000_000, 1600))
    assertFalse(policy.observe(1_100_000_000, 66_000_000, null))
    assertFalse(policy.observe(1_200_000_000, 66_000_000, 1600))
    assertTrue(policy.observe(2_200_000_000, 66_000_000, 1600))
  }
}
