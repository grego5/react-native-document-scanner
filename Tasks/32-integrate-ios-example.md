# 32 — Integrate the complete scanning example on iOS

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [21](21-implement-ios-processor.md), [23](23-implement-ios-image-pager.md), [24](24-implement-ios-crop-editor.md), [30](30-implement-ios-capture-feedback.md), [31](31-verify-ios-lifecycle-and-resources.md)

## Objective

Run the existing React scan workflow on iOS without separate session logic.

## Non-goals

Do not create separate iOS session/navigation state or duplicate native readiness in React. Refactor existing example wiring where needed for the unified workflow, preserving Android behavior. Do not add SwiftUI, a second media/share stack, Android-only camera capabilities on iOS, unrelated optimization or handwritten generated changes.

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

1. Verify and reuse the iOS bundle identity and camera usage text already added by Task 25; complete only missing deployment, photo-library add permission and supported Expo development/test configuration. Do not overwrite existing app settings with an older scaffold.
2. Keep the existing scan-session owner, reducer, metadata subscription, crop submit/cancel workflow and export retention. React owns navigation/defaults and the operation UX lock; native code owns readiness, scan retries, physical busy state, pixels and file retirement. Use session/page IDs throughout, with no alternate iOS flow or JS image janitor.
3. Integrate Task 29/30 state and wait reasons into the existing shutter/error/feedback UI. Keep the camera mounted but inactive under review/crop/options; block conflicting operations until the authoritative capture settles. Do not mirror native scan attempts or light acknowledgements in React.
4. Preserve the current torch UX: parent-owned auto/off default, scan-local on/off override, long press changes the default, and New scan resets the override. Keep squareAspectRatio functional. Hide deferred sample controls and hide or clearly disable Android-only lowLightBoost on iOS; never imply that a no-op prop enables an iOS capability.
5. Verify native share-sheet/iPad presentation and photo-library saving through existing dependencies. Export rendered JPEG/PNG/PDF to host-owned locations outside session/source resources; internal source encoding remains private.
6. Update public platform documentation and maintainer guidance to match implemented behavior, including the iOS focus-evidence rule and physical checks still outstanding.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- JS session/interaction contracts: torch default versus scan override, New scan reset, unsupported controls, capture wait reasons/errors and blocking conflicting actions without extra native state copies.
- iOS capture→review→crop/filter→accept→navigate→PDF/share/gallery, plus tap/quality scans, dark scenes, motion rejection and retry/recovery. Check 50-page limits, unsaved-change confirmation, Add page, delete/new scan, TIFF reopen and retained-preview handoff.
- Existing Android example behavior remains intact; validate shared JS changes on both platforms.

## Validation commands

- Run npm run check for shared JS changes. Build/install the existing Expo example on the verified Mac configuration; use GitHub simulator tests for UIKit/native integration that requires the simulator.
- Perform human-triggered camera and share/gallery workflows on iPhone and iPad where supported, recording OS/device and unavailable checks. Keep native policy test, build, simulator and physical-device results separate.

## Completion criteria

One existing example session flow works on both platforms and no exported file depends on internal source encoding.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(example): enable the iOS scanner workflow

