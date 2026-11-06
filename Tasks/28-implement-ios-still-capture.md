# 28 — Implement bounded pixel/JPEG capture and source publication

[Back to task index](../TASKS.md)

Status: Implemented (physical-device checks pending)
Complexity: High
Dependencies: [04](04-verify-ios-feasibility.md), [19](19-implement-ios-source-store.md), [21](21-implement-ios-processor.md), [25](25-implement-ios-camera-session.md), [26](26-implement-ios-live-guidance.md), [27](27-implement-ios-camera-controls.md)

## Objective

Admit one captured page to the bound native scan session and resolve takePhotoResult with that page ID after durable source admission. Source files, retained pixels, and the matching shutter frame stay native.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentScannerCamera.nitro.ts — session, renderOptions, TakePhotoOptions, takePhotoResult
- src/ScanSession.nitro.ts and Task 21's internal source-admission/producer handoff
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — saveShot and writeLatestFrameCapture
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LatestFrameCapture.kt
- ios/ source store from Task 19

## Current behavior and invariants

One accepted takePhoto starts one physical shot. Admission freezes the bound session, camera render defaults, model selection, and capture-gate options. Durable source/page publication precedes page-ID resolution; readiness remains observable through session metadata. Crop coordinates match the visible region and no successful result references an incomplete file. Session close/replacement or detach invalidates publication immediately; physical capture/writers retain their busy slot and producer pins until settlement.

## Implementation steps

1. Implement per-shot delegate ownership with a strong lifetime through final completion and exactly-once settlement.
2. Use native JPEG capture as the provisional low-friction default. Honor explicit jpegQuality with native AVVideoQualityKey; otherwise follow the existing capture-mode defaults (quality 1.0, latency 0.95). Use latency when ZSL is unsupported. Per user direction (2026-10-31), defer the TIFF/BGRA alternative and format-cost measurements until a working sample runs on a physical device.
3. Apply the centered square ROI before source persistence and expensive downstream processing. Persist with provisional JPEG backing, preserving requested JPEG quality after any required crop.
4. Return correct encoded dimensions/EXIF metadata; normalize orientation after pixel crop and remove or regenerate stale embedded thumbnails.
5. Publish the durable file through Task 19, map liveCandidate to final source coordinates, and transfer source/producer ownership plus the retained shutter frame through Task 21's tested admission boundary. Insert the page and schedule analysis through Task 20 followed by session rendering, then return its ID; never return a source URI or introduce a second camera-owned page store.
6. Defer format/framing changes during an active shot. Reject each stale/failed capture once, suppress later previews/stages, and retire temporary/published-but-unadmitted sources after physical writers drain. An old session's close must not cancel a replacement session's capture.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Test explicit/omitted quality, uncompressed fallback, square/non-square, orientation, smaller delivered dimensions, and both pixel limits.
- Inject save/delegate failures and disposal; assert one shot, one result, no leaked delegates/files, and resident/reopened source equivalence.

## Validation commands

- Run iOS capture-delegate/source-store tests with injected data.
- Run physical captures only with a person's shutter tap; report capture, file, and image-validation evidence separately.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

takePhoto admits a page through the existing native session and returns its ID, with correct JPEG backing under the provisional default, the preconfigured capture cap, and preserved framing/producer/pixel lifetimes.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement bounded native-pixel still capture

## Implementation record — 2026-11-05

Changed files: `ios/StillCapture.swift` (new: still settings, format choice, geometry, processor, ticket, shot), `ios/CameraSession.swift` (`capture`, self-retaining photo delegate, per-shot settings, quality prioritization ceiling and zero shutter lag in configuration), `ios/HybridDocumentScannerCameraView.swift` (shot acceptance, busy slot, capture state, deferred restart and square framing, invalidation on session replacement/close/unmount), `ios/Tests/StillCaptureTests.swift` (new), `ios/Tests/CameraSessionTests.swift`, `ios/README.md`.

Decisions and limits:

- The quality gate (Task 29) and capture stages/frozen preview (Task 30) are not here: an accepted shot fires at once and `onCaptureStage` stays silent.
- The JPEG still is decoded once and re-encoded by the source store at the same quality, so it is encoded twice. Kept as the provisional default until physical-device measurements.
- `zsl` maps to balanced prioritization with the output's zero-shutter-lag buffer enabled; whether AVFoundation actually uses the buffer for it is unverified on a device.
- The model selection is frozen on main at acceptance; this waits briefly for the shared inference queue.
- The shutter frame is not waited for: a frame not yet copied when the still is processed is omitted.
- The view relies on AVFoundation's guarantee of a final delegate callback per request to free its busy slot.

Executed checks:

- `npm run check`: passed.
- Mac VM build-for-testing (Xcode 26.6, 17F113): succeeded after two Swift 6.3 sending fixes (`CornerModelLoader`, `AVCameraSession.perform`).
- Simulator suite, built on the Mac VM and run by `ios-validation.yml` on `macos-26-intel` with the same Xcode build: 165 executed, 1 skipped, 0 failures.
- Review fixes (any camera stop invalidates the shot; focus/torch controls follow the shot; only a guide in the current framing seeds it): `CameraSessionTests` and `CameraControlsTests`, split run, 36 executed, 0 failures.
- Shutter frame straightened only when its orientation matches the frozen guide (a half turn after acceptance keeps the size): `StillCaptureTests` and `CameraSessionTests`, split run, 38 executed, 0 failures.
- Not run: physical-device capture (needs a person's shutter tap), ZSL behaviour, real photo-output timing.
