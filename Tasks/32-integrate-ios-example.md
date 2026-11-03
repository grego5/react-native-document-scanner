# 32 — Integrate the complete scanning example on iOS

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [21](21-implement-ios-processor.md), [23](23-implement-ios-image-pager.md), [24](24-implement-ios-crop-editor.md), [30](30-implement-ios-capture-feedback.md), [31](31-verify-ios-lifecycle-and-resources.md)

## Objective

Run the existing React scan workflow on iOS without separate session logic.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- example/app.config.ts
- example/app/index.tsx
- example/hooks/useScanReview.ts
- example/lib/scanSession.ts
- example/components/OptionsPanel.tsx
- example/components/ExportSheet.tsx
- example/lib/pdfExports.ts

## Current behavior and invariants

One explicitly owned native scan session supplies ordered page metadata through the existing JS adapter. React owns selection/navigation/crop-screen state and the operation UX lock; native code owns pixels, processing, and file retirement. Camera remains mounted/inactive under review; originals remain available for re-edit.

## Implementation steps

1. Add iOS bundle identity, deployment target, camera usage description, photo-library add permission, and iOS development/test commands using the repository's Expo version.
2. Keep the existing scan-session owner, reducer, metadata subscription, crop submit/cancel workflow, and host export retention. Use session/page IDs throughout React; TIFF/JPEG/PNG sources remain native-private. Export rendered JPEG/PNG/PDF to host-owned locations outside native session/source resources; do not add a JS image janitor or alternate iOS session flow.
3. Hide deferred sample controls on iOS; keep squareFrame and jpegQuality controls functional.
4. Verify native share-sheet/iPad presentation and photo-library saving through existing dependencies; do not introduce a second media/share stack.
5. Update relevant public source-format/platform documentation and maintainer architecture guidance when the implementation becomes true.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Run JS session contracts plus iOS capture→review→crop/filter→accept→navigate→PDF/share/gallery scenarios.
- Check 50-page limits, unsaved-change confirmation, Add page, delete/new scan, TIFF reopen, and retained preview handoff.

## Validation commands

- npm run check
- On the verified Mac, generate/install the iOS example using supported Expo commands and run its verified scheme.
- Perform human-triggered camera and share/gallery workflow checks on iPhone and iPad where supported.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

One existing example session flow works on both platforms and no exported file depends on internal source encoding.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(example): enable the iOS scanner workflow

