package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMapTest {
  private val tile = TextRegion(0.1, 0.44, 0.8, 0.12)
  private fun read(text: String, y: Double, x: Double = 0.3, width: Double = 0.4) =
    TextMap.Read(text, 0.9f, TextRegion(x, y, width, 0.03).corners())
  private fun shift(dy: Double): (DocumentCorners) -> DocumentCorners? = { c ->
    DocumentCorners(DocumentPoint(c.topLeft.x, c.topLeft.y + dy), DocumentPoint(c.topRight.x, c.topRight.y + dy),
      DocumentPoint(c.bottomRight.x, c.bottomRight.y + dy), DocumentPoint(c.bottomLeft.x, c.bottomLeft.y + dy))
  }

  @Test fun everyLineOfATileIsReadAndAgreesOnItsSecondRead() {
    val map = TextMap()
    map.merge(tile, listOf(read("FIRST", 0.45), read("SECOND", 0.5)), sensorNs = 0L)
    assertEquals(2, map.size)
    assertNull(map.at(0.5, 0.515)!!.agreed())
    map.merge(tile, listOf(read("FIRST", 0.451), read("SECOND", 0.501)), sensorNs = 0L)
    assertEquals("FIRST", map.at(0.5, 0.465)!!.agreed()!!.text)
    assertEquals("SECOND", map.at(0.5, 0.515)!!.agreed()!!.text)
  }

  @Test fun theAimSelectsANeighbouringLineWithoutANewRead() {
    val map = TextMap()
    repeat(2) { map.merge(tile, listOf(read("FIRST", 0.45), read("SECOND", 0.5)), sensorNs = 0L) }
    assertEquals("FIRST", map.at(0.5, 0.465)!!.agreed()!!.text)
    // The page moves up a line under the aim: the line below is now there.
    assertTrue(map.move(shift(-0.05)))
    assertEquals("SECOND", map.at(0.5, 0.465)!!.agreed()!!.text)
  }

  @Test fun theAimDoesNotSelectTheGapBetweenLinesOrPastTheEnd() {
    val map = TextMap()
    repeat(2) { map.merge(tile, listOf(read("FIRST", 0.45), read("SECOND", 0.49)), sensorNs = 0L) }
    assertEquals("FIRST", map.at(0.5, 0.47)!!.agreed()!!.text)
    assertNull(map.at(0.5, 0.485))
    assertNull(map.at(0.71, 0.47))
    assertEquals("SECOND", map.at(0.5, 0.50)!!.agreed()!!.text)
  }

  @Test fun theAimUsesTheWarpedBoxRatherThanExtendingItsTopAndLeftEdges() {
    val map = TextMap()
    val corners = DocumentCorners(DocumentPoint(0.3, 0.45), DocumentPoint(0.7, 0.45),
      DocumentPoint(0.6, 0.49), DocumentPoint(0.3, 0.49))
    map.merge(tile, listOf(TextMap.Read("TEXT", 0.9f, corners)), sensorNs = 0L)
    assertTrue(map.at(0.55, 0.48) != null)
    assertNull(map.at(0.66, 0.48))
  }

  @Test fun aMisreadAtTheSamePlaceIsOutvoted() {
    val map = TextMap()
    map.merge(tile, listOf(read("AB1234", 0.45)), sensorNs = 0L)
    map.merge(tile, listOf(read("A81234", 0.45)), sensorNs = 0L)
    assertEquals(1, map.size)
    assertNull(map.at(0.5, 0.465)!!.agreed())
    map.merge(tile, listOf(read("AB1234", 0.45)), sensorNs = 0L)
    assertEquals("AB1234", map.at(0.5, 0.465)!!.agreed()!!.text)
  }

  @Test fun aLineTheTileNoLongerFindsIsGoneButOneOutsideItStays() {
    val map = TextMap()
    map.merge(tile, listOf(read("INSIDE", 0.45)), sensorNs = 0L)
    map.move(shift(-0.1))
    // INSIDE is now at 0.35, above the next tile.
    map.merge(tile, listOf(read("BELOW", 0.5)), sensorNs = 0L)
    assertEquals(2, map.size)
    map.merge(tile, emptyList(), sensorNs = 0L)
    assertEquals(1, map.size)
    assertTrue(map.at(0.5, 0.365) != null)
  }

  @Test fun linesFarFromTheAimArePruned() {
    val map = TextMap()
    map.merge(tile, listOf(read("NEAR", 0.45)), sensorNs = 0L)
    map.move(shift(-0.3))
    map.prune(TextRegion(0.0, 0.32, 1.0, 0.36), oldestReadNs = 0L)
    assertEquals(0, map.size)
  }

  @Test fun aLineThatCannotBeMovedLosesTheMap() {
    val map = TextMap()
    map.merge(tile, listOf(read("FIRST", 0.45)), sensorNs = 0L)
    assertFalse(map.move { null })
    assertEquals(0, map.size)
    assertNull(map.tile)
  }

  @Test fun theTileCoversTheRegionUntilThePageMovesOn() {
    val map = TextMap()
    assertFalse(map.covers(tile))
    map.merge(tile, emptyList(), sensorNs = 0L)
    assertTrue(map.covers(tile))
    map.move(shift(0.02))
    assertTrue(map.covers(tile))
    map.move(shift(0.04))
    assertFalse(map.covers(tile))
  }

  @Test fun aCoveredRegionOverNoTextIsNotReadUntilMotionLosesTheTile() {
    val map = TextMap()
    assertTrue(map.needsRead(tile, map.at(0.5, 0.5), sensorNs = 0L))
    map.merge(tile, emptyList(), sensorNs = 0L)
    assertFalse(map.needsRead(tile, map.at(0.5, 0.5), sensorNs = 0L))
    // The phone moves from blank paper with nothing to track: the tile's place is unknown.
    map.clear()
    assertTrue(map.needsRead(tile, map.at(0.5, 0.5), sensorNs = 0L))
    map.merge(tile, listOf(read("TEXT", 0.485)), sensorNs = 0L)
    assertTrue(map.needsRead(tile, map.at(0.5, 0.5), sensorNs = 0L))
    map.merge(tile, listOf(read("TEXT", 0.485)), sensorNs = 0L)
    assertFalse(map.needsRead(tile, map.at(0.5, 0.5), sensorNs = 0L))
    assertEquals("TEXT", map.at(0.5, 0.5)!!.agreed()!!.text)
  }

  @Test fun flowDoesNotRenewTheOcrPositionOfATile() {
    val map = TextMap()
    repeat(2) { map.merge(tile, listOf(read("TEXT", 0.45)), sensorNs = 0L) }
    map.move(shift(0.005))
    val aimed = map.at(0.5, 0.47)
    assertFalse(map.needsRead(tile, aimed, TextMap.REFRESH_NS - 1))
    assertTrue(map.needsRead(tile, aimed, TextMap.REFRESH_NS))

    map.merge(tile, listOf(read("TEXT", 0.45)), sensorNs = TextMap.REFRESH_NS)
    assertEquals(0.45, map.at(0.5, 0.47)!!.corners.topLeft.y, 1e-9)
    assertFalse(map.needsRead(tile, aimed, TextMap.REFRESH_NS))
  }

  @Test fun expiredBoxesCannotBeSelectedAndAnUnreadTileCanStillRefresh() {
    val map = TextMap()
    map.merge(tile, listOf(read("OLD", 0.45)), sensorNs = 0L)
    val now = TextMap.LINE_LIFETIME_NS + 1
    map.prune(tile, now - TextMap.LINE_LIFETIME_NS)
    assertNull(map.at(0.5, 0.465))
    assertTrue(map.needsRead(tile, null, now))

    map.merge(tile, emptyList(), sensorNs = now)
    assertFalse(map.needsRead(tile, null, now + TextMap.REFRESH_NS))
  }

  @Test fun aLocksFirstReadThatMissesIsNoVerdict() {
    val reads = TextMap.LockReads()
    assertNull(reads.recognized(false))
    assertFalse(reads.reported)
    assertEquals(true, reads.recognized(true))
    assertNull(reads.recognized(false))
    reads.reset()
    repeat(TextMap.MAX_READS - 1) { assertNull(reads.recognized(false)) }
    assertEquals(false, reads.recognized(false))
  }

  @Test fun aLinesWinnerIsTalliedWhenAReadArrivesWithTheSameTieBreaks() {
    val map = TextMap()
    map.merge(tile, listOf(TextMap.Read("AB12", 0.6f, TextRegion(0.3, 0.45, 0.4, 0.03).corners())), sensorNs = 0L)
    map.merge(tile, listOf(TextMap.Read("A812", 0.9f, TextRegion(0.3, 0.45, 0.4, 0.03).corners())), sensorNs = 0L)
    val line = map.at(0.5, 0.465)!!
    // One read each: the higher confidence leads, and stays the same object until a read arrives.
    assertEquals("A812", line.winner()!!.text)
    assertTrue(line.winner() === line.winner())
    map.merge(tile, listOf(TextMap.Read("AB12", 0.6f, TextRegion(0.3, 0.45, 0.4, 0.03).corners())), sensorNs = 0L)
    assertEquals("AB12", line.agreed()!!.text)
    assertEquals(2, line.agreed()!!.votes)
  }

  @Test fun readsThatNeverAgreeAreExhausted() {
    val map = TextMap()
    for (text in listOf("A", "B", "C", "D")) map.merge(tile, listOf(read(text, 0.45)), sensorNs = 0L)
    assertTrue(map.at(0.5, 0.465)!!.exhausted)
  }
}
