# iOS scanner parity execution plan

Status: Tasks 01-13 completed (Task 04 physical-device checks deferred); Task 14 Android complete / iOS pending; Task 15 skipped; Task 16 completed; Tasks 17-24 completed; Tasks 25-33 planned

Implement iOS 18+ scanning on a shared C++20 algorithm/policy engine, migrating Android first. This index contains only the current iOS/shared-engine goal; every implementation task is linked below.

## Required constraints

- Preserve the public scanning workflow and current Android observable behavior. Share substantial algorithms and policies where the boundary is justified; keep cameras, permissions, lifecycle, runtime, files, and views platform-owned.
- Cap iOS still capture before allocation: long edge <=4096 and total pixels <=12,582,912, using only supported advertised dimensions. Exclude oversized modes instead of capturing then shrinking.
- Use JPEG source backing as the provisional low-friction iOS default, with quality following the existing capture-mode defaults and explicit jpegQuality honored. Per user direction (2026-10-31), defer TIFF/JPEG format and storage measurements until a working sample runs on a physical device. Keep source pixels leased/cached within the existing memory bound; do not claim format or end-to-end efficiency before measurement.
- Square framing must agree across preview, analysis, captured source, review, editing, thumbnails, and exports. Source bytes remain immutable after publication.
- Preserve one native model runtime/worker, session-owned FIFO admission and keyed replacement, scoped file cleanup, and generation-based stale-result rejection. Do not restore the obsolete one-running/one-waiting processor limit or add processor/busy. On iOS, keep source pixels behind explicit leases and a one-entry reusable full-source cache, with a bound on all live source memory including leased evicted buffers; Android keeps decoding each request during the migration.
- Android links the core against its existing OpenCV runtime; no second OpenCV runtime. FastCores.run stays around rendering and FastCores.prime on the inference worker.
- Policies take time explicitly in a documented clock domain, return decisions with any deadline, and leave wakeups and generation cancellation to their adapters (Task 02).
- Defer iOS sample collection with explicit public feature/unsupported errors. No automatic camera captures; every shutter is human-triggered.
- Read AGENTS.md and the docs skill before work. Private photos/reports stay in ignored .local/. Never import .local/archive or edit generated bindings by hand.

## Execution order

Tasks are numbered sequentially; each task declares its exact prerequisites. Task 04 verifies the iOS toolchain and model run early, so later iOS work does not discover a blocked dependency. Complete the Android migration gate before starting iOS integration. Task 01 establishes fixtures and Android baseline assertions; Task 02 adds the portable build/pixel boundary and separates existing native kernel bodies from JNI. Task 03 shares model resources; Task 04 verified x86_64 simulator execution and arm64 device linkage; physical-device checks are deferred until a working sample, preprocessing parity belongs to Task 06, and official OpenCV lacks an arm64 simulator slice. Task 05 moved source/crop/render geometry into the shared core and verified Android JNI integration. Task 06 moved tensor preparation, tensor metadata validation and prediction interpretation into the shared core and verified bitwise Android tensor parity and the existing photo budgets. Task 07 moved perspective/page-edge rendering and straight-warp coverage into the shared core, with exact Android pre-filter synthetic goldens and portable geometry tests. Task 08 moved shadows, paper normalization and ink/filter processing into the shared core, preserving render/preview ownership and validating 16 corrected-Kotlin-reference pre-encoding outputs within one intensity level, including the authorized sharpness depth-change fix. Task 09 moved four-bit grayscale PNG encoding and PDF predictor streams into the shared core with leased rows and failing sinks. Its full-suite checks also resolved Task 08’s unsafe sharpness depth-change alias, verified against a corrected Kotlin reference. Task 10 moved PDF option validation, layout/probing/reuse and streaming serialization into the shared core, preserving Android codecs/publication and verifying unchanged payloads plus failure atomicity. Task 11 moved optical-flow selection/tracking, regional motion evidence, fitted transforms and their bounded history, gyro integration/rotation and exposure-blur math into the shared core. Android retains sensor acquisition/clock validation and synchronous frame ownership; portable contracts, recorded-motion math and Android JNI/detector integration passed. Task 12 moved guide acceptance/expiry, tracked retention, adaptive pause/revalidation and its confirmed reference, score rejection and movement wake decisions into the shared core. Android retains scheduling/generations and publication; portable, JNI parity/replay, detector and JVM checks passed with existing score gating preserved. Task 13 moved document/tap focus ownership, retry/release/scan deadlines, completion tokens, snapshot generations into the shared core; Android retains CameraX operations, device retriggers and diopter parking. Portable contracts, 500-event Kotlin parity, fake-CameraX lifecycle and detector integration passed; overdue target-loss release after late scan completion is covered with and without a shot. Task 14 retains native torch policy; Task 15 is skipped. Capture gating remains platform-native, with Swift parity in Task 29. Task 16 is completed; Tasks 17-24 are completed; Tasks 25-33 remain planned.

