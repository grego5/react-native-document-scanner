package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.SampledSpot
import com.margelo.nitro.documentscanner.processing.SpotRequests

/**
 * Main thread, `focusLock`: the document camera's guide spot, the guide area's most textured point that a guide lock
 * aims at, through the one request the detector answers for it. Each request ends in an answer, a spot or a failed
 * pick, or is cancelled; an answer lost on its way, as a focus candidate dropped for its age, is detected and asked for
 * again, so a steady hold never waits on an answer that will not come.
 */
internal class GuideSpot {
  sealed interface State {
    data object None : State
    data class Requested(val id: Long) : State
    data class Picked(val point: DocumentPoint) : State
    /** The pick found no spot, as a featureless guide area; the guide lock takes the centre. */
    data object Failed : State
  }

  var state: State = State.None
    private set

  val point: DocumentPoint? get() = (state as? State.Picked)?.point

  /** Whether a guide lock can aim: at the spot picked, or at the centre after a failed pick. */
  val ready: Boolean get() = state is State.Picked || state == State.Failed

  /**
   * One frame without a page, at [sensorNs]. [sampled] is the answer the frame carries, [requests] the detector's
   * requests now. A request the detector answered on an earlier frame whose answer never arrived, or one it no longer
   * holds and never answered, is lost. With no request or spot, [wanted] asks for one, and [ask] sends it and returns
   * its id.
   */
  fun update(sampled: SampledSpot?, requests: SpotRequests, sensorNs: Long, wanted: Boolean, ask: () -> Long) {
    val current = state
    if (current is State.Requested) {
      val id = current.id
      if (sampled?.request == id) {
        state = sampled.point?.let(State::Picked) ?: State.Failed
        return
      }
      val lost = requests.pending != id && (requests.taken != id || sensorNs >= requests.takenNs)
      if (lost) state = State.None
    }
    if (state == State.None && wanted) state = State.Requested(ask())
  }

  /** Forgets the spot and any request; the caller cancels the detector's request too. */
  fun cancel() {
    state = State.None
  }
}
