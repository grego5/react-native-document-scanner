# 12 — Share live guide and adaptive-inference policy

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [06](06-share-model-preprocessing.md), [11](11-share-flow-and-motion.md)

## Objective

Use one state machine for guide acceptance, tracked retention, and inference pacing.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveQuadStabilizer.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDetectionState.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/AdaptiveDetectionPolicy.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDocumentDetector.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/LiveQuadTraceReplayTest.kt

## Current behavior and invariants

Valid model results publish immediately; score never gates acceptance; reliable flow carries the outline; model inference remains independent of autofocus.

## Implementation steps

1. Move guide validity/acceptance, held expiry, tracking retention, and adaptive wake decisions into the core.
2. Keep Android analysis scheduling, overlays, callback dispatch, and model-runtime calls in its adapter.
3. Preserve monotonically rising acceptance IDs across resets and invalidate old binding/model completions.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Replay acceptance, held expiry, invalid/missing detections, tracking loss, region loss, and shape changes.
- Assert ordinary movement alone does not wake adaptive inference and focus transitions do not reset confirmation.

## Validation commands

- Run shared build and ctest, including migrated guide/adaptive/replay assertions.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android delegates guide decisions to C++ and replay/state tests preserve visible behavior.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share live guide and adaptive detection policies

