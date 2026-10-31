# 07 — Share perspective warping and curved page edges

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [05](05-share-image-geometry.md)

## Objective

Move the renderer's geometric image stage into the common engine.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt — targetCanvas, warp, clipping
- android/src/main/java/com/margelo/nitro/documentscanner/processing/PageEdges.kt
- android/src/main/cpp/PageEdgesRemap.cpp
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/DocumentRendererTest.kt

## Current behavior and invariants

4096-pixel source/output limits, no enlargement beyond available pixels, fixed quad corners, straight-warp coverage polygon, and curved-edge fallback behavior remain unchanged.

## Implementation steps

1. Implement portable render geometry and perspective/curved remap stages using the existing OpenCV version and algorithm order.
2. Keep Android decoding, filter invocation, encoding, output publication, and thumbnail registration in the platform orchestrator for this slice.
3. Replace Android PageEdges and warp math with the core stage; keep JNI as an owning conversion boundary. Rendering stays inside FastCores.run, so the core stage runs on the pinned thread and OpenCV workers.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Compare pre-filter pixels and geometry fixtures for flat, bent, ambiguous, and out-of-frame edges.
- Assert valid-content polygons derive from the straight warp, and unsupported bends remain straight.

## Validation commands

- Run shared build and ctest.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.DocumentRendererTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android geometric rendering uses C++ with equivalent dimensions, coverage, and pre-filter output.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share perspective and page-edge rendering

