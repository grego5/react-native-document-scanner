# 17 — Add a compilable iOS package and Nitro registrations

[Back to task index](../TASKS.md)

Status: Completed — Intel simulator verified; arm64 device build/link verified
Complexity: High
Dependencies: [04](04-verify-ios-feasibility.md), [16](16-verify-android-engine-migration.md)

## Objective

Make the library install and build on iOS 18+ with all generated registrations aligned.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- package.json — package files and Nitro versions
- nitro.json — eight current autolinking registrations
- react-native.config.js — ios:null
- src/*.nitro.ts — platform mappings
- C:/Users/User/.codex/skills/react-native-modules-development/references/nitro/docs/getting-started/how-to-build-a-nitro-module.md — generated iOS podspec integration

## Current behavior and invariants

Public names remain Camera, DocumentScanner, CropEditor, ImageViewer, ImagePager, and CameraPermission. Bindings are generated from TypeScript only.

## Implementation steps

1. Add ReactNativeDocumentScanner.podspec, ios/ Swift/Objective-C++ sources, generated autolinking integration, shared C++ sources, and package files; enable iOS dependency autolinking.
2. Add ios:'swift' to eight specs and matching Swift classes/registration keys, using generated base classes.
3. Add feature/unsupported transport. Until later tasks supply functionality, make unfinished operations explicit typed failures; report the scaffold as incomplete, never release-ready.
4. Package the OpenCV and TFLite integration Task 04 verified, with the slices and versions it recorded; re-verify linkage in the library build.
5. Bundle the shared model resources from Task 03 into the iOS package.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Regenerate and compile both platforms; instantiate registered objects and mount all five view types without unresolved native symbols.
- Verify the iOS bundle contains the exact shared model bytes Android verifies.

## Validation commands

- npm run nitrogen
- npm run check
- On a verified Mac, install pods and run xcodebuild build-for-testing for a verified library test-host scheme; record exact workspace, scheme, SDK, and destination.
- Build the Android library using the documented development procedure.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Package installs/builds on iOS 18+ and Android still builds; dependency slices and source generation are verified.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

build: add iOS Nitro scanner package


## Implementation and validation — 2026-11-02

Adapted the pre-session task to the current eight registrations, including ScanSession and PageThumbnailView. Added Swift conformances for every generated spec, empty stable UIKit view scaffolds, feature/unsupported result transport, example display copy, and CocoaPods package/autolinking setup. All shared core sources and canonical model resources are included; TensorFlowLiteC is pinned to Task 04's 2.17.0. A local DocumentScannerOpenCV podspec uses the official 4.12.0 archive; the consuming Podfile must declare it as documented in ios/README.md. This packaging/linkage has not yet been validated on the Mac. The official binary still lacks an arm64 simulator slice; no Apple Silicon simulator support is claimed.

Nitrogen 0.37.1 regenerated all eight specs (no generated hand edits). Both typechecks and 40 JS contracts passed. Android :react-native-document-scanner:assembleDebug -PreactNativeArchitectures=arm64-v8a passed in 2m 4s (79 actionable tasks). Logs: .local/task17-check.log and .local/task17-android-build.log. Package dry run includes the podspecs, Swift sources, generated iOS registration and canonical model. Generated files contain generator-emitted trailing whitespace. Added unrun XCTest smoke coverage for scaffold failures, stable identities of the five UIKit views, and the canonical model SHA256 in the resource bundle.

Mac validation initially failed because Admins-Mac did not resolve (vmac run c3e34be5-df70-4044-b38b-ceff9f97b6d9, no build/test result). User subsequently restored the Mac and requested storage cleanup before continuing. iOS pod installation, library build, registered-object/view mounting, model bundle verification and dependency linkage remain unrun. Task 17 remains In progress and the package is not release-ready. All machine-specific infrastructure remains outside this repository.

### Restored Mac validation

User authorized Task 17 repository transfers and restored the Mac. Cleaned inspected regenerable caches/old Inksign build output, recovering approximately 9 GiB (2.4 GiB free before cleanup, 11 GiB immediately after; about 8 GiB after preparing package dependencies). Preserved source checkouts, installed Xcode/simulator runtimes, Task 04 fixtures/dependencies and saved reports.

Installed the packaged library in an isolated host under /Users/admin/Projects/react-native-document-scanner/.local/task17. External host preparation script: C:/dev/mcp/vmac/profiles/docscanner/task17-package.sh. CocoaPods installation passed, including official OpenCV 4.12.0 and TensorFlowLiteC 2.17.0. Xcode 26.6 (17F113), simulator SDK 26.5; workspace host/ScannerPackageValidation.xcworkspace, scheme ReactNativeDocumentScanner-Unit-ScaffoldTests, Debug, destination platform=iOS Simulator,id=7C539F34-E6DA-4D55-AF59-98A50A35C2D7, CODE_SIGNING_ALLOWED=NO, build-for-testing.

Initial isolated-host compile missed NitroModulesSpec because the host package manifest omitted Nitro; corrected the host and regenerated pods. The corrected build reached library Swift compilation but failed importing vector<string> collection conformances: generated callback .map and ScanSessionChange/ScanSessionSnapshot map/for-in conversions fail. An authored CxxStdlib re-export experiment did not resolve it and was removed. No generated source was hand-edited. This resembles upstream Nitro issues https://github.com/margelo/nitro/issues/1186 and https://github.com/margelo/nitro/issues/1376; the exact trigger here is not yet isolated. Task 17 remains In progress: no successful iOS test build or XCTest run, generic-device package linkage and runtime/mount/model-bundle assertions remain pending.

Mac logs: .local/task17/package-build.log, package-install-retry.log, xcode-simulator.log, xcode-simulator-retry.log, xcode-cxx-import.log. XCTest source also exercises all eight generated factories and the pinned TFLite version, but remains unrun.

### Swift importer continuation — 2026-11-02

Implemented a candidate workaround for the generated defaulted equality/importer interaction described in Nitro #1186/#1376. tools/nitrogen.cjs creates an isolated copy of the installed 0.37.1 generator under ignored .local, changes equality emission at its source, and generates bindings normally. It never modifies installed packages or post-edits generated output. Generated equality remains enabled for normal C++/Android; DOCUMENT_SCANNER_SWIFT_IMPORT is defined only for the pod's Swift Clang importer to hide those convenience operators there. This candidate has not yet been validated by the Swift compiler.

All 338 generated files reproduced byte-for-byte on a second run. Both typechecks and 40 JS contracts passed. Android assembleDebug arm64-v8a rebuilt successfully in 1m 21s (79 actionable tasks, 11 executed, 68 up-to-date), log .local/task17-android-equality-build.log. Added Objective-C++ smoke coverage for actual Nitro registry construction, core/OpenCV runtime linkage, and UIKit mount/unmount coverage for all five views, alongside model hash/TFLite checks. These iOS tests remain unrun.

The attempted package transfer/reinstall failed before transfer because Admins-Mac stopped resolving again; direct SSH and local hostname discovery also failed. Asked the user whether the VM is running or for its current IP. The Mac still has the earlier package without the candidate generator fix. iOS compilation, XCTest and generic arm64 linkage are pending reachable Mac access; Task 17 is not completed.

## Completion evidence — 2026-11-02

Restored Mac access allowed the regenerated package to install and build. The scoped generator workaround fixes this package's Swift vector/Sequence import failure: the pod's Swift importer omits generated defaulted equality convenience operators, while normal C++/Android retains them. Installed Nitrogen remains unchanged and no generated files were hand-edited. Source generation is version-guarded at 0.37.1 and all 338 generated files reproduced byte-for-byte in a second generation run.

Validation passed:

- CocoaPods package installation with OpenCV 4.12.0 and TensorFlowLiteC 2.17.0. The helper OpenCV podspec is declared by the consuming Podfile as documented in ios/README.md.
- xcodebuild build-for-testing on ScannerPackageValidation.xcworkspace, scheme ReactNativeDocumentScanner-Unit-ScaffoldTests, Debug, destination platform=iOS Simulator,id=7C539F34-E6DA-4D55-AF59-98A50A35C2D7, CODE_SIGNING_ALLOWED=NO.
- Seven actual XCTest cases passed, zero failures, 8.415 s overall, using the previously verified standalone Intel simulator XCTest runner. These construct all eight registered objects through Nitro's actual registry and generated factories, mount/unmount all five UIKit views, exercise unsupported failures, verify OpenCV 4.12.0/TFLite 2.17.0/core linkage and confirm the bundled canonical model SHA256 830ece3373c3ce6ff67a9023f10f56e3576c257de22d662f985ce27b747f3b35. This is native UIKit scaffold coverage, not a full React Native example/camera run.
- The same workspace/scheme/configuration built for testing with destination generic/platform=iOS and CODE_SIGNING_ALLOWED=NO. The resulting test binary is Mach-O arm64; no physical device run is claimed.
- Android arm64-v8a assembleDebug passed after the generator change (1m 21s); both TypeScript checks and all 40 JS contracts passed.

Toolchain: Xcode 26.6 (17F113), Apple Clang 21.0.0 (clang-2100.1.1.101), Apple Swift 6.3.3 (swiftlang-6.3.3.1.3), iOS and simulator SDK 26.5, deployment target iOS 18.0. Validation host: /Users/admin/Projects/react-native-document-scanner/.local/task17/host; derived products are in sibling derived-data. Host preparation and standalone test scripts are maintained outside the repository in the vmac docscanner profile. Earlier failed builds/connectivity are retained above as history; they are superseded by these actual results.

Evidence copied to this worktree's ignored .local/task17-ios-simulator-build.log, task17-ios-device-build.log, task17-ios-xctest.log and task17-pods.log; original logs remain in the Mac's .local/task17. Authored-file whitespace checks pass; unmodified generator formatting still emits trailing whitespace.

Task 17 is completed for the user-approved Intel simulator / arm64 device-build scope. The official OpenCV framework still lacks an arm64 simulator slice, so Apple Silicon simulator support is not claimed. Physical-device behavior, a full iOS React Native example and all functional features belong to subsequent tasks; unfinished scaffold operations remain explicit failures. This is not iOS release readiness.
