# 24 — Implement the iOS crop editor

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [05](05-share-image-geometry.md), [08](08-share-document-filters.md), [19](19-implement-ios-source-store.md), [21](21-implement-ios-processor.md)

## Objective

Edit normalized upright corners with native handles and filter previews.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentCropEditor.nitro.ts
- src/DocumentCropEditor.tsx
- src/ScanSession.nitro.ts and Task 21's native page-binding/mutation contract
- android/src/main/java/com/margelo/nitro/documentscanner/CropEditorView.kt
- android/src/main/java/com/margelo/nitro/documentscanner/CropCornerEditing.kt
- android/src/androidTest/java/com/margelo/nitro/documentscanner/CropEditorViewTest.kt

## Current behavior and invariants

Bind a native session/page ID and initialize the local draft from its saved corners when the bound page changes. Drags and none/auto presets change only that draft; getCorners reads it and submit validates/applies it through the session. Cancel/unmount sends no page mutation. Emit initial and changed none/auto/custom classification, with full frame taking precedence over an equal auto candidate. Invalid submitted corners are rejected, not silently repaired.

## Implementation steps

1. Implement native handle/edge dragging through shared crop geometry, constant-screen-size handles, magnifier, pinch/pan up to 5×, and double-tap fit.
2. Read TIFF/JPEG/PNG originals through Task 21's leased session-page binding over Task 19; keep normalized upright coordinates independent of display transform. Removal/close invalidates the binding and draft operations.
3. Compute full-photo filter previews off main at the existing 2400-pixel cap; retain current image and discard outdated filter/photo results.
4. Keep onLoad dimensions and error behavior consistent; release images/observers and invalidate work on detach/disposal. Implement current submitResult/getCornersResult/setPresetResult transport without adding corners props or JS draft ownership.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Test corner and edge clamps, invalid drags, prop replacement, magnifier geometry, zoomed edits, orientation, stale previews, and failed loads.
- Assert filter changes never modify the saved original or implicitly commit crop edits.

## Validation commands

- npm run check
- Run shared geometry tests and iOS crop-editor integration tests.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Crop interactions, source formats, previews, and callback timing match the contract.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native document crop editor

## Implementation report — 2026-11-05

Changed files:

- `ios/CropEditorView.swift`: UIKit editor over a contain-fitted upright photo. The
  photo is a `UIImageView` and the outline, handles, edge grips and magnifier are
  layers, so a drag only moves layers. Corner and edge drags snap the grabbed handle
  under the finger, keep the last valid corners and publish once on release. The first
  finger picks handle or viewport for the whole sequence. Two-finger pinch and pan
  count translation once, up to 5x, and the pan stops at the photo's edges. A double
  tap fits again, and a handle press breaks the tap sequence. Handles keep their
  screen size. The magnifier sits above the finger, or beside it away from the
  nearer edge, glides on a display link, and scales `max(3 / zoom, 1.5)`. Touch
  entry points take IDs and times, so tests drive them without synthesized
  `UITouch`es.
- `ios/CropCornerEditing.swift` and `ScannerCropGeometry` (in
  `ios/ScannerDocumentRender.h/.mm`): move, clamp and editor validity through the
  shared `moveCorner`, `moveEdge`, `clampToFrame` and `validCropCorners`. Validity also
  requires finite coordinates inside the photo, matching Android's editor check.
- `ScannerDocumentRenderer.filterPreview`: draws the photo into opaque RGBA8 at most
  2400 on its long side, runs the shared `filterDocument` without shadow removal, and
  returns an independent 32-bit image.
- `ios/HybridDocumentCropEditorView.swift`: Nitro boundary ported from Android. It
  binds the original through `SessionPageBinding` only while attached, and decodes
  JPEG/PNG/TIFF upright at most 4096 on the session image worker. `onLoad` reports
  the decoded size. Filter previews run on the same worker, keeping the current
  image meanwhile. Load and filter generations discard outdated results. Presets
  classify none, auto or custom with full frame first. Methods run on main. Detach
  releases the image and pin; unmount, removal and close invalidate draft
  operations; only `submit` mutates the page.
- `ios/DocumentRenderService.swift`: `filterIndex` is internal for the preview.
- `ios/Tests/CropEditorTests.swift`: 17 tests covering:
  - corner snap and clamp, edge clamp and last-valid corners, invalid drags and corners,
    and replacement cancelling a drag
  - touches that start before the photo is shown
  - two-finger pan, simultaneous pinch and pan, the zoom cap, and double tap with a
    handle break
  - handle screen size and zoomed edits
  - magnifier placement and magnification
  - the ported Android session-draft test, the auto preset without a candidate, and
    prop/session/page replacement
  - EXIF-rotated TIFF/PNG/JPEG originals, failed loads, and the capped off-main
    filter preview leaving the page and original bytes unchanged
  - stale filter and photo results
  - detach, reattach and removal
- Docs: architecture reference (per-platform editor ownership and the iOS
  saved-crop rule) and `ios/README.md`.

Decision: a saved crop outside the photo, which renders allow, is invalid input, as
on Android. A Swift `afterUpdate` cannot throw, so iOS reports it through `onError`
and shows the clamp (or the full frame when even that is invalid) as a suggestion.
`getCorners` and `submit` reject, and no preset is classified, until an edit or preset
replaces it, so opening the editor never changes the saved crop on its own.

Review follow-up (same day):

- The out-of-frame crop handling above replaced an earlier version in which the clamp
  became the draft and `submit` could save it without any user choice.
- Clearing the photo (detach, unmount, removal) now cancels the touch sequence in
  progress: a drag returns to its starting corners, the magnifier's display link stops,
  and further moves are ignored until every finger lifts. Handle moves also ignore an
  empty content rect.
- A touch held longer than 0.5 s is not a tap, so a long press never starts a double
  tap.
- Tests: the prop-replacement test covers the suggestion, the rejected reads and
  submits, and choosing by preset and by edit. The lifecycle test removes the page
  during a corner drag. The double-tap test covers a long press.
- Checks: `npm run check` 41 passed; vmac `41968072125` CropEditorTests 17/17 passed.

Checks:

- `npm run check`: 41 passed.
- vmac (GitHub Actions `ios-validation.yml`, `macos-15-intel` simulator, scheme
  `ReactNativeDocumentScanner-Unit-ScaffoldTests`):
  - `41920803102`: simulator discovery failed before the build (infrastructure).
  - `41921895589`: build error, missing explicit `self` in a closure; fixed.
  - `41923892078`: 29 tests, 2 failing on wrong expectations. A press on a moved
    corner re-snaps with float rounding, and a page without an explicit crop takes
    the analysis candidate. Tests corrected. DocumentRender 7/7 and Scaffold 5/5
    passed.
  - `41927369595`: 54 tests, 0 failures: CropEditor 17, ImagePager 8, ImageViewer 12,
    ScanSession 17.
- Not run:
  - Shared C++ geometry tests: the code is unchanged; the bridge only wraps it.
  - Physical-device touch feel, magnifier glide and pinch feel.
  - Android: its code is unchanged.

Limitations:

- The editor handles raw touches. Inside a scrolling parent, UIKit's pan
  recognizer can still claim a drag; Android's `requestDisallowInterceptTouchEvent`
  has no direct counterpart.
- Magnifier gliding is time-based and was checked only for its first-frame
  placement.
