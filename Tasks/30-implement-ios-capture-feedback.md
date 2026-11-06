# 30 — Implement native iOS capture feedback and retained preview

[Back to task index](../TASKS.md)

Status: Planned
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
3. Reuse Task 28's bounded shutter-frame/outline snapshot and session publication. Preserve its orientation/framing checks; do not request or decode a second original for feedback. Keep the frozen preview until the current page image installs through the existing native binding. A decoded thumbnail releases session retention; removal/close clears it, while displayed views retain their own references.
4. Detach, stop, session replacement and disposal invalidate obsolete view feedback by request/run identity without releasing a physical shot's resources early. Feedback disabled suppresses drawing/haptics without changing gate behavior or stage delivery. Do not introduce preview-release props or JS handoff state.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Verify waiting through scans/retries and settled-frame allowance, ordered shooting/captured/failed stages, no stages for pre-admission rejection, and captured→failed on write error.
- Cover feedback disabled, mode=off, detach/disposal, session replacement, late old-request events, retained-frame release after thumbnail decode/removal/close, and orientation changes around acceptance.
- Check the preview-to-review handoff stays covered until ImageViewer installs the current image; no duplicate capture/frame owner or release prop is introduced.

## Validation commands

- Run hardware-free timeline tests on the Mac VM where the target supports host execution; compile package/test products there. Run UIKit/retention/binding integration suites on the verified GitHub simulator route, including affected gate, camera and viewer regressions.
- Validate haptics, exposure-stage timing and preview-to-review handoff with human-triggered physical-device captures. Report simulator, build and device results separately and keep missing physical evidence pending.

## Completion criteria

Feedback stages and retained preview match the observable Android workflow.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native capture feedback and preview retention

