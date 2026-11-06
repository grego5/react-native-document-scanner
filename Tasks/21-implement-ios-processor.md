# 21 — Implement iOS native sessions, rendering, and PDF

[Back to task index](../TASKS.md)

Status: Completed — Phase A and Phase B validated on the Intel iOS simulator; camera capture and physical-device validation remain separate
Complexity: High
Dependencies: [08](08-share-document-filters.md), [09](09-share-gray-png.md), [10](10-share-pdf-writer.md), [19](19-implement-ios-source-store.md), [20](20-implement-ios-inference.md)

## Objective

Implement the current ScanSession contract on iOS over the shared render/PDF engine. DocumentScanner.createSession() creates a headless, explicitly closed native owner. The processor remains a session factory plus deferred sample-folder controls; rendering, exports, page state, and cleanup belong to the session.

## Scope decision and delivery order

The original brief targeted the pre-session processor API. Its standalone URI render calls, processor disposal namespace, and one-running/one-waiting processor/busy rule do not describe current main and must not be restored.

Deliver this expanded task in two separately reviewable phases:

- Phase A — Internal render/codec/PDF services and their failure/ownership tests. Keep createSessionResult unsupported until Phase B is ready; Phase A alone does not complete Task 21.
- Phase B — Native session orchestration, resource/read contracts, and the current HybridScanSession transport. Enable createSessionResult only with those contracts implemented and tested.

Task 22 and subsequent session consumers require both phases. Task 28 later supplies physical captures through the same internal source-admission boundary that Task 21 tests with native fixtures. No public image-import or test-page API is added.

## Non-goals

Do not implement cameras, native view UI/gestures, later hardware policies, or sample collection. Do not redesign Android behavior, change public signatures/error codes, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- src/ScanSession.nitro.ts, src/ScanSession.ts, src/index.ts, src/ScannerFailure.ts — current transport, reconciliation, and public errors
- src/DocumentScannerProcessor.nitro.ts and src/DocumentScannerCamera.nitro.ts — factory and capture/page-ID boundary
- android/src/main/java/com/margelo/nitro/documentscanner/HybridScanSession.kt and HybridDocumentScannerProcessor.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ScanSessionState.kt, SessionPageProcessing.kt, ScanSessionEvents.kt
- android/src/main/java/com/margelo/nitro/documentscanner/ScannerWorkQueue.kt, SessionResources.kt, ScanSessionRegistry.kt
- android/src/main/java/com/margelo/nitro/documentscanner/SessionExports.kt, SessionExportSnapshot.kt, SessionPageBinding.kt, ScannerImageReads.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt and PdfWriter.kt
- ios/SourceStore.swift, CornerInference.swift, HybridScanSession.swift, HybridDocumentScannerProcessor.swift
- .agents/skills/docs/references/architecture.md — session, publication, source, export, and image-read invariants

## Current behavior and invariants

- Native session state owns ordered page membership, effective settings, requested/ready revisions, and separate analysis/render failures. Originals are immutable; each render derives from the original. Source paths, artifacts, and caches remain native.
- Source admission freezes render defaults, model selection, and seed geometry. Task 20 performs analysis before the first render. Explicit crop edits win over a later automatic candidate; superseded work never publishes current readiness/failure. Keep the previous displayed artifact until replacement, including after render failure.
- Session-owned image work uses a shared serial FIFO. Keyed replacement coalesces waiting work by owner/kind/page and appends the replacement at the tail; it does not interrupt running work or impose a global one-waiting-job cap. Preserve current cancellation/error transport; do not introduce processor/busy. Keep Task 19's live-source-memory admission bound distinct from queue scheduling.
- Observation registration and initial snapshot are atomic. Observable changes advance sourceVersion and deliver ordered page deltas on main, outside state synchronization. Accepted mutations publish native state before resolving; the existing JS adapter continues to own frame batching and recovery.
- A session outlives borrowed camera/view attachments and closes explicitly. Close rejects new commands/registrations immediately, cancels waiting work, invalidates unclaimed publication, and waits for running work, producer/read/export pins, model leases, and physical cleanup. One session cannot shut down process-wide workers, SourceStore.shared, or CornerInference.shared.
- Session resources own render/frame/thumbnail artifacts and retirement. SourceStore remains the sole source-pixel cache/accounting owner; sessions retire their owned source URLs through it. Leases survive replacement/removal until the final native consumer releases them.
- Exports freeze ordered IDs (including explicit duplicates), settings/revisions, analysis requirements, and resource pins at admission. They may finish from pinned inputs after page removal. Outputs use external staging and cannot target registered/reserved session or source resources, including canonical aliases. Failed/stale work removes unpublished outputs; successful external outputs survive session close. Close waits for export publications already claimed.

