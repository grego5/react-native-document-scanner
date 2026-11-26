package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.assertEquals
import org.junit.Test

/** Edge widths recorded at 640×480 on the SM-G998U: a dim room at ISO 3937 and a lit desk at ISO 1500 to 2600. */
class FocusEvidenceTest {
  private val width = 640
  private val passive = CameraFrameState(33_000_000, 0, false, afState = 2)
  private val locked = CameraFrameState(33_000_000, 0, false, afState = 4)
  private val scanning = CameraFrameState(33_000_000, 0, true, afState = 3, lensMoving = true)

  private fun FocusEvidence.verdict(edges: EdgeWidth?, frame: CameraFrameState? = passive, blur: Double? = null) =
    judge(edges, width, frame, blur).verdict

  @Test fun passiveFocusShortOfThePageReadsDefocusedAndTheRightDistanceSharp() {
    val evidence = FocusEvidence()
    // Continuous autofocus reported focus at 6.6 diopters with the page at 4.4.
    repeat(10) { assertEquals(FocusVerdict.DEFOCUSED, evidence.verdict(EdgeWidth(4.47, 3.48, 4.58, 102))) }
    repeat(10) { assertEquals(FocusVerdict.DEFOCUSED, evidence.verdict(EdgeWidth(3.91, 2.67, 4.0, 105))) }
    assertEquals(FocusEvidence.FLOOR_DEFAULT, evidence.floor(), 1e-9)
    repeat(10) { assertEquals(FocusVerdict.SHARP, evidence.verdict(EdgeWidth(1.65, 1.59, 1.71, 311))) }
    assertEquals(1.65, evidence.floor(), .01)
  }

  @Test fun theFloorIsLearnedFromFocusedFramesAndAWrongLockDoesNotTeachIt() {
    val evidence = FocusEvidence()
    repeat(20) { evidence.verdict(EdgeWidth(1.13, 1.34, .8, 4261), locked) }
    assertEquals(1.13, evidence.floor(), .01)
    // A lock that settled the wrong way.
    repeat(20) { assertEquals(FocusVerdict.DEFOCUSED, evidence.verdict(EdgeWidth(14.58, 14.8, 12.9, 56), locked)) }
    assertEquals(FocusVerdict.DEFOCUSED, evidence.verdict(EdgeWidth(4.19, 4.63, 3.17, 199), null))
    assertEquals(1.13, evidence.floor(), .01)
    evidence.reset()
    assertEquals(FocusEvidence.FLOOR_DEFAULT, evidence.floor(), 1e-9)
  }

  @Test fun motionReadsSmearedAndAMovingLensUnknown() {
    val evidence = FocusEvidence()
    // One direction wide while the other stays sharp.
    assertEquals(FocusVerdict.SMEARED, evidence.verdict(EdgeWidth(1.2, .87, 2.81, 320)))
    // The gyroscope's blur over the exposure past a floor's width.
    assertEquals(FocusVerdict.SMEARED, evidence.verdict(EdgeWidth(3.0, 2.9, 3.1, 300), blur = .003))
    // Edges that measure sharp were not blurred, whatever the gyroscope says.
    assertEquals(FocusVerdict.SHARP, evidence.verdict(EdgeWidth(1.4, 1.4, 1.4, 300), blur = .003))
    assertEquals(FocusVerdict.SHARP, evidence.verdict(EdgeWidth(1.4, 1.4, 1.4, 300), blur = .0005))
    assertEquals(FocusVerdict.UNKNOWN, evidence.verdict(EdgeWidth(4.07, 3.02, 4.27, 101), scanning))
    assertEquals(FocusVerdict.UNKNOWN, evidence.verdict(null))
    // Between sharp and defocused.
    assertEquals(FocusVerdict.UNKNOWN, evidence.verdict(EdgeWidth(3.0, 2.9, 3.1, 140)))
    // Smeared and moving-lens frames teach no floor.
    repeat(20) { evidence.verdict(EdgeWidth(1.0, 1.0, 1.0, 300), scanning) }
    assertEquals(FocusEvidence.FLOOR_DEFAULT, evidence.floor(), 1e-9)
  }

  @Test fun aFloorLearnedUnderTheTorchStillReadsAHandsTremorAsSharp() {
    val evidence = FocusEvidence()
    // Lit by the torch at ISO 268, focused frames measured a single pixel's turn.
    repeat(20) { evidence.verdict(EdgeWidth(.84, 1.04, .8, 5200)) }
    assertEquals(FocusEvidence.FLOOR_MIN, evidence.floor(), .05)
    // A handheld turn of 0.2 to 0.4 rad/s over the exposure.
    assertEquals(FocusVerdict.SHARP, evidence.verdict(EdgeWidth(.85, 1.11, .8, 5146), blur = .0015))
    assertEquals(FocusVerdict.SHARP, evidence.verdict(EdgeWidth(1.13, 1.42, .85, 379)))
    // Continuous autofocus at 7.1 diopters with the page at 4.
    assertEquals(FocusVerdict.DEFOCUSED, evidence.verdict(EdgeWidth(7.27, 6.16, 7.33, 44)))
  }
}
