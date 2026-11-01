# 07 — Publish stable frame-batched JS snapshots

[Back to task index](TASKS.md)

Status: Implemented — build-free verification passed
Complexity: High
Dependencies: [06](06-expose-native-session.md)

## Objective

Stable published snapshots satisfy the agreed identity, batching and registration contract.

## Non-goals

Do not implement later task outcomes, change the model/filter/gate thresholds, add compatibility aliases, implement iOS or perform unrelated optimization/cleanup. Add no public APIs beyond this brief and its prerequisites.

## Read before editing

Read AGENTS.md and the docs skill first. This is a lookup map: locate symbols and read bounded ranges only to answer the next implementation question. For new prerequisite files, use the result of the linked task rather than assuming they already exist.

- `src/ScanSession.nitro.ts (Task 06)`
- `src/index.ts: createSession facade`
- `src/scanner-contracts.test.cjs: load test harness`
- `src/DocumentScannerCamera.tsx: stable callback wrapper pattern`

## Current behavior and invariants

There is no current session subscription adapter. Nitro transport objects are freshly marshalled; their identity alone cannot satisfy useSyncExternalStore.

## Implementation steps

1. Add src/ScanSession.ts (new) wrapping the native session. Separate incoming native sourceVersion from public snapshot.version. Keep pending metadata distinct from the published JS snapshot.

2. Initialize with native atomic registration plus snapshot/version, buffering callbacks that arrive before initialization returns. Apply only newer source versions; on a version gap reconcile from a full versioned native snapshot.

3. At one requestAnimationFrame flush, apply pending page deltas, structurally share unchanged immutable entries, install the complete new snapshot, increment publication version and then notify each subscribed listener once. If no observable change remains, retain identity and do not notify.

4. getSnapshot() always returns the published object; never expose pending metadata, including from getSnapshot during listener registration or a mid-frame mount.

5. subscribe(listener) registers without synchronous delivery. Use read/register/published-version comparison and reconciliation to avoid missed publications; document useSyncExternalStore and plain-store integration. Listener removal and repeat unsubscribe are safe during notification.

6. close() marks native ownership closed immediately, keeps the cached close Promise for repeat calls and releases bridge subscription/frame scheduling after terminal metadata publication. Attach logging for ignored cleanup rejection without swallowing rejection for callers that await the returned Promise.

7. Add deterministic adapter tests in src/scanner-contracts.test.cjs using a fake frame scheduler and versioned native transport; update the facade mocks.

## Ownership and boundary rules

No processing queue, render cache or stale-render logic in this adapter. Native sourceVersion is transport sequencing; snapshot.version counts public publications. Reentrant listeners read the already-installed same snapshot.

Observe the index's single-worker, lease, version, explicit lifetime and no-automated-shutter constraints. Callbacks must not escape closed/disposed generations; use existing typed ScannerFailure -> ScannerError translation.

## Tests and expected results

Repeated reads share identity; mid-frame reads see old published state; unchanged pages retain identity; burst updates notify once per frame; subscribe has no eager callback; initialization/version races and gaps reconcile; removed listeners never fire; repeated close works awaited or ignored.

Add tests with the behavior, using synthetic pixels, controlled workers and fake frame schedulers where appropriate. Preserve useful existing regression coverage. Avoid one-time wiring assertions.

## Validation commands and unavailable environments

npm run check

Also run `git diff --check`. Native commands are instructions for a later explicitly authorized run, not permission to build now. Before a native run verify the JDK, prepared example Android project, ABI and device target; never execute `<verified-adb-id>` literally. Record actual commands and passed/failed/skipped results. Missing SDK/private assets/device or withheld build authorization must be reported as not run, never passed. Typechecks/JS tests do not establish Kotlin/device correctness.

## Completion criteria

Stable published snapshots satisfy the agreed identity, batching and registration contract.

Record evidence in this file. Keep status Planned while required verification is pending; disclose temporary example incompatibilities from Tasks 08–10 until Task 12 rather than weakening the contract.

## Proposed commit title

feat(session): publish stable batched snapshots

## Implementation evidence

- Added src/ScanSession.ts and routed DocumentScanner.createSession through it. Native sourceVersion remains private transport sequencing; the public immutable snapshot contains version, closed and ID-indexed pages. The adapter retains pending metadata separately from the published object and owns no image processing, render cache or native stale-result policy.
- Atomic registration installs the bridge observer before exposing the store, buffering callbacks received during initialization. Older/duplicate source versions are ignored. Gaps trigger a full versioned native read, with reentrant events queued in source order; if that read fails, diagnostics are logged and the already-full incoming transport snapshot remains usable.
- One animation-frame flush computes page changes, shares unchanged entries, copies/freezes changed metadata recursively, installs the complete snapshot, increments public version only on observable change and then notifies active listeners. Native-only versions and metadata reverted within a frame preserve the whole published identity. getSnapshot never reads native or exposes pending data.
- Stable subscribe/getSnapshot functions support useSyncExternalStore. Subscribe does not call listeners eagerly; read/register/published-version comparison schedules reconciliation if needed. Plain-store read/subscribe/read usage is documented in the API and architecture. Notification observes current subscription membership, so removal/repeated unsubscribe and addition during callbacks are safe. Listener exceptions do not stall publication.
- Close invokes native immediately, caches its cleanup promise and preserves its rejection for awaiting callers while attaching diagnostic handling for ignored failures. Terminal publication releases the bridge observer, outstanding frames and listeners; later callbacks are ignored. Cleanup settlement reconciles a missing terminal callback. Initialization failure closes its newly created native session.
- Updated facade mocks/contracts and architecture. The temporary public raw observe wrapper is replaced by the planned stable subscribe/getSnapshot store, while atomic observation remains internal native transport. No compatibility aliases, generated-binding edits, native implementation changes or later-task capture/view/export work were added.

### Verification

- Passed: npm run check — library and example typechecks plus 32/32 JavaScript contracts. Sixteen session/store contracts use a deterministic frame scheduler and fake full native transport, covering identity/sharing/freezing, burst/no-op/reverted updates, mid-frame subscriptions, initialization races, stale versions/gap reconciliation and page removal, opaque IDs, reentrant listener changes, immediate/idempotent close, terminal teardown and late events, ignored cleanup rejection and typed facade errors.
- Corrected during validation: initial npm run typecheck found Object.hasOwn outside the configured library target; the implementation now uses Object.prototype.hasOwnProperty.call. The first full contract run exposed a fake native fixture incorrectly reopening after close; it now delivers late callbacks without changing native ownership. The final full check passed all 32 tests.
- Passed: git diff --check; the new adapter also passed git diff --no-index --check (exit 1 for a new-file diff, with no whitespace diagnostics).
- Supplementary: LSP returned no diagnostic items for the adapter and facade. The successful TypeScript compiler checks are the type verification evidence.
- Not run: native builds, JVM/APK/device tests or camera actions. Task 07 changes only the JS adapter and requires build-free checks; native verification pending from earlier tasks remains pending. Source specs did not change, so Nitrogen was not needed.
- All required Task 07 checks passed. Status confirmed and implementation committed at user request.
