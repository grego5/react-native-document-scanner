# 01 — Fix depth-changing Laplacian aliasing

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: Low
Dependencies: None

## Objective

The input survives Laplacian destination allocation; measurements match the independent reference.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `android/src/main/java/com/margelo/nitro/documentscanner/processing/InkDetail.kt: measure, temporary Mats and tile sharpness`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentFilters.kt: InkDetail consumer`
- `android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/DocumentFiltersTest.kt`
- `.agents/skills/docs/references/architecture.md: Images`

## Current behavior and invariants

InkDetail blurs into edges, then calls Laplacian(edges, edges, CV_16S). OpenCV 4.12 creates the destination before using the source; depth-changing aliasing can replace the blurred input. Preserve filter arithmetic and constants.

## Implementation steps

1. Use distinct Mat headers/storage for blurred 8-bit input, signed 16-bit Laplacian output, and 8-bit absolute magnitude; feed only the magnitude into tile measurements.

2. Release all added temporaries in finally alongside the existing Mats. Do not clone whole pages or change sharpening/deepening constants.

3. Add a focused InkDetailTest instrumentation class comparing measurements against the separate-buffer reference on blank, sharp and blurred synthetic pages. Exercise repeated calls with other allocations between them.

## Ownership and boundary rules

No JNI or public API change. Do not combine with filter tuning or performance work.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Blank input has zero edge magnitude; sharp/blurred fixtures match the reference and are stable across repeated allocations. Existing DocumentFiltersTest expectations remain unchanged.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

Authorized device command: npm run test:device -- -Device <verified-adb-id> -Class com.margelo.nitro.documentscanner.processing.InkDetailTest,com.margelo.nitro.documentscanner.processing.DocumentFiltersTest

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

The input survives Laplacian destination allocation; measurements match the independent reference.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

fix(filters): preserve blurred input during sharpness measurement

## Implementation evidence — 2026-10-31

- Changed: InkDetail.kt now keeps distinct blurred CV_8U input, CV_16S Laplacian output and CV_8U magnitude Mats. Tile measurements consume magnitude only; all temporary Mats release in finally. Filter arithmetic and constants are unchanged.
- Added: InkDetailTest.kt instrumentation coverage for blank, sharp and partly blurred synthetic pages. A separate-buffer reference computes tile quantiles from sorted pixels and expected sharpening/deepening maps. Six measurements per fixture run with competing allocations and verify unchanged source pixels. Private maps are inspected in tests without adding production APIs.
- Source inspection: DocumentFilters owns and closes the returned InkDetail; existing DocumentFiltersTest expectations remain unchanged.
- Passed: git -C C:/dev/react-native-document-scanner diff --check.
- Not run: InkDetailTest and DocumentFiltersTest instrumentation, native compilation, builds and device checks. The brief requires separate explicit authorization for native commands; none was requested. Kotlin LSP reported stopped, so discovery used focused source inspection.
- Status updated at user request: implementation is present, but required native measurement/reference verification is pending. No claim of Kotlin compilation or runtime correctness is made.
