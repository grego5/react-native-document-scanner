# 01 â€” Establish reusable Android parity fixtures

[Back to task index](../TASKS.md)

Status: Completed
Complexity: Medium
Dependencies: None

## Objective

Record the observable Android baseline before moving production algorithms.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- .agents/skills/docs/references/architecture.md â€” Images, Capture quality, Live guidance, Document focus
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/DocCornerNetRuntimeTest.kt â€” runtime fixture assertions
- android/src/test/java/com/margelo/nitro/documentscanner/CaptureGateTest.kt and CaptureStabilityTest.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/LiveQuadTraceReplayTest.kt
- tools/models/README.md â€” private fixture preparation

## Current behavior and invariants

The bundled model is 224Ã—224 float32 RGB with nine outputs. Photo tensor error is bounded by the existing 2e-5 assertion; photo corner fixture limits are mean .005 and worst .015.

## Implementation steps

1. Inventory existing pure-policy, instrumented image, and trace cases; map every later migration to named baseline cases.
2. Create portable synthetic fixtures for orientation, strided pixels, quads, policy events, filters, gray PNG, and PDF placement under tests/fixtures; keep private images and reports in ignored .local/.
3. Add an Android export harness only where existing tests cannot expose engine-boundary inputs and expected outputs. Do not record new captures automatically.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Each fixture records dimensions, format, coordinate space, expected results, and applicable tolerance.
- Show which cases run without private assets and report private-fixture skips explicitly.

## Validation commands

- npm run check
- npm run test:native -- -Test '*CaptureGateTest,*CaptureStabilityTest,*LiveQuadStabilizerTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Portable fixture inventory and Android baseline assertions exist; unavailable device runs are identified without claiming parity.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test: establish cross-platform scanner parity fixtures



## Implementation and validation — 2026-10-31

Implemented portable UTF-8 TSV fixtures and a migration inventory in [tests/fixtures](../tests/fixtures/README.md). Seven fixture files cover orientation, padded RGB rows, normalized quads, ordered capture policy events, filters, gray PNG and PDF placement. A test-only reader loads the same files as JVM resources and instrumentation assets. Existing source-loader, faint-stroke and independent PNG assertions consume those fixtures; new JVM and PDF assertions freeze boundary results. No production algorithms, thresholds, generated bindings or public contracts changed. No export harness was needed because existing test boundaries expose the required inputs and results.

Changed files: tests/fixtures/README.md and parity/*.tsv; android/build.gradle; testSupport/processing/ParityFixtures.kt; test/processing/ParityFixtureTest.kt and GrayscalePngTest.kt; androidTest/processing/SourceImageLoaderTest.kt, DocumentFiltersTest.kt and PdfWriterTest.kt; this task and TASKS.md. Test paths above are under android/src with the existing com/margelo/nitro/documentscanner package.

Validation:
- npm run check: passed library/example typechecks and all 16 JS contract tests.
- The planned npm run test:native command with comma-separated -Test values compiled but failed with no tests found: npm forwarded one combined Gradle filter. Corrected invocation below passed 46 tests, zero failures, one assumption skip across 47 tests. The skip is LiveQuadTraceReplayTest.recordedTracesReplay: no private recorded trace path was supplied.
- Expanded equivalent native invocation from repository root:
  ./tools/test-native.ps1 -Test @('*CaptureGateTest','*CaptureStabilityTest','*LiveQuadStabilizerTest','*ParityFixtureTest','*GrayscalePngTest','*DocCornerGeometryTest','*LiveQuadTraceReplayTest')
- Device: Samsung SM-G998U, adb serial 192.168.1.55:5555, Android API 35, arm64-v8a. Existing prepared example/android project; compile SDK/build tools 37, NDK 27.1.12297006, Kotlin 2.2.10, existing org.opencv:opencv:4.12.0. No new OpenCV runtime or shared-core build.
- Expanded device invocation from repository root (platform-tools added to PATH):
  $env:ORG_GRADLE_PROJECT_docCornerFixtures = 'C:/dev/react-native-document-scanner/.local/doccornernet-runtime/assets'
  ./tools/test-device.ps1 -Device '192.168.1.55:5555' -Class @('com.margelo.nitro.documentscanner.processing.SourceImageLoaderTest','com.margelo.nitro.documentscanner.processing.DocumentFiltersTest','com.margelo.nitro.documentscanner.processing.PdfWriterTest','com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessorTest','com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest')
- Instrumentation APK built and installed successfully. Runner returned OK (26 tests): 25 executed successfully, privatePhotosMeetNativeParityBudgetAndRecordWarmTimings assumption-skipped because -Measure was not supplied. Prepared private assets were included in this test APK; their presence does not establish private-photo parity.
- Attempted focused follow-up with -SkipBuild -Measure -Class @('com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest#privatePhotosMeetNativeParityBudgetAndRecordWarmTimings'): not executed. Automatic approval review rejected installing/executing an APK containing private-photo fixtures on the network-connected device without explicit user authorization for the transfer. Do not count this check as passed. Explicit authorization is needed before retrying.
- Verified all TSV row widths and referenced inventory method names; git diff --check passed.
- Private command logs remain ignored: .local/task01-native.log, .local/task01-device.log and .local/task01-private.log. The completed photo parity report is stored at .local/task01-photo-report.json.
- Kotlin LSP was unavailable; used focused source inspection. Existing PNG test compiler warning (Any? vs Any) and Gradle deprecation warnings remain unchanged.

The fixture/inventory prerequisite and required local checks are complete. Private recorded traces remain unvalidated because no trace path was supplied. Photo parity completed successfully after the explicit authorization recorded below. Cross-platform parity, shared C++ compiler setup, Mac/Xcode setup, performance/memory release gates and human-triggered camera evidence are not established by Task 01 and remain with their owning tasks.


### Authorized photo-parity follow-up — 2026-10-31

The user explicitly authorized transferring the existing test fixtures and executing tests on 192.168.1.55:5555. Re-ran the previously rejected command using the successfully built test APK:

    ./tools/test-device.ps1 -Device '192.168.1.55:5555' -SkipBuild -Measure -Class @('com.margelo.nitro.documentscanner.processing.DocCornerNetRuntimeTest#privatePhotosMeetNativeParityBudgetAndRecordWarmTimings')

Result: installation succeeded, runner returned OK (1 test), 9.388 seconds. No camera capture occurred. Retrieved the generated report to ignored .local/task01-photo-report.json and parsed it with Python json.load semantics. All 13 reference photos ran on Samsung SM-G998U with photo decode cap 2000 pixels. The test verifies model/photo hashes and the unchanged tensor and corner-error assertions.

- Maximum tensor-output error: 7.71135091782e-07 (limit 2e-5).
- Upright PNG corner error: mean 1.04931313123e-07, worst 7.85400401241e-07.
- Sampled JPEG corner error: mean 0.000297064968413, worst 0.00169124685261.
- Both photo paths pass mean <=.005 and worst <=.015. This resolves the earlier photo-test approval blocker; the recorded-trace skip remains. It does not establish later cross-platform release gates.
