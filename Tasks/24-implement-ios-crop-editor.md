# 24 — Implement the iOS crop editor

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [05](05-share-image-geometry.md), [08](08-share-document-filters.md), [19](19-implement-ios-source-store.md)

## Objective

Edit normalized upright quads with native handles and filter previews.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentCropEditor.nitro.ts
- src/DocumentCropEditor.tsx
- android/src/main/java/com/margelo/nitro/documentscanner/CropEditorView.kt
- android/src/main/java/com/margelo/nitro/documentscanner/CropQuadEditing.kt
- android/src/androidTest/java/com/margelo/nitro/documentscanner/CropEditorViewTest.kt

## Current behavior and invariants

The editor owns local edits until a different quad prop arrives; only changed drag completion publishes; invalid quads are rejected, not silently repaired.

## Implementation steps

1. Implement native handle/edge dragging through shared crop geometry, constant-screen-size handles, magnifier, pinch/pan up to 5×, and double-tap fit.
2. Load TIFF/JPEG originals through Task 19, with normalized upright coordinates independent of display transform.
3. Compute full-photo filter previews off main at the existing 2400-pixel cap; retain current image and discard outdated filter/photo results.
4. Keep onLoad dimensions and error behavior consistent; release images and invalidate work on detach/disposal.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Test corner and edge clamps, invalid drags, prop replacement, magnifier geometry, zoomed edits, orientation, stale previews, and failed loads.
- Assert filter changes never modify the saved original or implicitly commit crop edits.

## Validation commands

- npm run check
- Run shared geometry tests and iOS crop-editor integration tests.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Crop interactions, source formats, previews, and callback timing match the contract.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native document crop editor

