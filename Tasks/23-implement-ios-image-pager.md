# 23 — Implement the iOS native image pager

[Back to task index](../TASKS.md)

Status: Planned
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

