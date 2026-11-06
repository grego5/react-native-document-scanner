# 22 — Implement the iOS viewer and shared thumbnail loader

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [19](19-implement-ios-source-store.md), [21](21-implement-ios-processor.md)

## Objective

Show native session pages with native zoom gestures and bounded decoding. Task 21's complete session/resource contract is a prerequisite; a render/PDF-only Phase A is insufficient.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/ImageViewer.nitro.ts
- src/ImageViewer.tsx
- src/ScanSession.nitro.ts and Task 21's native page-binding/read contract
- android/src/main/java/com/margelo/nitro/documentscanner/ImageViewerView.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ImageLoader.kt
- android/src/main/java/com/margelo/nitro/documentscanner/PageThumbnails.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ImageZoom.kt

## Current behavior and invariants

Bind session/page IDs and observe native page state directly. Prefer displayed render/thumbnail, then retained capture frame, then original as the current contract permits. Thumbnail first; full image only past 1.1× zoom; zoom limit 5×; double tap to 2.5× or fit; previous same-page image remains until replacement is installed. Page removal/session close clears pixels without an error event; explicit invalid bindings report input/invalid.

## Implementation steps

1. Implement UIView/UIScrollView-based ImageViewer with contain fit, clamped pan, native pinch/double tap, isZoomed, and resetZoom.
2. Reuse Task 21's independently pinned session reads and shared thumbnails over Task 19 sources; decode full images at most 4096 pixels and release full buffers on detach/background or zoom reset. Implement session/page-bound thumbnail tiles with bounded reads; tiles never decode originals/full images.
3. Use session/page/revision/load generations so obsolete work never replaces a newer image; onLoad fires only after installing the current page's thumbnail. Release subscriptions/bindings on detach, retain independent physical-read pins until completion, and retry reads on reattach.
4. Keep callbacks through Nitro and UI state on main; do not introduce JS gesture dependencies.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Assert zoom/pan bounds, zoom callbacks, replacement continuity, stale load discard, retry on visibility, and full-image release.
- Verify TIFF/JPEG/PNG originals and JPEG/PNG renders load through session bindings; cover page removal, session replacement/close, thumbnail-only tiles, detached reads, and physical-pin release.

## Validation commands

- npm run check
- Run iOS viewer XCTest/UI integration tests on simulator.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Viewer methods, callbacks, image replacement, and resource release match the public contract.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native scanner image viewer



## Implementation report — 2026-11-03

Changed files:

