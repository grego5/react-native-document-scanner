# 31 — Complete iOS interruption and resource-lifecycle integration

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [18](18-implement-ios-camera-permission.md), [21](21-implement-ios-processor.md), [22](22-implement-ios-image-viewer.md), [23](23-implement-ios-image-pager.md), [24](24-implement-ios-crop-editor.md), [25](25-implement-ios-camera-session.md), [26](26-implement-ios-live-guidance.md), [27](27-implement-ios-camera-controls.md), [28](28-implement-ios-still-capture.md), [29](29-implement-ios-capture-gating.md), [30](30-implement-ios-capture-feedback.md)

## Objective

Prove all iOS owners stop, recover, and dispose together without stale publication.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/architecture.md — Lifecycle
- src/DocumentScannerCamera.nitro.ts — CameraMode and state semantics
- ios/ session, source-store, inference, processor, and view owners from preceding tasks

## Current behavior and invariants

Each resource has one owner; one component's teardown cannot destroy an interpreter or source lease still used by another component.

## Implementation steps

1. Integrate session interruption/runtime-error recovery, foreground/permission return, view attachment, and mode=off around a single session eligibility state.
2. Reject requests that cannot finish after interruption; finish an already-submitted shot when the platform allows, settle once, and prevent callbacks from resurrecting stopped owners.
3. Ensure model buffers, CVPixelBuffers, KVO tokens, notifications, timers, queued results, source leases, and temporary files release on every teardown path.
4. Add bounded-resource stress scenarios across camera→pager→crop→background→resume and processor disposal; fix failures in the owning component rather than duplicating lifecycle state.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Exercise late delegate/model/load completion after detach, permission revoke, interruption, dispose, and immediate remount.
- Verify no background camera/sensor activity, no observer cycles, no increasing native source count, and no stale UI installs.

## Validation commands

- Run focused iOS lifecycle/concurrency XCTest integration tests.
- Run device and simulator stress scenarios; use supported ownership/concurrency sanitizers and report their availability.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Lifecycle integration has passing adverse-path tests and measured bounded ownership across repeated sessions.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test(ios): verify scanner lifecycle and resource ownership

