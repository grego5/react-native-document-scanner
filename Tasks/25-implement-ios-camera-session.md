# 25 — Implement the bounded iOS camera session and preview

[Back to task index](../TASKS.md)

Status: Implemented — Intel simulator checks passed; physical-device preview check pending
Complexity: High
Dependencies: [17](17-add-ios-package-and-nitro-bindings.md), [18](18-implement-ios-camera-permission.md), [19](19-implement-ios-source-store.md), [21](21-implement-ios-processor.md)

## Objective

Stream a lifecycle-controlled rear-camera preview with a strict still-resolution ceiling.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/DocumentScannerCamera.nitro.ts — props and CameraState
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — bind/unbind and viewport
- android/src/main/java/com/margelo/nitro/documentscanner/CaptureResolutionPolicy.kt
- example/app.config.ts

## Current behavior and invariants

mode=off stops camera and sensors while keeping the view mounted; non-off modes require permission, attachment, and foreground visibility.

## Implementation steps

1. Implement ios/HybridDocumentScannerCameraView.swift with one AVFoundation camera owner/serial queue and main-thread preview layer. Borrow the bound Task 21 scan session; camera detach or AVFoundation stop never closes the headless scan-session owner. Observe session replacement/close to invalidate stale camera publication.
2. Choose the rear physical wide-angle camera. Enumerate supported formats and photo dimensions; prefer largest valid 4:3 still size, then largest valid other aspect, under both 4096-long-edge and 12,582,912-total-pixel bounds.
3. Configure output and per-shot maxPhotoDimensions using a supported dimension before starting the session; exclude 24/48 MP modes. If no bounded configuration works, remain suspended and reject capture as camera/unavailable.
4. Add preview and bounded video-data output with analysisFrameWidth default 640; discard late frames and permit no unbounded frame backlog.
5. Implement framing/orientation transforms and virtual square presentation independently of model tensor resizing.
6. Implement basic start/ready/suspended states and deterministic attach/detach/foreground shutdown from the beginning; later interruption tests extend this lifecycle rather than defer resource correctness.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Unit-test dimension selection with advertised 12/24/48 MP combinations, portrait axes, no valid size, and smaller delivered photos.
- Run device preview without triggering capture; verify permissions, mode changes, rotations, and no analysis backlog.

## Validation commands

- npm run check
- Run iOS camera-configuration XCTest tests and a physical-device preview check; simulator cannot validate camera delivery.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

A bounded preview/session runs with consistent framing and known supported still dimensions; shutter functionality remains explicitly incomplete.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): add bounded camera session and preview

## Implementation report — 2026-11-05

Changed files:

- `ios/CameraConfiguration.swift`: `PixelSize`, `CameraStillPolicy` (long side at most
  4096 and at most 12,582,912 pixels; largest bounded 4:3, then largest bounded other
  aspect; a delivered photo may be smaller in either orientation, never larger) and
  `CameraFormatPolicy` (still first; among formats offering it, 4:3 video, then the
  video width nearest `analysisFrameWidth` at or above it, then below; `analysisFrameWidth`
  clamped 160–1920, default 640, as on Android).
- `ios/CameraFraming.swift`: sensor-axis buffer → upright framed image: clockwise
  quarter turn from AVFoundation's rotation angle, EXIF orientation for live inference,
  integral centered square crop in buffer pixels, point mapping both ways, and the
  aspect-fitted presentation rect. Tensor resizing stays inside inference.
