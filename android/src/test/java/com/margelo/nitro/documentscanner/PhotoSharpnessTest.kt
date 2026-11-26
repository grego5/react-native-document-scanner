package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.EdgeWidth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photo edge widths recorded on the SM-G998U at 4000×3000. */
class PhotoSharpnessTest {
  @Test fun densePrintFlagsSoftOnlyPastTheLimit() {
    assertFalse(photoSoft(EdgeWidth(1.67, 1.73, 1.62, 8737)))
    assertFalse(photoSoft(EdgeWidth(2.09, 2.20, 1.95, 6907)))
    assertTrue(photoSoft(EdgeWidth(3.4, 3.2, 3.6, 2400)))
  }

  @Test fun denseSmearOneWayFlagsDespiteASharpMedian() {
    assertTrue(photoSoft(EdgeWidth(2.36, 7.12, 2.31, 2294)))
    assertFalse(photoSoft(EdgeWidth(2.09, 2.34, 1.76, 7168)))
  }

  @Test fun sparsePrintFlagsOnlyPastItsWiderLimit() {
    // Sparse print measures somewhat wider while sharp.
    assertFalse(photoSoft(EdgeWidth(3.00, 2.72, 3.11, 183)))
    assertFalse(photoSoft(EdgeWidth(2.67, 2.93, 2.50, 144)))
    // Blur thins the edges found, so a smeared photo has few of them.
    assertTrue(photoSoft(EdgeWidth(6.44, 6.44, 6.23, 130)))
    assertTrue(photoSoft(EdgeWidth(3.56, 3.03, 4.24, 853)))
  }

  @Test fun tooLittlePrintNeverFlags() {
    assertFalse(photoSoft(EdgeWidth(7.0, 7.0, 7.0, 60)))
  }
}
