# 11 — Export immutable page snapshots and save samples by ID

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: [08](08-capture-session-pages.md)

## Objective

PDF, gallery and samples consume IDs safely, with no JS file pins or URI derivation.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `android/src/main/java/com/margelo/nitro/documentscanner/processing/PdfWriter.kt: write, Layout.page, temporary output`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/SampleCollection.kt: saveCapture, noteAnalysis`
- `android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessor.kt: createPdf, saveCaptureSampleResult`
- `example/hooks/usePhotoActions.ts: saveAll, sharePdf`
- `example/lib/pdfExports.ts`

## Current behavior and invariants

PDF and gallery exports consume URI arrays. PdfWriter can embed JPEG/4-bit PNG data unchanged. SampleCollection opens photos before queueing saves. Preserve these encodings and the gallery/share actions after hiding owned URIs.

## Implementation steps

1. Expose session.createPdf(pageIds, options) without options.pages. Snapshot the caller's ordered IDs, effective requested revisions/settings and resource leases atomically at invocation. Unknown IDs fail input/invalid before creating output.

2. Enqueue one export job; render missing snapshot artifacts inline through the serial synchronous pipeline, never await jobs queued behind it. If admission occurs during analysis, pin that source's already-requested analysis outcome and the invocation's explicit-quad override/settings: later page mutations cannot change the geometry chosen for this snapshot. Analysis failures required to obtain its automatic crop fail export; no-candidate analysis uses full frame.

3. Edits/deletion after snapshot admission cannot alter export content. Reuse matching artifacts, preserve existing PdfWriter quality/DPI/EXIF behavior, and never fall back to an older display. Close invalidates pending/running publication and cleans temporary outputs before replacement.

4. Add session.exportImages(pageIds, outputDirectory) returning independent exported file URIs in input order. Apply the same snapshot rules; copy output artifacts to unique files outside session directories. Roll back partial exported copies on failure. These exported URIs are artifacts, not access to page-owned files.

5. Add session.saveCaptureSample(pageId, quad?) that opens/pins the source before sample queue admission, preserving folder/model provenance. Keep folder selection and live sample controls on their existing owners.

6. Require PDF/image output paths outside active session directories and preserve existing explicit destination validation. Export leases survive page removal; external copies survive session close.

7. Add snapshot isolation tests, writer regressions and gallery-copy/sample lifetime tests.

## Ownership and boundary rules

React export lock is UX only. Native snapshots and leases enforce correctness. No source mutation, PDF deletion on successful session close or conversion of 4-bit PNG to JPEG. No worker self-deadlock.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Export after pending edits includes them; later edit/delete does not change it; missing/current failure rejects; duplicate page IDs retain requested order; snapshot leases survive removePage; queued close cancels; running close leaves no partial published output; gallery copies remain readable after session close.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run typecheck; update src/scanner-contracts.test.cjs for export facade semantics. Authorized device regressions: ScanSessionExportTest, PdfWriterTest and session sample tests; use test-device.ps1 only after authorization.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

PDF, gallery and samples consume IDs safely, with no JS file pins or URI derivation.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(export): snapshot session pages for output

## Implementation evidence - 2026-10-31

- Added public session createPdf(pageIds, SessionPdfOptions without pages), exportImages(pageIds, outputDirectory), and saveCaptureSample(pageId, quad?) with generated typed result unions and existing ScannerError translation. Native snapshots pin caller order/duplicates, requested revision/settings, matching cache files and originals atomically.
- ScanPage now retains its one authoritative analysis outcome. Pending automatic exports pin that outcome and invocation-time explicit geometry; later edits/removal cannot select a different crop. Analysis stays ahead of the export FIFO job, with no worker wait. Missing artifacts render inline through the same synchronous leased pipeline; matching current cache artifacts are reused, old displays are not substituted, and current/required analysis failures reject.
- SessionExports stages PDF/image output outside active/reserved session directories, including canonical aliases. Publication claims an open state before final moves/replacement; queued/running close discards staged output. Partial copies/moves roll back. Unique external images/PDFs survive successful session close. Existing PdfWriter encoding/DPI/EXIF logic is unchanged.
- Sample ID admission snapshots original/analysis/model provenance. The sample-private analysis record is named SamplePhotoAnalysis to avoid colliding with the shared PhotoAnalysis pipeline object. The sample queue opens/pins the source before admission, retains invocation folder selection and releases the lease on settlement. Page-owned provenance remains available after live-ticket cache eviction or global model changes. The example export/gallery hooks remain intentionally staged until Task 12.
- Added ScanSessionExportTest: synthetic controlled-worker snapshot isolation during analysis/edit/delete, explicit crop override, pending settings, duplicate order/cache reuse, missing/private paths and current failures, required analysis failure, queued/running close and old destination preservation, rollback, source/gallery/PDF lifetimes, page model provenance, sample source/folder admission, no-candidate full frame, unchanged JPEG embedding and four-bit PNG/PDF output. These native tests are NOT RUN.
- `npm run nitrogen`: passed, 8/8 generated. `npm run typecheck`: passed. `npm run test:contracts`: passed, 36/36, including immediate native export admission, ordered/duplicate IDs, PDF options without page URIs, independent artifact results, samples and typed failures.
- `npm run typecheck:example`: expected failure until Task 12: missing camera session, removed CapturedPhoto, old ReviewPanel uris/originalUri props. No compatibility aliases or premature example migration were added.
- `git diff --check`: generated-only trailing spaces in HybridScanSessionSpec.kt:36/40/44. New-file checking additionally found Nitrogen whitespace in JVariant_Array_String__ScannerFailure.hpp:65/71/75. Authored tracked/new-file checks passed; generated output was not hand edited.
- LSP returned no diagnostic items for changed production Kotlin and the new native test; its limited coverage does not establish compilation. Native builds, JVM tests, ScanSessionExportTest, PdfWriterTest/device sample regressions and physical camera work: NOT RUN, because native build authorization is withheld. Status updated at the user's explicit request after implementation; required native verification remains pending.
