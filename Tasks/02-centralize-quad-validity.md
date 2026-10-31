# 02 — Centralize native quad validation

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: Medium
Dependencies: None

## Objective

All native producers/consumers use explicit shared policies without changing supported prediction or render behavior.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `android/src/main/java/com/margelo/nitro/documentscanner/CropQuadEditing.kt: clampToFrame, moveCorner, moveEdge`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/DocCornerSession.kt: decodeOutput, validNormalizedQuad`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt: validateQuad, targetCanvas`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/PhotoInferenceCrop.kt`
- `android/src/test/java/com/margelo/nitro/documentscanner/CropQuadEditingTest.kt`

## Current behavior and invariants

The native editor already rejects crossed, concave and collapsed drags and clamps movement to the image. Model decoding rejects invalid normalized quads; render validation permits bounded extrapolation. These are different supported policies, not one universal bounds check.

## Implementation steps

1. Introduce NativeQuadGeometry (new, pure Kotlin) with shared finite/turn/area primitives and named prediction, editor and render validation policies. Preserve existing tolerances and renderer extrapolation/size checks.

2. Route CropQuadEditing, DocCornerSession, PhotoInferenceCrop and DocumentRenderer through it; analysis publishes valid in-frame candidates or no candidate. Revalidate mapped photo coordinates before publication.

3. Keep onQuadChange valid-only. Do not add a redundant boolean or unconstrained-editor mode. Define invalid developer-supplied crop geometry as input/invalid rather than silently repairing it.

4. Add NativeQuadGeometryTest; extend existing crop and photo-crop tests. Leave JS helpers until the example cutover.

## Ownership and boundary rules

Coordinates stay normalized upright TL/TR/BR/BL. Editor geometry stays inside 0..1; renderer extrapolation remains available internally. Do not apply editor minimum area to model prediction acceptance.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Finite/full-frame, crossed, concave, collinear, tiny, non-finite and extrapolated cases use their intended policies. Invalid editor moves emit nothing; invalid render input rejects before allocation.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

Authorized JVM command: npm run test:native -- -Test '*NativeQuadGeometryTest,*CropQuadEditingTest,*PhotoInferenceCropTest,*DocCornerGeometryTest'

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

All native producers/consumers use explicit shared policies without changing supported prediction or render behavior.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

refactor(geometry): centralize native quad validity

## Implementation evidence — 2026-10-31

- Added NativeQuadGeometry.kt with shared finite, turn and area primitives and distinct prediction, editor and render policies. Prediction retains turn > 1e-6 without an editor area floor; editor retains turn > 1e-8 and area >= 1e-4 inside the frame; render retains turn > 1e-8, extrapolation -0.8..1.8 and area 1e-4..4.0. Renderer side-length, aspect-ratio and pixel-count checks remain in targetCanvas.
- Routed CropQuadEditing, CropEditorView, HybridDocumentCropEditorView, DocCornerSession, PhotoInferenceCrop, DocumentRenderer and the former live-guide validator callers through the shared policies. Removed the old validator and editor clamping helpers.
- Invalid developer crop props report typed input/invalid and preserve the last valid editor quad. Drag clamping remains supported; invalid moves return no replacement and emit no edit. No public API or generated bindings changed.
- PhotoInferenceCrop.toPhoto now returns no quad when mapped coordinates fail prediction policy; HybridDocumentScannerProcessor propagates that as no candidate. Invalid render geometry rejects before source loading or output allocation.
- Added NativeQuadGeometryTest; extended CropQuadEditingTest, PhotoInferenceCropTest and DocCornerGeometryTest. Extended CropEditorViewTest for rejected props and no callback after an invalid drag, and DocumentRendererTest for rejection before source loading.
- Passed: git -C C:/dev/react-native-document-scanner diff --check. New-file whitespace checks also passed.
- LSP: requested diagnostics for the shared geometry, crop editing/view/boundary, processor, model decoding, photo mapping and renderer; no diagnostics were reported. Reference coverage was incomplete, so focused source searches confirmed callers. LSP results do not establish compilation or runtime correctness.
- Not run: npm run test:native -- -Test '*NativeQuadGeometryTest,*CropQuadEditingTest,*PhotoInferenceCropTest,*DocCornerGeometryTest', instrumentation tests, native compilation/builds or device checks. The brief requires separate explicit native-run authorization, which has not been supplied.
- Status updated at user request: implemented, with required native verification pending. Implementation-stage scope only; no threshold tuning or later-task work.
