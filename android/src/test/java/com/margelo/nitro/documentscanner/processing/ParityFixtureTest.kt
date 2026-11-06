package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.CaptureDecision
import com.margelo.nitro.documentscanner.CaptureGate
import com.margelo.nitro.documentscanner.CaptureState
import com.margelo.nitro.documentscanner.WaitReason
import com.margelo.nitro.documentscanner.ScannerErrorCode
import org.junit.Assert.*
import org.junit.Test

class ParityFixtureTest {
  @Test fun paddedRgbRowsKeepTheExistingIndependentResizeGoldens() {
    val resampler = BilinearRgbResampler()
    for (row in ParityFixtures.resource("strided-rgb")) {
      fun n(key: String) = row.getValue(key).toInt()
      val width = n("width"); val height = n("height"); val stride = n("stride"); val offset = n("offset")
      val storage = IntArray(offset + stride * height) { 0xffff00ff.toInt() }
      for (y in 0 until height) for (x in 0 until width) {
        storage[offset + y * stride + x] = (255 shl 24) or (((x * 17 + y * 31) % 256) shl 16) or
          (((x * 53 + y * 7) % 256) shl 8) or ((x * 11 + y * 97) % 256)
      }
      val result = IntArray(n("targetWidth") * n("targetHeight"))
      resampler.resize(width, height, n("targetWidth"), n("targetHeight"), result) { y, pixels ->
        storage.copyInto(pixels, 0, offset + y * stride, offset + y * stride + width)
      }
      assertEquals(row.getValue("id"), n("hash"), result.contentHashCode())
    }
  }

  @Test fun captureEventsPreserveOrderingAndDeadlines() {
    for ((_, events) in ParityFixtures.resource("capture-events").groupBy { it.getValue("id") }) {
      val gate = CaptureGate(1_500)
      for (row in events) {
        fun b(key: String) = row.getValue(key).toBooleanStrict()
        val actual = gate.next(CaptureState(
          nowMs = row.getValue("nowMs").toLong(), focusCheckEnabled = true, documentRequired = true,
          requireFocus = true, focusTimeoutMs = 2_000, documentFound = b("document"), focused = b("focus"),
          zslCheckEnabled = true, zslReady = b("zsl"), lightReady = b("torch"),
          motionCheckEnabled = true, steady = b("steady"),
        ))
        val expected = when (row.getValue("decision")) {
          "wait" -> CaptureDecision.Wait(WaitReason.valueOf(row.getValue("reason")), row.getValue("deadline").toLong())
          "shoot" -> CaptureDecision.Shoot
          else -> CaptureDecision.Reject(ScannerErrorCode.valueOf(row.getValue("decision")),
            WaitReason.valueOf(row.getValue("reason")))
        }
        assertEquals(row.toString(), expected, actual)
      }
    }
  }
}
