# 03 — Introduce the shared keyed FIFO worker

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: None

## Objective

Bounded admission is replaced by serial FIFO execution with deterministic keyed replacement and settlement.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessor.kt: executor, submit, pendingPromises, dispose`
- `android/src/main/java/com/margelo/nitro/documentscanner/ScannerErrors.kt`
- `src/ScannerFailure.ts`
- `android/src/androidTest/java/com/margelo/nitro/documentscanner/HybridDocumentScannerProcessorTest.kt`

## Current behavior and invariants

The processor currently admits one executing and one queued job via ArrayBlockingQueue(1), rejecting saturation as processor/busy. Cleanup has a separate executor. Preserve exactly-once Promise settlement and the shared inference worker.

## Implementation steps

1. Add ScannerWorkQueue (new) with one process-wide serial image worker, an unbounded metadata FIFO and replacement keys (sessionId, kind, pageId). Use a short synchronized admission/state section; run operations and callbacks outside that lock.

2. Each job records immutable metadata, execution action and success/failure cleanup. New same-key work removes only queued predecessors and settles them with processor/superseded. Running work is never interrupted or replaced. Processor/session disposal cancels only its own namespace and never shuts down the shared worker while other sessions still use it.

3. Support cancelling queued jobs for a session with processor/disposed; running completion must consult its owner generation before publication. Provide a completion barrier for eventual close without occupying the image worker waiting for itself.

4. Route current processor admission through the queue while old APIs remain transitional consumers. Preserve separate deletion execution; it must later honor resource leases.

5. Replace the saturation test with FIFO, unbounded admission, kind/session isolation, replacement and disposal tests. Add ScannerWorkQueueTest with latch-controlled actions.

## Ownership and boundary rules

Queue entries do not decode or retain full image buffers. One queue across sessions, not one worker per session. No priority scheme, hard limit or retry promise chain. Do not change CornerInference's single interpreter ownership.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

More than two jobs are admitted; pending same-key jobs reject once; analysis survives render replacement; running jobs finish; close cancels pending jobs only for its session; no callback occurs under the admission lock.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

Authorized JVM command: npm run test:native -- -Test '*ScannerWorkQueueTest'. Authorized device regression: npm run test:device -- -Device <verified-adb-id> -Class com.margelo.nitro.documentscanner.HybridDocumentScannerProcessorTest

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

Bounded admission is replaced by serial FIFO execution with deterministic keyed replacement and settlement.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(processor): add shared keyed FIFO scheduling

## Implementation evidence — 2026-10-31

- Added ScannerWorkQueue.kt: one process-wide serial image worker, an unbounded metadata FIFO and immutable session/kind/page metadata. A same-key submission removes only queued predecessors and appends at the tail; unkeyed requests are never coalesced. Actions and settlement/cleanup callbacks run outside the admission lock.
- Owners have independent namespaces and generations. Close cancels only that owner's waiting work with processor/disposed; running work is not interrupted, checks generation before committing publication and invokes discard cleanup for an unpublished successful result. The close barrier completes after actions and callbacks retire, without waiting on the image worker from itself. A publication already claimed before close is committed; the barrier still waits for its callback to finish.
- Replaced HybridDocumentScannerProcessor's bounded executor/pending-promise bookkeeping with queue settlement. URI-based legacy calls remain unkeyed because they do not have session page IDs. Model-lease close chains onto the image owner's barrier; CornerInference's shared interpreter worker is unchanged. Cache deletion uses a separate shared queue and cancellation owner, preserving independence from image work.
- Added processor/superseded in place of the removed saturation-only processor/busy error; regenerated Nitro Kotlin/C++ conversions with npm run nitrogen, without editing generated files. Updated the example error-message consumer. No iOS implementation or later-task session/resource APIs were added.
- Added ScannerWorkQueueTest with latch-controlled FIFO (101 jobs), running/queued replacement, kind/page/session isolation, owner close/discard/barrier behavior, concurrent admission/replacement/close, cross-thread admission from actions/callbacks, callback/discard failures and executor rejection. Replaced timing-dependent processor saturation/disposal instrumentation with blocked-worker admission and owner-isolation checks.
- Updated the architecture reference for the shared workers and transitional unkeyed endpoints.
- Passed: npm run nitrogen (6/6 specs); npm run check (library/example typechecks and 16/16 JavaScript contracts); git -C C:/dev/react-native-document-scanner diff --check, including separate whitespace checks of the two new Kotlin files.
- Initial npm run check failed because example/lib/errorMessage.ts still named processor/busy; updated that affected consumer, then the full check passed.
- LSP reported no diagnostics for ScannerWorkQueue.kt, HybridDocumentScannerProcessor.kt, ScannerWorkQueueTest.kt and HybridDocumentScannerProcessorTest.kt. References were incomplete, so focused source inspection confirmed submission, disposal and error consumers. LSP results and JS checks do not establish Kotlin compilation or queue runtime correctness.
- Not run: npm run test:native -- -Test '*ScannerWorkQueueTest', processor instrumentation, native compilation/builds, APK installation or device checks. The task requires separate explicit native-run authorization, which has not been supplied.
- Status updated at user request: implemented, with required native verification pending. Implementation-stage scope only; file leases, native session APIs and the example session migration remain later tasks.
