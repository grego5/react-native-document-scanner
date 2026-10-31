# 25 — Implement the bounded iOS camera session and preview

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [17](17-add-ios-package-and-nitro-bindings.md), [18](18-implement-ios-camera-permission.md), [19](19-implement-ios-source-store.md)

## Objective

Stream a lifecycle-controlled rear-camera preview with a strict still-resolution ceiling.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentScannerCamera.nitro.ts — props and CameraState
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — bind/unbind and viewport
- android/src/main/java/com/margelo/nitro/documentscanner/CaptureResolutionPolicy.kt
- example/app.config.ts

## Current behavior and invariants

mode=off stops camera and sensors while keeping the view mounted; non-off modes require permission, attachment, and foreground visibility.

## Implementation steps

1. Implement ios/HybridDocumentScannerCameraView.swift backed by one session owner/serial queue and main-thread preview layer.
2. Choose the rear physical wide-angle camera. Enumerate supported formats and photo dimensions; prefer largest valid 4:3 still size, then largest valid other aspect, under both 4096-long-edge and 12,582,912-total-pixel bounds.
3. Configure output and per-shot maxPhotoDimensions using a supported dimension before starting the session; exclude 24/48 MP modes. If no bounded configuration works, remain suspended and reject capture as camera/unavailable.
4. Add preview and bounded video-data output with analysisWidth default 640; discard late frames and permit no unbounded frame backlog.
5. Implement framing/orientation transforms and virtual square presentation independently of model tensor resizing.
6. Implement basic start/ready/suspended states and deterministic attach/detach/foreground shutdown from the beginning; later interruption tests extend this lifecycle rather than defer resource correctness.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Unit-test dimension selection with advertised 12/24/48 MP combinations, portrait axes, no valid size, and smaller delivered photos.
- Run device preview without triggering capture; verify permissions, mode changes, rotations, and no analysis backlog.

## Validation commands

- npm run check
- Run iOS camera-configuration XCTest tests and a physical-device preview check; simulator cannot validate camera delivery.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

A bounded preview/session runs with consistent framing and known supported still dimensions; shutter functionality remains explicitly incomplete.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add bounded camera session and preview

