# 27 — Connect iOS focus, torch, and exposure controls

[Back to task index](../TASKS.md)

Status: Implemented — simulator checks passed; physical-iPhone focus/torch scenarios pending
Complexity: High
Dependencies: [13](13-share-focus-policy.md), [14](14-share-torch-policy.md), [26](26-implement-ios-live-guidance.md)

## Objective

Execute shared control decisions through AVFoundation with truthful completion evidence.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/FocusController.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentFocusPolicy.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/TorchPolicy.kt
- android/src/main/java/com/margelo/nitro/documentscanner/CameraExposurePolicy.kt
- src/DocumentScannerCamera.nitro.ts — focus, torch, exposure and frame-rate contracts

## Current behavior and invariants

Document detection continues during focus; capture starts no scan; taps retain priority; torch changes cannot interrupt an exposure.

## Implementation steps

1. Adapt document/tap scan commands to supported AVFoundation focus points/modes, KVO transitions, lock-completion timestamps, and policy generations.
2. Use continuous autofocus until a learned lock exists; park at the learned lens position when supported. Never convert 30 cm to an invented lensPosition or equate isAdjustingFocus=false with Camera2 success metadata.
3. Implement exposure bias and supported frame-duration selection; use exposure×ISO observations for latched auto torch.
4. Normalize device and buffer clock domains before comparisons; mark unavailable metadata unknown and estimated observations explicitly.
5. Serialize hardware commands on the session owner and feed completion events back into policy snapshots; unregister observations on unbind.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Use injected device events for tap expiry, scan completion/timeout, stale generations, fixed-focus/no-torch hardware, exposure clamping, and deferred commands.
- Verify focus can reacquire while a capture is waiting and iOS callbacks do not assert unsupported focus certainty.

## Validation commands

- Run shared focus/torch policy tests and focused iOS device-adapter tests.
- Run human-observed focus/torch scenarios on a physical iPhone.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

iOS controls follow shared policy with documented platform evidence and no observer or timestamp-lifetime errors.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement scanner focus torch and exposure controls


Torch policy stays in Swift rather than C++. Match the Kotlin policy using `tests/fixtures/parity/torch.tsv`; implement the same threshold, sustain, interruption and latch/reset semantics.

Learned focus position stays platform-owned: use a Swift store with explicit lifetime and synchronization in native lens-position units. No C++ storage or diopter conversion is required.

## Implementation report — 2026-11-05

Scope decision: step 2's lens parking at a learned position is not implemented. Android dropped parking on 2026-11-04 after phone measurements: parked pages focused in about 0.7 s against a median of about 0.15 s with continuous autofocus. iOS follows the current Android design: continuous autofocus aimed at the guide area whenever no scan holds the lens, tap and capture scans, and no learned-position store. No focus policy exists in C++ (it lives in Kotlin), so the Swift `FocusPolicy` is a port of Kotlin's, as `TorchPolicy` is.

Changed files:

