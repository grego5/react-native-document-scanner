# 31 — Complete iOS interruption and resource-lifecycle integration

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [18](18-implement-ios-camera-permission.md), [21](21-implement-ios-processor.md), [22](22-implement-ios-image-viewer.md), [23](23-implement-ios-image-pager.md), [24](24-implement-ios-crop-editor.md), [25](25-implement-ios-camera-session.md), [26](26-implement-ios-live-guidance.md), [27](27-implement-ios-camera-controls.md), [28](28-implement-ios-still-capture.md), [29](29-implement-ios-capture-gating.md), [30](30-implement-ios-capture-feedback.md)

## Objective

Prove all iOS owners stop, recover, and dispose together without stale publication.

## Non-goals

Do not reopen completed controls/view tasks or replace their owners. Refactor existing lifecycle glue where integration exposes duplicated state or cleanup; fix defects in the owning component. Do not change model/quality thresholds, add Android-only light features, perform unrelated optimization, or hand-edit generated sources.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/architecture.md — Lifecycle
- src/DocumentScannerCamera.nitro.ts — CameraMode and state semantics
- ios/ session, source-store, inference, processor, and view owners from preceding tasks

## Current behavior and invariants

Each resource has one owner; one component's teardown cannot destroy an interpreter or source lease still used by another component.

## Implementation steps

1. Integrate interruption/runtime-error recovery, foreground/permission return, attachment and mode=off around the existing session eligibility owner. Retain typed reasons for configuration/light recovery where applicable; do not add a second camera-running flag or revive parking.
2. Exercise Task 29's gate and Task 28's physical capture as separate lifetimes. Cancel waiting requests/deadlines on invalidation; finish an already-submitted shot when the platform permits, reject publication once, and hold its busy slot/leases until terminal cleanup. Repeated stops, immediate remount and session replacement must not resurrect an old request.
3. Verify pending focus/light commands, command acknowledgements, focus observations and gate wakeups are run/request-scoped. Deferred settings apply once after settlement. Preserve established light decisions only where policy requires; interrupted partial observation windows must restart without counting time when frames were absent. Do not infer command success from stale callbacks.
4. Ensure model buffers, CVPixelBuffers, KVO tokens, notifications, timers, queued results, source leases and temporary files release on all success/failure/cancellation paths. Preserve the existing camera-queue ordering contract and immutable cross-owner snapshots.
5. Add bounded-resource stress sequences across camera→pager→crop→background→resume and scan-session close/replacement. Check state/event ordering as well as allocation counts, including a scan retry, settled-frame wait and deferred light command interrupted at each boundary.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Fake-session adverse sequences: late scan/light/frame/delegate/model/load completions after detach, permission loss, interruption, dispose and immediate remount; exactly one terminal publication and no early physical-slot release.
- Resume after partial light evidence versus an established decision; no stale deadline, duplicate restart, control application or feedback install.
- Verify no background camera/sensor activity, observer cycles, increasing source/lease counts or stale UI installs. Exercise simulator lifecycle integration separately from real camera interruption/recovery.

## Validation commands

- Run deterministic owner/clock/decision tests on the Mac VM where host-runnable, compile there, and run simulator-dependent lifecycle/concurrency/resource integration through GitHub Actions. Use focused suites for fixes and a full iOS regression suite for the integrated result.
- Run human-triggered physical-device interruption, background/resume and repeated-session checks. Use supported ownership/concurrency sanitizers and report availability and actual results. Simulator/fake-session success is not hardware recovery or memory-pressure evidence.

## Completion criteria

Lifecycle integration has passing adverse-path tests and measured bounded ownership across repeated sessions.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test(ios): verify scanner lifecycle and resource ownership

