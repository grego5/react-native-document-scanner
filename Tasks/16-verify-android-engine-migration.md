# 16 — Verify Android after the shared-engine migration

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [06](06-share-model-preprocessing.md), [08](08-share-document-filters.md), [10](10-share-pdf-writer.md), [12](12-share-live-guide-policy.md), [13](13-share-focus-policy.md), [14](14-share-torch-policy.md), [15](15-share-capture-readiness.md)

## Objective

Establish Android equivalence before iOS builds on the migrated engine.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/development.md — routine checks, device tests, hand checks
- android/CMakeLists.txt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessor.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerInference.kt
- tests/scanner-contracts.test.cjs

## Current behavior and invariants

Android public behavior, error transport, model sharing, bounded queue, disposal, sample collection, image cleanup, and export behavior remain functional.

## Implementation steps

1. Run the complete relevant JVM, core, instrumented image, and JS suites against the migrated production path.
2. Check every extracted algorithm has one production owner and no generated output was hand-edited.
3. Record Android build, device behavior, numerical parity, and timing evidence separately under .local/.
4. Keep blocking regressions attached to the migration task that introduced them; do not hide failures by widening fixture tolerances.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Validate processor queue overflow/disposal, shared model leases, render publication, thumbnail handoff, and scoped deletion.
- Use a person for all camera shutter actions; replay-only results do not establish visible guide quality.

## Validation commands

- npm run check
- npm run test:native
- npm run test:device -- -Device <verified-device-id> -Class <verified-noninteractive-class-list>

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Required Android checks pass; any unavailable physical-camera evidence is explicitly outstanding and blocks the final parity claim.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test: verify Android scanner shared-engine parity

