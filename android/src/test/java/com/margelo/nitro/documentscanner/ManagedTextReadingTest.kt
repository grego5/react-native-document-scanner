package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedTextReadingTest {
  private val tile = TextRegion(0.1, 0.4, 0.8, 0.2)

  private fun read(text: String, y: Double, height: Double) =
    TextMap.Read(text, 0.9f, TextRegion(0.3, y, 0.4, height).corners())

  /** Scales every corners by [factor] about its own centre, as moving nearer does to text near the aim. */
  private fun grow(factor: Double): (DocumentCorners) -> DocumentCorners? = { c ->
    val box = c.boundingRegion()
    val cx = box.x + box.width / 2
    val cy = box.y + box.height / 2
    fun p(q: DocumentPoint) = DocumentPoint(cx + (q.x - cx) * factor, cy + (q.y - cy) * factor)
    DocumentCorners(p(c.topLeft), p(c.topRight), p(c.bottomRight), p(c.bottomLeft))
  }

  @Test fun aScanThatStartsAndFailsDuringARecognitionChangesTheAdmission() {
    val settled = ReadPermission().next(cycle = true, scanning = false, settled = 5, latestCaptureNs = 100)
    assertEquals(5L, settled.lock)
    // A recognition starts under this admission; a scan starts, then fails, handing the lens back to lock 5.
    val recognizing = settled.admission
    val scanning = settled.next(cycle = true, scanning = true, settled = null, latestCaptureNs = 200)
    assertEquals(0L, scanning.lock)
    val failed = scanning.next(cycle = true, scanning = false, settled = null, latestCaptureNs = 300)
    assertEquals(5L, failed.lock)
    assertNotEquals(recognizing, failed.admission)
    // Frames queued while the scan ran are not read once the lock returns.
    assertEquals(301L, failed.fromNs)
  }

  @Test fun aHighlightQueuedAcrossALockChangeIsStale() {
    val first = ReadPermission().next(cycle = true, scanning = false, settled = 5, latestCaptureNs = 100)
    val queued = first.admission
    val next = first.next(cycle = true, scanning = true, settled = null, latestCaptureNs = 150)
      .next(cycle = true, scanning = false, settled = 6, latestCaptureNs = 200)
    assertNotEquals(queued, next.admission)
    assertEquals(201L, next.fromNs)
  }

  @Test fun aReleasedLockKeepsItsAdmissionAndTheMap() {
    val settled = ReadPermission().next(cycle = true, scanning = false, settled = 5, latestCaptureNs = 100)
    val released = settled.next(cycle = true, scanning = false, settled = null, latestCaptureNs = 200)
    assertEquals(5L, released.lock)
    assertEquals(settled.admission, released.admission)
    val stopped = released.next(cycle = false, scanning = false, settled = null, latestCaptureNs = 300)
    assertNull(stopped.lock)
    assertEquals(0L, stopped.lastLock)
  }

  @Test fun identicalTextAtAnotherSizeIsANewBaselineNotAChangeOfDistance() {
    val reading = ManagedTextReading()
    reading.underLock(5)
    // Two lines reading the same, the second half again as large.
    repeat(2) { reading.map.merge(tile, listOf(read("AB12", 0.42, 0.02), read("AB12", 0.5, 0.03)), sensorNs = 0L) }
    val small = reading.select(0.5, 0.43, afterRead = true, admission = 1).line
    assertNotNull(small)
    assertNull(reading.distanceChange(true, 5, small, 1.15))
    val large = reading.select(0.5, 0.515, afterRead = false, admission = 1)
    assertTrue(large.changed)
    assertNull(reading.distanceChange(true, 5, large.line, 1.15))
    // The same line growing is a change of distance, reported once.
    assertTrue(reading.map.move(grow(1.2)))
    val grown = reading.select(0.5, 0.515, afterRead = false, admission = 1).line
    assertEquals(1.2, reading.distanceChange(true, 5, grown, 1.15)!!, 1e-9)
    assertNull(reading.distanceChange(true, 5, grown, 1.15))
  }

  @Test fun aReReadOfTheAimedLinePublishesItsRenewedBounds() {
    val reading = ManagedTextReading()
    reading.underLock(5)
    repeat(2) { reading.map.merge(tile, listOf(read("AB12", 0.48, 0.03)), sensorNs = 0L) }
    assertTrue(reading.select(0.5, 0.495, afterRead = true, admission = 1).publish)
    assertFalse(reading.select(0.5, 0.495, afterRead = false, admission = 1).publish)
    reading.map.merge(tile, listOf(read("AB12", 0.481, 0.03)), sensorNs = 0L)
    val again = reading.select(0.5, 0.495, afterRead = true, admission = 1)
    assertTrue(again.publish)
    assertFalse(again.changed)
  }

  @Test fun aNewLockReadsAfresh() {
    val reading = ManagedTextReading()
    reading.underLock(5)
    reading.map.merge(tile, listOf(read("AB12", 0.48, 0.03)), sensorNs = 0L)
    reading.lockReads.recognized(false)
    reading.underLock(5)
    assertEquals(1, reading.map.size)
    reading.underLock(6)
    assertEquals(0, reading.map.size)
    assertFalse(reading.lockReads.reported)
  }

  @Test fun aClearMainDroppedIsRepublishedUnderTheNextAdmission() {
    val reading = ReadingFixture()
    // The aim on agreed text, published under admission 1.
    assertTrue(reading.select(0.5, 0.495, admission = 1).publish)
    // The aim moves onto blank paper: the clear is queued under admission 1 as a scan starts, and main drops it.
    assertTrue(reading.select(0.5, 0.58, admission = 1).publish)
    assertFalse(reading.select(0.5, 0.58, admission = 1).publish)
    // The scan fails back to the same lock under admission 3: the clear is published again.
    val repaired = reading.select(0.5, 0.58, admission = 3)
    assertTrue(repaired.publish)
    assertNull(repaired.agreed)
    assertFalse(reading.select(0.5, 0.58, admission = 3).publish)
  }

  private inner class ReadingFixture {
    val state = ManagedTextReading().apply {
      underLock(5)
      repeat(2) { map.merge(tile, listOf(read("AB12", 0.48, 0.03)), sensorNs = 0L) }
    }
    fun select(x: Double, y: Double, admission: Long) = state.select(x, y, afterRead = false, admission = admission)
  }
}
