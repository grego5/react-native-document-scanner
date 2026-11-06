package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad

class LiveQuadStabilizerTest {
  @Before fun initializeNative() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
  }
  private val page = quad(.2,.2,.8,.8)
  private fun quad(l:Double,t:Double,r:Double,b:Double) = DocumentQuad(DocumentPoint(l,t),DocumentPoint(r,t),DocumentPoint(r,b),DocumentPoint(l,b))
  private fun input(q:DocumentQuad?, ms:Long) = LiveQuadInput(q,1000,1000,ms*1_000_000,ms*1_000_000)
  private fun acquired():LiveQuadStabilizer = LiveQuadStabilizer().also { s -> for (t in listOf(0L,33L,66L)) s.update(input(page,t)) }

  @Test fun nativePolicyMatchesFormerPolicyAcrossDeterministicReplay() {
    LiveQuadStabilizer().use { actual ->
      val reference = ReferenceLiveQuadStabilizer()
      var sensor = 0L
      for (i in 0 until 240) {
        if (i % 31 == 0) { actual.reset(); reference.reset() }
        if (i % 47 == 0) {
          val config = LiveQuadStabilizerConfig(holdNs = if (i % 2 == 0) 200_000_000 else 400_000_000)
          actual.config = config; reference.config = config
        }
        sensor += when { i % 23 == 0 -> -10_000_000; i % 17 == 0 -> 0; else -> 33_000_000 }
        val candidate = when (i % 7) {
          0 -> null
          1 -> quad(-.1, .2, .8, .8)
          2 -> quad(.2, .2, .21, .21)
          3 -> quad(.3, .1, .9, .7)
          else -> page
        }
        val input = LiveQuadInput(candidate, 240, 320, sensor, i * 77_000_000L)
        if (i % 3 == 0) assertEquals(reference.retainTracked(input), actual.retainTracked(input))
        assertEquals("row $i", reference.update(input), actual.update(input))
      }
    }
  }
  @Test fun nativeMetadataPreservesLargeTimestampsAndCloseIsIdempotent() {
    val s = LiveQuadStabilizer()
    val time = 9_007_199_254_740_993L
    val result = s.update(LiveQuadInput(page, 240, 320, time, time))
    assertEquals(time + 400_000_000, result.holdDeadlineNs)
    s.close(); s.close()
    assertThrows(IllegalStateException::class.java) { s.update(input(page, 1)) }
    val unused = LiveQuadStabilizer(); unused.close()
    assertThrows(IllegalStateException::class.java) { unused.update(input(page, 1)) }
  }
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
