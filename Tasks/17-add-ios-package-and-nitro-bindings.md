# 17 — Add a compilable iOS package and Nitro registrations

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [04](04-verify-ios-feasibility.md), [16](16-verify-android-engine-migration.md)

## Objective

Make the library install and build on iOS 18+ with all generated registrations aligned.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- package.json — package files and Nitro versions
- nitro.json — six Android-only autolinking registrations
- react-native.config.js — ios:null
- src/*.nitro.ts — platform mappings
- C:/Users/User/.codex/skills/react-native-modules-development/references/nitro/docs/getting-started/how-to-build-a-nitro-module.md — generated iOS podspec integration

## Current behavior and invariants

Public names remain Camera, DocumentScanner, CropEditor, ImageViewer, ImagePager, and CameraPermission. Bindings are generated from TypeScript only.

## Implementation steps

1. Add ReactNativeDocumentScanner.podspec, ios/ Swift/Objective-C++ sources, generated autolinking integration, shared C++ sources, and package files; enable iOS dependency autolinking.
2. Add ios:'swift' to six specs and matching Swift classes/registration keys, using generated base classes.
3. Add feature/unsupported transport. Until later tasks supply functionality, make unfinished operations explicit typed failures; report the scaffold as incomplete, never release-ready.
4. Package the OpenCV and TFLite integration Task 04 verified, with the slices and versions it recorded; re-verify linkage in the library build.
5. Bundle the shared model resources from Task 03 into the iOS package.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Regenerate and compile both platforms; instantiate registered objects and mount all four view types without unresolved native symbols.
- Verify the iOS bundle contains the exact shared model bytes Android verifies.

## Validation commands

- npm run nitrogen
- npm run check
- On a verified Mac, install pods and run xcodebuild build-for-testing for a verified library test-host scheme; record exact workspace, scheme, SDK, and destination.
- Build the Android library using the documented development procedure.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Package installs/builds on iOS 18+ and Android still builds; dependency slices and source generation are verified.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

build: add iOS Nitro scanner package

