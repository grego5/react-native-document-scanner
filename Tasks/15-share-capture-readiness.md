# 15 — Share ordered capture stability and gate decisions

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [11](11-share-flow-and-motion.md), [13](13-share-focus-policy.md), [14](14-share-torch-policy.md)

## Objective

Make capture waiting/deadlines/stability portable while retaining truthful platform evidence.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/CaptureGate.kt — CaptureState, CaptureGate, CaptureStability
- android/src/main/java/com/margelo/nitro/documentscanner/processing/FrameRecord.kt — FrameHistory
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — frame cursor and gate evaluation
- android/src/test/java/com/margelo/nitro/documentscanner/CaptureGateTest.kt
- android/src/test/java/com/margelo/nitro/documentscanner/CaptureStabilityTest.kt

## Current behavior and invariants

Four qualifying frames, ordered consumption, 32-record history, focus-generation resets, no polling/settling delay, and existing focus/steady deadlines remain unchanged.

## Implementation steps

1. Move stability geometry, frame-history sequencing, deadline state, and wait/shoot/reject decisions into the core. Wait keeps returning its deadline; the adapter schedules the reevaluation and cancels it by gate generation (Task 02's timing convention).
2. FrameHistory is written on the analysis thread and read on the main thread, synchronized today. The C++ history must preserve that safety explicitly, by synchronization or by transferring ownership of immutable records; document which, and keep each record read once in order.
3. Represent platform capture readiness as evidence supplied by adapters. Keep CameraX's exact ZSL AF/AE/AWB eligibility in Android.
4. Restart on missed history or broken frame chains; preserve unknown-motion and requireFocus=false semantics.
5. Switch Android calls and migrate pure assertions atomically; preserve one-shot admission and callback ordering.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Cover unknown smear, low-texture gyro fallback, document drift, focus restart, ring overflow, torch pending, zero timeout, and late deadline events.
- Exercise concurrent analysis-thread writes and main-thread reads of the history, including a reader falling more than 32 records behind.
- Assert exactly one Shoot and no focus command originates from the shutter.

## Validation commands

- Run shared build and ctest, including migrated CaptureGateTest, CaptureStabilityTest, and FrameHistoryTest assertions.
- npm run check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android capture policy delegates to C++ and all baseline decision sequences remain equivalent.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share ordered capture readiness and stability

