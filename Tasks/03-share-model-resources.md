# 03 — Move the canonical model into shared resources

[Back to task index](../TASKS.md)

Status: Completed
Complexity: Medium
Dependencies: [02](02-introduce-portable-core.md)

## Objective

Give both platforms one canonical copy of the corner model, its provenance, and license, with Android behavior unchanged.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions. Do not add iOS packaging; Task 17 bundles these resources.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/build.gradle — asset source sets and SHA-256 verification
- android/src/main/assets/doccornernet/ — model, provenance.json, license
- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerModels.kt — bundled asset lookup
- tools/models/README.md, prepare_doccornernet_runtime.py, export_adapted_doccornernet.py — regeneration output paths
- package.json — published files

## Current behavior and invariants

The build verifies the bundled model's SHA-256; Android loads it as an APK asset; tools/models regenerates it. The bundled input is 224×224.

## Implementation steps

1. Move the model, provenance, and license to one shared resource directory included in the published package.
2. Point Android's asset packaging, hash verification, and model lookup at it, keeping the APK asset path the runtime reads.
3. Update tools/models output paths and documentation in the same change; correct any comment that states a different input size.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- The Android build fails on a modified model and passes on the canonical bytes.
- The built APK contains byte-identical model bytes; the packed npm tarball contains the shared resources once.

## Validation commands

- npm run check
- Build the Android library using the documented development procedure.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

One canonical model copy exists; Android packaging, hash checks, runtime loading, and regeneration tools use it.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

build: move the corner model into shared resources

## Completion report

Moved the unchanged model, provenance, and license from `android/src/main/assets/doccornernet/` to `resources/doccornernet/`. Android main assets now include `../resources`; verification reads the shared files. The APK asset name remains `doccornernet/model.tflite`, so `DocCornerSession.BUNDLED_ASSET` and runtime callers need no change. The task lookup's `CornerModels.kt` is absent; the current constant lives in `DocCornerSession.kt`. Its bundled input constant and provenance already correctly specify 224, so no numeric behavior changed.

Updated the npm allowlist, export default and documented command, fixture generator lookup, and architecture reference. No iOS packaging was added. Updated the index to reflect completed Task 02 as well.

Validation on 2026-10-31:

- `npm run check`: library/example typechecks and all 16 JS contracts passed.
- Android setup: installed NDK `27.1.12297006`, Clang `18.0.2`, SDK CMake `4.1.2`, existing OpenCV prefab `4.12.0`; JDK `C:\Program Files\Android\Android Studio\jbr`. Device confirmed with `C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe devices`: `192.168.1.55:5555`, Samsung SM-G998U.
- From `example/android`, with that `JAVA_HOME`: `./gradlew.bat :react-native-document-scanner:assembleDebug -PreactNativeArchitectures=arm64-v8a --console=plain -q` passed. The same build with one model byte deliberately changed failed at `verifyDocCornerModelAsset` with `DocCornerNet model SHA-256 mismatch`; a finally block restored canonical bytes before the passing build. Logs: `.local/task03-tamper.log`, `.local/task03-build.log`.
- With SDK platform-tools on PATH and `ORG_GRADLE_PROJECT_docCornerFixtures=C:/dev/react-native-document-scanner/.local/doccornernet-runtime/assets`: `./tools/test-device.ps1 -Device '192.168.1.55:5555' -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -Class @('com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest') -Measure` built and installed the APK and passed all 5 tests, including the 13-photo measured parity check. Log: `.local/task03-device.log`. No shutter was automated.
- Python ZIP inspection of the built debug AAR and instrumentation APK confirmed model bytes equal the canonical source. SHA-256: `830ece3373c3ce6ff67a9023f10f56e3576c257de22d662f985ce27b747f3b35`. All three moved files also match their prior Git bytes exactly.
- `npm pack --dry-run --json --ignore-scripts --cache .local/npm-cache` and actual `npm pack --json --ignore-scripts --cache .local/npm-cache --pack-destination .local` passed. Tarball inspection found exactly one model and exactly the shared model/provenance/license resource set, with byte equality. The default external npm cache was unwritable in the sandbox, so validation used the ignored local cache.
- Both updated Python tools parse successfully; model regeneration was not run because the existing canonical bytes must remain unchanged. `git diff --check` passed.

Required Android/package checks passed. iOS bundling remains Task 17.
