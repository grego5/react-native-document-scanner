# 33 — Validate numerical parity, bounded capture, and release readiness

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [16](16-verify-android-engine-migration.md), [32](32-integrate-ios-example.md)

## Objective

Produce concrete Android/iOS correctness and resource evidence for release.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/development.md
- tools/models/README.md
- tests/scanner-contracts.test.cjs
- TASKS.md — completed task dependencies and explicit platform differences

## Current behavior and invariants

Shared algorithms/contracts are required; identical camera hardware output and JPEG bytes are not. Performance improvements require measurements.

## Implementation steps

1. Run all required core, JS, Android, and iOS tests/builds, verifying regenerated output is reproducible and packed library contents contain every iOS dependency/resource.
2. Compare tensor, corner, and pre-encoding image fixtures using existing thresholds; do not widen them to conceal migration drift.
3. On iOS 18 and a current iOS release, verify every still dimension obeys both caps before capture and square framing agrees through final exports.
4. Measure the shipped source format against the alternative Task 04 compared, end to end: capture-to-review latency, peak resident memory, file sizes, and repeat-edit cost. Record the method and actual device/configuration; if the chosen default loses materially or contradicts Task 04's evidence, return that evidence for architectural review rather than changing source policy silently.
5. Exercise a 50-page session with navigation/re-edit and repeated background/remount; verify source memory does not scale with page count and report actual disk cost.
6. Update README/maintainer references only to describe implemented supported behavior. Retain unmet physical-device evidence as outstanding and do not mark the release complete.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Cover native/JPEG paths, unsupported sample failures, bounded resolution on high-megapixel hardware, model sharing, interruptions, TIFF reopen, PDF/PNG semantics, and janitor ownership.
- Use human shutter taps only and keep all private photos/reports under ignored .local/.

## Validation commands

- npm run check
- npm run test:native
- Run shared CMake build/ctest, verified Android instrumented suites, and exact Mac xcodebuild test/build commands recorded by Task 17.
- git diff --check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Required platform evidence passes, package is usable from a clean consumer install, and documented limitations match actual implementation.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

docs: record iOS scanner parity and release validation

