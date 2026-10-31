# 04 — Own session directories and leased files

[Back to task index](../TASKS.md)

Status: Implemented — native verification pending
Complexity: High
Dependencies: [03](03-introduce-keyed-queue.md)

## Objective

Native resource ownership supports safe replacement/removal/close without a JS janitor.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `android/src/main/java/com/margelo/nitro/documentscanner/processing/CachedImageFiles.kt`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/LatestFrameCapture.kt`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt: outputDirectoryFor, render`
- `android/src/main/java/com/margelo/nitro/documentscanner/PageThumbnails.kt: forget`
- `android/src/main/java/com/margelo/nitro/documentscanner/processing/SampleCollection.kt: forget, saveCapture`

## Current behavior and invariants

Photos, frames and renders live in a shared cache folder; the example janitor controls deletion. Renderer outputDirectoryFor searches for an ancestor named cache. This must not remain the destination policy for session work.

## Implementation steps

1. Add process-wide ScanSessionRegistry and SessionResources (new) with opaque session IDs and a private per-session directory under the scanner cache. Register a directory under the registry lock before any file creation.

2. Add resource records/leases for original, frame, render and thumbnail files. Deletion removes logical ownership immediately but waits for all decoder, operation, view and sample/export leases to release. Register temporary output cleanup on failure and stale completion.

3. Make DocumentRenderer and LatestFrameCapture accept an explicit native output destination; retain old callers only until final cutover. Session outputs must stay inside their session directory, never the ancestor cache root.

4. Startup cleanup scans only the session-directory namespace and deletes directories absent from the process-wide registry. Never delete a registered directory, the parent cache, exports or unrelated files. A registry is shared by every JS context and survives Fast Refresh.

5. Implement idempotent asynchronous close: stop admission/cancel queued work, wait for running resource users on cleanup coordination, forget thumbnails/sample mappings and delete the directory. Registry protects it until physical cleanup finishes; failed cleanup remains logged and can be retried on a later process startup.

6. Add SessionResourcesTest covering canonical path constraints, registry races, pinning, close and failure cleanup.

## Ownership and boundary rules

No recursive deletion based on a caller-provided path. Preserve originals, thumbnail bitmap draw safety and existing sample-folder grants. Cleanup must never wait on a barrier while holding a lock needed by the image worker.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Pinned files survive deletion until release; temporary files leave after failures; close twice settles consistently; two contexts cannot clean each other's registered sessions; abandoned unregistered directories are removed without touching exports.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

Authorized JVM command for pure state tests: npm run test:native -- -Test '*SessionResourcesTest'. Android filesystem assertions belong in SessionResourcesDeviceTest, run only with explicitly authorized test:device.

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

Native resource ownership supports safe replacement/removal/close without a JS janitor.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(session): own cache directories and file leases

## Implementation evidence — 2026-10-31

- Added ScanSessionRegistry.kt, SessionResources.kt and SessionFiles.kt. The shared process registry reserves opaque session directories under the scanner cache's sessions namespace before mkdir or output creation. Registered directories remain protected across JS contexts/Fast Refresh, including during close and after failed directory cleanup.
- Resources cover original, frame, render and thumbnail files. Producer transactions register staging/final paths before writes; immutable publication rejects overwrites. Retirement immediately prevents new leases, while decoder/view/operation/sample/export leases and writer pins defer physical removal. Failed or stale transactions retire both paths, including partially committed render pairs.
- Close is idempotent and asynchronous: it cancels the session image-queue owner, observes the completion barrier without waiting on the image worker, drains resource users, forgets thumbnail/sample mappings and deletes only its generated session directory on cleanup coordination. Cleanup errors are logged and exposed through futures; failed directory close retains registration for recovery after process restart.
- Startup cleanup runs from React package view-manager initialization and native session opening (including headless use). It claims only unregistered generated session directories under the registry lock. Filesystem deletion rejects path escape and root/namespace links; directory walking never follows child symlinks. Parent cache, exports, unrelated namespace entries and registered directories are excluded.
- DocumentRenderer accepts an original resource and explicit native render/thumbnail destinations, acquiring its source lease internally. LatestFrameCapture consumes an explicit frame transaction. Legacy callers remain for non-session files; session paths cannot fall back to ancestor-cache placement or legacy URI deletion. CachedImageFiles now validates the whole removal before forgetting mappings, so rejected session deletion cannot bypass its owner.
- SampleCollection has a native original-resource entry point that retains a lease through queued storage work and releases it on admission, open, write and completion paths. Existing sample-folder configuration/grants are unchanged. PageThumbnails.forget remains non-recycling for bitmap draw safety.
- Added SessionResourcesTest with a fake filesystem and controlled executors for registration/cleanup interleaving, multiple contexts, all resource kinds, pinning, failed/stale/retired writers, partial render cleanup, queue cancellation, running work, repeated close, failed creation/removal and cleanup-executor rejection.
- Added SessionResourcesDeviceTest for actual canonical/symlink boundaries, immutable originals, retained files, legacy-bypass rejection, session-only render/frame destinations, failed render cleanup and drawing an already-forgotten thumbnail bitmap.
- Updated the architecture reference. No public Nitro spec, generated binding, iOS implementation, native page state or later-task API was added.
- Passed: npm run check (library/example typechecks and 16/16 JavaScript contracts); git -C C:/dev/react-native-document-scanner diff --check, including separate checks of all five new Kotlin files.
- LSP reported no diagnostics for the new resource/filesystem/registry classes, renderer, frame capture, sample collection, package startup and the two new test classes. Source inspection confirmed disposal, mapping and file-consumer relationships because LSP reference coverage was incomplete. These results do not establish Kotlin compilation or native runtime correctness.
- Not run: npm run test:native -- -Test '*SessionResourcesTest', SessionResourcesDeviceTest and other native instrumentation, native compilation/builds, APK/device checks or camera automation. The brief requires separate explicit native-run authorization, which has not been supplied.
- Status updated at user request: implemented, with required native verification pending. Later tasks will bind page state, views, capture and export snapshots to these native resource primitives; the transitional example still uses its existing non-session cache flow.
