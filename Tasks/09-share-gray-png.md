# 09 — Share four-bit grayscale PNG output

[Back to task index](../TASKS.md)

Status: Planned
Complexity: Medium
Dependencies: [02](02-introduce-portable-core.md)

## Objective

Provide one portable four-bit grayscale PNG encoder.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/GrayscalePng.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/GrayscalePngTest.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt — PNG output branch

## Current behavior and invariants

PNG is 16-level grayscale without dithering and is allowed only for grayscale/blackWhite rendering.

## Implementation steps

1. Move row quantization, packing, compression, and PNG serialization into the core behind an output sink.
2. Replace Android encoder use with the shared writer; keep temporary file ownership in the adapter.
3. Migrate pure encoder assertions atomically; preserve PDF-readable compressed image data.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Check PNG signature, chunk integrity, bit depth, odd widths, row padding, quantization boundaries, and lossless decode of quantized values.
- Verify sink failure stops output and leaves publication to the platform adapter.

## Validation commands

- Run shared build and ctest, including all migrated GrayscalePngTest assertions.
- npm run check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android emits valid equivalent four-bit PNG through the common writer.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share four-bit grayscale PNG encoding

