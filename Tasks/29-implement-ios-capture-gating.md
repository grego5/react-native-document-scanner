# 29 — Implement native iOS capture readiness

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [27](27-implement-ios-camera-controls.md), [28](28-implement-ios-still-capture.md)

## Objective

Implement native Swift capture readiness against the current Android behavior and public contract. Include the necessary refactoring of the existing iOS controls and still-capture integration in this task; Task 27 remains closed, with its outstanding device checks retained. Reuse the UIKit/Nitro view and AVFoundation session owners; no SwiftUI migration is part of this work.

## Non-goals

Do not reopen Task 27, redesign unrelated Android behavior, change model or image-quality thresholds, or implement later feedback/UI outcomes. Refactoring existing iOS owners is in scope only where needed for this capture workflow. Do not add CameraX AF/AE/AWB states, Android ZSL fallback, or lowLightBoost support to iOS by analogy. Do not hand-edit generated sources.

## Read before editing

Locate declarations/callers with LSP, then inspect bounded source; use focused rg when indexing is incomplete.

- android/src/main/java/com/margelo/nitro/documentscanner/CaptureGate.kt — decisions, retries, deadlines and settled-frame handoff
- android/src/main/java/com/margelo/nitro/documentscanner/CameraLightController.kt — command ownership, confirmation, deferral and fallback lifetime
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — frame consumption and action execution
- android/src/main/java/com/margelo/nitro/documentscanner/processing/FrameRecord.kt
- android/src/test/java/com/margelo/nitro/documentscanner/CaptureGateTest.kt
- ios/CameraControls.swift and ios/CameraControlPolicies.swift — existing focus/torch owners and unknown scan outcomes
- ios/CameraSession.swift, ios/HybridDocumentScannerCameraView.swift and ios/StillCapture.swift — ordered observations, admission, exposure and terminal settlement
- `src/DocumentScannerCamera.nitro.ts`, [shared engine](../.agents/skills/docs/references/engine.md), and [iOS camera](../.agents/skills/docs/references/ios/camera.md) for public behavior and platform limitations

## Current behavior and invariants

Task 28 already admits and publishes bounded stills; its capture currently fires without readiness gating. Task 27 already supplies continuous focus, tap/capture scans, ordered camera-queue acknowledgements, torch deferral and run-scoped observations. Integrate these owners rather than introducing parallel admission or control state.

The new per-request gate owns StartFocusScan, Wait, Shoot and Reject decisions, scan attempt/movement history and readiness deadlines. The adapter gathers evidence, executes actions and schedules returned deadlines; hardware controls own lens ownership and command completion. Four qualifying consecutive frames establish steadiness; missing motion is unknown, focus generations restart the sequence, and one physical capture retains its slot until terminal cleanup even after Promise rejection.

AVFoundation scan completion currently reports an unknown focus result, not success or failure. A settled unknown result must remain distinguishable from an active scan and a known failure. Android buffer eligibility and light-driven zsl-to-latency switching are Android-only; iOS must use the evidence its actual configuration exposes.

## Implementation steps

