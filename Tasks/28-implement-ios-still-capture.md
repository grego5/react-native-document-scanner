# 28 — Implement bounded pixel/JPEG capture and source publication

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [04](04-verify-ios-feasibility.md), [19](19-implement-ios-source-store.md), [25](25-implement-ios-camera-session.md), [26](26-implement-ios-live-guidance.md), [27](27-implement-ios-camera-controls.md)

## Objective

Return a durable captured source and matching shutter thumbnail from one bounded photo request.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentScannerCamera.nitro.ts — CapturedPhoto, takePhotoResult
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — saveShot and writeLatestFrameCapture
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LatestFrameCapture.kt
- ios/ source store from Task 19

## Current behavior and invariants

One accepted takePhoto starts one physical shot. Source publication precedes resolution, crop coordinates match the visible region, and no successful result references an incomplete file.

## Implementation steps

1. Implement per-shot delegate ownership with a strong lifetime through final completion and exactly-once settlement.
2. Use native JPEG capture as the provisional low-friction default. Honor explicit jpegQuality with native AVVideoQualityKey; otherwise follow the existing capture-mode defaults (quality 1.0, latency 0.95). Use latency when ZSL is unsupported. Per user direction (2026-10-31), defer the TIFF/BGRA alternative and format-cost measurements until a working sample runs on a physical device.
3. Apply the centered square ROI before source persistence and expensive downstream processing. Persist with provisional JPEG backing, preserving requested JPEG quality after any required crop.
4. Return correct encoded dimensions/EXIF metadata; normalize orientation after pixel crop and remove or regenerate stale embedded thumbnails.
5. Publish the durable file, register a native pixel lease for immediate consumers, save the shutter analysis thumbnail, and map liveCandidate to final source coordinates.
6. Defer format/framing changes during an active shot. Clean temporary/published-but-unreturned files on failure/disposal.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Test explicit/omitted quality, uncompressed fallback, square/non-square, orientation, smaller delivered dimensions, and both pixel limits.
- Inject save/delegate failures and disposal; assert one shot, one result, no leaked delegates/files, and resident/reopened source equivalence.

## Validation commands

- Run iOS capture-delegate/source-store tests with injected data.
- Run physical captures only with a person's shutter tap; report capture, file, and image-validation evidence separately.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

takePhoto produces correct JPEG sources under the provisional default within the preconfigured capture cap and preserves framing/pixel lifetimes.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement bounded native-pixel still capture

