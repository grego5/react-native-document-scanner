# 06 — Expose the native session and versioned metadata transport

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: [05](05-own-page-revisions.md)

## Objective

The native session is constructible through the facade and exposes typed, versioned metadata and ID commands.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `src/index.ts: nativeProcessor, DocumentScanner`
- `src/DocumentScannerProcessor.nitro.ts: public types`
- `src/ScannerError.ts: scannerOperation, scannerValue`
- `nitro.json: autolinking`
- `android/src/main/java/com/margelo/nitro/documentscanner/ScannerErrors.kt`

## Current behavior and invariants

The public facade wraps one global processor. Native failures travel as typed result unions and become ScannerError. Retain that boundary pattern for new session operations.

## Implementation steps

1. Add src/ScanSession.nitro.ts and HybridScanSession.kt (new), with native session creation exposed through DocumentScanner.createSession(). Session lifetime is explicit, not tied to JS garbage collection.

2. Expose page-ID commands setQuad, setFilter, setRemoveShadows, removePage and close. Mutations return promises that acknowledge admission; page processing outcome arrives through metadata. Invalid/unknown IDs reject input/invalid, closed sessions reject processor/disposed.

3. Define immutable transport snapshots containing sourceVersion, closed and page entries keyed by ID with page revision, analysis, effective quad/options, analyzing/rendering/ready status and operation-specific failure. Do not include image paths or presentation order.

4. Implement atomic observer registration returning the initial snapshot/version and sequenced subsequent changes. Copy state under its short state lock, then dispatch callbacks in order on the native main owner; never call JS while holding the state lock.

5. Add stable error codes processor/superseded, capture/busy and capture/not-ready to source definitions and their mappings. Preserve separate model/permission/sample-folder controls.

6. Register through nitro.json and run npm run nitrogen; inspect generated Android transport signatures. Add bridge integration tests for registration, errors and close. Legacy URI endpoints remain temporary old implementation, not aliases, until Task 13 removes them.

## Ownership and boundary rules

No hand edits to generated output. Native registry owns session/page lifetime. No page HybridObjects, file paths or native ordering in JS state. Creation and observer registration must not lose events before callbacks install.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Snapshot and events bracket a concurrent mutation without loss; unknown IDs fail explicitly; sourceVersion increases on observable changes; close rejects admission and delivers terminal metadata once; every Promise settles once.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run nitrogen; npm run typecheck; npm run test:contracts. Native bridge checks require separately authorized test:device with ScanSessionBridgeTest.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

The native session is constructible through the facade and exposes typed, versioned metadata and ID commands.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(api): expose native scan sessions

## Implementation evidence

- Added src/ScanSession.nitro.ts and HybridScanSession, registered the ScanSession HybridObject in nitro.json, and exposed DocumentScanner.createSession through a typed processor factory. The public facade translates native failure unions through the existing ScannerError helpers. Native registry state/pages survive JS wrapper collection until explicit close and physical cleanup; processor disposal does not close independent sessions.
- Added ID commands setQuad, setFilter, setRemoveShadows and removePage with immediate admission acknowledgements. Unknown/invalid IDs or geometry return input/invalid; closed-session mutations and observer registrations return processor/disposed. Repeated close uses the native state's stable completion, with each result promise settling independently after cleanup.
- Added sourceVersion, closed and ID-indexed page metadata: requested revision, ready revision, analyzing/rendering/ready status, authoritative analysis, effective quad/options and separate analysis/render failures. No image paths, cache objects or presentation order cross transport. Source versions increase only for observable metadata changes, including admission, edits, processing outcomes, removal and terminal close.
- Added ScanSessionEvents sharing the short state lock. Registration captures the initial snapshot and installs its observer atomically. Snapshots use fresh unmodifiable native maps; callbacks dispatch FIFO on the Android main owner outside the state lock and then through Nitro's JS scheduler. Unsubscribe is idempotent, callback exceptions cannot stall other observers, dispatch rejection releases retained callbacks, and close suppresses unclaimed old-generation delivery before one terminal event.
- Added capture/busy and capture/not-ready to source error definitions, generated mappings and the example's exhaustive messages. processor/superseded was already present from Task 03 and remains unchanged. Model, permission and sample-folder controls retain their existing owners.
- Added three JavaScript facade contracts for typed admission/errors, registration versions and asynchronous close completion. Extended controlled ScanSessionStateTest coverage for version/no-op rules, immutable retained snapshots, concurrent registration without gaps, unlocked/reentrant callbacks, unsubscribe during dispatch, terminal delivery, callback exceptions and rejected dispatch. Added ScanSessionBridgeTest using real generated structs/Nitro promises, synthetic source pixels and a blocked worker to cover admission without waiting, factory independence, errors, main-owner callbacks and repeated close. Native tests are written, not executed.
- Updated the architecture Images section. Legacy URI endpoints remain their temporary existing implementation. No capture, view, export or frame-batched JS-store work from later tasks was added; the current example remains type-compatible.

### Verification

- Passed: npm run nitrogen — final run generated 7/7 HybridObjects. Inspected generated Kotlin session/result/observation signatures, JNI snapshot Map conversion, constructor registration and autolinking sources. No generated files were edited manually.
- Generation initially rejected Readonly<Record<...>> as an unsupported empty mapped struct. The spec now uses supported Record transport; the public facade applies recursive read-only types and native maps are unmodifiable. Regeneration then passed.
- Passed: npm run check — library and example typechecks plus 19/19 JavaScript contracts. An initial duplicate DocumentFilter export was corrected before the successful runs. These checks do not establish native correctness.
- Passed: tracked/new authored-source whitespace checks. Standard git diff --check reports trailing whitespace emitted by Nitrogen in generated method separators (including HybridDocumentScannerProcessorSpec.kt); new generated bindings also contain emitted whitespace (including JHybridScanSessionSpec.cpp). Generated output remains unmodified under the project rule; the full whitespace check is not reported as passed.
- Supplementary inspection: LSP returned no diagnostic items for changed Kotlin production/test files. Reference results were incomplete and were confirmed by focused source/generated-binding inspection. This is not native compilation evidence.
- Not run: native JVM tests, Gradle compilation/APK or ScanSessionBridgeTest device integration. Native build/device authorization is withheld by the task/index; JDK/ABI/device readiness was not asserted.
- Status updated at user request: implemented, with required native bridge verification pending.
