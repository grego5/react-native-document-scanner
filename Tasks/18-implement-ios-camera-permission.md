# 18 — Implement iOS camera permission ownership

[Back to task index](../TASKS.md)

Status: Completed — Intel simulator build and focused permission tests passed
Complexity: Medium
Dependencies: [17](17-add-ios-package-and-nitro-bindings.md)

## Objective

Expose process-wide camera authorization without requiring a mounted camera.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/CameraPermission.nitro.ts
- src/CameraPermission.ts
- android/src/main/java/com/margelo/nitro/documentscanner/CameraPermissions.kt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridCameraPermission.kt
- ios/HybridCameraPermission.swift — scaffold from Task 17

## Current behavior and invariants

Concurrent requests share one prompt, subscribers receive real status changes, and foreground return refreshes settings changes.

## Implementation steps

1. Implement AVFoundation status mapping: notDetermined→not-determined, authorized→granted, denied/restricted→blocked.
2. Serialize pending authorization requests and settle each exactly once; request UI only from a foreground application context.
3. Refresh authorization on foreground activation and notify registered listeners only on change; release observers/listeners safely.
4. Keep errors in ScannerFailure payloads and display copy in React.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Use injected authorization/notification boundaries for initial states, shared prompts, denial, setting changes, listener removal, and unavailable presentation.
- Verify a permission change can resume an attached camera once session implementation exists.

## Validation commands

- npm run check
- Run focused iOS XCTest permission tests on the verified Mac host.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Permission API behaves correctly without any camera view and has no observer/promise leak.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement camera permission service


## Implementation and validation — 2026-11-02

Changed files: ios/CameraPermissions.swift, ios/HybridCameraPermission.swift,
ios/Tests/CameraPermissionsTests.swift, ios/Tests/ScaffoldTests.swift,
ios/README.md and src/CameraPermission.nitro.ts; this report and TASKS.md track completion.

The process-wide CameraPermissions owner serializes mutable state and callbacks on
main. AVFoundation status maps notDetermined to not-determined, authorized to
granted, and denied/restricted to blocked. Concurrent callers join the active
prompt even while it makes the app inactive. A generation token ignores repeated
completion callbacks; every admitted caller settles once. An active foreground
application and nonempty NSCameraUsageDescription are required before prompting.
Unavailable presentation returns permission/request-failed; missing host usage
copy returns permission/unavailable through the existing ScannerFailure union.
No native display copy or new public error code was added.

Subscribers refresh on UIApplication.didBecomeActiveNotification and receive only
changed status. Unsubscribe is idempotent, releases the callback and removes the
activation observer when the last subscriber leaves. Notification callbacks use
weak ownership. An in-flight authorization callback retains the owner until its
waiters settle, then releases it. The native HybridObject uses this same owner
without requiring any camera view. Native camera attachment/resume remains for
the camera implementation task.

Validation passed:

- npm run check: both TypeScript checks and 40 JS contracts passed.
- npm run nitrogen: all eight bindings generated with no generated diff. The first
  attempt encountered a transient Windows file-open error and partial generation;
  the retry reproduced the complete checked-in output, without hand edits.
- CocoaPods preparation of the existing isolated package host succeeded. Verified
  Xcode 26.6 (17F113), Swift 6.3.3 (swiftlang-6.3.3.1.3), simulator SDK 26.5,
  deployment target iOS 18 and Intel simulator ID below before building.
- In /Users/admin/Projects/react-native-document-scanner/.local/task17/host:
  `DEVELOPER_DIR="/Users/admin/Downloads/Xcode 2.app/Contents/Developer" xcodebuild -workspace ScannerPackageValidation.xcworkspace -scheme ReactNativeDocumentScanner-Unit-ScaffoldTests -configuration Debug -destination "platform=iOS Simulator,id=7C539F34-E6DA-4D55-AF59-98A50A35C2D7" -derivedDataPath ../derived-data CODE_SIGNING_ALLOWED=NO build-for-testing`
  succeeded, including the final background-completion lifetime test.
- The existing external vmac standalone XCTest runner executed 17 tests with zero
  failures: ten focused permission cases plus seven package smoke cases. Permission
  coverage includes initial status mapping, shared prompts, repeated callbacks,
  denial/restriction, typed unavailable presentation and retry, Settings changes,
  listener removal/capture release, observer-owner release, background completion
  and pending-owner release, and two HybridObjects sharing one permission owner.
- git diff --check and new Swift file whitespace checks passed.

The Mac workspace was reused from Task 17; infrastructure remains outside the
repository. Logs are copied to ignored .local/task18-ios-build.log,
.local/task18-ios-xctest.log and .local/task18-pods.log. Local logs include
.local/task18-check.log and .local/task18-nitrogen-retry.log.

No real system permission prompt, physical-device run or attached-camera resume
was attempted. Authorization and activation were injected for deterministic tests;
real AVFoundation status reading and generated registry construction were also
exercised by the package smoke suite. These limits do not claim functional camera
implementation or iOS release readiness. No Android runtime/API signatures changed;
generated bindings remain identical.
