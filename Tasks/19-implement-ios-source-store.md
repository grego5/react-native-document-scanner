# 19 — Implement bounded iOS source storage and pixel leases

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [04](04-verify-ios-feasibility.md), [17](17-add-ios-package-and-nitro-bindings.md)

## Objective

Make captured sources durable while immediate native consumers reuse uncompressed pixels.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentScannerCamera.nitro.ts — CapturedPhoto
- src/DocumentScannerProcessor.nitro.ts — URI operations
- android/src/main/java/com/margelo/nitro/documentscanner/processing/SourceImageLoader.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/CachedImageFiles.kt
- android/src/main/java/com/margelo/nitro/documentscanner/PageThumbnails.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ImageLoader.kt

## Current behavior and invariants

originalUri remains a real local file URI; source bytes do not change after publication; operations must never delete a host-owned image. Android has no resident source cache: its loader decodes each requested source from its file. This cache and its leases are iOS-only, and Android does not adopt them during the migration.

## Implementation steps

1. Add ios/ source-store and image-loader types accepting TIFF/JPEG/PNG through ImageIO; map upright metadata and owned/borrowed pixels to the shared core.
2. Define one source store that the camera view (publishing), processor, crop editor, and viewers (acquiring) all reach, without any of those components owning it. A process-wide object is one valid owner; record the chosen owner and why. Specify its synchronization (which queue or lock guards entries and leases), eviction, memory-pressure response, and shutdown: what a pending acquire receives and when buffers are freed.
3. Keep one resident reusable full-resolution entry, with per-operation leases surviving eviction until that operation finishes. Older pages are file-backed.
4. Bound all live full-resolution source memory, not just the resident entry: the resident entry plus evicted buffers still held by leases. When admitting another buffer would exceed the bound, acquisition follows one documented rule (wait for a lease to end, or fail with a typed error); outstanding leases never grow memory without limit.
5. Implement file publication using provisional JPEG backing, using temporary files and cleanup on failure. Per user direction (2026-10-31), compare TIFF and JPEG later, once a working sample runs on a physical device; this measurement does not block the initial sample. Retain required capture metadata and correct dimensions/orientation after crop.
6. Add source/thumbnail lookup keyed by canonical URI so analysis/rendering can acquire the captured pixel lease without decoding the file.
7. Implement whole-request validation before cache deletion, refusing foreign files, directories, and symbolic links; defer physical removal while an operation holds a lease.
8. Generalize source-format documentation across the capture, editor, viewer, and pager contracts without widening Android support beyond what its loader implements.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Round-trip all EXIF orientations and TIFF/JPEG/PNG headers; verify equivalent pixel access from resident and reloaded sources.
- Test the live-memory bound with several outstanding leases across evictions, concurrent publish/acquire/evict from different queues, shutdown with pending acquires, eviction with outstanding leases, file-publication failure, cache deletion validation, already-removed files, and release on memory pressure/disposal.

## Validation commands

- npm run check
- Run iOS XCTest source-store, ImageIO, and cache-boundary tests.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Real local source URIs reopen correctly, native leases avoid the first decode, and ownership is bounded and testable.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add bounded source storage and pixel leases

