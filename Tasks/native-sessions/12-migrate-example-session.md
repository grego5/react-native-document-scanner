# 12 — Migrate the example to IDs and one session subscription

[Back to task index](TASKS.md)

Status: Implemented — build-free verification passed
Complexity: High
Dependencies: [07](07-publish-batched-snapshots.md), [08](08-capture-session-pages.md), [09](09-bind-scanner-views.md), [10](10-add-page-thumbnail.md), [11](11-export-page-snapshots.md)

## Objective

The app uses one session subscription and owns only app decisions; render/URI/file coordination is gone.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `example/hooks/useScanSession.ts`
- `example/lib/scanSession.ts: reducer, render state, references`
- `example/components/ReviewPanel.tsx and example/app/index.tsx`
- `example/hooks/usePhotoActions.ts`
- `src/scanner-contracts.test.cjs: real reducer/adapter fixtures`

## Current behavior and invariants

The example uses a reducer/useReducer, not Zustand. It owns draft renders and URI derivation, has a CachedImageJanitor and copies capture readiness/busy. Preserve navigation, crop Cancel/Submit, initial defaults, 50-page app limit, sample actions, gallery saving and PDF sharing.

## Implementation steps

1. Create one native session within useScanSession's committed lifecycle and close it on cleanup without awaiting. React effect replay/unmount must not reuse a closed session or leak a session created during an abandoned render. Attach exactly one subscription for the current session and seed/reconcile using published versions.

2. Replace image/render/cache fields in the plain store with session snapshot metadata and ordered page IDs. Store ignores record enumeration order. Keep selection, screen/back decisions, unsubmitted crop draft, settings and export UX lock.

3. Shutter availability/progress comes from onCaptureStateChange; keep only app policy disables such as page limit/export. Pass saved filter/shadow plus existing A4/200dpi output settings in takePhoto. Add the returned ID to the React order; a capture completing after scan replacement must not mutate the new scan.

4. Wire native viewer/pager with session and IDs. Keep the JS PageStrip and replace each Image/thumbnail URI tile with PageThumbnail(session, pageId), using resizeMode="cover". Remove thumbnailUri/pageThumbnailUri plumbing. Preserve movePage/selectIndex, offset-based dragging, orderVersion and count shared-value behavior wherever present at implementation time; order and selection remain in the store. Image render updates flow directly to the native tile; React tile updates are limited to actual presentation/interaction changes. This checkout currently has selection-only tiles: do not invent or move dragging into the library.

5. Keep No crop and Auto crop as draft updates; Submit calls setCorners once, Cancel calls no page command. Filters/shadow commands update native pages immediately; Back no longer discards edits to existing pages.

6. Switch PDF/gallery/sample actions to Task 11 APIs. Delete CachedImageJanitor and referencedUris/render-cache/URI-derivation helpers and JS corners-validation/clamping logic once no callers remain; retain FULL_FRAME and only genuinely necessary UI equality logic. Exported gallery copies are host-owned artifacts: extend example/lib/pdfExports.ts with unique image-export names and the existing seven-day startup retention policy, excluding active native sessions.

7. Remove capture busy/readiness copies and any preview subscriptions/promise chains. Update real reducer/store tests with a mocked session including subscription and close behavior.

## Ownership and boundary rules

This is a plain app-state store, not a new global ScannerRuntime. JS metadata snapshots are presentation mirrors only, never processing truth. Do not restore stale-render guards or aliases to make old tests pass.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Capture defaults and returned-ID insertion; Back/Cancel/Submit; immediate saved edits; reorder/delete selection; old-session events ignored; reset/unmount/StrictMode session cleanup; export UX lock and share/gallery errors. Existing workflow tests are rewritten to outcomes, not deleted wholesale.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run check. All example typecheck failures disclosed in Tasks 08-10 must be resolved here. No native builds or unattended photos.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

The app uses one session subscription and owns only app decisions; render/URI/file coordination is gone.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

refactor(example): use session-owned scanner pages

## Implementation evidence (2026-10-31)

- Replaced JS image/render/cache coordination with a resource-free plain ScanStore and a committed useScanSession lifecycle. Exactly one current-session subscription seeds/reconciles published versions; replay, reset and unmount unsubscribe and close each owner without awaiting. Old-session events and capture completions are ignored.
- React owns ordered IDs, selection, screen decisions, crop drafts, the 50-page policy, default settings and the export UX lock. Camera capture state supplies readiness/progress, and takePhoto snapshots A4/200 dpi, Original filter and the saved shadow default.
- Camera, retained ImagePager, CropEditor and memoized PageThumbnail tiles bind the same native session and IDs. Selection-only strip behavior is retained; no drag UI or library API was added. Crop Cancel sends no command, Submit sends setCorners once, and filters/shadow changes persist immediately across Back.
- PDF, gallery and capture-sample actions use Task 11 ID APIs. Gallery copies use unique host export directories and the existing seven-day startup retention policy, outside native session namespaces. Stale sample/export UI callbacks are suppressed; failures release the app export lock.
- Deleted CachedImageJanitor, referenced URI/render-cache helpers, URI derivation and JS corners clamping/validation. Only FULL_FRAME and UI outline equality remain.
- Rewrote obsolete reducer/render tests as real store/hook/UI workflow tests with mocked sessions and filesystem/gallery/share/camera boundaries. Coverage includes abandoned render, Effect replay, reset/unmount, read-register-read reconciliation, version ordering, default capture options and returned IDs, crop Cancel/Submit, saved edits, ordering/deletion, page limits, export overlap/errors, permission denial, stale callbacks and host export retention. All camera test calls use a fake camera; no physical capture was requested.

Validation:

- npm run check: passed library typecheck, example typecheck and all 36 JS contracts. The staged Tasks 08-10 example type errors are resolved.
- git diff --check: passed after removing one extra EOF blank line.
- Focused example search: no original/thumbnail URI plumbing, legacy analyze/render calls, janitor or JS corners validation/clamping remains.
- LSP returned no scanSession.ts diagnostics, but incorrectly parsed ReviewPanel.tsx as non-JSX TypeScript and reported cascading JSX syntax errors. The repository's actual example TypeScript check passed; LSP is not counted as validation evidence for TSX.
- Native builds, JVM/device tests and human camera/gallery/share checks: not run; no native build or device work was authorized. Build-free checks do not establish native runtime correctness.

React implementation pass complete for the example lifecycle, state ownership, native view binding and export flows. No optimization or unrelated cleanup pass was performed.
