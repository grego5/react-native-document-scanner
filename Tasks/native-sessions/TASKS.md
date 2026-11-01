# Native scan sessions — execution plan

## Goal

Move scanner page processing and file ownership into native sessions addressed by page IDs. React keeps order, selection, navigation, settings, crop drafts and a UX-only export lock. Add a composable native page thumbnail, pager direction control and the Laplacian fix. Strip/gallery layout and drag reordering stay in React. Tasks 01–06 are implemented with native verification pending; Task 07 is implemented with build-free verification passed; Tasks 08–11 are implemented with native verification pending; Task 12 is implemented with build-free verification passed; Task 13 is implemented with build-free/package verification passed and native/device verification pending. This index does not implement the APIs.

## Constraints

- Read `AGENTS.md` and `.agents/skills/docs/SKILL.md` before work. Follow the relevant coding skills for the implementation stage.
- Target the current Android module. Do not start an iOS/C++ migration, change model/quality thresholds or introduce compatibility aliases.
- One process-wide serial image worker; unbounded FIFO metadata jobs; replacement key = session + kind + page ID. Native owns revisions, resource leases and stale publication rejection.
- Sessions own files and pages explicitly. No page HybridObjects or page-owned URIs in app state. Source pixels remain immutable. IDs and all callback indices refer to React's input order, regardless of direction.
- Snapshots are id-indexed, immutable, versioned and published at most once per animation frame. `getSnapshot()` exposes only the last published snapshot with stable identity and unchanged-entry sharing. Subscribe has no eager callback.
- Captures receive initial render settings; authoritative analysis precedes filtering. Crop drafts stay unsubmitted until Submit. Saved edits apply immediately.
- No native build, compile-only Gradle, APK, device tests or camera automation without an explicit request. `npm run check` is build-free. Every physical shutter is human-triggered.
- Never hand-edit `nitrogen/generated/`. Run `npm run nitrogen` after source-spec changes during implementation, not while authoring this plan.
- Tasks 03–07 temporarily retain old implementations for staging, not aliases. Tasks 08–10 break old example types until Task 12; record expected failures instead of adding compatibility paths. Task 13 removes old endpoints.

## Order

Execute numbered tasks in order; dependencies below identify the minimum prerequisites. Each file owns its implementation detail, tests and proposed commit.

| Task | Outcome | Complexity | Depends on |
| --- | --- | --- | --- |
| [01](01-fix-ink-sharpness.md) | Fix depth-changing Laplacian aliasing | Low | — |
| [02](02-centralize-quad-validity.md) | Centralize native quad validation | Medium | — |
| [03](03-introduce-keyed-queue.md) | Introduce the shared keyed FIFO worker | High | — |
| [04](04-own-session-resources.md) | Own session directories and leased files | High | 03 |
| [05](05-own-page-revisions.md) | Own native page state, analysis and rendering | High | 02, 03, 04 |
| [06](06-expose-native-session.md) | Expose the native session and versioned metadata transport | High | 05 |
| [07](07-publish-batched-snapshots.md) | Publish stable frame-batched JS snapshots | High | 06 |
| [08](08-capture-session-pages.md) | Capture directly into sessions and publish capture state | High | 06, 07 |
| [09](09-bind-scanner-views.md) | Bind viewer, pager and crop editor to session pages | High | 08 |
| [10](10-add-page-thumbnail.md) | Add a session-bound page thumbnail | Medium | 09 |
| [11](11-export-page-snapshots.md) | Export immutable page snapshots and save samples by ID | High | 08 |
| [12](12-migrate-example-session.md) | Migrate the example to IDs and one session subscription | High | 07, 08, 09, 10, 11 |
| [13](13-remove-old-api-and-document.md) | Remove legacy endpoints and verify the complete migration | Medium | 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 12 |

## Validation and evidence

Each task records changed files, actual commands, passed/failed/skipped results and remaining limitations. Write tests with each behavior change; do not postpone them all to the final task. Native commands in briefs are conditional on explicit authorization: first confirm JDK, prepared `example/android`, ABI and `adb devices`; substitute a verified target instead of running placeholders. Use repository scripts, not ad hoc Gradle builds. No current SDK/device availability is assumed.

Keep `Status: Planned` until the task's required checks are actually satisfied; if implemented but native verification is not authorized, record that fact without calling the task validated. Status changes require evidence, not only source inspection. No tests or builds were run to author this plan.

Discovery used focused source inspection because the Kotlin LSP server was unavailable. No previous task index or Tasks directory existed in this checkout. The installed plan-to-tasks/plan-breakdown skills remain explicit-only; this work adds no new auto-triggered skill or product configuration.
