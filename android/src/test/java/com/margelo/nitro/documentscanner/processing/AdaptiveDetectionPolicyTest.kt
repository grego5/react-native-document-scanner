package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import org.junit.Assert.*
import org.junit.Test

class AdaptiveDetectionPolicyTest {
  @Test fun sharedPlatformSequences() {
    val stream = javaClass.classLoader!!.getResourceAsStream("parity/adaptive.tsv")!!
    val rows = stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
    assertTrue(rows.isNotEmpty())
    var case: String? = null
    var policy = AdaptiveDetectionPolicy()
    for (line in rows) {
      val v = line.split('\t')
      assertEquals(line, 12, v.size)
      if (v[0] != case) { case = v[0]; policy = AdaptiveDetectionPolicy() }
      when (v[1]) {
        "reference" -> policy.reference(corners(v[5]))
        "confirmed" -> policy.confirmed()
        "reset" -> policy.reset()
        "next" -> {
          val gap = policy.next(v[2].toBooleanStrict(), v[3].toBooleanStrict(), v[4].toBooleanStrict(), corners(v[5]),
            v[6].toInt(), v[7].toInt())
          assertEquals(line, if (v[8] == "max") Long.MAX_VALUE else v[8].toLong(), gap)
          assertEquals(line, v[9], policy.status)
          assertEquals(line, v[10], policy.lastReset)
          assertEquals(line, v[11].takeUnless { it == "-" }, policy.doubt)
          assertEquals(line, v[9] == "paused", policy.paused)
        }
        else -> fail("Unknown step: $line")
      }
    }
  }

  @Test fun resetDropsTheReferenceAndKeepsTheLastWakeReason() {
    val policy = AdaptiveDetectionPolicy()
    val page = corners("0.2,0.2,0.8,0.2,0.8,0.8,0.2,0.8")
    policy.reference(page)
    policy.next(true, false, false, null, 240, 320)
    policy.reset()
    assertFalse(policy.hasReference)
    assertEquals("acquiring", policy.status)
    assertEquals("tracking lost", policy.lastReset)
  }

  private fun corners(text: String): DocumentCorners? {
    if (text == "-") return null
    val v = text.split(',').map(String::toDouble)
    assertEquals(8, v.size)
    return DocumentCorners(DocumentPoint(v[0], v[1]), DocumentPoint(v[2], v[3]), DocumentPoint(v[4], v[5]), DocumentPoint(v[6], v[7]))
  }
}