## Implementation steps

### Phase A — Internal engine and file adapters

1. Implement native-worker render services using Task 19 source leases, shared warp/filter composition, JPEG/PNG format policy, valid-content geometry, and paired render/thumbnail artifacts. Return private staged artifacts to the session owner; service completion does not itself make a page ready or publish a JS result.
2. Implement PDF platform adapters for direct eligible JPEG/four-bit gray PNG embedding, DPI/quality re-encoding, and TIFF-to-JPEG fallback. Preserve current SessionPdfOptions meanings, streaming behavior, and transactional output handling.
3. Test unchanged originals, render options/formats/filter/shadow behavior, pixel/codec leases, PDF layout/embedding, and rollback. Services own local handles/unpublished temporaries; session publication and lifetime decisions remain Phase B responsibilities.

### Phase B — Session owner and public transport

1. Implement the native headless session/registry/resource owner and its internal source-admission handoff. Freeze render defaults/model/seed, transfer producer/source ownership transactionally, insert a native page, and schedule analysis/rendering. Failed or obsolete admission retires its unpublished resources. The future camera and native tests use this same boundary.
2. Implement current FIFO/keyed replacement, owner generations, exactly-once settlement/discard, and per-owner drain barriers with a separate cleanup coordinator. Never block the image worker on work queued behind it or on cleanup; releasing one owner must not stop shared services.
3. Implement page metadata, revision-aware cache/publication, crop/filter/shadow/order/remove commands, full snapshots, atomic observation, ordered deltas, and terminal delivery. Preserve no-op/retry and stale-compatible-cache behavior from the current session contract.
4. Define and implement the native session-page/read contract used by Tasks 22–24: resolve current display/original sources, admit independently pinned reads, invalidate publication on removal/close, and release observers/pins on detach. UI, gestures, and view-sized image loading remain in those later tasks.
5. Implement createPdfResult/exportImagesResult through immutable export snapshots and Phase A services. Export work must honor invocation-time revisions/analysis instead of substituting an older displayed render. Validate all destinations before publication; roll back partial/stale outputs.
6. Complete HybridScanSession's current methods and enable HybridDocumentScannerProcessor.createSessionResult. Implement closeResult as the physical-cleanup barrier, including draining producer/read/model/export ownership and scoped file cleanup. Preserve registry protection for active resources across JS contexts and startup orphan cleanup without deleting host-owned files.
7. Implement deferred sampling consistently: getSampleFolder returns collecting:false; clear/disable are idempotent; async pick/save operations return feature/unsupported. Unsupported synchronous configuration setters, including enabling collection or setting a folder on iOS, must produce a public ScannerError with feature/unsupported at the JS facade when the void native method cannot transport a typed failure. Preserve Android dispatch and add Platform stubs to contract tests.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

The session owns membership, revisions, admission, publication, and resource retirement. Engine services own synchronous computation, codecs, and local temporary handles. The process-wide source/inference services retain their existing ownership; camera/views borrow the session. Do not add a second processor-owned queue/cache/disposal namespace.

Task 19's remove is nonblocking while leases exist, and drain only joins already-enqueued callbacks; neither alone proves physical source retirement. Where session close needs that guarantee, the source owner must expose retirement completion/failure through final-pin release. Keep the authoritative pin/deletion state there rather than mirroring it in the session; cleanup failures must reach the close result and preserve registry protection for retry.

## Tests and expected results

