# 10 — Add a session-bound page thumbnail

[Back to task index](TASKS.md)

Status: Implemented — native verification pending
Complexity: Medium
Dependencies: [09](09-bind-scanner-views.md)

## Objective

A lightweight PageThumbnail follows native page renders while React owns layout, selection and dragging.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `example/components/PageStrip.tsx: tile presentation and app-owned interaction`
- `android/src/main/java/com/margelo/nitro/documentscanner/ImageLoader.kt: thumbnail loading and bitmap lifetime`
- `android/src/main/java/com/margelo/nitro/documentscanner/PageThumbnails.kt`
- Task 09's native page bindings and resource leases
- `src/ImageViewer.tsx: native view wrapper pattern`
- `nitro.json: view autolinking`
- `README.md: usage examples`

## Current behavior and invariants

This checkout's JS PageStrip uses Image tiles and selection. The user's working app may also contain offset-based dragging, orderVersion handling and a count shared value; preserve those when present. Do not replace the app strip with a native container or introduce native ordering.

## Implementation steps

1. Add src/PageThumbnail.nitro.ts, src/PageThumbnail.tsx, HybridPageThumbnailView.kt and PageThumbnailView.kt. Expose session, pageId, style and resizeMode ('cover' or 'contain', default 'cover'). No JS load, error, selection or reorder callbacks; processing status comes from the session snapshot.

2. Reuse Task 09's native page binding: show the capture-frame placeholder until the first thumbnail, then the latest thumbnail. Keep the displayed image until its replacement is ready. Subscribe directly to native page render changes, without JS snapshot-driven image replacement.

3. Use a thumbnail-only decode path bounded by the tile's measured pixel size. Reuse available small thumbnails and never request the pager's preview/full-resolution decode. Rebind safely on size/session/page changes, discard stale decode completion and release subscriptions/file leases on detach or disposal. Preserve bitmap draw safety while an old image is still displayed.

4. Render empty for removed or unknown page IDs and closed sessions, without emitting an error. Clear an old page's image immediately when rebound to an unknown ID; a late completion cannot restore it. Keep an old image during replacement only for the same existing page.

5. Register the view and regenerate Nitro bindings. Add PageThumbnailViewTest for placeholder handoff, replacement, fit, size-bounded decoding, missing-page behavior and lifetime safety.

6. Add a short README example composing PageThumbnail inside a React FlatList/grid or Pressable tile. Demonstrate that order, selection, badges and dragging belong to the host; do not expose internal image URIs. Task 12 migrates the example strip's tiles.

## Ownership and boundary rules

React owns arrangement, order, selection, gestures, labels and overlays. The native thumbnail owns only page-image observation, decoding and display. No URI props or persistent duplicate ordering in the library.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Placeholder appears before the first thumbnail; same-page replacement keeps the previous image until ready; page render updates do not require a React tile render; cover/contain respect tile bounds. Unknown/deleted pages clear without errors; rebind/close/detach prevents stale publication and releases leases. A 50-tile strip uses size-bounded thumbnail decodes and never requests full-resolution images. App badges remain driven by snapshot metadata.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run nitrogen; npm run typecheck. Authorized device command: npm run test:device -- -Device <verified-adb-id> -Class com.margelo.nitro.documentscanner.PageThumbnailViewTest

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

A lightweight PageThumbnail follows native page renders while React owns layout, selection and dragging.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(views): add session-bound page thumbnails

## Implementation evidence - 2026-10-31

- Added PageThumbnail specs/wrapper, HybridPageThumbnailView and PageThumbnailView, public exports and Nitro autolinking. Props are session/pageId/resizeMode plus standard host view props, with cover default. There are no image load/error, selection or ordering callbacks.
- Reused SessionPageBinding with thumbnail-only source selection: current native thumbnail, capture-frame placeholder, or empty. The original and pager preview/full decode paths are not used. Render changes reach the tile without JS snapshot subscriptions. README demonstrates Pressable/FlatList composition with host-owned IDs/order, selection, drag handling and snapshot badges; the example strip remains unchanged for Task 12.
- PageThumbnails.loadTile reuses small shared cache pixels or creates a measured-size scale/file decode on the shared serial image worker. Same-page replacements keep displayed pixels until ready. Size/session/ID changes invalidate queued completion; unknown/deleted/closed bindings clear silently. Read pins survive cancellation until queued/running work finishes; detach/dispose releases subscriptions and display pins. Shared/published bitmaps are never recycled; never-published private stale results are recycled.
- Added PageThumbnailViewTest with synthetic pixels and controlled workers: placeholder-to-thumbnail handoff without React updates, cover/contain, measured-size changes, missing-frame behavior, unknown/deleted/closed pages, session replacement, detach/dispose, close waiting for queued read pins, private/shared stale bitmap safety, and 50 size-bounded tiles reading only small sources. Native tests remain unrun.
- `npm run nitrogen`: passed, 8/8 specs generated. `npm run typecheck`: passed. `npm run test:contracts`: passed, 35/35 after updating facade test imports for the new component. Added a thumbnail composition contract for native session identity, host style/accessibility, defaults, rebinding and typed invalid-session rejection.
- `npm run typecheck:example`: expected failure until Task 12, unchanged from Task 09: missing camera session, removed CapturedPhoto import, old ReviewPanel uris/originalUri props. No compatibility aliases were added.
- `git diff --check`: passed for tracked changes. New-file checks found only Nitrogen whitespace in JHybridPageThumbnailViewSpec.cpp:82, JHybridPageThumbnailViewSpec.hpp:62, HybridPageThumbnailViewSpec.kt:35/41/49 and HybridPageThumbnailViewSpec.hpp:63. Generated files were not hand edited; authored whitespace checks passed.
- LSP returned no diagnostic items for changed production Kotlin and PageThumbnailViewTest; its limited coverage does not establish native compilation. Native builds, JVM/device tests and physical camera work: NOT RUN because native build authorization is withheld. Status updated at the user's explicit request after implementation; required native verification remains pending.
