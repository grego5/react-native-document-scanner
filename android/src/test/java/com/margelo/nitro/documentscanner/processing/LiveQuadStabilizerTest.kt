package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test

class LiveQuadStabilizerTest {
  private val page = quad(.2,.2,.8,.8)
  private fun quad(l:Double,t:Double,r:Double,b:Double) = DocumentQuad(DocumentPoint(l,t),DocumentPoint(r,t),DocumentPoint(r,b),DocumentPoint(l,b))
  private fun input(q:DocumentQuad?, ms:Long) = LiveQuadInput(q,1000,1000,ms*1_000_000,ms*1_000_000)
  private fun acquired():LiveQuadStabilizer = LiveQuadStabilizer().also { s -> for (t in listOf(0L,33L,66L)) s.update(input(page,t)) }

  @Test fun acceptsFirstValidResultAndIgnoresDuplicateTimestamp() {
    val s=LiveQuadStabilizer()
    assertEquals(page,s.update(input(page,0)).accepted)
    assertEquals(LiveQuadDecision.DUPLICATE_TIMESTAMP,s.update(input(page,0)).decision)
  }
  @Test fun acceptsAValidConvexPerspective() {
    val q=DocumentQuad(DocumentPoint(.2,.2),DocumentPoint(.8,.2),DocumentPoint(.9,.8),DocumentPoint(.45,.6))
    val o=LiveQuadStabilizer().update(input(q,0))
    assertEquals(q,o.accepted)
    assertEquals(LiveQuadStatus.TRACKING,o.status)
  }
  @Test fun expiredOutlineCanBeReplacedImmediately() {
    val o=acquired().update(input(page,470))
    assertTrue(o.expiredBeforeInput)
    assertEquals(page,o.accepted)
    assertEquals(LiveQuadStatus.TRACKING,o.status)
  }
  @Test fun invalidAndTinyOutlinesNeverAcquire() {
    val s=LiveQuadStabilizer()
    assertEquals(LiveQuadDecision.INVALID,s.update(input(quad(-.1,.2,.8,.8),0)).decision)
    assertEquals(LiveQuadDecision.TOO_SMALL,s.update(input(quad(.2,.2,.21,.21),33)).decision)
  }
  @Test fun aDifferentValidOutlineReplacesImmediately() {
    val changed=quad(.4,.2,.95,.8)
    val output=acquired().update(input(changed,100))
    assertEquals(changed,output.accepted)
    assertEquals(LiveQuadStatus.TRACKING,output.status)
  }
  @Test fun missingModelWithoutReliableTrackingClearsImmediately() {
    assertNull(acquired().update(input(null,100)).accepted)
  }
  @Test fun reliableFlowKeepsMissingResultOnlyForTheCurrentFrame() {
    val s=acquired()
    assertNotNull(s.retainTracked(input(page,100)))
    assertEquals(page,s.update(input(null,100)).accepted)
    assertNull(s.update(input(null,133)).accepted)
    assertNull(s.retainTracked(input(page,166)))
  }
  @Test fun expiredFlowCannotResurrectOutlineAndIdsKeepIncreasing() {
    val s=acquired()
    assertNull(s.retainTracked(input(page,470)))
    val old=s.update(input(page,500))
    s.config=s.config.copy(holdNs=200_000_000)
    val fresh=s.update(input(page,533))
    assertTrue(fresh.acceptanceId>old.acceptanceId)
    assertEquals(LiveQuadDecision.TIMESTAMP_BACKWARD,s.update(input(page,50)).decision)
  }
}