1. Map existing Swift state, callbacks and queues before editing. Refactor CameraControls, the view and Task 28 integration only as needed so hardware focus/light command state has one owner, per-request gate policy has one owner, and the view remains the adapter. Keep command application and focus observations ordered on the camera queue, publication on main, and immutable run-tagged snapshots across owners. Retain separate pending-capture and exposing-shot boundaries.
2. Implement the Swift decision policy with explicit clock inputs and event/deadline-driven reevaluation. Before admitting a first scan, enforce the 4 s page/focus admission deadline. Quality captures request a scan at the current page or preview centre; zsl/latency request one for stale page focus or a dark scene without effective illumination where the supported iOS evidence permits that decision. A held user tap remains authoritative, and skipLowQualityControl starts no scan. Use the current 100,000 exposure-ms × ISO dark threshold and shared torch vectors. Do not port Android light-fallback-only triggers to iOS.
3. For a known failed owned scan, retry once independently of its original trigger, then retry only when motion during the failed scan warrants it. An admitted owned scan holds both page and focus waits past their deadlines; after settlement, allow up to 500 ms for a frame exposed after the focus change. Match the 1 s light/steadiness window after page/focus readiness. Keep hardware scan timeout separate from capture readiness deadlines; verify the appropriate AVFoundation timeout rather than copying CameraX's 5 s completion/6 s backstop.
4. Define and document the iOS focus-acceptance rule from available AVFoundation observations before claiming parity. Unknown completion is neither proof of focus nor a failed scan eligible for blind retries. Cover fixed-focus devices and unavailable readiness explicitly; never synthesize focused/locked-success or Android metadata. Record any unavoidable platform difference in this task and the public platform notes.
5. Consume complete ordered frame records with normalized timestamps, framing, focus generation, exposure provenance and motion evidence. Reuse shared flow/gyro evidence where available; preserve unknown motion and estimated exposure labels. Reset readiness across relevant control/settings changes and discard old-run results. Do not replace frame evidence with separately updated UI fields.
6. Keep torch and any supported exposure-affecting command deferral/acknowledgement in the existing control owner. A pending light command holds light readiness, and capture settlement applies the latest deferred settings. Optional focus must not waive buffer/light safety; if an iOS buffered path is used, establish its actual eligibility/freshness guarantees or use a supported fresh-frame path. skipLowQualityControl retains its explicit opt-out semantics.
7. Integrate admission before waiting and allow only the gate's first Shoot to invoke the existing Task 28 shot. Detach, permission loss, background, session close/replacement and disposal invalidate publication once; physical delegates/writers keep their slot and leases until settled. Cancel gate deadlines and suppress stale scan/frame/light completions by the appropriate request/run identity. Do not duplicate StillShot or session resource ownership.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Decision sequences: first-scan admission before/at deadline; trigger disappears before failure; one unconditional retry; movement-dependent later retries; still failure stops retries; unknown scan completion is distinct from scanning and failure.
- Rescue scan crosses the page deadline; a post-settlement frame arrives or the 500 ms allowance expires. Test coalesced callbacks so policy does not depend on observing every intermediate hardware state.
- Four-frame stability, low texture/unknown motion, frame gaps, stale page focus, tap priority, focus-generation reset and settings transitions.
- Light request pending/failure, optional focus with buffer not ready, fixed focus, lowQualityControl=false and skipLowQualityControl. Keep unsupported Android-only cases out of fabricated Swift hardware evidence.
- Fake-session integration: one admitted shot, busy until physical settlement, cancellation before/after exposure, deferred controls applied once, and late old-run completions publish nothing.
- Reuse shared decision-sequence fixtures for platform-independent rules; document platform-specific evidence adapters separately.

## Validation commands

- Classify tests by their actual dependencies. Run hardware-free policy/fixture tests on the SSH Mac VM in a host-runnable target when available; a pure policy inside a UIKit test bundle still requires the simulator until separated.
- Build/typecheck the iOS package and compile test products on the Mac VM. Run simulator-dependent integration suites on GitHub Actions through the verified vmac runner/workflow. Use the split runner only when supported by the active server and the build/test toolchains match; otherwise use the verified GitHub route. A build-only result is not a test pass.
- Run relevant existing Android gate/stability/history tests if parity fixtures change, and npm run check if public specs or JS change. Use focused suites during iteration and broader native regression coverage after integration.
- On a physical iPhone, use human shutter taps to check page arrival, stale focus, dark/torch scenes, tap priority, motion waits and interruption. Record focus evidence, timings and any difference from Android; unavailable device checks remain pending.
- Verify actual profile, scheme, compiler and destination before commands, and record commands/results/skips. Do not repeatedly run unrelated full builds for local policy edits.

## Completion criteria

The Swift gate owns capture decisions and retries; existing controls and Task 28 capture have no duplicated policy or admission owner. Shared decision sequences pass, platform-specific focus/readiness rules are documented and tested, and focused integration plus native regression checks pass. Report implementation and device validation separately; without the required physical evidence use Implemented — physical-device checks pending, not an unqualified Completed status. Task 27 is not reopened and release readiness remains blocked on missing device evidence.

## Proposed commit title

feat(ios): implement native capture quality gating

