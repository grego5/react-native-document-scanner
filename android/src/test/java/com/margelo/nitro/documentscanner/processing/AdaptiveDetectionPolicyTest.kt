package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test

class AdaptiveDetectionPolicyTest {
  @Test fun reliableTrackingPausesImmediatelyWithoutSettlingTimers() {
    val p=AdaptiveDetectionPolicy()
    assertEquals(Long.MAX_VALUE,p.interval(0,true,true,false))
    assertEquals(Long.MAX_VALUE,p.interval(5_000_000_000,true,true,false))
  }
  @Test fun lossDegradationAndSceneChangeWakeInference() {
    val p=AdaptiveDetectionPolicy()
    assertEquals(0L,p.interval(0,false,false,false))
    assertEquals(60_000_000L,p.interval(1,true,false,false))
    assertEquals(60_000_000L,p.interval(2,true,true,false))
    p.confirmed()
    assertEquals(Long.MAX_VALUE,p.interval(3,true,true,false))
    assertEquals(60_000_000L,p.interval(4,true,true,true))
    p.confirmed()
    assertEquals(60_000_000L,p.interval(5,true,true,false,"scene changed"))
  }
  @Test fun accumulatedTranslationAndPerspectiveWakeInference() {
    val q=quad(.2 to .2,.8 to .2,.8 to .8,.2 to .8)
    assertFalse(AdaptiveDetectionPolicy.changed(q,quad(.21 to .2,.81 to .2,.81 to .8,.21 to .8),240,320))
    assertTrue(AdaptiveDetectionPolicy.changed(q,quad(.3 to .2,.9 to .2,.9 to .8,.3 to .8),240,320))
    assertTrue(AdaptiveDetectionPolicy.changed(q,quad(.3 to .2,.7 to .2,.8 to .8,.2 to .8),240,320))
  }

  private fun quad(vararg p: Pair<Double, Double>) = DocumentQuad(DocumentPoint(p[0].first, p[0].second),
    DocumentPoint(p[1].first, p[1].second), DocumentPoint(p[2].first, p[2].second), DocumentPoint(p[3].first, p[3].second))
}
