# 30 — Implement native iOS capture feedback and retained preview

[Back to task index](../TASKS.md)

Status: Planned
Complexity: Medium
Dependencies: [28](28-implement-ios-still-capture.md), [29](29-implement-ios-capture-gating.md)

## Objective

Match capture stages and frozen-preview handoff without JS frame processing.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

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

1. Implement a Swift feedback timeline preserving CaptureFeedbackTimeline's observable behavior, with UIKit drawing and platform haptics; keep colors and outline snapshots native.
2. Publish captured after the AVFoundation exposure-complete event, never merely willCapture; preserve later failure reporting.
3. Freeze the shutter frame/outline and hand it to the admitted session page on success. A decoded thumbnail releases the session reference; page removal/close clears it, while displayed views retain their own image references. Failure/detach invalidates obsolete camera feedback. Do not add retain/release-preview props or JS handoff state.
4. Preserve state callback initial resend and stage-event non-resend semantics through stable Nitro callbacks.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Verify stage order, no stages for pre-admission rejection, captured→failed on write error, retention through mode=off, release prop, and disposal.
- Check preview-to-review handoff remains covered until ImageViewer's current image installs.

## Validation commands

- Run iOS feedback-timeline and preview-handoff integration tests.
- Validate native feedback and retained-preview handoff with human-triggered device captures.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Feedback stages and retained preview match the observable Android workflow.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native capture feedback and preview retention

