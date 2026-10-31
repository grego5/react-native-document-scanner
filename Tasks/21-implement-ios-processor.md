# 21 — Implement iOS rendering, PDF, and processor admission

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [08](08-share-document-filters.md), [09](09-share-gray-png.md), [10](10-share-pdf-writer.md), [19](19-implement-ios-source-store.md), [20](20-implement-ios-inference.md)

## Objective

Complete the iOS DocumentScanner processor facade over the shared engine.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/index.ts — nativeProcessor facade
- src/ScannerError.ts
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessor.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt
- src/DocumentScannerProcessor.nitro.ts

## Current behavior and invariants

One operation runs and one queues; overflow returns processor/busy; cleanup executes separately; thumbnails are registered before successful render resolution.

## Implementation steps

1. Complete ios/HybridDocumentScannerProcessor.swift using a bounded serial image-operation queue, separate cleanup, and supported Nitro Promise/result types.
2. Implement render through source leases and the shared warp/filter pipeline, requested JPEG/gray PNG encoding, paired output/thumbnail publication, and valid-content geometry.
3. Implement PDF platform image/file adapters including TIFF input fallback to JPEG while preserving direct JPEG and gray PNG embedding paths.
4. Track all pending results; disposal rejects once with processor/disposed, suppresses later publication, cleans unpublished files, and drains native resources.
5. Implement deferred sampling: getSampleFolder returns collecting:false; clear/disable are idempotent; async pick/save operations resolve feature/unsupported payloads. In src/index.ts, reject iOS setSampleCollection(true) with a public ScannerError before the void native call, because scannerValue cannot recover a stable code from an arbitrary native exception. Preserve Android dispatch and add the React Native Platform stub to facade contract tests.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Exercise render filters/formats/options, unchanged source bytes, TIFF reopen, PDF layout/embedding, queue overflow, failure rollback, and disposal races.
- Verify stale operations cannot leave visible orphan outputs or replace an existing PDF on failure.
- Assert iOS setSampleCollection(true) throws ScannerError with feature/unsupported synchronously, disabling succeeds, and Android still invokes its native implementation.

## Validation commands

- npm run check
- Run iOS processor/render/PDF/cache XCTest suites and shared engine tests.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Every processor method has defined behavior and passes file, queue, and teardown assertions.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement scanner rendering and PDF processor

