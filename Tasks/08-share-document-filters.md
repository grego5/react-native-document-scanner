# 08 — Share shadows, paper normalization, and ink filters

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [07](07-share-page-warping.md)

## Objective

Run all document filter algorithms through the shared render engine.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/ShadowRemoval.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentFilters.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/InkDetail.kt
- android/src/main/cpp/FieldKernels.cpp
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/DocumentFiltersTest.kt and ShadowRemovalTest.kt

## Current behavior and invariants

Shadow removal precedes filtering; one paper estimate preserves channel ratios; blackWhite remains continuous; ink enhancement preserves broad midtone regions.

## Implementation steps

1. Port orchestration and parameter maps into C++ using existing kernels and arithmetic order.
2. Replace Kotlin image algorithms with direct engine calls; keep native files and bitmap lifetimes in the renderer adapter.
3. Provide the same filtered-image stage to rendering and editor previews, preserving their different source/decode sizes. Renders and filter previews keep their FastCores.run scope.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Compare pre-encoding outputs within one intensity level for identical pixels.
- Test color patches, broad gray regions, faint ink, shaded paper, empty pages, and filter=none with independent shadow settings.

## Validation commands

- Run shared build and ctest.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.DocumentFiltersTest,com.margelo.nitro.documentscanner.processing.ShadowRemovalTest,com.margelo.nitro.documentscanner.processing.FieldKernelsTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android filters call the shared engine; no production Kotlin algorithm copy remains.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share document shadow and filter processing

