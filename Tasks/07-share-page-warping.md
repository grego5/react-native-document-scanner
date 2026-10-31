# 07 — Share perspective warping and curved page edges

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [05](05-share-image-geometry.md)

## Objective

Move the renderer's geometric image stage into the common engine.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt — platform orchestration
- cpp/include/document_scanner/RenderGeometry.hpp and cpp/src/RenderGeometry.cpp — target canvas, perspective warp and straight coverage
- cpp/src/PageEdges.cpp — migrated detection/fitting and source-boundary checks
- cpp/src/PageEdgesRemap.cpp — existing private curved remap kernel
- android/src/main/cpp/NativeRenderGeometry.cpp — owning JNI conversion
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


## Implementation report

- Added `RenderGeometry.hpp` / `RenderGeometry.cpp`: the portable pre-filter stage borrows a full upright RGBA8 pixel lease (padded rows allowed), validates the submitted normalized quad, chooses the existing target dimensions, constructs/inverts the Point2f homography, performs the perspective or curved warp, and returns independent immutable pixels with the normalized straight-warp coverage polygon. Source/output limits, original-scale sizing, paper limits, interpolation, border values, coverage mask and rejection behavior are preserved.
- Moved Android `PageEdges.kt` detection/fitting/source-boundary logic to private `cpp/src/PageEdges.cpp` / `internal/PageEdges.hpp`, preserving the 800-pixel detection canvas, 6% band, 31 stations, strongest-step tie handling, triple search and least-squares order, thresholds, fixed corners and float32 offset arrays. The existing strip/parallel remap kernel is unchanged. Unsupported sides stay straight; any outside submitted corner disables all bending.
- Replaced the old `PageEdgesRemap.*` JNI wrapper with `NativeRenderGeometry.*` and a small Kotlin value adapter. JNI retains a source Mat header as the lease owner for its synchronous call and copies the independently owned result into Android's mutable filter input. No Mat, JNI address or platform handle enters the portable headers. Added a read-only span accessor to the validated PixelView for private library adapters.
- `DocumentRenderer.kt` now delegates its geometric stage while retaining decode, filter/shadow order, encoding, thumbnail registration and atomic file publication. The processor's existing `FastCores.run { DocumentRenderer.render(...) }` scope remains unchanged, using the same Android OpenCV runtime. No public/Nitro API or generated binding changed.
- Captured five public synthetic pre-filter pixel/geometry goldens from the original Android stage before replacement (flat, bent, a competing middle rule, outside corner, and scaled output). `GeometricRenderTest` checks exact SHA-256, dimensions, bend decisions, polygons and source/error ownership. `RenderGeometryTest` adds portable padded-source identity/ownership, bent-grid/corner/border behavior, straight coverage, outside/unsupported fallback and invalid/no-overlap/source-boundary assertions. The existing nine renderer instrumentation tests remain intact; no JVM assertions were removed.
- Updated CMake, native registration/keep rules, architecture/core ownership documentation and fixture inventory. iOS integration/execution remains outside this task; no performance or release-parity claim is made. The JNI output copy is intentional ownership conversion, not a performance optimization. C++ and Kotlin changes used the implementation stage only.

### Validation

- Baseline command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.GeometricRenderTest'` against the original stage: APK built/installed and the baseline recorder passed (1 test, 35.107 seconds). Only generated synthetic pixels were used; no camera captures or reference-photo transfers occurred.
- `cmd /c .local\task05\build-core.cmd` invokes the installed Visual Studio developer environment and `cmake --build .local/build/core-tests --config Release`: Windows x64 MSVC 14.51.36231 / C++20 / OpenCV 4.12 build passed. Initial validation caught a temporary-lease access in the new test and a namespace-qualified fbjni registration macro; both were corrected before passing builds.
- With `.local/opencv-4.12.0/opencv/build/x64/vc16/bin` on PATH: `ctest --test-dir .local/build/core-tests -C Release --output-on-failure` passed all four suites (render geometry, tensor, pixel and geometry contracts).
- `npm run check`: both TypeScript checks and all 16 JS contract tests passed.
- Device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.DocumentRendererTest,com.margelo.nitro.documentscanner.processing.GeometricRenderTest'`. The migrated stage APK built/installed; all 11 tests passed in 22.131 seconds, including exact pre-migration pre-filter pixels and geometry at 1e-12.
- Device: connected Samsung SM-G998U, Android 15, arm64 at `192.168.1.55:5555`; Kotlin 2.2.10, NDK 27.1.12297006, CMake 4.1.2, compile SDK 37, target SDK 36, min SDK 24. No Mac/iOS execution or new JVM run was required/performed for this geometric slice; later cross-platform release evidence remains pending.
- Final fixture/header verification reran the same Android command: APK built/installed and all 11 tests passed again (22.032 seconds). All five pre-filter pixel hashes remain exact, including the competing-rule fixture's existing bend decision. Final C++ formatting and diff whitespace checks passed.
