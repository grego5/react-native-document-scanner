# 05 — Share source, crop, and coordinate geometry

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [02](02-introduce-portable-core.md)

## Objective

Use one C++ implementation for normalized source geometry and crop transformations.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/ImageGeometry.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/PhotoInferenceCrop.kt
- android/src/main/java/com/margelo/nitro/documentscanner/CropQuadEditing.kt
- android/src/test/java/com/margelo/nitro/documentscanner/CropQuadEditingTest.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/PhotoInferenceCropTest.kt

## Current behavior and invariants

All public quads are normalized upright TL/TR/BR/BL. Photo inference uses 10% seed margins expanded to source aspect; editing rejects crossed, concave, or collapsed quads.

## Implementation steps

1. Move orientation transforms, region mapping, quad validation, crop-edit clamping, and target geometry into the shared engine.
2. Replace Android math with JNI calls while retaining Android-owned gesture state and image resources.
3. Represent centered square framing as a region transform, distinct from resizing into the model tensor.
4. Move affected pure assertions into core tests atomically with production replacement; retain Android-only assertions in JVM tests.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Round-trip all eight EXIF orientations and portrait/landscape square crops.
- Verify crop-edge motion, rejected invalid drags, inference-region mapping, outside-image render quads, and normalized output polygons.

## Validation commands

- npm run check
- Run shared CMake build and ctest, including the migrated crop/orientation/inference-region assertions.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.SourceImageLoaderTest,com.margelo.nitro.documentscanner.CropEditorViewTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android uses shared geometry; equivalent fixture results and coordinate round trips pass.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share scanner source and crop geometry

## Implementation report — 2026-10-31

Moved source orientation, normalized/image conversion, region mapping, photo inference crop selection, crop-edit clamping/validation, render validation/target sizing and valid-content polygon geometry into `cpp/include/document_scanner/Geometry.hpp` and `cpp/src/Geometry.cpp`. The public boundary contains only C++ values. `NativeGeometry.kt` and its fbjni adapter marshal synchronous values; Android retains bitmap allocation/rotation, CameraX viewport negotiation, gesture state, rendering resources and file publication.

Image-edge transforms and pixel-center bitmap matrices are explicit separate conventions. Centered square framing is represented by a region transform, including fractional offsets for odd dimensions, independently of model resizing. Existing photo seed margins, edit rejection thresholds, render extrapolation limits and Point2f projection rounding are preserved. JNI translates argument errors explicitly and preserves `InvalidInputException` for public render validation; consumer rules retain the JNI class and reflected exception constructor.

All assertions from `CropQuadEditingTest.kt` and `PhotoInferenceCropTest.kt` moved into `cpp/tests/GeometryTest.cpp` with production replacement. Added core orientation/region round trips, square framing, render sizing/clipping and normalized polygon checks. Retained Android bitmap and viewport tests; added JNI marshalling/error checks and a real editor gesture sequence that keeps the last valid quad and publishes once on release.

Scope boundary: `DocCornerSession.decodeOutput` / `validNormalizedQuad` and their JVM assertions remain for Task 06's prediction interpretation migration. Photo crop selection preserves that acceptance predicate locally, without applying the editor's distinct minimum-area rule. Perspective warping and page-edge algorithms remain for Task 07. No model, public API or generated bindings changed.

Validation:

- `npm run check`: both typechecks and all 16 JS contracts passed.
- Windows x64 core: MSVC 14.51.36231, C++20, OpenCV 4.12.0; `cmake --build .local/build/core-tests --config Release` passed after initializing the x64 Visual Studio build environment. `ctest --test-dir .local/build/core-tests -C Release --output-on-failure` passed 2/2 with the verified OpenCV runtime directory on PATH.
- `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.NativeGeometryTest,com.margelo.nitro.documentscanner.processing.SourceImageLoaderTest,com.margelo.nitro.documentscanner.CropEditorViewTest,com.margelo.nitro.documentscanner.processing.DocumentRendererTest'`: build/install passed, all 17 tests passed on Samsung SM-G998U, Android 15, arm64-v8a. This includes all eight existing EXIF marker fixtures, sampled decoding, crop gestures, outside-image rendering and output polygons. No shutter was invoked.
- `npm run test:native`: passed; 120 tests reported across 21 suites, zero failures/errors, one optional trace replay skipped without trace input.
- `git diff --check`: passed.

The first JNI error-path run exposed fbjni's default `CppException` mapping; the final 17-test run passed after explicit translation. No iOS execution was performed for this task; cross-platform release validation remains pending.

