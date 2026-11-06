# 08 — Share shadows, paper normalization, and ink filters

[Back to task index](../TASKS.md)

Status: Completed
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
- cpp/src/DocumentFilters.cpp — migrated paper, shadow and ink algorithms
- cpp/include/document_scanner/DocumentFilters.hpp and cpp/src/DocumentFilters.cpp — shared filter stage
- android/src/main/cpp/NativeDocumentFilters.cpp — synchronous owning JNI conversion
- cpp/src/FieldKernels.cpp — unchanged native kernels
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


## Implementation report

- Added the portable `DocumentFilters.hpp` API and `DocumentFilters.cpp` stage. Optional shadows precede filtering; none still skips paper/ink normalization. Full upright RGBA8 leases accept padded rows and the existing 4096-side render limit. Calls borrow synchronously without source mutation and publish independent immutable RGB8 or gray8 pixels; shadow-only diagnostics publish RGBA8 with unchanged alpha. OpenCV allocations remain private and use RAII.
- Moved the existing shadow closing/percentile/gain, neutral-paper estimate and three-scale fill, white balance, tile ink/relative sharpness/neighbour fill, sharpening and continuous tone curves to C++. Constants, operation order, Kotlin positive rounding and float32 maps/narrowing are preserved. The existing field kernels and their arithmetic/threading flags are unchanged; the new orchestration disables FMA contraction (MSVC keeps `/fp:strict`).
- Added `NativeDocumentFilters.*` C++/Kotlin registration and keep rules. JNI retains a source Mat header for its lease, copies the immutable result into Android-owned storage and converts portable RGB to the existing BGR encoding contract. These ownership conversions add copies; no optimization or performance claim is made.
- `DocumentRenderer.kt` calls the combined stage. `DocumentFilters.kt` retains bitmap/Mat allocation and conversion only; `ShadowRemoval.kt` retains the diagnostic stage adapter; production `InkDetail.kt` was removed. Editor previews reach the same stage through their unchanged bitmap path, preserving their smaller source and filter-only setting. Existing render/preview `FastCores.run` scopes, decode, encode, thumbnail/file publication, public APIs and generated bindings are unchanged.
- Captured 16 public synthetic original-stage pre-encoding outputs before removing the Kotlin algorithms. `tests/fixtures/parity/filters/` contains deterministic gzip BGR8/gray8 bytes and a decompressed length/SHA-256 manifest. `FilterParityTest` compares every byte within one intensity level for all filters with independent shadow settings, with shaded paper, color, gray, faint/dark ink and empty pages; it also checks combined/separate ordering and input ownership. Portable `DocumentFiltersTest.cpp` covers padded-source none identity, paper/ink/color/broad-gray behavior, continuous blackWhite, alpha, source/result lifetime, combined ordering, empty pages and invalid boundaries.
- Existing filter, shadow, kernel and renderer assertions remain intact; no JVM algorithm assertions were removed. `RenderTimingTest.kt` still times complete filter and shadow/gain stages, but its six lines exposing the now-private Kotlin ink stages were removed. Architecture/core/fixture documentation records the new ownership and historical nature of earlier timing results. C++ and Kotlin implementation stages only; no optimization/cleanup pass.

### Validation

- Verified target: `adb devices` showed `192.168.1.55:5555` online; model SM-G998U (Android 15, arm64). Android build uses React Native 0.88.1, Nitro 0.37.1, AGP 9.2.1, Kotlin 2.2.10, min SDK 24, target SDK 36, compile SDK 37, NDK 27.1.12297006 and CMake 4.1.2.
- Original-stage recorder: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.FilterParityTest' -Argument recordFilters=true`: APK built/installed; 1 test passed (0.451 seconds). Retrieved only generated synthetic bytes, then removed recorder code. All 16 decompressed fixture lengths and hashes were independently verified locally. No camera captures or private-photo transfers occurred.
- Portable build: `cmd /c .local\task05\build-core.cmd` uses the installed Visual Studio developer environment and `cmake --build .local/build/core-tests --config Release`; MSVC 14.51.36231, C++20, Windows x64, OpenCV 4.12. With `.local/opencv-4.12.0/opencv/build/x64/vc16/bin` on PATH, `ctest --test-dir .local/build/core-tests -C Release --output-on-failure` passed all 5 suites (1.31 seconds). Initial builds caught a Mat field typo and it was corrected. An overly strict exact combined/separate host assertion was replaced with the task's one-level pixel budget; final portable contracts passed. The broad-gray filter-only assertion is separate from optional shadow lifting, whose existing behavior can lift large dark areas.
- `npm run check`: both TypeScript checks and all 16 JS contracts passed.
- Full Android command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.DocumentFiltersTest,com.margelo.nitro.documentscanner.processing.ShadowRemovalTest,com.margelo.nitro.documentscanner.processing.FieldKernelsTest,com.margelo.nitro.documentscanner.processing.FilterParityTest,com.margelo.nitro.documentscanner.processing.DocumentRendererTest'`: APK built/installed, all 24 tests passed (9.773 seconds), including every original-stage output within one intensity level. An earlier parity attempt used the wrong gzip asset path; Android packaging expands gzip/removes its suffix, and the consumer was corrected before the passing run.
- No new JVM run was needed/performed: the migrated algorithms had device tests, and those were retained. No Mac/iOS execution, private-photo evaluation or performance measurement was run for this slice; cross-platform release readiness remains pending later tasks.
- Final additional command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.FilterParityTest'`: final APK built/installed, both tests passed (0.640 seconds), adding bitmap-preview equivalence and JNI invalid-input/recovery to the original-stage comparisons. Final C++ formatting and diff whitespace checks passed.
- User approved proceeding without GitHub Actions for this migration; iOS filter execution stays explicitly pending for integration/release validation.
- Sharpening correction: the original Kotlin and migrated C++ reused one matrix across 8U blur, 16S Laplacian and 8U magnitude. OpenCV recreates Laplacian's destination before reading its source; aliasing the same matrix across the depth change produced allocation-dependent ink maps. A later run exposed a 48-level combined/separate mismatch. The earlier rounding explanation was incomplete; the user authorized correcting the behavior.
- Blur, signed Laplacian and magnitude now have independent storage. A portable regression repeats all ink filters while retaining prior allocations; 20 targeted host processes and the final portable suites passed. The test-only Kotlin reference comes from `234886b`, changed only for symbol names/test comments and independent sharpness intermediates. Source comparison verified that provenance. It independently generated the corrected baselines only after shared output matched within the unchanged one-level budget (2 Android tests passed, 0.803 seconds).
- Six shaded-page enhance/grayscale/blackWhite baselines changed; none and empty-page outputs stayed identical. Manifest retains original hashes. The final Android run passed all 37 tests (14.278 seconds), including corrected-reference comparisons, semantic filters/shadows/kernels, bitmap previews and renderer/PDF integration; that combined run also included the separately pending PNG task's tests. No tolerance was widened. The sharpening correction is included in this Task 08 commit.
