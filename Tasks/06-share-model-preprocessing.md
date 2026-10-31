# 06 — Share tensor preprocessing and prediction interpretation

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [05](05-share-image-geometry.md)

## Objective

Use the same tensor preparation and output interpretation on both platforms.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocCornerSession.kt — tensor checks, decodeOutput, validNormalizedQuad
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

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

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