Task 21 now owns the missing iOS native-session prerequisite as well as rendering/PDF: Phase A implements internal engine/file services; Phase B implements session state/resources, scheduling, observations, mutations, exports, and physical-close cleanup. Phase A alone leaves createSession unsupported and does not complete Task 21. Complete both phases before Task 22; Task 24 and the camera attachment/capture tasks also depend on that session owner. Synthetic native pages exercise the production admission path before Task 28 connects human-triggered captures. Tasks 22–33 retain their numbers; no public URI/import API or alternate JS session flow is introduced.

| Task | Outcome | Complexity |
| --- | --- | --- |
| [01](Tasks/01-establish-parity-fixtures.md) | Establish reusable Android parity fixtures | Medium |
| [02](Tasks/02-introduce-portable-core.md) | Introduce the portable C++ build and pixel boundary | High |
| [03](Tasks/03-share-model-resources.md) | Move the canonical model into shared resources | Medium |
| [04](Tasks/04-verify-ios-feasibility.md) | Verify iOS toolchain feasibility and source-format costs | High |
| [05](Tasks/05-share-image-geometry.md) | Share source, crop, and coordinate geometry | High |
| [06](Tasks/06-share-model-preprocessing.md) | Share tensor preprocessing and prediction interpretation | High |
| [07](Tasks/07-share-page-warping.md) | Share perspective warping and curved page edges | High |
| [08](Tasks/08-share-document-filters.md) | Share shadows, paper normalization, and ink filters | High |
| [09](Tasks/09-share-gray-png.md) | Share four-bit grayscale PNG output | Medium |
| [10](Tasks/10-share-pdf-writer.md) | Share streaming PDF layout and serialization | High |
| [11](Tasks/11-share-flow-and-motion.md) | Share optical flow and motion evidence | High |
| [12](Tasks/12-share-live-guide-policy.md) | Share live guide and adaptive-inference policy | High |
| [13](Tasks/13-share-focus-policy.md) | Share document and tap focus policy | High |
| [14](Tasks/14-share-torch-policy.md) | Keep automatic torch policy platform-native | Low |
| [15](Tasks/15-share-capture-readiness.md) | Keep capture readiness platform-native (skipped migration) | Skipped |
| [16](Tasks/16-verify-android-engine-migration.md) | Verify Android after the shared-engine migration | High |
| [17](Tasks/17-add-ios-package-and-nitro-bindings.md) | Add a compilable iOS package and Nitro registrations | High |
| [18](Tasks/18-implement-ios-camera-permission.md) | Implement iOS camera permission ownership | Medium |
| [19](Tasks/19-implement-ios-source-store.md) | Implement bounded iOS source storage and pixel leases | High |
| [20](Tasks/20-implement-ios-inference.md) | Implement shared iOS inference and photo analysis | High |
| [21](Tasks/21-implement-ios-processor.md) | Implement iOS native sessions, rendering, and PDF (two phases) | High |
| [22](Tasks/22-implement-ios-image-viewer.md) | Implement the iOS viewer and shared thumbnail loader | High |
| [23](Tasks/23-implement-ios-image-pager.md) | Implement the iOS native image pager | Medium |
| [24](Tasks/24-implement-ios-crop-editor.md) | Implement the iOS crop editor | High |
| [25](Tasks/25-implement-ios-camera-session.md) | Implement the bounded iOS camera session and preview | High |
| [26](Tasks/26-implement-ios-live-guidance.md) | Connect iOS frame analysis to shared live guidance | High |
| [27](Tasks/27-implement-ios-camera-controls.md) | Connect iOS focus, torch, and exposure controls | High |
| [28](Tasks/28-implement-ios-still-capture.md) | Implement bounded pixel/JPEG capture and source publication | High |
| [29](Tasks/29-implement-ios-capture-gating.md) | Implement native iOS capture readiness | High |
| [30](Tasks/30-implement-ios-capture-feedback.md) | Implement native iOS capture feedback and retained preview | Medium |
| [31](Tasks/31-verify-ios-lifecycle-and-resources.md) | Complete iOS interruption and resource-lifecycle integration | High |
| [32](Tasks/32-integrate-ios-example.md) | Integrate the complete scanning example on iOS | High |
| [33](Tasks/33-validate-cross-platform-release.md) | Validate numerical parity, bounded capture, and release readiness | High |

## Validation and reporting

