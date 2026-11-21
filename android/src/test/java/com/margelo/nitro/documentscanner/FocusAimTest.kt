package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusAimTest {
  private val area = FocusAim.AREA

  @Test fun driftInsideAQuarterOfTheBoxDoesNotMoveIt() {
    val aim = FocusAim()
    assertTrue(aim.centred)
    assertFalse(aim.observe(.55f, .52f, area))
    assertTrue(aim.observe(.6f, .5f, area))
    assertEquals(.6f, aim.x)
    assertFalse(aim.centred)
  }

  @Test fun aSmallerBoxInPlaceAlsoMovesTheAim() {
    val aim = FocusAim()
    assertTrue(aim.observe(.5f, .5f, .1f))
    assertEquals(.1f, aim.size)
    assertFalse(aim.centred)
  }

  @Test fun resetReturnsToTheCentre() {
    val aim = FocusAim()
    aim.observe(.2f, .2f, area)
    aim.reset()
    assertTrue(aim.centred)
  }

  private val page = floatArrayOf(.1f, .1f, .9f, .1f, .9f, .9f, .1f, .9f)

  @Test fun aBoxInsideThePageStays() {
    val box = fitInside(page, .4f, .6f, .5f, .5f, .3f)!!
    assertEquals(listOf(.4f, .6f, .3f), box.toList())
  }

  @Test fun aBoxPastAnEdgeMovesTowardsTheCentreUntilInside() {
    val box = fitInside(page, .15f, .15f, .5f, .5f, .3f)!!
    assertEquals(.25f, box[0], .002f)
    assertEquals(.25f, box[1], .002f)
    assertEquals(.3f, box[2])
  }

  @Test fun aLetterboxedImageNarrowsTheBox() {
    // The image spans half the view's width, so the box is half as wide and fits nearer the left edge.
    val box = fitInside(page, .15f, .5f, .5f, .5f, .3f, imageWidth = .5f)!!
    assertEquals(.175f, box[0], .002f)
    assertEquals(.5f, box[1], .002f)
  }

  @Test fun aPageSmallerThanTheBoxShrinksItAroundTheCentre() {
    val small = floatArrayOf(.45f, .4f, .55f, .4f, .55f, .6f, .45f, .6f)
    val box = fitInside(small, .5f, .45f, .5f, .5f, .3f)!!
    assertEquals(.5f, box[0])
    assertEquals(.5f, box[1])
    assertEquals(.1f, box[2], .002f)
  }

  @Test fun aPageTooNarrowForTheSmallestBoxHasNone() {
    val sliver = floatArrayOf(.49f, .2f, .51f, .2f, .51f, .8f, .49f, .8f)
    assertNull(fitInside(sliver, .5f, .5f, .5f, .5f, .3f))
  }

  @Test fun aTiltedPageKeepsTheBoxWithinItsEdges() {
    val diamond = floatArrayOf(.5f, .1f, .9f, .5f, .5f, .9f, .1f, .5f)
    val box = fitInside(diamond, .5f, .2f, .5f, .5f, .3f)!!
    // The box's top corners touch the diamond's upper edges.
    assertEquals(.5f, box[0], .002f)
    assertEquals(.1f + .3f, box[1], .003f)
  }
}
