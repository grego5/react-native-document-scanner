# 23 — Implement the iOS native image pager

[Back to task index](../TASKS.md)

Status: Completed
Complexity: Medium
Dependencies: [22](22-implement-ios-image-viewer.md)

## Objective

Page through native zoomable viewer cells without JS gesture coordination.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/ImagePager.nitro.ts
- src/ImagePager.tsx
- android/src/main/java/com/margelo/nitro/documentscanner/ImagePagerView.kt
- android/src/androidTest/java/com/margelo/nitro/documentscanner/ImagePagerViewTest.kt

## Current behavior and invariants

Selection and callbacks use page IDs; indices remain native internals. Omitted pageIds follows session order; an explicit complete order updates the session. Reordering retains the selected ID unless selectedPageId changes. Zoom blocks paging; leaving a page resets its zoom; programmatic selection changes jump without animation; failed neighbors retry when shown.

## Implementation steps

1. Implement a recycling UICollectionView pager using Task 22's viewer/loader behavior.
2. Reconcile session/page-ID order and selectedPageId through Task 21's page bindings; retain same-page pixels during render replacements. Session replacement clears old bindings; unknown configured IDs report input/invalid, while observed removal/close clears pixels without choosing a replacement selection for the host.
3. Give native gesture arbitration one owner: zoomed viewer pans; fitted pager swipes when swipeEnabled is true.
4. Publish settled user page IDs and shown-page load/errors through existing callback semantics; recycle/offscreen cells release full images.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Cover index clamp, insert/remove/replace pages, zoomed-swipe blocking, programmatic navigation, empty lists, failed neighbors, and cell reuse.
- Assert image memory stays bounded as many pages are visited.

## Validation commands

- npm run check
- Run focused iOS pager integration tests.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Native paging and zoom interaction match Android behavior and public callbacks.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add native scanner image pager

## Implementation report — 2026-11-03

Changed files:

- `ios/ImagePagerView.swift`: recycling `UICollectionView` pager whose cells each hold
  a Task 22 `ImageViewerView` and `ImageViewerLoader`. Selection is retained by page ID
  across order changes, insertions and removals; a replaced session releases every old
  binding; programmatic jumps clamp the index, move without animation, report nothing
  and fit the page left. Only the shown page reports load failures, and a neighbour
  that failed retries once shown. Cells leaving the laid-out window release their
  binding and images, so memory stays bounded however many pages are visited.
- Gesture arbitration has one owner: the pager turns collection scrolling off while
  the shown page is zoomed (its drags pan) and on at the fit when `swipeEnabled`.
- Neighbour preloading: UICollectionView only realizes cells inside its own bounds,
  so the collection view spans one page beyond each side of the clipping pager (the
  counterpart of Android's extra layout space). Being wider than a page, it snaps one
  page per swipe in `scrollViewWillEndDragging` instead of `isPagingEnabled`.
- Direction: a small custom `PageLayout` computes page frames and mirrors them for
  `rtl`, or for `auto` in a right-to-left app (following trait changes).
  `UICollectionViewFlowLayout` with `flipsHorizontallyInOppositeLayoutDirection`, and
  then mirroring layered on the flow layout, both left page 0 on the left in the
  simulator, so neither UIKit flipping nor the flow-layout cache is relied on.
- `ios/HybridImagePagerView.swift`: Nitro boundary ported from Android. Optional
  `pageIds` applies a complete order to the session; omitted, the pager follows the
  session order. Session observation refreshes on order, removal, close and new pages;
  unknown selections, unknown orders and non-native sessions report `input/invalid`;
  observed removal or close clears without errors or a replacement selection.
- `ios/Tests/ImagePagerTests.swift`: index clamp and empty lists, programmatic jumps
  while zoomed, zoom/`swipeEnabled` swipe blocking, settled swipe reporting, one-page
  snapping, order/insert/remove/session replacement, failed neighbours, cell reuse and
  bounded image memory over 12 zoomed pages (including laid-out neighbours), RTL
  mirroring, and two Hybrid scenarios ported from Android's pager tests.
- `ios/Tests/ImageViewerTests.swift`: picture fixtures shared with the pager tests.
- Architecture reference: pager recycling, neighbour layout and gesture arbitration.

Checks:

- `npm run check`: 41 passed.
- Focused simulator runs through vmac (GitHub Actions, `macos-15-intel`):
  - `485df9ff`: build failed on a Swift 6 implicit-`self` error in the session
    observer; fixed.
  - `df14b0ab`: viewer 12/12 and scaffolds 5/5 passed; pager 2 tests failed (RTL not
    mirrored; neighbours not realized by `layoutAttributesForElements` expansion).
  - `1991e8c7`, `899bf7f2`: neighbour preloading passed; RTL still failed under the
    flow layout.
  - `6b5eeffc` (GitHub run `41709061643`): 25 tests, 0 failures — pager 8, viewer 12,
    scaffolds 5.
- Not run: physical-device swiping and pinch feel; Android code is unchanged.

Limitations: snapping is velocity-based in `scrollViewWillEndDragging`, so its feel
was verified only by unit assertions on the snap targets, not by hand.
