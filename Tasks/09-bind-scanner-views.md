# 09 — Bind viewer, pager and crop editor to session pages

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: [08](08-capture-session-pages.md)

## Objective

All scanner views resolve by session/page IDs, preserving current loading and interaction semantics.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `src/ImageViewer.nitro.ts, src/ImagePager.nitro.ts, src/DocumentCropEditor.nitro.ts: view props`
- `android/src/main/java/com/margelo/nitro/documentscanner/HybridImageViewerView.kt, HybridImagePagerView.kt, HybridDocumentCropEditorView.kt`
- `android/src/main/java/com/margelo/nitro/documentscanner/ImageLoader.kt: show, loadFull, release`
- `android/src/main/java/com/margelo/nitro/documentscanner/ImagePagerView.kt: setPages, show, settle`
- `android/src/main/java/com/margelo/nitro/documentscanner/CropEditorView.kt`

## Current behavior and invariants

Viewer/pager accept URIs and ImageLoader keeps thumbnails, zoomed full decodes and generation guards. The crop editor reads originalUri and emits valid constrained geometry. Preserve gestures, no-flash replacement, decode retry and bitmap draw safety.

## Implementation steps

1. Replace viewer URI with session/pageId and pager URI array with session/pageIds; make CropEditor resolve original pixels by session/pageId while retaining an unsubmitted quad prop.

2. Introduce native page bindings that lease the current placeholder/thumbnail/render, observe native page revisions and update ImageLoader directly. onLoad reports the page ID rather than an internal URI. No JS snapshot subscription drives image replacement.

3. Lease files while queued/running reads or fades still need them. Recycle only never-published discarded bitmaps; keep current drawn bitmaps safe as existing code does. Detach/hidden/zoom reset releases full-resolution resources.

4. Add pager direction auto/ltr/rtl. Use inherited Android layout for auto and explicit per-view layoutDirection for overrides. Runtime direction/order changes preserve selection by page ID; logical indices refer to input pageIds.

5. On page removal or closed/session-replaced binding, detach subscriptions, clear invalid content and suppress stale decoder callbacks. Invalid configured IDs report input/invalid instead of selecting another page silently.

6. Keep crop drag callbacks valid-only; initial/programmatic geometry is validated natively. Do not auto-submit draft gestures. Regenerate view bindings and update native viewer/editor tests.

## Ownership and boundary rules

Views read authoritative native state, not batched JS metadata. Native image ownership never depends on an onLoad callback from React. No generic URI mode or page handle retained by JS.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Placeholder->render, failure/retry, visible deletion, stale decode, zoom/release, session replacement and unmount all release leases. RTL overrides and runtime changes retain selected ID and correct callback indices. Cancel crop changes no page revision.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run nitrogen; npm run typecheck; update JS bridge contracts. Old example remains intentionally incompatible until Task 12. Authorized device classes: ImageViewerViewTest, ImagePagerViewTest, CropEditorViewTest.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

All scanner views resolve by session/page IDs, preserving current loading and interaction semantics.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(views): resolve scanner pages natively

## Implementation evidence — 2026-10-31

- Viewer/pager/crop specs and public wrappers now require session/page IDs; pager direction is auto/ltr/rtl. Viewer/pager onLoad reports IDs; crop retains decoded width/height. Native-backed session validation uses the existing ScannerError boundary. No compatibility URI props or JS metadata image subscriptions were added.
- Native SessionPageBinding observes authoritative state, atomically leases displayed render/thumbnail or frame/original fallback, and invalidates removed/closed/replaced owners. Queued reads retain independent pins, installed sources survive fades, and detach/unmount clears subscriptions and generations. Viewer/crop decoding and filter previews now share the process image FIFO. Crop draft callbacks do not mutate sessions; native geometry validation remains in place.
- Pager order changes retain selection by ID; explicit and inherited directions retain logical callback indices. Session replacement immediately clears old holder bindings without recycling transitioning views.
- Synthetic native coverage was added/updated in ImageViewerViewTest, ImagePagerViewTest and CropEditorViewTest with SessionViewFixture: controlled reads, placeholder/render failure and retry, stale replacement/deletion, queued-read close barriers, attachment release, direction/order retention and crop draft cancellation. Existing gesture/geometry regressions remain.
- `npm run nitrogen`: passed, 7/7 specs generated. The first library typecheck caught a malformed source callback signature; corrected and regenerated successfully.
- `npm run typecheck`: passed. `npm run test:contracts`: passed, 34/34 after correcting new test fixtures. The added bridge contract checks session replacement, stable committed callbacks, direction defaults/updates and typed invalid-session failures across all three wrappers.
- `npm run typecheck:example`: expected failure until Task 12: missing camera session, removed CapturedPhoto import, old ReviewPanel uris and originalUri props. The example was not migrated or shimmed.
- `git diff --check`: generated-only trailing whitespace in HybridDocumentCropEditorViewSpec.kt:35, HybridImagePagerViewSpec.kt:35/41 and HybridImageViewerViewSpec.kt:35. Generated files were not hand edited. Authored whitespace checked separately.
- Native builds, JVM tests, device tests and physical camera work: NOT RUN; build authorization is withheld. Empty LSP diagnostic responses supplement source inspection but do not establish native compilation or device correctness. Status updated at the user's explicit request after implementation; required native verification remains pending.