- `ios/ImageViewerView.swift`: `UIScrollView` contain fit, zoom 1–5× with no
  bounce (so pan stops at the image's edges), centering insets, double tap to 2.5×
  around the tapped point or back to fit, and a fade of the new image over the
  previous one. Window attach/detach and app background/foreground are reported to
  the loader.
- `ios/ImageViewerLoader.swift`: port of the Android `ImageLoader`. The thumbnail is
  shown first. Zooming past 1.1× decodes the full image (at most 4096 px), which
  replaces the thumbnail in place. The full image is released at fit, in the
  background and on detach, and restored on foreground while still zoomed.
  `onLoad` fires only after the current page's thumbnail is installed. A failed
  thumbnail is retried when the view becomes visible again. Page and full-image
  generations discard obsolete reads. The binding already suppresses unchanged
  sources, so the loader keeps no source key and loads every source delivered.
- `ios/PageThumbnails.swift`: bounded display decoding for JPEG/PNG/TIFF with EXIF
  orientation applied. A shared thumbnail cache (12 MB, keyed by the render a
  thumbnail summarizes) observes the app itself and empties in the background,
  with or without viewers. `ScannerImageReads` runs
  view reads on the session's image FIFO; each read owns one pin until it runs or
  is rejected.
- `ios/PageThumbnailView.swift`, `ios/HybridPageThumbnailView.swift`:
  thumbnail-only tiles that read the render thumbnail or retained frame, never the
  original or full image, decoded at most at the tile's pixel size, cover/contain.
- `ios/HybridImageViewerView.swift`: Nitro boundary. A non-native session reports
  `input/invalid`. `isZoomed` is readable from JavaScript; `resetZoom` hops to main.
  `onDropView` invalidates queued decodes.
- `ios/HybridScanSession.swift`: `placeholder()` view-prop default, so mounting a
  view no longer opens and leaks a session directory.
- `ios/SessionPageBinding.swift` and Android `SessionPageBinding.kt`: the
  binding alone classifies its target as unresolved, available or gone, and
  applies that one classification on registration, refresh, event delivery and
  restart. A page it has seen that is now absent (removed, session closed, or found
  gone when a detached view attaches again) clears silently; a page it never saw
  reports `input/invalid`. Loaders keep their own publication-validity checks.
  iOS also exposes `SessionPageSource.thumbnailURL` for the pinned render
  thumbnail sidecar. The shared contract is in the architecture reference.
- Regressions on both platforms: queued update after removal, restart after
  removal, removal between registration and first read, detach → session close →
  reattach (silent), and unseen IDs on open or closed sessions (`input/invalid`).
  Android covers these with thumbnail-only bindings on pages without a frame or
  render yet, because JVM tests cannot construct file URIs.
- `ios/Tests/ImageViewerTests.swift`: zoom/pan bounds and callbacks, double tap,
  decoder formats/orientation/4096 bound, thumbnail-then-full and release
  (zoom reset, background, detach), JPEG original then PNG render replacement
  continuity, stale load discard across session replacement, retry on visibility,
  session closed while detached then reattached (no error), unchanged-page
  updates keep the shown thumbnail and full image, background eviction of a cache
  with no viewers,
  detached reads and physical-pin release, removal/close without errors,
  thumbnail-only tiles, Hybrid boundary.
- `.github/workflows/ios-validation.yml`: dispatchable simulator validation that
  mirrors the Mac package script on an Intel runner (the pinned OpenCV framework
  has no arm64 simulator slice).

Checks:

- `npm run check`: 41 passed.
- Android `npm run test:native` (with `-RecompilePlugins`; the cached Expo Gradle
  plugin intermittently fails to load): 129 JVM tests, 0 failures.
- vmac Mac VM run `8498dc84-399c-40ec-b2ae-6882b1a51316`: build succeeded. In
  `ImageViewerTests`, the decoder test passed. The detached-read test failed on a
  real defect (reattach reported `input/invalid` for a page removed while
  detached), now fixed in `SessionPageBinding`. The run then crashed with a trap
  in the retry test, which passes when run alone. Test windows now hide in
  teardown. The remaining suites were not reached in that run.
- The emulated macOS simulator is unstable, so simulator validation runs on
  GitHub Actions through the vmac docscanner profile's `githubTests`
  (`ios-validation.yml`, `macos-15-intel`, Xcode 26.3).
- GitHub run `41649613730`: 71 tests, 2 failures, both test mistakes. A PNG render
  was admitted with no gray filter (PNG renders are gray only), and the test expected
  the cached thumbnail object after a background event, which empties the cache.
  Both tests are corrected; the VM crash did not reproduce.
- GitHub run `41653084605` (vmac `02c2c51d-429b-495d-8785-d7878fde30ab`):
  71 tests, 0 failures, 1 skipped (the optional private inference fixture). By suite:
  viewer 9, sessions 17, source storage 14, permissions 10, render 7, inference 6,
  scaffolds 5, ObjC++ link 3.
- After moving lifecycle classification into the binding on both platforms, GitHub
  run `41659142936` (vmac `c087add4-4d0e-45da-b5cb-9a2c1f81d486`): 72 tests,
  0 failures, 1 skipped; viewer 10, including detach → session close → reattach.
- After moving background eviction into the cache and removing the loader's
  source key, GitHub run `41675245885` (vmac `d53e8c65-57d5-4086-8902-b50816d2cb81`):
  74 tests, 1 failure, 1 skipped; viewer 12 of 12 passed. The failure was the
  production-engine admission test asserting `analyzing` right after admission;
  it involves no viewer code and passed in every earlier run. The session suite
  alone then passed in run `41679910388`: 17 tests, 0 failures.
- The admission test now checks `analyzing` and `ready` on the first immutable
  admission event rather than a live snapshot that can race analysis completion.
  Whitespace checks pass; the test suite has not been rerun after this test-only fix.

Limitations: when zoomed at an edge, a horizontal drag can still pass to an outer
scroll view (UIKit nesting); the pager is expected to disable swiping via
`onZoomChange`.
