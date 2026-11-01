# 16 — Verify Android after the shared-engine migration

[Back to task index](../TASKS.md)

Status: In progress - automated Android integration verified; human camera evidence pending
Complexity: High
Dependencies: [06](06-share-model-preprocessing.md), [08](08-share-document-filters.md), [10](10-share-pdf-writer.md), [12](12-share-live-guide-policy.md), [13](13-share-focus-policy.md), [14](14-share-torch-policy.md)

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


## Android optimization and validation - 2026-10-31

Completed a scoped C++/Kotlin optimization pass followed by cleanup at render/filter/PNG boundaries. Production Android now composes warp and filters on native leases, converts no-filter RGBA directly into owned RGB, and gives the shared PNG writer a synchronous native gray Mat lease. Source immutability, output ownership, file publication, FastCores and pixel budgets remain unchanged. Separate stage adapters remain for previews, diagnostics and tests.

Passed 181 Android release instrumentation tests (40.523 s), 10 rebuilt Release CTest suites, 66 JVM tests, both typechecks and 16 JS contracts. Added exact combined/split geometry/pixel tests and PNG ROI/odd-width/throwing-sink coverage. New 1536x2048 source outputs match corrected historical Kotlin exactly for all four filters. The isolated pre-C++ checkout at aca47cd needed only the already-known sharpening alias fix for that comparison; the active original worktree was not built or modified.

Release profiling includes paired same-APK boundary tests, full render/PNG runs in the isolated Kotlin checkout, and Perfetto sched/frequency/GC traces. Both measured render/worker sets ran on cores 4-7, but frequencies differed materially between revisions. Do not use the aggregate Kotlin/C++ percentages as a performance gate. Evidence and commands: .local/engine-optimization/report.md; reproducible harnesses: EngineParityTimingTest and RenderBoundaryTimingTest; tools/test-device.ps1 supports -BuildType Release -Measure.

Remaining: human camera/visible guidance evidence and any other required migration checks not covered by these selected automated suites. iOS and release-readiness claims remain deferred. No camera captures were automated. Changes were checkpointed before integrating the native-session branch.

## Native-session integration — 2026-11-01

Merged current main's native-session workflow into the shared-engine branch. Main remains the owner of ordered page membership, crop drafts, revisions, model/file leases, transactional render destinations, export snapshots and stale-publication rejection. Generated bindings and the current public API are taken unchanged from main. The prior native-session task plan is preserved under Tasks/native-sessions, separate from this shared-engine/iOS plan.

Integration preserves two newer main behaviors absent from the earlier extraction: bounded region decoding for rendering, and square-expanding photo-inference framing. RenderSourceRegion maps full-photo corners through the decoded origin and sample factor inside the shared renderer; validation remains in full-photo coordinates. Portable and JNI regressions cover identity and nonzero-origin/sample mappings. Current main's crop assertions were retained on the instrumented JNI path, including inverse mapping and invalid mapped predictions. Retired Kotlin private ink tests now verify the corrected historical maps and actual shared filter output under allocation pressure.

Validation before moving to main: both typechecks and 39 JS contracts passed; 128 JVM tests passed (18 suites, no failures/errors/skips); all 10 rebuilt Release CTest suites passed. The initial JVM invocation failed during Expo plugin output cleanup; retry proceeded, then exposed obsolete candidate-provenance references, which were fixed before the successful run. Initial instrumentation compilation exposed retired InkDetail/candidate-field references; these were retargeted rather than dropping coverage. Package dry run includes 491 files and confirms canonical model, shared/JNI internals and session entry points. Logs and class selections are retained in ignored .local/task16-integration after migration.

Physical camera, human shutter and visible-guide evidence remain pending. No camera shutter was automated; this integration does not establish a new performance comparison or iOS readiness.

The first broad device run crashed in current main's dropped-pager transition regression: a parent layout could still recycle retained children after adapter data was cleared. Drop now suppresses RecyclerView layout before releasing bindings; all four ImagePagerViewTest cases passed in the focused rerun (10.572 s). This is a bounded main integration fix, not a changed image algorithm or relaxed assertion. The full integrated device run was restarted after that correction.

Final integrated Debug instrumentation: OK (252 tests), 194.463 s, zero failures/skips/ignored. This includes session bridge/pipeline/export/resource/view suites, shared geometry/filter/PNG/PDF/inference/motion/guide/focus coverage, all four preserved main photo-crop cases, the region-aware JNI regression, and camera-off model loading. The exact expanded class selection and build/device output are in .local/task16-integration/task16-merge-device-verified.log and task16-merge-final-classes.txt. Device: SM-G998U, Android 15, arm64, 192.168.1.55:5555. Native build: Kotlin 2.2.10, min/compile/target SDK 24/37/36, NDK 27.1.12297006, CMake 4.1.2. APK compilation/installation passed; no physical torch/focus/camera quality measurement is claimed. A bounded wake helper prevented device doze during the noninteractive run and made no persistent device setting changes.

Task 16 stays In progress for human camera/visible-guidance evidence. Automated results above validate the integrated implementation rather than carrying forward the pre-session pass as proof. Existing generated/main whitespace warnings are inherited; focused integration source whitespace checks pass and generated files are unchanged from main.

## Minified app startup follow-up — 2026-11-01

A human release launch reported ExceptionInInitializerError caused by ClassNotFoundException for processing.FieldKernels during JNI_OnLoad. The bridge has no production Java callers after shared-filter extraction, but native library initialization still registers it unconditionally by class name. Added explicit consumer keep rules for FieldKernels and FastCores (also eagerly registered). Debug instrumentation did not exercise R8 class removal; release library instrumentation is not a substitute for launching a minified example APK.

Whitespace check passed. Actual minified release verification remains outstanding: tools/android.ps1 -Release failed to clear the locked prepared host (EBUSY). Building the existing host directly with :app:assembleRelease, arm64-v8a and android.enableMinifyInReleaseBuilds=true failed configuring expo-constants: No such property: libraryVariants for class: java.lang.String. An isolated --no-daemon, nonincremental/in-process Kotlin retry failed at the same configuration step before app compilation/R8. No rebuilt APK was installed or launched. Logs: .local/task16-release-startup.log, .local/task16-release-first-build.log, .local/task16-release-r8-build.log. Keep-rule correction is uncommitted; Task 16 also remains pending this minified-startup check in addition to human camera/guide evidence.
