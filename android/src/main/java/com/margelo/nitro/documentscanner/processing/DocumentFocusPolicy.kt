package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CameraMetadata

internal enum class DocumentFocusAction { NONE, START, RELEASE }

/** OFF and EDOF have no adjustable autofocus scan; the minimum focus distance may be missing. */
internal fun hasAdjustableAutofocus(modes: IntArray?): Boolean = modes?.any {
  it == CameraMetadata.CONTROL_AF_MODE_AUTO || it == CameraMetadata.CONTROL_AF_MODE_MACRO ||
    it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE || it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
} == true

/** Where `scanner` mode parks the lens before any lock, in diopters: about 30 cm, a typical scan distance. At
 * the model's 224-pixel input a lens parked there blurred pages from 15 cm to infinity by under 0.7% of the frame in
 * defocus estimates, below the 1% at which simulated blur first moved detections.
 */
internal const val DOCUMENT_PARK_DIOPTERS = 1 / 0.3f

/** The lens can be held at a set distance: autofocus can be turned off, focus distance is a manual control, and the
 * lens reports its focus range.
 */
internal fun canParkFocus(afModes: IntArray?, capabilities: IntArray?, minimumFocusDiopters: Float?): Boolean =
  afModes?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true &&
    capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
    minimumFocusDiopters != null && minimumFocusDiopters > 0f && hasAdjustableAutofocus(afModes)

/** The parked distance: the last focus lock's, which follows how far the user holds the phone, else
 * [DOCUMENT_PARK_DIOPTERS]; within the lens's range. An uncalibrated lens reports distances in its own monotonic units,
 * which only the learned distance matches.
 */
internal fun parkDiopters(learned: Float?, minimumFocusDiopters: Float): Float =
  (learned?.takeIf { it.isFinite() } ?: DOCUMENT_PARK_DIOPTERS).coerceIn(0f, minimumFocusDiopters)