- Phase A: render filters/formats/options, unchanged source bytes, TIFF reopen/fallback, PDF placement/embedding, write/codec failures, and transactional rollback.
- Phase B: empty-session creation/observation/close through the current Hybrid boundary; synthetic source admission through the production native handoff; analysis-before-render; edit/analysis races; retry/no-op behavior; stale cache/publication; page order/removal; and observer ordering/teardown.
- Exercise keyed replacement and FIFO across pages/owners, exact settlement, closing one of two sessions, queued cancellation, close during running work, independently pinned reads, source handoff failure, cleanup failures, and final pin release.
- Exercise default/explicit export ordering and duplicates, requested-revision readiness, frozen analysis after edits/removal, alias/destination refusal, stale/failed output rollback, and close before/after publication claim. Preserve existing external outputs and host files.
- Verify deferred sampling's stable public errors and idempotent disable/clear, including unchanged Android dispatch. Run existing JS session reconciliation/publication contracts without platform-specific session logic.
- Synthetic pages validate the native session without a camera. Report Task 28 capture and physical-device validation as separate, still outstanding work.

## Validation commands

- npm run check
- Run focused iOS engine/session/export/resource XCTest suites, including existing source/inference regressions, and relevant shared engine tests.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Both phases pass their required checks. DocumentScanner.createSession() returns a functional native headless session with current metadata/mutation/export/observation/close behavior; deferred sampling has explicit supported/unsupported behavior. Native fixture pages traverse the same source-admission/processing/resource path later used by Task 28. Tasks 22–24 can consume the native session resource/read contract without inventing session state.

Required checks must pass before Status changes to Completed. A phase may be prepared with unavailable validation explicitly recorded, but Task 21 remains incomplete until both phases are validated. Record changed files, executed checks, actual results, and material limitations. Completion does not claim working camera capture, a full sample, or physical-device release readiness.

## Proposed commit title

- Phase A: feat(ios): add native render and PDF services
- Phase B: feat(ios): implement native scan sessions


## Phase A validation report — 2026-11-03

The internal ObjC++ render/PDF bridge, Swift services, test suite and exported
header are implemented. Shared `Point` and `Size` names are explicitly qualified
to avoid Apple SDK type collisions. The test corners uses an explicit type and
contextual initializer to avoid the observed Swift/C++ lookup diagnostic.
Generated bindings and public session behavior are unchanged.

The external package runner now builds the actual Swift/ObjC++/C++ pod rather
than the core-only harness. Verified Xcode 26.6 / Swift 6.3.3, OpenCV 4.12.0,
TensorFlowLiteC 2.17.0 and Intel iOS 26.5 simulator destination
`7C539F34-E6DA-4D55-AF59-98A50A35C2D7`. The package workspace is
`ScannerPackageValidation.xcworkspace`, scheme
`ReactNativeDocumentScanner-Unit-ScaffoldTests`, configuration Debug and deployment
iOS 18. The runner executes `build-for-testing` with `CODE_SIGNING_ALLOWED=NO`,
then the produced XCTest bundle through the simulator SDK's standalone `xctest`
agent. No application, camera or physical-device validation is claimed.

Two unfiltered vmac MCP runs passed with exit code zero:

- Clean canonical-path build: `b47c54e3-a3cc-48ff-9e4f-2842189a9d94`.
- Immediate cache-reuse run: `9035fd2a-4062-44d6-b25d-8debb81db492`, with no cache deletion.

Each executed **45 tests, zero failures, one skipped**: render 7, source storage
14, permissions 10, scaffolds 5, inference 6 and ObjC++ dependency-link tests 3.
The earlier 42-test selection omitted the three ObjC++ cases. Only the optional
`testPrivateTensorAndImageParity` skipped because private fixtures were not enabled
for these runner invocations; Task 20's earlier private parity result is separate.
The cached run reused Pods and preserved the dependency link, package.json,
Podfile, Pods manifest and Nitro Swift module inodes/modification times. The previous
empty-filter Bash error and repeat-build Nitro crash did not recur in these runs.

Runner corrections remain outside this repository: canonical on-disk path casing,
stable dependency links and host files, dependency/file-registration fingerprints,
Bash 3.2-safe empty selector handling, and Windows OpenSSH selection independent
of Git Bash PATH. The Node controller regression suite passed 99 tests with two
existing opt-in integration skips. The current MCP connection can use the explicit
package scheme; reconnecting picks up the new default. The CLI default is
`vmac test docscanner`.

Evidence stays ignored under `.local`: `task21-phase-a-unfiltered-clean.log`,
`task21-phase-a-unfiltered-cached.log`, `task21-runner-reuse-before.json`,
`task21-runner-reuse-after.json`, `task21-runner-all-tests.log` and the existing
`task21-phase-a-check.log` / `task21-phase-a-core-tests.log` (40 JS contracts and
10 shared C++ suites passed). Whitespace and bridge formatting checks passed.

