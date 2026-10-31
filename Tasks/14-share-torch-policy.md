# 14 — Share automatic torch policy

[Back to task index](../TASKS.md)

Status: Planned
Complexity: Medium
Dependencies: [02](02-introduce-portable-core.md)

## Objective

Use one automatic-light decision policy with platform-owned torch application.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/TorchPolicy.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/TorchPolicyTest.kt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — torch changes and pending capture

## Current behavior and invariants

Auto uses exposure×ISO, waits before enabling, and stays lit until camera stop/mode change. Torch cannot change during a shot.

## Implementation steps

1. Move darkness accumulation and latched auto decisions into a portable state machine that takes observation times explicitly (Task 02's timing convention).
2. Keep capability checks, hardware commands, and completion evidence in each platform adapter.
3. Preserve pending-change readiness and defer commands while a capture is in flight.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Assert dark delay, interrupted dark runs, latch/reset behavior, unsupported hardware, and mode changes during capture.
- Do not import Camera2 flash-required metadata into the shared policy.

## Validation commands

- Run shared build and ctest, including migrated TorchPolicyTest assertions.
- npm run check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android torch decisions use the shared state machine and existing timing behavior passes.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share automatic torch policy

