package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import com.margelo.nitro.documentscanner.processing.CornerModelStore
import java.util.UUID

/** Reserves one camera capture's immutable destinations and keeps writer pins through its last callback. */
internal class SessionCapture private constructor(
  val session: ScanSessionState,
  val options: DocumentRenderOptions,
  private val selection: CornerModelStore.Selection?,
  val original: SessionResources.Output,
  val frame: SessionResources.Output,
) : AutoCloseable {
  val pageId = UUID.randomUUID().toString()
  private var kept = false
  private var ended = false
  private var invalidated = false
  fun invalidate() { invalidated = true }

  /** Call only after original/frame saving and the view generation's publication claim succeed. */
  fun publish(frameResource: SessionResources.Resource?, liveCorners: DocumentCorners?, autoCrop: Boolean = true): String {
    check(!ended && !kept)
    if (invalidated) throw ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)
    session.admit(pageId, original.resource, frameResource, liveCorners, options, selection, autoCrop, normalized = true)
    kept = true
    return pageId
  }

  override fun close() {
    if (ended) return
    ended = true
    if (!kept) { original.discard(); frame.discard() }
    frame.close()
    original.close()
  }

  companion object {
    /** Freezes the camera's model at reservation and reserves the original and frame outputs. */
    fun reserve(session: ScanSessionState, options: DocumentRenderOptions?): SessionCapture {
      if (session.snapshot().closed) throw ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
      val effective = DocumentRenderer.effectiveOptions(options)
      val original = session.resources.output(SessionResources.Kind.ORIGINAL)
      try {
        return SessionCapture(session, effective, CornerModelStore.selected, original,
          session.resources.output(SessionResources.Kind.FRAME))
      } catch (error: Throwable) {
        original.close()
        throw error
      }
    }
  }
}

/** A single admission slot includes waiting, physical shooting and saving, even after promise rejection. */
internal class CaptureAdmission<T : Any>(private val lock: Any) {
  private var active: T? = null
  val current: T? get() = synchronized(lock) { active }
  fun admit(create: () -> T): T = synchronized(lock) {
    if (active != null) throw ScannerException(ScannerErrorCode.CAPTURE_BUSY)
    create().also { active = it }
  }
  fun finish(value: T): Boolean = synchronized(lock) {
    if (active !== value) false else { active = null; true }
  }
}

/** Progress is descriptive; existing CaptureGate alone decides whether a waiting shot can fire. */
internal fun cameraCaptureState(available: Boolean, stage: CaptureStage?, pending: Boolean): CameraCaptureState = when {
  !available -> CameraCaptureState.UNAVAILABLE
  !pending -> CameraCaptureState.READY
  stage == CaptureStage.SHOOTING -> CameraCaptureState.SHOOTING
  stage == CaptureStage.CAPTURED || stage == CaptureStage.FAILED -> CameraCaptureState.SAVING
  else -> CameraCaptureState.WAITING
}
