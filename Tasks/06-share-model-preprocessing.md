# 06 — Share tensor preprocessing and prediction interpretation

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [05](05-share-image-geometry.md)

## Objective

Use the same tensor preparation and output interpretation on both platforms.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocCornerSession.kt — tensor checks, decodeOutput, validNormalizedCorners
- android/src/main/java/com/margelo/nitro/documentscanner/processing/NativeTensorPreprocessor.kt
- android/src/main/cpp/NativeTensorPreprocessor.cpp
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/NativeTensorPreprocessorTest.kt

## Current behavior and invariants

One float32 NHWC input and one nine-float output; score is ranking evidence and never an acceptance threshold. Interpreter lifetime stays platform-owned.

## Implementation steps

1. Move resampling, RGB normalization, shape validation, and output-to-candidate geometry into the core.
2. Keep Android interpreter invocation on CornerInference's existing worker and pass borrowed prepared tensor buffers with completion-bound lifetime. Keep that worker's FastCores.prime pinning before every task.
3. Remove the production Kotlin prediction decoder once JNI returns the shared interpretation.
4. Make model dimensions selection-driven; preserve rectangular custom inputs and bundled 224×224 defaults.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Compare preprocessing bitwise for identical synthetic pixels, including strided and rotated sources.
- Retain native buffer-boundary failure tests and existing model fixture tolerances; reject malformed/nonfinite output.

## Validation commands

- Run shared build and ctest.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessorTest,com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android inference still uses one runtime and equivalent tensors/candidates; shared preprocessing has no JNI dependency.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share scanner tensor preparation and decoding


## Implementation report

- Added `cpp/include/document_scanner/Tensor.hpp`, `cpp/src/Tensor.cpp` and `cpp/tests/TensorTest.cpp`; registered the core source and `tensor_contracts` in `cpp/CMakeLists.txt`.
- Moved the existing integer separable resize kernels and RGB normalization without changing arithmetic. The portable workspace borrows `PixelLease` and writable float32 output synchronously; it applies encoded crop/orientation before resizing the entire upright region. Source dimensions may change between calls; rectangular target dimensions come from the selected model. The native helper defaults to the bundled 224x224 input; custom model selection semantics remain unchanged.
- Shared tensor metadata validation and nine-float interpretation reject malformed/nonfinite output, preserve exact float32-to-double corner promotion, and return no candidate for invalid geometry. Scores have no acceptance threshold. Photo inference crop selection now reuses the shared prediction predicate.
- Android `NativeTensorPreprocessor.*` retains only JNI, bitmap locks, buffer validation/references and shared workspace ownership. `DocCornerSession.kt` retains interpreter invocation and maps accepted shared values to existing candidate/provenance types. `CornerInference` still serializes one runtime and primes its worker before every task; its completion-bound image ownership is unchanged.
- Moved the decoder/corners fixture assertions out of JVM tests into CTest, added portable resize goldens and all-EXIF crop/stride/format tests, and added JNI metadata/decoder error-contract assertions in `NativeTensorPreprocessorTest.kt`. Existing Kotlin resampling oracle tests remain independent. Updated portable-core and architecture documentation.
- The separate Kotlin `validNormalizedCorners` helper remains only for existing live/tracked-guide policy consumers pending their later migration; production model-output decoding no longer uses it. No iOS adapter/runtime integration was added or executed; that remains Task 20, with cross-platform release evidence still required.

### Validation

- `npm run check`: both TypeScript checks and all 16 JS contract tests passed.
- `cmd /c .local\task05\build-core.cmd`: Windows x64 MSVC 14.51.36231 C++20 build with OpenCV 4.12 passed. This invokes the installed Visual Studio developer environment and `cmake --build .local/build/core-tests --config Release`.
- With `.local/opencv-4.12.0/opencv/build/x64/vc16/bin` on PATH, `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`: all three suites passed (tensor, pixel and geometry contracts). Tensor assertions include independent Pillow goldens, all 256 channel values, padded row/pixel strides, crop offsets, all eight EXIF orientations, malformed metadata/output, score independence and post-failure reuse.
- Device verified by `adb devices` and getprop: `192.168.1.55:5555`, Samsung SM-G998U, Android 15, arm64. Android builds use Kotlin 2.2.10, NDK 27.1.12297006, CMake 4.1.2, compile SDK 37, target SDK 36 and min SDK 24.
- Device command: `$env:ORG_GRADLE_PROJECT_docCornerFixtures=(Resolve-Path .local/doccornernet-runtime/assets).Path; npm run test:device -- -Device 192.168.1.55:5555 -Measure -Class 'com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessorTest,com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest,com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest#directPlanesMatchBitmapTensorsForOffsetsStridesCropsAndRotations'`. Fixture transfers were already user-authorized; photos/reports remain ignored. No camera captures were triggered.
- Final optimized Android APK built and installed successfully; all 10 selected instrumentation tests passed in 51.147 seconds. These include bitwise Kotlin-oracle tensors, dynamic rectangular inputs, input/lock/close failure paths, direct planes versus bitmap tensors over offsets/strides/crops/rotations, shared decoder/metadata errors, interpreter lease concurrency and model replacement/failure recovery. The wrong-shape model and measurement-only reference test were enabled with prepared fixtures.
- All 13 reference photos passed existing budgets unchanged: upright PNG mean/worst normalized corner error `1.0493131312273292e-7` / `7.854004012410134e-7`; sampled JPEG `0.00029706496841287305` / `0.0016912468526111024` (budgets mean <=0.005, worst <=0.015; exported tensor/model error <=2e-5). Full report: ignored `.local/task06/report.json`.
- `npm run test:native` first failed before testing because the Expo Gradle plugin jar lacked its implementation class. The documented `npm run test:native -- -RecompilePlugins` workaround succeeded (BUILD SUCCESSFUL, 1m50s): 21 suites, 117 passed, zero failures/errors, one optional recorded-trace replay skipped because no trace was supplied. The two migrated JVM decoder tests are now covered by the portable suite, rather than counted as missing coverage.
- C++ clang-format dry run and `git diff --check` passed. C++ and Kotlin work used the implementation stage only; no optimization/cleanup pass or performance claim was made. Existing `-O2` and disabled floating-point contraction were preserved for the moved resize code.