Use npm run check for routine JS validation; native/core/Mac/device checks are separate. Record actual commands, device/SDK/scheme, passed/failed/skipped results, and unavailable prerequisites. A build is not a device result. Preserve existing numerical tolerances. Do not mark release validation complete with required physical evidence missing.

Environment facts not established here: an available Mac test host/project registration, Xcode scheme/destination, usable OpenCV device/simulator artifacts, native C++ test toolchain, and physical iOS devices. Resolve these in the owning setup task before dependent work; do not invent them.

Plan-authoring verification: validate links, sequential numbers, required task sections, and git diff --check after saving.


Task 14 revised: Android torch stays in Kotlin; Swift parity is pending Task 27. Both policies consume tests/fixtures/parity/torch.tsv. The C++/JNI torch migration was reversed because its boundary overhead outweighed the small policy.

Task 16 Android optimization/integration pass: 181 release device tests, 10 core suites, 66 JVM tests and JS/typechecks passed. Native render/filter composition and direct Mat PNG input remove known copies. Full Kotlin/C++ timing remains frequency-confounded; subsequent human acceptance is recorded below. See Tasks/16-verify-android-engine-migration.md and .local/engine-optimization/report.md.

Native-session migration history: [separate task plan](Tasks/native-sessions/TASKS.md).

Task 16 integrated with current native-session main (2026-11-01): 252 Android tests, 128 JVM tests, 10 core suites, both typechecks and 39 JS contracts passed. Native sessions own orchestration/files; shared core owns processing stages. Human acceptance on 2026-11-02 confirmed minified-release startup, live detection/visible guidance, capture, crop and export; Task 16 is completed.

Task 17 completed (2026-11-02): current eight Nitro registrations and five UIKit scaffolds packaged for iOS 18+, with shared core and canonical model resources. Intel simulator and generic arm64 test builds passed; seven native smoke tests, Android build, typechecks and 40 JS contracts passed. Task 17 established scaffolds; subsequent functional implementation is tracked below. Apple Silicon simulator support is not claimed.

Task 18 completed (2026-11-02): process-wide iOS camera authorization now supports shared prompts, typed presentation failures, foreground Settings refresh and listener/observer teardown independently of a camera view. Intel simulator build and 17 native tests (10 permission + 7 package smoke), both typechecks and 40 JS contracts passed. Real-device prompting and attached-camera resume remain for later camera validation.

Task 19 completed (2026-11-02): internal process-wide iOS source storage provides bounded resident/leased RGBA pixels, ImageIO TIFF/JPEG/PNG access, provisional JPEG backing with normalized EXIF/crop metadata, thumbnail reuse and validated deferred owned-file deletion. Intel simulator build and 30 native tests (12 source-store + 1 new C++ lease + 17 existing), both typechecks and 40 JS contracts passed; generation is unchanged. Session/camera/view integration and physical-device quality measurements remain for later tasks.

Task 19 local post-review corrections (2026-11-02): completed retirement releases full metadata with a 256-URL FIFO idempotence history, shutdown clears the registry, and memory-warning eviction is asynchronous. Local checks passed; both new iOS regressions subsequently compiled and passed in Task 20’s 38-test simulator suite.


Task 20 completed (2026-11-02): internal shared iOS CPU inference now owns immutable model generations, serialized validation/warmup/replacement and native owner leases. Shared tensor preparation/decoding and seeded photo mapping serve leased source pixels; synchronous CVPixelBuffer access unlocks on success/failure before returning. Intel simulator build and all 38 native tests passed, including 13 private photo fixtures and Task 19 review regressions; tensor output error <=7.31e-7 and image/reload corner errors stayed within existing budgets. All 10 portable suites, both typechecks and 40 JS contracts passed. Public session/camera integration and physical-device validation remain later work.


Task 21 Phase A validated (2026-11-03): native render/PDF services passed seven focused tests and two unfiltered Intel simulator runs, each 45 tests with zero failures and one optional private-inference-fixture skip. The second run reused the cache successfully. Three ObjC++ dependency-link cases are included beyond the earlier 42-test selection. Runner repairs remain outside the repo.

Task 21 Phase B validated (2026-11-03): native sessions, resources, exports, read contract and deferred sampling passed 15 new session tests within a 60-test clean simulator run (zero failures, one optional private-fixture skip) and a cached rerun; npm run check passed 41 JS contracts. Task 21 is completed; camera capture and physical-device validation remain with later tasks.

Task 23 completed (2026-11-03): the iOS pager recycles Task 22 viewer cells in a UICollectionView, keeps selection by page ID, arbitrates zoom versus swipes natively and preloads one neighbour per side. 8 focused pager tests passed with the viewer (12) and scaffold (5) suites on the Intel simulator; npm run check passed 41 JS contracts. Physical-device swipe feel remains unverified.
