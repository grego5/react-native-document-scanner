# 13 — Share document and tap focus policy

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [12](12-share-live-guide-policy.md)

## Objective

Give focus decisions one portable owner while retaining platform hardware commands.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/FocusController.kt — FocusSnapshot, start, settle, unbind
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentFocusPolicy.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/DocumentFocusPolicyTest.kt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — focus snapshot consumers

## Current behavior and invariants

Document focus runs independently of capture waiting; tap priority lasts five seconds; target loss releases after one second; focus generations change on start/settle/release.

## Implementation steps

1. Move document/tap ownership, retries/deadlines, learned-position lifecycle, and snapshot generation into portable policy state, following Task 02's timing convention: tap priority, retry, and release deadlines are returned to the adapter, which owns their wakeups.
2. Leave CameraX FocusMeteringAction, Samsung-specific retrigger eligibility, diopter parking, and callback interpretation in Kotlin.
3. Make platform scan completion an event with generation and known/unknown success; do not require Apple to fabricate an Android success flag.
4. Remove mirrored ownership fields from the adapter after confirming every hardware callback maps to the policy owner.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Cover tap takeover/expiry, document reacquisition, failed/unknown scans, size/sharpness changes, capture hold, stale completion, and unbind.
- Preserve Android's 30 cm initial parking and learned diopters; reserve iOS learned lens positions for its adapter.

## Validation commands

- Run shared build and ctest, including migrated document/tap focus assertions.
- npm run check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android focus actions/snapshots derive from shared policy without changing device behavior.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share scanner focus ownership and policy

