# 20 — Implement shared iOS inference and photo analysis

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [06](06-share-model-preprocessing.md), [17](17-add-ios-package-and-nitro-bindings.md), [19](19-implement-ios-source-store.md)

## Objective

Run the same corner model and photo-region contract on iOS.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerInference.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocCornerSession.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerModels.kt
- src/DocumentScannerProcessor.nitro.ts — analyzePhotoResult
- src/DocumentScannerCamera.nitro.ts — cornerNetModel

## Current behavior and invariants

One serialized CPU interpreter serves live and photo callers; invalid replacement keeps the previous model; photo analysis performs one fresh inference on its selected region.

## Implementation steps

1. Add an iOS inference worker owning interpreter/model bytes/tensor workspace, with native leases and immutable model selection generation.
2. Use shared preprocessing and decoding; validate exact tensor count/types/shapes before installing a custom model and perform blank-input warmup.
3. Acquire source pixels from Task 19, apply shared seeded crop geometry, run inference once, and map results to full upright source coordinates.
4. Serialize replacement validation/publication on the inference owner, reset live generations on success, and preserve the old selection on failure.
5. Release buffers/runtime only after in-flight work and the last owner lease finish; never borrow CVPixelBuffer memory beyond completion.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Run model tensor parity ≤2e-5 and existing image fixture limits; compare resident-source and reloaded-source analysis.
- Test two owners, failed replacement, rectangular model dimensions, stale selection publication, warmup failure, and disposal.

## Validation commands

- npm run check
- Run focused iOS inference XCTest fixtures and shared core tests; report missing private fixtures explicitly.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Photo analysis and model ownership match Android contracts using the same model bytes and algorithms.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement shared corner inference and analysis

