# 27 — Connect iOS focus, torch, and exposure controls

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [13](13-share-focus-policy.md), [14](14-share-torch-policy.md), [26](26-implement-ios-live-guidance.md)

## Objective

Execute shared control decisions through AVFoundation with truthful completion evidence.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/FocusController.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentFocusPolicy.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/TorchPolicy.kt
- android/src/main/java/com/margelo/nitro/documentscanner/CameraExposurePolicy.kt
- src/DocumentScannerCamera.nitro.ts — focus, torch, exposure and frame-rate contracts

## Current behavior and invariants

Document detection continues during focus; capture starts no scan; taps retain priority; torch changes cannot interrupt an exposure.

## Implementation steps

1. Adapt document/tap scan commands to supported AVFoundation focus points/modes, KVO transitions, lock-completion timestamps, and policy generations.
2. Use continuous autofocus until a learned lock exists; park at the learned lens position when supported. Never convert 30 cm to an invented lensPosition or equate isAdjustingFocus=false with Camera2 success metadata.
3. Implement exposure bias and supported frame-duration selection; use exposure×ISO observations for latched auto torch.
4. Normalize device and buffer clock domains before comparisons; mark unavailable metadata unknown and estimated observations explicitly.
5. Serialize hardware commands on the session owner and feed completion events back into policy snapshots; unregister observations on unbind.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Use injected device events for tap expiry, scan completion/timeout, stale generations, fixed-focus/no-torch hardware, exposure clamping, and deferred commands.
- Verify focus can reacquire while a capture is waiting and iOS callbacks do not assert unsupported focus certainty.

## Validation commands

- Run shared focus/torch policy tests and focused iOS device-adapter tests.
- Run human-observed focus/torch scenarios on a physical iPhone.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

iOS controls follow shared policy with documented platform evidence and no observer or timestamp-lifetime errors.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement scanner focus torch and exposure controls

