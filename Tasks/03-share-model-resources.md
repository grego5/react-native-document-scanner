# 03 — Move the canonical model into shared resources

[Back to task index](../TASKS.md)

Status: Planned
Complexity: Medium
Dependencies: [02](02-introduce-portable-core.md)

## Objective

Give both platforms one canonical copy of the corner model, its provenance, and license, with Android behavior unchanged.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions. Do not add iOS packaging; Task 17 bundles these resources.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/build.gradle — asset source sets and SHA-256 verification
- android/src/main/assets/doccornernet/ — model, provenance.json, license
- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerModels.kt — bundled asset lookup
- tools/models/README.md, prepare_doccornernet_runtime.py, export_adapted_doccornernet.py — regeneration output paths
- package.json — published files

## Current behavior and invariants

The build verifies the bundled model's SHA-256; Android loads it as an APK asset; tools/models regenerates it. The bundled input is 224×224.

## Implementation steps

1. Move the model, provenance, and license to one shared resource directory included in the published package.
2. Point Android's asset packaging, hash verification, and model lookup at it, keeping the APK asset path the runtime reads.
3. Update tools/models output paths and documentation in the same change; correct any comment that states a different input size.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- The Android build fails on a modified model and passes on the canonical bytes.
- The built APK contains byte-identical model bytes; the packed npm tarball contains the shared resources once.

## Validation commands

- npm run check
- Build the Android library using the documented development procedure.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

One canonical model copy exists; Android packaging, hash checks, runtime loading, and regeneration tools use it.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

build: move the corner model into shared resources
