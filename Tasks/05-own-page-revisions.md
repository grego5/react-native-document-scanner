# 05 — Own native page state, analysis and rendering

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: [02](02-centralize-quad-validity.md), [03](03-introduce-keyed-queue.md), [04](04-own-session-resources.md)

## Objective

Page state and render reuse/publication are fully native and independent of React identity checks.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessor.kt: analyzePhoto, renderDocument, openSession`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerInference.kt: Lease`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt`
- `example/lib/scanSession.ts: PagePreview, ReviewDraft, withCachedPreview`

## Current behavior and invariants

React currently owns render configurations, per-filter reuse, analysis transitions and current-preview checks. Native rendering always derives from the original. The last valid displayed image must remain until a replacement is ready.

## Implementation steps

1. Add native ScanSessionState and ScanPage (new) keyed by page ID. Page state owns immutable source references, authoritative analysis, effective quad/options, requested revision, ready revision, failure and cached artifacts.

2. Add session commands setQuad, setFilter and setRemoveShadows. Validate before acceptance; equal settings are a no-op. Commands change effective settings immediately and enqueue keyed renders without promise chains.

3. On saved-source admission, capture initial render options and the model selection/lease semantics from the existing analysis flow. Perform authoritative photo analysis before the first processed render; show the capture-frame placeholder meanwhile.

4. Track explicit user geometry separately from analysis-derived geometry. A setQuad admitted during analysis prevents its later candidate from replacing that geometry; filter changes do not cancel useful analysis.

5. Cache one compatible result per filter/shadow combination for the same geometry/output configuration. Crop/configuration changes invalidate incompatible files after leases release. Do not expose cache objects/URIs in metadata.

6. Only the latest requested revision publishes readiness/failure. Stale results are cleaned or retained only when still compatible; they never overwrite the displayed revision. Analysis failure is exposed separately and leaves full-frame/manual cropping usable; render failure keeps the previous display and is retried by resubmitting the failed settings or a new edit.

7. Add ScanSessionStateTest using controlled fake analysis/render actions, plus synthetic-image integration coverage.

## Ownership and boundary rules

Native is the single owner of revisions and stale-result rejection. Never filter the live-frame placeholder. No native presentation order, saved-edit transaction, page handle or setCropEnabled command.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Edit during analysis, A/B/A filters, failed render followed by retry, delete during render, stale success/failure, no-candidate full frame and equal-setting no-ops produce deterministic page state. One first render uses capture defaults.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

Authorized JVM command: npm run test:native -- -Test '*ScanSessionStateTest'. Device integration belongs in ScanSessionPipelineTest and must be separately authorized.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

Page state and render reuse/publication are fully native and independent of React identity checks.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(session): own page revisions and processing

## Implementation evidence

- Added native ScanPage and ScanSessionState. Pages own immutable original/frame tokens, authoritative analysis, effective quad/options, requested/ready revisions, separate analysis/render failures and native artifacts. ID commands validate before acceptance, apply immediately and schedule keyed FIFO work. Explicit geometry (including an explicit full-frame choice during analysis) prevents candidate replacement; equal settings do not increment revisions unless retrying a render failure.
- Added SessionPageProcessing and shared PhotoAnalysis. Admission captures normalized initial render options and the selected immutable model; photo decoding/inference precedes the first processed render. Model leases are acquired only on the serial image worker and closed after its owner drains. The transitional analyzer retains its URI contract, disposal check and duration measurement. No model, filter or quality thresholds changed.
- Caches retain one result per filter/shadow combination for compatible geometry/output settings. Stale success can fill a compatible cache slot but cannot publish an old ready revision; stale failures cannot fail a newer edit. Incompatible artifacts retire through SessionResources. The previous display survives pending crops and render failures; reader leases can outlive replacement. Frames are retained as placeholders and never submitted to filtering.
- Added ScanSessionStateTest with controlled analysis/render actions and manual executors: captured defaults/model selection; edits during analysis; full-frame/no-candidate/analysis failure; equal settings; invalid admission without consuming sources; A/B/A and shadow reuse; queued replacement; compatible/incompatible stale success; stale failure; failed-setting retry; crop invalidation with retained readers; deletion, ID reuse and close during active work.
- Added ScanSessionPipelineTest with synthetic JPEG pixels and real rendering: analysis-before-render, reuse, geometry invalidation, immutable original/frame bytes, prior display retention, actual render failure cleanup and retry. No camera or shutter is used. These tests are written but have not been executed.
- Updated the architecture Images section. Existing URI endpoints and the example remain staged unchanged in behavior; no public session APIs, generated bindings or later-task transport/capture/view/export outcomes were added.

### Verification

- Passed: npm run check — library and example typechecks, 16/16 JavaScript contract tests. This does not establish native correctness.
- Passed: git diff --check for tracked edits and git diff --no-index --check for each new Kotlin file (exit 1 denotes a new-file diff; no whitespace diagnostics).
- Supplementary inspection: LSP returned no diagnostic items for changed Kotlin production/test files. Reference results were incomplete, so consequential analyzer, renderer, queue, model and resource relationships were confirmed in focused source. This is not Kotlin compilation evidence.
- Not run: npm run test:native -- -Test '*ScanSessionStateTest'; native compilation, Gradle/APK and ScanSessionPipelineTest device integration. The task/index withholds native build/device authorization; JDK/ABI/device readiness was not asserted.
- Status updated at user request: implemented, with required native verification pending.
