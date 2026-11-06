package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CaptureResult

/**
 * Whether auto white balance keeps searching, so that CameraX's zero-shutter-lag buffer, which accepts only frames with
 * white balance converged, takes no new frame. It turns unsettled after [SUSTAIN_NS] of frames whose exposure has
 * converged but whose white balance has not, and settled again after as long converged. Frames with exposure still
 * moving are skipped, so a camera start or a change of light, where both search, does not count. Under the torch and a
 * lamp on the SM-G998U, exposure converged and white balance searched for every frame. Confined by its caller.
 */
internal class WhiteBalancePolicy {
  var unsettled = false
    private set
  private var changingSince: Long? = null

  /** Observation stopped: [unsettled] stands, but a flip under way restarts its second from the next frame, so time
   * without frames never counts. */
  fun interrupt() {
    changingSince = null
  }

  /** Returns true on the observation that flips [unsettled]. The states are the frame's raw CONTROL_AE_STATE and
   * CONTROL_AWB_STATE, null when unreported. */
  fun observe(observationNs: Long, aeState: Int?, awbState: Int?): Boolean {
    if (awbState == null || aeState != CaptureResult.CONTROL_AE_STATE_CONVERGED) {
      changingSince = null
      return false
    }
    val toward = (awbState != CaptureResult.CONTROL_AWB_STATE_CONVERGED) != unsettled
    if (!toward) {
      changingSince = null
      return false
    }
    val since = changingSince ?: observationNs.also { changingSince = it }
    if (observationNs - since < SUSTAIN_NS) return false
    unsettled = !unsettled
    changingSince = null
    return true
  }

  companion object {
    const val SUSTAIN_NS = 1_000_000_000L
  }
}
