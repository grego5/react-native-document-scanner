# 26 — Connect iOS frame analysis to shared live guidance

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [11](11-share-flow-and-motion.md), [12](12-share-live-guide-policy.md), [20](20-implement-ios-inference.md), [25](25-implement-ios-camera-session.md)

## Objective

Run iOS native detection/tracking and publish the same guide states.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDocumentDetector.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDetectionState.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LatestFrameCapture.kt
- src/DocumentScannerCamera.nitro.ts — guide, adaptiveDetection, document callbacks

## Current behavior and invariants

Live pixels stay native; guidance never captures; valid inference publishes immediately; flow retains the guide while adaptive inference pauses.

## Implementation steps

1. Process owned/retained CVPixelBuffer frames on a serial analysis context using shared transforms, preprocessing, flow, and guide policy.
2. Carry frame sequence/timestamp, geometry, model/session generation, and truthful available metadata into immutable native snapshots.
3. Keep only bounded latest-frame and shutter-thumbnail state; do not retain every analysis buffer.
4. Preload/warm the shared interpreter during session start; implement guide enable/disable without rebinding, adaptive pacing, overlay easing/color, and current-state callbacks. Guide acceptance and flow evidence come from shared C++; adaptive pacing is a Swift port of Android's `AdaptiveDetectionPolicy` and must pass `tests/fixtures/parity/adaptive.tsv`.
5. Suppress stale model/binding completions and preserve guide IDs across resets.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Replay portable guide fixtures through the iOS adapter; test padded BGRA, orientation, square ROI, guide toggles, model changes, and tracking loss.
- Use physical scenes for motion/occlusion/recovery; do not claim pixel-free replay proves optical tracking quality.

## Validation commands

- Run shared guide/flow tests and focused iOS frame-adapter tests.
- Perform physical-device preview/guide checks without automated shutter actions.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

iOS shows matching guide-state behavior and adaptive tracking with bounded native frame ownership.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): connect shared live detection and tracking

