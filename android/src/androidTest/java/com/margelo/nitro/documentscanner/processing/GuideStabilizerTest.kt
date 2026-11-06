package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad

class GuideStabilizerTest {
  @Before fun initializeNative() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
  }
  private val page = corners(.2,.2,.8,.8)
  private fun corners(l:Double,t:Double,r:Double,b:Double) = DocumentCorners(DocumentPoint(l,t),DocumentPoint(r,t),DocumentPoint(r,b),DocumentPoint(l,b))
  private fun input(q:DocumentCorners?, ms:Long) = GuideInput(q,1000,1000,ms*1_000_000,ms*1_000_000)
  private fun acquired():GuideStabilizer = GuideStabilizer().also { s -> for (t in listOf(0L,33L,66L)) s.update(input(page,t)) }

  @Test fun nativePolicyMatchesFormerPolicyAcrossDeterministicReplay() {
    GuideStabilizer().use { actual ->
      val reference = ReferenceGuideStabilizer()
      var sensor = 0L
      for (i in 0 until 240) {
        if (i % 31 == 0) { actual.reset(); reference.reset() }
        if (i % 47 == 0) {
          val config = GuideStabilizerConfig(holdNs = if (i % 2 == 0) 200_000_000 else 400_000_000)
          actual.config = config; reference.config = config
        }
        sensor += when { i % 23 == 0 -> -10_000_000; i % 17 == 0 -> 0; else -> 33_000_000 }
        val candidate = when (i % 7) {
          0 -> null
          1 -> corners(-.1, .2, .8, .8)
          2 -> corners(.2, .2, .21, .21)
          3 -> corners(.3, .1, .9, .7)
          else -> page
        }
        val input = GuideInput(candidate, 240, 320, sensor, i * 77_000_000L)
        if (i % 3 == 0) assertEquals(reference.retainTracked(input), actual.retainTracked(input))
        assertEquals("row $i", reference.update(input), actual.update(input))
      }
    }
  }
  @Test fun nativeMetadataPreservesLargeTimestampsAndCloseIsIdempotent() {
    val s = GuideStabilizer()
    val time = 9_007_199_254_740_993L
    val result = s.update(GuideInput(page, 240, 320, time, time))
    assertEquals(time + 400_000_000, result.holdDeadlineNs)
    s.close(); s.close()
    assertThrows(IllegalStateException::class.java) { s.update(input(page, 1)) }
    val unused = GuideStabilizer(); unused.close()
    assertThrows(IllegalStateException::class.java) { unused.update(input(page, 1)) }
  }
  @Test fun acceptsFirstValidResultAndIgnoresDuplicateTimestamp() {
    val s=GuideStabilizer()
    assertEquals(page,s.update(input(page,0)).accepted)
    assertEquals(GuideDecision.DUPLICATE_TIMESTAMP,s.update(input(page,0)).decision)
  }
  @Test fun acceptsAValidConvexPerspective() {
    val q=DocumentCorners(DocumentPoint(.2,.2),DocumentPoint(.8,.2),DocumentPoint(.9,.8),DocumentPoint(.45,.6))
    val o=GuideStabilizer().update(input(q,0))
    assertEquals(q,o.accepted)
    assertEquals(GuideTrackingStatus.TRACKING,o.status)
  }
  @Test fun expiredOutlineCanBeReplacedImmediately() {
    val o=acquired().update(input(page,470))
    assertTrue(o.expiredBeforeInput)
    assertEquals(page,o.accepted)
    assertEquals(GuideTrackingStatus.TRACKING,o.status)
  }
  @Test fun invalidAndTinyOutlinesNeverAcquire() {
    val s=GuideStabilizer()
    assertEquals(GuideDecision.INVALID,s.update(input(corners(-.1,.2,.8,.8),0)).decision)
    assertEquals(GuideDecision.TOO_SMALL,s.update(input(corners(.2,.2,.21,.21),33)).decision)
  }
  @Test fun aDifferentValidOutlineReplacesImmediately() {
    val changed=corners(.4,.2,.95,.8)
    val output=acquired().update(input(changed,100))
    assertEquals(changed,output.accepted)
    assertEquals(GuideTrackingStatus.TRACKING,output.status)
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
    assertEquals(GuideDecision.TIMESTAMP_BACKWARD,s.update(input(page,50)).decision)
  }
}