- `ios/CameraControlPolicies.swift` (new): `FocusPolicy`, `FocusOwner` and `FocusSnapshot`, ported from Kotlin; `TorchPolicy`, ported from Kotlin and checked against `torch.tsv`; `CameraExposurePolicy` (bias clamping, and Android's frame-rate choice over AVFoundation's continuous intervals).
- `ios/CameraDeviceControl.swift` (new): `CameraCapabilities`, `CameraCommand`, `FocusObservation` and `CameraFrameMetadata`; the AVFoundation adapter (capabilities, command application under the configuration lock, frame durations clamped to the supported range's own durations, and per-frame EXIF exposure with an estimated device fallback); `hostClockNs`.
- `ios/CameraControls.swift` (new): the main-thread executor. It runs tap and capture scans with tokens, binding generations and deadlines, reports autofocus, defers the torch during a capture, latches the auto torch from frame-queue observations, and applies exposure bias and frame rate.
- `ios/CameraSession.swift`: `CameraConfiguration.capabilities`. The protocol gains `metadataSink`, `focusEvents` and `perform` (defaulted for owners without a device). `AVCameraSession` serializes commands on its session queue, and drops their completions for a stopped run. It observes `isAdjustingFocus` and `focusMode` with KVO per run and invalidates the observers at stop. It converts frame PTS from the session clock to the host clock, and delivers per-frame metadata.
- `ios/CameraPreviewView.swift`: a tap recognizer converts view points to device points through the preview layer.
- `ios/HybridDocumentScannerCameraView.swift`: owns `CameraControls`. It binds them on `configured` and unbinds them at stop; it maps `tapToFocus`, `exposureCompensation`, `minFrameRate` and `torch` (non-finite values count as omitted); it accepts taps only while streaming; and it reports autofocus changes. The camera sinks capture the controls weakly.
- `ReactNativeDocumentScanner.podspec`: the `torch.tsv` test resource.
- Tests: `ios/Tests/CameraControlsTests.swift` (new, 11 tests).
- Docs: `ios/README.md` (Camera controls), `.agents/skills/docs/references/architecture.md`, `tests/fixtures/README.md`.

Checks:

- iOS (vmac, project `docscanner`, Intel simulator, Xcode 26.3), selecting `CameraControlsTests`, `LiveGuidanceTests`, `CameraSessionTests`, `CornerInferenceTests` and `ScaffoldTests`. GitHub Actions run 42036052268: **56 tests, 0 failures, 1 expected skip** (the optional private inference fixture). Per class: CameraControlsTests 11/11, LiveGuidanceTests 18/18, CameraSessionTests 16/16, CornerInferenceTests 6 (1 skipped), ScaffoldTests 5/5.
- The earlier run 42033515113 failed to compile, from an implicit `self` in the nested main-queue closure of the focus observer; it was fixed. It does not count as a pass.
- `npm run check`: 41 JS contracts passed. `git diff --check` is clean.
- No C++ changed, so the portable core was not rerun. The Android focus and torch policies are unchanged, so the JVM suites were not rerun.

Test coverage, all with injected device events and a manual clock:

- The shared torch vectors.
- Bias clamping, and the frame-rate choice (reaching, unreachable, unset, nothing advertised).
- A tap scan settles only on a lock observed after its own command, reports `scan` and `inactive` only, and resumes centred continuous focus after its 5 s hold.
- Taps are refused when off, during a shot, and on fixed focus; a fixed-focus capture settles at once as unknown.
- A capture scan times out after 2 s, can restart while the capture waits, is refused during a shot, and returns to continuous focus after the capture.
- An overdue tap waits for the shot.
- An unbind retires late completions, observations and timers; a refused scan command settles as a failure.
- A torch change is deferred during a capture; no torch commands go to a camera without a torch; the auto torch latches and a mode change resets it.
- Bias and frame rate are applied and restored.
- The view's taps reach the device only while streaming, and the autofocus callback follows observations and stops.

Not run:

- Physical-iPhone focus and torch scenarios (tap hold, continuous focus on a page, auto torch in a dim room, exposure bias, `minFrameRate`): no device is available. Whether video frames carry EXIF exposure, and how KVO `focusMode` and `isAdjustingFocus` sequence on real hardware, are unverified.

Limitations:

- AVFoundation gives no focus-success evidence, so every iOS scan settles with an unknown result, and the autofocus state is only `scan` or `inactive`. Capture gating, which comes later, must decide what counts as focus on iOS.
- Focus observation times are when KVO reached the adapter, a bound rather than the transition time. AVFoundation stamps no frame for a focus change.
- `capture(at:)`, `afterCapture`, `shotInFlight` and `capturePending` have no caller until still capture and capture gating land.
- `exposureCompensation` has no step on iOS: it is clamped, not rounded. `minFrameRate` applies without restarting the camera, unlike Android's rebind.

## Review follow-up — 2026-11-05

Three review findings were confirmed in source and fixed:

- An earlier scan could settle a newer queued one. A scan was stamped when main requested it, before the session queue applied it, so an earlier scan finishing in that gap (adjusting, then `.locked`) completed the new scan. `perform` now reports the host-clock time the session queue finished applying each command, after the configuration lock is released. A scan settles only on a `.locked` observation at or after its command's apply time. Applying the scan leaves the device in `.autoFocus` until that scan ends, and each observation reads the device's current state, so a stale callback reads `.autoFocus`.
- A stopped run's last frame could reach the auto torch. `unbind` reset the latch, but frames were still admitted. Frame admission is now under `torchLock`: closed by `unbind`, and opened with a fresh latch by `bind`.
- A missing exposure did not break the darkness window. Such a frame now goes through the policy's reset path, as a bright frame does. Android has the same gap (it only observes frames with an exposure); it was left unchanged here.

Regressions were added to `CameraControlsTests`: an earlier scan finishing while the new command waits; dark, then unknown, then dark needing a fresh second; and a frame arriving after a stop not reaching the next binding. The tap test now models the delay before the command applies.

Checks: iOS run 42047503400: **59 tests, 0 failures, 1 expected skip** (CameraControlsTests 14/14, LiveGuidanceTests 18/18, CameraSessionTests 16/16, CornerInferenceTests 6 with 1 skipped, ScaffoldTests 5/5). `git diff --check` is clean.

## Second review follow-up — 2026-11-05

The apply-time rule still had a race: command completions and focus observations reached main through separate hops, so a `.locked` observation taken after the command applied could arrive before its completion and be dropped, leaving the scan to time out. Comparing timestamps from two threads was the wrong model, so it was replaced rather than patched. Focus change notifications now only wake a sample taken on the session queue, the same queue that applies commands and sends their completions. Main therefore receives samples and completions in queue order, and a scan settles on the first `.locked` sample after its completion. `perform` completes with a `Bool`, and `FocusObservation` no longer carries a time.

`CameraSessionTests` checks the ordering on a real `AVCameraSession`: a sample requested from another thread before a command reaches main before its completion, and one requested after reaches main after it, sampled off main. `CameraControlsTests` now inject samples in delivery order.

Checks: iOS run 42064892956: **60 tests, 0 failures, 1 expected skip**.
