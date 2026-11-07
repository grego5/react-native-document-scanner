# 33 — Validate numerical parity, bounded capture, and release readiness

[Back to task index](../TASKS.md)

Status: Validation in progress
Complexity: High
Dependencies: [16](16-verify-android-engine-migration.md), [32](32-integrate-ios-example.md)

## Objective

Produce concrete Android/iOS correctness and resource evidence for release.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/development.md
- [Model export and private fixtures](../.agents/skills/docs/references/tools.md#model-export-and-private-fixtures)
- tests/scanner-contracts.test.cjs
- TASKS.md — completed task dependencies and explicit platform differences

## Current behavior and invariants

Shared algorithms/contracts are required; identical camera hardware output and JPEG bytes are not. Performance improvements require measurements.

## Implementation steps

1. Establish the exact reviewed Android baseline and iOS revisions, public specs, generated output and documented platform differences. Verify reproducible generation and clean-consumer package contents/dependencies/resources. Do not use historical parking or no-shutter-scan behavior as the parity baseline.
2. Run the required core/JS/native checks, separating host-runnable policies/fixtures and compilation on the Mac VM from simulator integration on GitHub Actions. Reuse matching compiled products only through the verified workflow; report test counts, failures, skips and toolchain identity. A build, zero executed tests or a compiled instrumented suite is not a pass.
3. Compare tensor, corner and pre-encoding image fixtures using existing thresholds. Run shared capture decision sequences for admission, retries, post-scan frame handoff, optional focus, light readiness and cancellation. Test platform adapters separately: Android AF/AE/AWB ZSL and light fallback do not become fabricated iOS evidence.
4. On Android hardware, validate the light-driven one-restart fallback, delayed return to zsl on an ordinary restart, explicit capture modes, boost refusal/unsupported cases, torch deferral and stale completion rejection. On iOS 18 and a current iOS release, validate the documented focus/readiness rule, tap/quality/dark/stale-focus scans, motion waits, deferred controls and interruption using human shutter taps. Carry forward unmet device checks from Tasks 25–31; no earlier implementation label waives them.
5. Verify still dimensions obey both caps before capture and square/orientation framing agrees through review, crop, thumbnails and exports. Measure the shipped source format against the alternative from Task 04 end to end: capture-to-review latency, peak resident memory, disk size and repeat-edit cost. Record actual device/configuration and method; return materially contradictory measurements for architecture review rather than silently changing source policy.
6. Exercise a 50-page session with navigation/re-edit and repeated background/remount, interruption and close/replacement. Check bounded source memory, actual disk cost, one physical capture slot, resource cleanup and no stale installs/events.
7. Update release/maintainer docs only for implemented supported behavior. Keep Android-only features, iOS unknown focus evidence, deferred sampling and missing hardware results explicit. Do not mark release readiness complete until required physical evidence exists.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Cover native/JPEG paths, unsupported sample failures, bounded resolution on high-megapixel hardware, model sharing, interruptions, TIFF reopen, PDF/PNG semantics, and janitor ownership.
- Use human shutter taps only and keep all private photos/reports under ignored .local/.

## Validation commands

- npm run check; npm run test:native; shared CMake/ctest and the affected Android instrumented suites on a reachable device.
- Verified Mac VM host tests and package/test-product builds; GitHub Actions simulator integration/full-suite runs with matching Xcode/products when using the split workflow. Use actual active vmac capabilities and report exact commands/run IDs; no assumed runner/profile names or test counts.
- Human-triggered physical Android/iPhone/iPad scenarios as applicable, with OS/device, method, results, skipped prerequisites and measurement artifacts recorded separately. Keep private photos/reports under ignored .local/.
- git diff --check. Report generated-output whitespace separately if it originates in the generator; do not hand-edit generated files to hide it.

## Completion criteria

Required platform evidence passes, package is usable from a clean consumer install, and documented limitations match actual implementation.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Validation checkpoint — 2026-11-07

The Windows checks used the clean combined Android/iOS source at `8c246fb36320b6e9f9e6599005be74ff1ae3d883`. Task 16's Android migration evidence is historical. The current camera policy includes the adaptive pacing changes in `3a328c5b`.

| Check | Result |
| --- | --- |
| `npm run check` with Node 26.5.0 and npm 12.2.2 | Library and example typechecks passed. JS contracts passed 47/47 with zero skips. |
| `npm run nitrogen` | Nitrogen 0.37.2 generated eight Hybrid Objects and left the working tree clean. |
| CMake Release rebuild and `ctest --test-dir .local/build/core-tests -C Release --output-on-failure` | Passed 9/9 after initializing the MSVC x64 environment and adding the OpenCV 4.12 DLL directory to PATH. Used CMake 3.30.3 and Visual Studio Build Tools 18.9.2. |
| `npm pack --dry-run --json --cache .local/task33-npm-cache` | Listed 689 entries, including the model, Nitro config and native build metadata. Manifest at `.local/task33-pack.json`. Clean consumer installation and native linking remain pending. |
| `npm run test:native` | Gradle 9.4.1 stopped in settings before executing tests. Gradle rejected the React Native plugin subproject directory. A follow-up filesystem check confirmed it exists. |
| Android instrumentation and physical camera | `adb devices` listed zero devices. |
| iOS native and simulator | vMac deferred by request. GitHub CLI reported invalid authentication. No Xcode results or simulator run ID recorded. |

Cross-platform adapter parity, physical capture and lifecycle checks from Tasks 25–32, still-dimension and framing checks, paired source-format measurements, and the 50-page resource exercise remain open. The host fixture results cover the shared core only.

## Proposed commit title

docs: record iOS scanner parity and release validation

