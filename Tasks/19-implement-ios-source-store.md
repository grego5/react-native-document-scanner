# 19 — Implement bounded iOS source storage and pixel leases

[Back to task index](../TASKS.md)

Status: Completed — initial native validation passed; local post-review corrections not rerun on iOS
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


## Implementation and validation — 2026-11-02

The lookup map above predates native-session main: CapturedPhoto/originalUri and
processor URI operations are no longer public APIs. This task preserves the
session/page-ID boundary. Real local source URLs stay internal; no public source
URI API or second view-owned resource owner was introduced.

Changed native files:

- ios/SourceStore.swift: process-wide SourceStore.shared, serial source queue,
  immutable file publication, canonical lookup, one full-resolution resident and
  one thumbnail slot (maximum 512 px), file pins and owned-file retirement.
- ios/SourceImageLoader.swift: TIFF/JPEG/PNG inspection and ImageIO decode,
  immutable opaque sRGB RGBA8 allocations, lifetime-based budget tickets,
  provisional JPEG publication metadata and typed native errors.
- ios/ScannerSourcePixels.h/.mm: generic coordinate-space adaptation through the
  existing shared orientation geometry, and a retained-owner C++ PixelLease bridge.
  The core receives upright RGBA8/full-crop metadata and no platform handles.
- ios/Tests/SourceStoreTests.swift and ios/Tests/DependencyLinkTests.mm: source,
  memory, lifecycle, cache boundary and native owner-retention coverage.
- ReactNativeDocumentScanner.podspec: ImageIO/UniformTypeIdentifiers dependencies
  and export of the authored bridge header. No generated source was edited.
- ios/README.md and capture/processor/editor/viewer/pager spec comments document
  formats and ownership without widening Android's source contract.

Chosen owner and admission policy: the process-wide service is shared by future
camera/processor/view consumers; sessions logically own their source IDs/URLs and
explicitly retire their files. Entries/pins/publication/decoding are queue-owned;
shutdown admission and byte accounting have independent locks. The default budget
is 256 MiB, with dimensions limited to 16384 per side and 32 million pixels. All
live resident/evicted Swift, CGImage and C++-owned allocations remain charged until
their final owner releases them. Admission also reserves three RGBA-size units of
pixel staging before creating the output allocation. Exceeding the budget fails
with native memoryLimit (mapped to processor/unavailable); it never waits for a
lease or permits an unbounded chain of evicted buffers. These are source allocation
limits, not a measured total-process RSS limit; opaque codec bookkeeping and the
caller's borrowed capture buffers are outside this owner's accounting.

Memory warnings evict cache slots while leases survive. Nonblocking shutdown marks
closed before queued work enters, rejects pending/new work with disposed, cancels
stale publication, and queues cache/observer release. Final publication/admission
is linearized with shutdown. Durable backing survives shutdown; explicit whole-
request-validated retirement refuses foreign files, directories and symlink paths,
rejects new reads and waits for the last file pin before physical deletion.

Validation passed:

- npm run check: both TypeScript checks and all 40 JS contracts.
- npm run nitrogen: all eight objects generated; no generated diff.
- CocoaPods installation in the existing isolated Task 17 package host.
- Verified Xcode 26.6 (17F113), Swift 6.3.3 (swiftlang-6.3.3.1.3), iOS simulator SDK
  26.5 and deployment target iOS 18. In
  /Users/admin/Projects/react-native-document-scanner/.local/task17/host:
  `DEVELOPER_DIR="/Users/admin/Downloads/Xcode 2.app/Contents/Developer" xcodebuild -workspace ScannerPackageValidation.xcworkspace -scheme ReactNativeDocumentScanner-Unit-ScaffoldTests -configuration Debug -destination "platform=iOS Simulator,id=7C539F34-E6DA-4D55-AF59-98A50A35C2D7" -derivedDataPath ../derived-data CODE_SIGNING_ALLOWED=NO build-for-testing`
  succeeded.
- The existing external standalone simulator runner executed 30 XCTest cases with
  zero failures (7.748 s test time): 12 source-store cases, one new C++ retained-
  owner lease test and the existing 17 permission/package cases. Source cases
  cover all eight EXIF orientations for TIFF/JPEG/PNG headers AND pixels, capture
  normalization without a first decode, resident pointer reuse, JPEG reopen,
  cropped dimensions/capture metadata, multiple outstanding evicted leases,
  concurrent publish/acquire/evict/retire, memory warnings/disposal, shutdown with
  queued acquisition/running publication, write rollback, whole-request validation,
  symlinks, already-removed files and deferred deletion through CGImage owners.
- Authored tracked/untracked file whitespace checks passed; package dry-run includes
  all new native sources and tests.

Earlier attempts exposed an unexported authored helper, Swift typing/test-module
interop issues, Quartz top-left/bottom-left transform differences for EXIF 5–8,
wrong crop placement, and URL directory-flag comparison on newly created folders.
The authored export/typing fixes, native Swift error assertions, generic Quartz
coordinate conversion and canonical path comparison resolve them; the final suite
above supersedes those failed attempts. Goldens were retained and per-format pixel
assertions strengthened. Tests assert typed errors at this native owner boundary;
Hybrid payload transport belongs to the later operation implementation.

Evidence: ignored .local/task19-ios-build.log, task19-ios-xctest.log,
task19-pods.log, task19-check.log and task19-nitrogen.log. Build/test infrastructure
remains outside this repository; the authorized Mac snapshot is under its existing
project's ignored .local/task17 folder.

Limits: JPEG backing is provisional and lossy. Resident capture pixels are reused
exactly; reopened JPEG access preserves geometry/content with quantization (the
synthetic tests allow at most 12 intensity levels), not bitwise raw equality. No
real-photo quality/performance measurement, physical-device run, camera capture,
model inference or full React Native iOS sample was attempted. TIFF/JPEG device
comparison remains deferred per user direction. Later tasks connect this tested
native source service to session orchestration, inference, cameras and views;
Task 19 does not claim those implementations or iOS release readiness. Android
runtime code and portable algorithms remain unchanged.

### Local post-review corrections — 2026-11-02

Addressed both P2 findings in SourceStore.swift. Successful/already-completed file
removal now drops the full Entry/header after its last pin. Only the latest 256
completed retirement URLs remain in FIFO bookkeeping for idempotent absent-file
removal; older URLs are rejected as no longer owned. Recreated files at remembered
URLs are rejected and left untouched. Shutdown clears entries and retirement
history; outstanding pins retain only their own needed state until unpin drains.

Memory-warning delivery now enqueues weak-owner eviction and returns without
waiting for the source worker. Explicit evict() remains synchronous. Added focused
XCTest regressions for bounded retirement recognition/replacement-file protection
and nonblocking notification delivery while publication is gated, followed by
actual queued eviction. README records the reclamation/notification policy.

Per the request for local corrections, no Mac build or XCTest rerun was performed.
The earlier 30-test result above applies to the pre-review snapshot; the two new
regressions and revised Swift source have not yet been compiled/executed. Local
npm run check and authored whitespace checks passed after these corrections.
