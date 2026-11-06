# 08 — Capture directly into sessions and publish capture state

[Back to task index](TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: [06](06-expose-native-session.md), [07](07-publish-batched-snapshots.md)

## Objective

Session-aware capture returns IDs, triggers native processing and replaces the need for JS capture/readiness flags.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `src/DocumentScannerCamera.nitro.ts: CapturedPhoto, TakePhotoOptions, methods`
- `src/DocumentScannerCamera.tsx: ref and callbacks`
- `android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt: takePhoto, capture admission, saved-photo completion`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/LatestFrameCapture.kt`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/SampleCollection.kt: beginCapture, remember`

## Current behavior and invariants

Camera takePhoto currently returns file metadata; the app sets busy, copies camera readiness and submits analysis itself. Capture gates, focus, model readiness, sample tickets and frozen-preview handoff already live native-side.

## Implementation steps

1. Require the native session prop and change takePhoto to Promise<string> returning the new page ID. Extend TakePhotoOptions with renderOptions using the existing DocumentRenderOptions type and preserve skipLowQualityControl.

2. At shutter admission, snapshot session identity, render options and gate overrides before posting asynchronous work. Reserve session resources; save the original and frame inside its directory. Publish the page only after successful source saving and start Task 05 analysis/rendering.

3. Prevent concurrent takes natively with capture/busy; unavailable model/camera/session/mode rejects appropriately before taking a photo. Closing/replacing the session invalidates pending publication but retains resources until the physical capture settles.

4. Expose onCaptureStateChange with unavailable/ready/waiting/shooting/saving; mode off reports unavailable even during review. Registering a callback replays current capture state; use the wrapper's stable refs/effect synchronization.

5. Preserve existing document-focus, torch, ZSL, quality-gate and captured-stage meanings; the new state reports progress, not another readiness gate. Preserve SampleCollection capture/model tickets and retained-preview behavior.

6. Update native CameraValidationActivity and fixtures to supply real session instances. Regenerate bindings and test state transitions without unattended shutters.

## Ownership and boundary rules

Every physical capture requires a human tap. No initial filtered live-frame render. Camera detach/session close rejects once and cannot publish into another scan. Existing model selection snapshots remain correct.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Mode off is unavailable; overlapping admission fails once; initial defaults appear on the first render; close/swap during save produces no new page; failure restores appropriate capture state. Human-only lifecycle tests cover actual capture.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run nitrogen; npm run typecheck. From this task until Task 12 the old example API causes expected typecheck:example/npm run check failures; do not add compatibility shims. Native capture checks require explicit authorization and a human shutter.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

Session-aware capture returns IDs, triggers native processing and replaces the need for JS capture/readiness flags.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(camera): capture pages into native sessions

## Implementation evidence

- Camera now requires a native-backed ScanSession prop and takePhoto resolves a page ID. TakePhotoOptions accepts DocumentRenderOptions while preserving skipLowQualityControl. Removed the obsolete public CapturedPhoto type; Nitrogen removed its unused transport bindings automatically. No compatibility aliases were added.
- Added SessionCapture and CaptureAdmission. Native admission snapshots session identity/generation, normalized render settings, selected immutable model and gate settings before asynchronous main work, and reserves original/frame writer pins. Overlap reports capture/busy. Model/camera readiness, first streaming preview, permission, mode and session validity are checked before a physical shot.
- CameraX writes directly to the reserved original staging file. Saved-source description and unfiltered frame encoding run as CAPTURE jobs on the session image FIFO. The main owner claims publication only for the admitted session generation, remembers the existing sample ticket under the immutable original's final name, publishes the page and starts native authoritative analysis/rendering with captured defaults/model selection. Analysis Actions now accept that explicit model selection.
- Close/session replacement/detach reject once and invalidate publication. Rejected physical captures retain their slot, original/frame producer pins and registered directory through physical/queued/running save completion. Stale results clean their outputs and never enter another scan. Nonterminal callbacks and frozen preview handoff are scoped to the admitted generation; an old session close cannot cancel a capture belonging to a newly bound owner. Original source pixels remain unchanged.
- Added onCaptureStateChange with unavailable/ready/waiting/shooting/saving and replay on handler registration. Mode off always reports unavailable, including review. The callback reports native readiness/progress; the existing CaptureGate still owns document/focus/motion/torch/ZSL decisions and all thresholds. Captured-stage timing, shutter sample tickets and retained-preview behavior remain in the camera owner. The wrapper keeps callback identity while synchronizing handlers in layout effects.
- Updated CameraValidationActivity with real native sessions and explicit cleanup. Human-only lifecycle/trace fixtures use IDs, authoritative session analysis and original read leases rather than file metadata/manual original deletion. Added a native original-lease lookup for these file consumers; no file paths enter JS metadata.
- Added SessionCaptureTest (camera-free, controlled workers/files) for initial options/model snapshots, successful source-only publication, close during physical save, swap invalidation, failure/missing placeholders, overlap/failed admission, simultaneous admission, phase availability and queued-source cancellation with main-owner pin release. Extended synthetic ScanSessionPipelineTest to save reserved JPEG/frame pixels, render only the original with captured defaults and verify source/placeholder bytes remain unchanged. Extended the no-shutter inactive-camera lifecycle case with capture-state replay/mode-off/readiness assertions.
- Added a JS camera contract covering native session binding, stable callback identity and committed handler refs, render/gate option forwarding, page IDs and capture/busy translation. Existing session/processor contracts remain covered.

### Verification

- Passed: final npm run nitrogen — 7/7 HybridObjects. Inspected required HybridScanSessionSpec prop, CameraCaptureState callback, string/failure capture result and renderOptions transport signatures. No generated output was edited manually.
- One regeneration failed with a transient UNKNOWN file-open error for generated TakePhotoOptions.hpp and left partial output; the immediate standalone retry regenerated all 7/7 HybridObjects and restored the complete camera output.
- Passed: npm run typecheck (library); npm run test:contracts — 33/33 JavaScript contracts. These do not establish native correctness.
- Expected failure: npm run typecheck:example — the old example camera omits session (index.tsx:122), and its hook imports removed CapturedPhoto (useScanSession.ts:5). This incompatibility is intentional from Tasks 08–10 until Task 12; npm run check cannot pass meanwhile. The example was not shimmed or prematurely migrated.
- Passed: whitespace checks for tracked/new authored sources. Standard git diff --check reports Nitrogen-emitted trailing spaces in HybridDocumentScannerCameraViewSpec.kt; new generated callback bindings may include the generator's normal whitespace. Generated output remains unmodified under the project rule, so the full whitespace check is not reported as passed.
- Supplementary: LSP returned no diagnostic items for final changed native production/test files. It initially caught a syntax error in the migrated trace fixture, which was corrected and rechecked. Reference coverage was incomplete and consequential relationships were confirmed in source/generated signatures. This is not Kotlin compilation evidence.
- Not run: native JVM tests, Gradle compilation/APK, synthetic device integration or human capture/lifecycle tests. Native/device authorization is withheld; JDK/ABI/device readiness was not asserted. No camera was opened and no physical shutter or camera takePhoto was invoked by automation.
- Status updated at user request: implemented, with required native capture verification pending.
