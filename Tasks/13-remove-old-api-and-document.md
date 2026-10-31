# 13 — Remove legacy endpoints and verify the complete migration

[Back to task index](../TASKS.md)

Status: Implemented — build-free verification passed; native/device verification pending
Complexity: Medium
Dependencies: [01](01-fix-ink-sharpness.md), [02](02-centralize-quad-validity.md), [03](03-introduce-keyed-queue.md), [04](04-own-session-resources.md), [05](05-own-page-revisions.md), [06](06-expose-native-session.md), [07](07-publish-batched-snapshots.md), [08](08-capture-session-pages.md), [09](09-bind-scanner-views.md), [10](10-add-page-thumbnail.md), [11](11-export-page-snapshots.md), [12](12-migrate-example-session.md)

## Objective

No old coordination/API remains, packaging/docs match the new workflow, build-free checks pass and outstanding physical verification is stated.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `src/index.ts and src/DocumentScannerProcessor.nitro.ts`
- `android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessor.kt`
- `README.md: startup, review and export`
- `.agents/skills/docs/references/architecture.md: Ownership, API boundary, Images, Example app, Lifecycle`
- `.agents/skills/docs/references/development.md: hand checks`

## Current behavior and invariants

Tasks 03-07 retain old implementations only to stage the migration. Final API has no compatibility aliases, generic URI scanner viewers or JS file ownership. Current documentation describes the old pipeline until this task.

## Implementation steps

1. Remove old public analyzePhoto/renderDocument/createPdf URI methods, removeCachedImages and original-URI capture/sample transport that the ID workflow replaces. Keep reusable internal renderer/writer algorithms and independent sample-folder/model/permission controls.

2. Remove transitional processor submission/disposal ownership superseded by shared queue/session state. Keep a focused utility HybridObject only for the independent operations it still implements; do not expose a second session lifetime.

3. Remove obsolete exported CapturedPhoto/RenderedDocument contracts if no public consumers remain, URI view props and unused native bridge classes/registrations. Regenerate via npm run nitrogen; never edit generated output by hand.

4. Search active sources, tests, examples and documentation for obsolete APIs, aliases and processing state. Preserve useful tests by retargeting them; remove assertions whose only purpose was enforcing the retired bounded queue/URI interface.

5. Rewrite README startup with createSession, Camera, ID viewers, subscribe/getSnapshot and createPdf examples; keep advanced settings at the bottom. Update architecture ownership and development hand checks to actual implemented behavior, including React-owned strip/gallery arrangement, PageThumbnail composition and immediate edits. Drop the native-strip/onMove design.

6. Verify package inclusion of new source/spec/generated files using npm pack --dry-run --json --ignore-scripts with a workspace-local cache. Validate the packed manifest, not just repository builds.

7. Run build-free checks and diff validation; list native/device checks still not run. Keep completion claims limited to evidence actually recorded.

## Ownership and boundary rules

No unrelated cleanup, iOS implementation, model/threshold changes or generic image-view fallback. No compatibility aliases. Documentation must describe implemented behavior rather than promises.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Final public facade has only the agreed ID workflow; package lists every needed file; examples typecheck; queue/session/snapshot/view/export regression cases remain present. Full native/device and manual camera/RTL/reordering scenarios remain explicit verification gates.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run nitrogen; npm run check; npm pack --dry-run --json --ignore-scripts --cache .local/npm-plan-check; git diff --check. Native verification only when authorized: test-native.ps1, test-device.ps1 and a human-operated example workflow.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

No old coordination/API remains, packaging/docs match the new workflow, build-free checks pass and outstanding physical verification is stated.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

refactor(api)!: finish session-only scanner workflow

## Implementation evidence (2026-10-31)

- Removed public URI analyze/render/PDF/cache-removal/capture-sample methods and the utility disposal facade. DocumentScanner now exposes createSession and independent sample-folder controls only. ExportSheet uses SessionPdfOptions; the old CreatePdfOptions/RenderedDocument transports are gone. CapturedPhoto and URI view props were already removed by prerequisites; no compatibility aliases were added.
- HybridDocumentScannerProcessor is a stateless factory/control object with no image/deletion queue owner, model lease, submission helper or custom disposal lifetime. Removed its unused shared deletion queue; session cleanup remains on the registry cleanup coordinator, outside the single image worker.
- Preserved renderer/PDF algorithms with private native PdfWriteOptions and RenderResult types. Standalone renderer/frame overloads retained solely for native pixel fixtures remain internal, reject owned session paths, and are neither public bridge APIs nor compatibility aliases. Capture sample admission now requires an already-pinned session original; old URI/resource admission overloads and processor-disposal analysis callback are removed.
- Removed obsolete processor URI/disposal and cache-janitor interface tests. Their useful FIFO, exact-once, cancellation and canonical/lease coverage remains in ScannerWorkQueueTest, SessionResourcesTest/DeviceTest and ScanSession bridge/state/pipeline/export tests. Retargeted factory lifetime/typed-creation-failure fixtures and the JS utility facade test. Native renderer/PDF/filter fixtures remain present; only their native-only result/options types changed.
- Rewrote README startup/lifetime, ID viewers, crop commands, metadata subscriptions, PageThumbnail composition and exports. Host-owned layout/order/selection/drag responsibilities and the example's selection-only strip are explicit. Architecture and development hand checks now describe immediate edits, session cleanup and host-owned exports rather than the retired URI pipeline.

Validation:

- npm run nitrogen: passed, 8/8 HybridObjects generated using Nitrogen 0.37.1; generator removed 12 obsolete bridge files and updated autolinking. Generated files were not edited by hand.
- npm run check: passed both library/example typechecks and all 36 JS contracts. No staged example incompatibilities remain.
- npm pack --dry-run --json --ignore-scripts --cache .local/npm-plan-check: passed. Parsed actual npm manifest at ignored .local/task13-package-manifest.json: 424 package entries; all 362 required runtime/source/spec/generated/build/config/assets files included, including the detection model. All four package entrypoints resolve to included src/index.ts. No old CreatePdfOptions/RenderedDocument/CapturedPhoto/janitor files, private workspace content or JS tests appear in the manifest.
- README TypeScript/TSX syntax check: all 7 blocks passed after correcting a sibling JSX example. This is syntax validation, not a runtime demonstration.
- Active source/example/native/generated/README/maintainer-doc search found no obsolete API calls, contracts, URI view props or original-URI sample transport. Native algorithm file inputs and exported host-owned URIs remain intentional.
- git diff --check passed. The two new native-only data files also passed no-index whitespace checks (exit 1 denotes new-file differences, with no whitespace diagnostics). No new generated whitespace warnings in this diff.
- Focused Kotlin LSP diagnostics returned no items, but the server reported limited/unrelated project sync metadata. These are supplementary inspection only, not compilation evidence.

Outstanding verification gates (not run): native JVM tests, Kotlin/C++ compilation, APK/instrumented session/view/export tests, physical camera permission/capture/lifecycle flows, human crop/filter/gallery/share workflow, auto/LTR/RTL paging and host reorder/delete scenarios. No native build/device work was authorized and no physical shutter was automated. Earlier native verification gates across Tasks 01-11 remain pending; build-free success does not establish Android runtime correctness.

Native-module/Kotlin/TypeScript implementation pass complete for the final public boundary and packaging/docs migration. No iOS implementation, model/filter/gate threshold change or unrelated optimization was performed.