- `ios/CameraSession.swift`: `CameraSessionControl` (the view's only camera dependency)
  and `AVCameraSession`: rear `builtInWideAngleCamera`; session created lazily and
  configured on its serial queue; `activeFormat` and `photoOutput.maxPhotoDimensions`
  set to a supported bounded size before `startRunning`; per-shot settings use the same
  size; BGRA video-data output with `alwaysDiscardsLateVideoFrames` that retains
  nothing (frame/drop counts logged on stop). Start/stop generations drop stale events;
  runtime errors fail as `camera/unavailable`; interruptions return to waiting for a
  frame.
- `ios/CameraPreviewView.swift`: black host view; preview layer aspect-filled into the
  framed region, rotated by the rotation coordinator's preview angle.
- `ios/HybridDocumentScannerCameraView.swift`: runs the camera only while mode is not
  `off`, attached, sized, foreground (UIApplication background/foreground notifications)
  and permitted (CameraPermissions listener); otherwise stops and keeps the view
  mounted. States `suspended`/`starting`/`ready` (first frame). A failed start stays
  suspended and reports `camera/unavailable` once, retrying only after a mode, request,
  attachment, foreground or permission change. `squareAspectRatio` changes framing without
  restarting. Borrows the session (prop default is now the placeholder, so a camera no
  longer opens its own session); replacement or close advances `sessionGeneration`;
  unmount stops the camera and never closes the session. `takePhoto` rejects
  (`camera/unavailable`, `camera/permission-denied`, `capture/not-ready`,
  `processor/disposed`, or `feature/unsupported` once ready); capture state stays
  `unavailable`, autofocus `inactive`, document `off`.
- `ios/Tests/CameraSessionTests.swift`: 14 tests (16 after review): 12/24/48 MP combinations, ceiling
  edges, other-aspect fallback, portrait axes, no valid size, smaller delivered photos,
  format choice; framing rotations, round trips, virtual square, presentation;
  lifecycle with a fake camera (mode, attach/detach, background/foreground, permission,
  size, request change, interruption, stale events, failure without retry loop,
  unmount), capture rejections and session borrowing/close, callbacks on set, and a
  real session without a camera failing as unavailable.
- `example/app.config.ts`: iOS bundle identifier and `NSCameraUsageDescription`.
- Docs: `ios/README.md` (camera session section; removed a task citation) and the
  architecture reference.

Checks:

- `npm run check`: 41 passed.
- vmac (GitHub Actions `ios-validation.yml`, `macos-15-intel` simulator):
  - `41975188998`: CameraSessionTests 14/14, ScaffoldTests 5/5. The no-camera test took
    98.7 s because building `AVCaptureSession` on the simulator stalls on a camera TCC
    error, so session creation moved onto the camera queue after a camera is found.
  - `41977195317`: CameraSessionTests 14/14 in 1.2 s, ScaffoldTests 5/5.
- Not run:
  - Physical-device preview check (permissions, mode changes, rotations, no analysis
    backlog): no iOS device is available here; the simulator has no camera.
  - Other iOS test classes: only camera-view code changed.
  - Android: unchanged.

Review follow-up (same day):

- First-frame readiness: `start` no longer arms on main. `CameraFrameMonitor` reports
  only an armed run, and the camera queue arms it after the previous run has stopped
  (`stopRunning`, then draining the frame queue). A late frame of an earlier run can no
  longer mark a restart `ready`.
- Buffer size: the video output turns off automatic buffer sizing and preview-sized
  buffers, so it delivers the active format's size. `streaming` and a new `frameSize`
  event carry each frame's actual size, and `analysisFraming` uses that size rather
  than the configured format.
- Tests (16): the monitor's arm, rearm, disarm and size reports across a restart; a
  real session ignoring a frame that arrives while its start waits on the camera queue;
  view framing from a delivered size smaller than the format, and a later size change.
- Checks: `npm run check` 41 passed; vmac `41983271980` CameraSessionTests 16/16,
  ScaffoldTests 5/5.
- These AVFoundation paths are still unverified on hardware; the simulator tests use a
  fake camera or a session without a camera.

Limitations:

- The chosen still and video sizes on real devices are unverified; the log line
  `camera configured photo=… video=…` reports them. iOS video-data buffers are the
  format's video size, so analysis receives the nearest supported 4:3 width rather
  than a resampled 640.
- The shutter, live guidance, focus, torch, exposure, frame rate and capture feedback
  props are accepted and ignored.
- Model preparation does not gate camera start yet.
