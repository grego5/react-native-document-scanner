# 10 — Share streaming PDF layout and serialization

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [05](05-share-image-geometry.md), [09](09-share-gray-png.md)

## Objective

Preserve PDF contracts with one portable layout and serialization implementation.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/PdfWriter.kt
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/PdfWriterTest.kt
- src/DocumentScannerProcessor.nitro.ts — CreatePdfOptions and createPdfResult

## Current behavior and invariants

Eligible JPEG/gray PNG payloads embed unchanged; EXIF changes placement, cover clips at the page, targetDpi never enlarges, and pages stream one at a time.

## Implementation steps

1. Move page-option validation, placement matrices, PDF objects/xref, direct embedding, and grayscale handling into C++.
2. Define platform image probe/decode/encode and file-stream adapters; keep destination replacement and sandbox access platform-owned.
3. Replace Android writer orchestration with the shared serializer and existing image/file adapters.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Verify page order, fit modes, orientations, page-size combinations, JPEG-quality forcing, DPI scaling, and four-bit preservation.
- Compare embedded eligible payloads byte-for-byte; inject stream failure and confirm existing destination survives.

## Validation commands

- Run shared build and ctest.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.PdfWriterTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android PDFs retain existing semantics and shared tests cover layout/serialization failures.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share streaming scanner PDF generation

