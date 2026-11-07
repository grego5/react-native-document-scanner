# 30 — Implement native iOS capture feedback and retained preview

[Back to task index](../TASKS.md)

Status: Implemented (native validation pending)
Complexity: Medium
Dependencies: [28](28-implement-ios-still-capture.md), [29](29-implement-ios-capture-gating.md)

## Objective

Match capture stages and frozen-preview handoff without JS frame processing.

## Non-goals

Do not duplicate Task 29 gate/control state or Task 28 still/frame publication. Refactoring existing feedback integration to consume the authoritative request state is in scope; unrelated optimization and SwiftUI migration are not. Do not change model/thresholds or hand-edit generated sources.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/CaptureFeedbackTimeline.kt
- android/src/main/java/com/margelo/nitro/documentscanner/CaptureFeedbackView.kt
- src/DocumentScannerCamera.nitro.ts — CaptureStage, captureFeedback, CameraCaptureState
- android/src/test/java/com/margelo/nitro/documentscanner/CaptureFeedbackTimelineTest.kt
- example/hooks/useScanReview.ts
- Task 21's session-owned retained frame and native page-read contract

## Current behavior and invariants

waiting pulses, shooting progresses, captured means movement is safe, and a later save failure can report failed. A successful shot hands one bounded retained frame to the scan session; native image bindings seed from it while decoding, without a React readiness handoff.

## Implementation steps

1. Implement the Swift feedback timeline with UIKit drawing and platform haptics, preserving the public timeline behavior. Consume Task 29's authoritative decisions/stages; feedback must not own retry counters, readiness flags, timers that trigger capture, or a second busy state.
2. Publish captured after the AVFoundation exposure-complete event, never merely willCapture. Retrying or waiting for a post-focus frame remains waiting; StartFocusScan is an internal decision, not a new public stage. Preserve later save failure reporting and initial-state callback resend versus stage-event non-resend semantics.
3. Reuse Task 28's bounded shutter-frame/outline snapshot and session publication. Preserve its orientation/framing checks; do not request or decode a second original for feedback. Two separate guarantees apply. Review: the shutter sample is best-effort, and the writer may proceed without one. When the session retains a sample, the native viewer opens with it and replaces it with the decoded thumbnail. The session releases it when that thumbnail installs, a newer capture's sample replaces it, the page is removed, or the session closes. Displayed views retain their own references, and a viewer that opens after the release starts from the thumbnail. Camera: the freeze lasts for the shot; a camera still active at settlement resumes live video, and one already inactive keeps the freeze until the viewer installs the thumbnail or the camera stops. Uninterrupted coverage from shutter to review therefore holds only when the host applies `active={false}` before the shot settles; the camera cannot know at settlement whether review will open.
4. Detach, stop, session replacement and disposal invalidate obsolete view feedback by request/run identity without releasing a physical shot's resources early. Feedback disabled suppresses drawing/haptics without changing gate behavior or stage delivery. Do not introduce preview-release props or JS handoff state.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Verify waiting through scans/retries and settled-frame allowance, ordered shooting/captured/failed stages, no stages for pre-admission rejection, and captured→failed on write error.
- Cover feedback disabled, mode=off, detach/disposal, session replacement, late old-request events, retained-frame release after thumbnail decode/removal/close, and orientation changes around acceptance.
- Check the viewer uses the retained sample, when one exists, until ImageViewer installs the current image, an active camera resumes live video at settlement, and an inactive one keeps its freeze until that install; no duplicate capture/frame owner or release prop is introduced.

## Validation commands

- Run hardware-free timeline tests on the Mac VM where the target supports host execution; compile package/test products there. Run UIKit/retention/binding integration suites on the verified GitHub simulator route, including affected gate, camera and viewer regressions.
- Validate haptics, exposure-stage timing and preview-to-review handoff with human-triggered physical-device captures. Report simulator, build and device results separately and keep missing physical evidence pending.

## Completion criteria

Feedback stages and retained preview match the observable Android workflow.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native capture feedback and preview retention

## Implementation and validation

- Camera stages and lifecycle are wired in `HybridDocumentScannerCameraView.swift`. `CaptureFeedbackTimeline.swift`, `CaptureFeedbackOverlay.swift`, `LiveGuideOverlay.swift` and `CameraPreviewView.swift` handle UIKit feedback and the frozen image.
- `StillCapture.swift` shares the bounded shutter sample. `ScanSessionState.swift` owns the recent preview and `ImageViewerLoader.swift` seeds and releases it. The iOS camera reference documents the handoff.
- Added timeline and viewer tests and extended camera and still tests for stage order, stale events, save failure and preview retention. The 45 JavaScript contracts and `git diff --check` passed.
- The Windows Swift syntax check could not start under the sandbox, and the elevated retry was declined. Mac build and host timeline tests, simulator integration tests, and human-triggered device checks are pending. The Mac build request encountered an active checkout before starting. Further Mac VM testing is deferred at the user's direction.

## Review follow-up — 2026-11-07

- A camera that stays active clears its freeze when the shot settles, as Android does, so a host without an `ImageViewer` sees live video when the camera is ready again. The session still retains the shutter frame for review; an inactive camera keeps the freeze until the viewer installs the thumbnail or the camera stops. The example opens review after `takePhoto()` resolves, so settlement can clear the freeze before React applies `active={false}`: review still uses the retained sample when one exists, but coverage from camera to review is not continuous there.
- An interruption or runtime failure of the camera run emits `failed` once for the admitted request, matching Android. Stop, session replacement and unmount still only invalidate its feedback, and the invalidated shot's late callbacks still report nothing.
- No tests were added or run for these changes, at the user's direction.

