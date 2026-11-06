package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CameraMetadata

/** OFF and EDOF have no adjustable autofocus scan; the minimum focus distance may be missing. */
internal fun hasAdjustableAutofocus(modes: IntArray?): Boolean = modes?.any {
  it == CameraMetadata.CONTROL_AF_MODE_AUTO || it == CameraMetadata.CONTROL_AF_MODE_MACRO ||
    it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE || it == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
} == true
