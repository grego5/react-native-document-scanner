# 29 — Implement native iOS capture readiness

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [27](27-implement-ios-camera-controls.md), [28](28-implement-ios-still-capture.md)

## Objective

Implement Swift capture gating with Android behavior parity, consuming shared focus snapshots and motion evidence. Capture gate state, frame history and scheduling remain platform-native.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/CaptureGate.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/FrameRecord.kt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — ordered gate evaluation
- src/DocumentScannerCamera.nitro.ts — qualityGate and QualityGateConfig

## Current behavior and invariants

Four ordered qualifying frames, unknown motion waits, focus-generation restart, event/deadline-driven reevaluation, one in-flight request, and no shutter-owned focus scan.

## Implementation steps

1. Feed native frame snapshots, focus generations, torch completion, and supported AVFoundation readiness into the Swift gate.
2. Keep Android-specific AF/AE/AWB ZSL eligibility out of the iOS adapter; use actual iOS adjustment/readiness observations and document their weaker evidence.
3. For device-observed exposure values record their estimated status, reset readiness across settings transitions, and keep unavailable motion unknown; never invent matching per-frame metadata.
4. Apply focusTimeout/requireFocus and motion timeout semantics, disabling gate checks only when the public configuration requests it. Pass times in the clock domain Task 27 normalized; schedule the reevaluation each Wait returns on the session owner and cancel it by gate generation.
5. Reject unavailable/duplicate requests before waiting starts; allow only the gate's first Shoot to call Task 28 capture.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Test moving page, low texture, unknown exposure/motion, frame gaps, changed focus generations, torch pending, focus timeout, requireFocus=false, and qualityGate=false.
- Assert one ordered terminal decision and no polling timer; deadline scheduling is allowed only at the next gate deadline.

## Validation commands

- Run Android capture gate/stability/history tests and equivalent Swift decision-sequence tests. Port Android test scenarios as shared value fixtures where practical.
- Validate focus/motion waits with human-triggered physical captures.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

iOS capture waits and typed failures follow Android gate semantics without fabricating Android metadata.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement native capture quality gating

