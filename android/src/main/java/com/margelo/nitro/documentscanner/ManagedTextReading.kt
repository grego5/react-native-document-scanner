package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Main thread, `focusLock`: what the text map may read. [lock] is the map's identity: the latest settled lock, kept
 * after its release so the map lives on, 0 during a lock's scan and before the first lock, null without the focus
 * cycle. [admission] changes with every change of [lock], a scan that starts and fails back to the same lock
 * included, so a recognition or a queued publication from before it is the old permission's and drops. Frames
 * exposed before [fromNs] were queued when the lock settled and are not read under it. [lastLock] is the latest
 * settled lock, which [lock] returns to once a scan ends.
 */
internal data class ReadPermission(
  val lock: Long? = null, val admission: Long = 0L, val fromNs: Long = 0L, val lastLock: Long = 0L,
) {
  /**
   * The permission once the focus cycle runs or not, as [cycle], with a scan holding the lens, as [scanning], or a
   * settled lock of generation [settled]; [latestCaptureNs] is the newest frame the camera reported.
   */
  fun next(cycle: Boolean, scanning: Boolean, settled: Long?, latestCaptureNs: Long): ReadPermission {
    val last = if (cycle) settled ?: lastLock else 0L
    val next = when {
      !cycle -> null
      settled != null -> settled
      scanning -> 0L
      else -> last
    }
    if (next == lock) return copy(lastLock = last)
    // Frames already queued when a lock settles were exposed before it.
    val from = if (next != null && next != 0L) latestCaptureNs + 1 else fromNs
    return ReadPermission(next, admission + 1, from, last)
  }
}

/**
 * Analysis thread, `focusLock`: the managed OCR state. It owns the text map read under one lock, the sensor times its
 * places refer to, the lock's first-read verdict, the aimed line published and that line's distance baseline. A new
 * lock clears the map, since what came before it may have been soft.
 */
internal class ManagedTextReading {
  val map = TextMap()
  val lockReads = TextMap.LockReads()
  /** The sensor time the map's places refer to. */
  var mapNs = 0L
    private set
  private var lock = 0L
  private var shownLine: TextMap.Line? = null
  private var shownText: String? = null
  private var shownAdmission = -1L
  private var baselineLine: TextMap.Line? = null
  private var baselineLock = 0L
  private var baselineArea = 0.0
  private var rescaledLock = 0L

  fun reset() {
    map.clear()
    lockReads.reset()
    lock = 0L
    mapNs = 0L
    shownLine = null
    shownText = null
    shownAdmission = -1L
    baselineLine = null
    baselineLock = 0L
    rescaledLock = 0L
  }

  /** A frame read under [lock], 0 for none yet: a new lock starts the map, and its first reads, afresh. */
  fun underLock(lock: Long) {
    if (lock == 0L || lock == this.lock) return
    this.lock = lock
    map.clear()
    lockReads.reset()
    baselineLine = null
  }

  /** The map's places now refer to [sensorNs]. */
  fun carried(sensorNs: Long) {
    mapNs = sensorNs
  }

  /**
   * The aim's selection: the line it is on, [aimed], and that line as [line] once its reads agree, on [agreed];
   * [publish] when the public reading must change, as the aimed line or its text does, or after a recognition renewed
   * the aimed line's bounds, confidence and votes, and once under each read [admission], empty selections included:
   * main drops a publication queued across a change of admission, and the first frame under the new one republishes
   * what the aim is on. [changed] when the line or text did.
   */
  class Selection(val aimed: TextMap.Line?, val line: TextMap.Line?, val agreed: TextVotes.Winner?, val publish: Boolean,
    val changed: Boolean)

  /** Selects for the aim at [x], [y] under read [admission]; [afterRead] after a recognition merged. */
  fun select(x: Double, y: Double, afterRead: Boolean, admission: Long): Selection {
    val aimed = map.at(x, y)
    val agreed = aimed?.agreed()
    val changed = agreed?.text != shownText || (agreed != null && aimed !== shownLine)
    val publish = changed || admission != shownAdmission || (afterRead && agreed != null)
    if (publish) {
      shownText = agreed?.text
      shownLine = aimed.takeIf { agreed != null }
      shownAdmission = admission
    }
    return Selection(aimed, aimed.takeIf { agreed != null }, agreed, publish, changed)
  }

  /**
   * The aimed, agreed [line] under the held lock [lock], while [locked]: its size ratio once it has grown or shrunk by
   * [limit] since it was first measured, as moving the phone nearer or farther does; null otherwise, and after the
   * lock's change was reported. The baseline is the line itself, so moving onto another line, even one with the same
   * text at another size, measures afresh.
   */
  fun distanceChange(locked: Boolean, lock: Long, line: TextMap.Line?, limit: Double): Double? {
    if (!locked || lock == 0L) {
      baselineLine = null
      return null
    }
    if (line == null || rescaledLock == lock) return null
    val area = line.corners.area()
    if (area <= 0.0) return null
    if (line !== baselineLine || baselineLock != lock) {
      baselineLine = line
      baselineLock = lock
      baselineArea = area
      return null
    }
    val scale = sqrt(area / baselineArea)
    if (abs(ln(scale)) < ln(limit)) return null
    rescaledLock = lock
    return scale
  }
}
