# 22 — Implement the iOS viewer and shared thumbnail loader

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [19](19-implement-ios-source-store.md), [21](21-implement-ios-processor.md)

## Objective

Show sources and renders with native zoom gestures and bounded decoding.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/ImageViewer.nitro.ts
- src/ImageViewer.tsx
- android/src/main/java/com/margelo/nitro/documentscanner/ImageViewerView.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ImageLoader.kt
- android/src/main/java/com/margelo/nitro/documentscanner/PageThumbnails.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ImageZoom.kt

## Current behavior and invariants

Thumbnail first; full image only past 1.1× zoom; zoom limit 5×; double tap to 2.5× or fit; previous image remains until replacement is installed.

## Implementation steps

1. Implement UIView/UIScrollView-based ImageViewer with contain fit, clamped pan, native pinch/double tap, isZoomed, and resetZoom.
2. Reuse source/thumbnail services; decode full images at most 4096 pixels and release full buffers on detach/background or zoom reset.
3. Use URI/load generations so obsolete work never replaces a newer image; onLoad fires only after installing the current URI's thumbnail.
4. Keep callbacks through Nitro and UI state on main; do not introduce JS gesture dependencies.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Assert zoom/pan bounds, zoom callbacks, replacement continuity, stale load discard, retry on visibility, and full-image release.
- Verify TIFF originals and JPEG/PNG renders load through the same view.

## Validation commands

- npm run check
- Run iOS viewer XCTest/UI integration tests on simulator.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Viewer methods, callbacks, image replacement, and resource release match the public contract.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native scanner image viewer

