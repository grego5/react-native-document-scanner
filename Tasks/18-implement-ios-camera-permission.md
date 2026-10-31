# 18 — Implement iOS camera permission ownership

[Back to task index](../TASKS.md)

Status: Planned
Complexity: Medium
Dependencies: [17](17-add-ios-package-and-nitro-bindings.md)

## Objective

Expose process-wide camera authorization without requiring a mounted camera.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/CameraPermission.nitro.ts
- src/CameraPermission.ts
- android/src/main/java/com/margelo/nitro/documentscanner/CameraPermissions.kt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridCameraPermission.kt
- ios/HybridCameraPermission.swift — scaffold from Task 17

## Current behavior and invariants

Concurrent requests share one prompt, subscribers receive real status changes, and foreground return refreshes settings changes.

## Implementation steps

1. Implement AVFoundation status mapping: notDetermined→not-determined, authorized→granted, denied/restricted→blocked.
2. Serialize pending authorization requests and settle each exactly once; request UI only from a foreground application context.
3. Refresh authorization on foreground activation and notify registered listeners only on change; release observers/listeners safely.
4. Keep errors in ScannerFailure payloads and display copy in React.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Use injected authorization/notification boundaries for initial states, shared prompts, denial, setting changes, listener removal, and unavailable presentation.
- Verify a permission change can resume an attached camera once session implementation exists.

## Validation commands

- npm run check
- Run focused iOS XCTest permission tests on the verified Mac host.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Permission API behaves correctly without any camera view and has no observer/promise leak.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement camera permission service