Phase A alone does not complete Task 21. Phase B has not started; native session
creation, orchestration, resource/read contracts, export publication and physical
close remain unsupported. Whole-source iOS rendering versus Android's region/sample
decode and red-channel gray PDF re-encoding remain the reported platform behavior.
No product commit was made as part of runner repair.


## Phase B validation report — 2026-11-03

Native sessions are implemented over the Phase A services and enabled through
`createSessionResult`.

- **Queue and barriers:** `ScannerWorkQueue` is a shared serial FIFO with keyed
  replacement of waiting work only, owner generations, exactly-once settlement or
  discard, and per-owner drain barriers. `NativeCompletion` provides the one-shot
  barriers.
- **Resources and registry:** `SessionResources` and `ScanSessionRegistry` own the
  session directory, the producer/lease/retirement records, the source claims,
  scoped cleanup, startup orphan sweeping of generated `session-<uuid>` directories
  and stranded store originals (`SourceStore.recoverAbandoned` spares this
  process's entries and anything not named like a generated original), and refusal of export targets inside session or source storage (including
  symbolic-link and case aliases).
- **Source retirement:** `SourceStore` gained `owns` and `retire(_:completion:)`,
  which report physical removal after the final pin. A failed removal stays
  registered for retry and reaches the close result as `cache/delete-failed`.
- **Session state:** `ScanSessionState`, `ScanPage` and `ScanSessionEvents` implement
  the frozen admission settings/model/seed, analysis before render, explicit crops
  winning over later candidates, no-op and retry edits, a revision-aware
  compatible-render cache, keeping the previous displayed render until replacement,
  order and removal, atomic observation, ordered main-queue deltas and terminal
  delivery.
- **Source admission:** `admitSource` is the internal handoff for future captures
  and native fixtures. A failed or obsolete admission retires the source it was
  given, and never touches a foreign or already claimed file.
- **Exports:** `SessionExports` and `SessionExportSnapshot` freeze ordered IDs
  (duplicates included), the requested revisions, automatic analysis and pins at
  admission. They render missing revisions from the pinned original, even after
  removal, stage outputs beside their destination and publish only under an open
  session claim.
- **Page-read contract** for the viewer, pager and crop tasks:
  `SessionPageSource` provides independently pinned, reference-counted reads of the
  displayed render, the frame, the original or the thumbnail. `SessionPageBinding`
  provides main-confined observation that clears on removal or close (including a
  queued update that arrives after removal, or a removal racing the first read) and
  reports input/invalid only for a page ID absent from its registration snapshot.
- **Hybrid boundary:** `HybridScanSession` implements every method;
  `saveCaptureSampleResult` returns feature/unsupported.
- **Deferred sampling:**
  - Native: `getSampleFolder` reports not collecting; clear and disable are
    idempotent; pick, enable and set-folder are unsupported.
  - JS facade: on iOS, `setSampleFolder` and `setSampleCollection(true)` throw
    ScannerError `feature/unsupported`. Android dispatch is unchanged, and the
    contract tests stub `Platform`.

Checks:

- `npm run check`: 41 JS contracts passed, including the new iOS sampling contract.
- vmac `docscanner`, clean derived data, run `b1dfe631-46a0-4f33-a024-9b1a9375ee50`:
  60 tests, 0 failures, 1 skipped. By suite: sessions 15, render 7, source storage 14,
  permissions 10, scaffolds 5, inference 6 (the optional private-fixture parity test
  skipped), ObjC++ link 3.
- Cached rerun `80651554-4e76-4e69-9b13-53d76504cba8` (sessions, scaffolds, source
  storage): 34 tests, 0 failures.
- Review follow-up (stranded originals, binding removal races), run
  `958fc845-ab73-4d9a-b8fb-a452f2db0024`: 62 tests, 0 failures, 1 skipped; sessions 17.
- No C++ sources changed, so the shared C++ suites were not rerun.

Limitations:

- Nothing on iOS selects a production model before the camera tasks. An admission
  without a selection therefore records `model/load-failed`, renders full frame, and
  requires an explicit crop before export.
- Synthetic pages exercise the native session; human-triggered camera capture and
  physical-device behavior remain outstanding.
