# 01 — Establish reusable Android parity fixtures

[Back to task index](../TASKS.md)

Status: Planned
Complexity: Medium
Dependencies: None

## Objective

Record the observable Android baseline before moving production algorithms.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/architecture.md — Images, Capture quality, Live guidance, Document focus
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/DocCornerNetRuntimeTest.kt — runtime fixture assertions
- android/src/test/java/com/margelo/nitro/documentscanner/CaptureGateTest.kt and CaptureStabilityTest.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/LiveQuadTraceReplayTest.kt
- tools/models/README.md — private fixture preparation

## Current behavior and invariants

The bundled model is 224×224 float32 RGB with nine outputs. Photo tensor error is bounded by the existing 2e-5 assertion; photo corner fixture limits are mean .005 and worst .015.

## Implementation steps

1. Inventory existing pure-policy, instrumented image, and trace cases; map every later migration to named baseline cases.
2. Create portable synthetic fixtures for orientation, strided pixels, quads, policy events, filters, gray PNG, and PDF placement under tests/fixtures; keep private images and reports in ignored .local/.
3. Add an Android export harness only where existing tests cannot expose engine-boundary inputs and expected outputs. Do not record new captures automatically.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Each fixture records dimensions, format, coordinate space, expected results, and applicable tolerance.
- Show which cases run without private assets and report private-fixture skips explicitly.

## Validation commands

- npm run check
- npm run test:native -- -Test '*CaptureGateTest,*CaptureStabilityTest,*LiveQuadStabilizerTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Portable fixture inventory and Android baseline assertions exist; unavailable device runs are identified without claiming parity.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test: establish cross-platform scanner parity fixtures

