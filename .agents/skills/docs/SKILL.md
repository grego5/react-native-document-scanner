---
name: docs
description: Maintain the React Native Nitro document scanner and example app on Android and iOS. Route implementation, review, debugging and validation to the owning reference.
---

# Document Scanner maintainer guide

Read only the references relevant to the task.

| Topic | Reference |
| --- | --- |
| Public API, host integration and example ownership | [Integration](references/integration.md) |
| Session lifecycle, revisions, native views and export | [Architecture](references/architecture.md) |
| Coordinates, processing, guidance and capture parity | [Shared engine](references/engine.md) |
| Routine checks, generation and parity fixtures | [Development](references/development.md) |
| CameraX, focus, light, capture and samples | [Android camera](references/android/camera.md) |
| Gradle, device validation and guide traces | [Android validation](references/android/validation.md) |
| CocoaPods, permissions and simulator validation | [iOS setup](references/ios/setup.md) |
| AVFoundation, guidance, capture and clocks | [iOS camera](references/ios/camera.md) |
| Original pixels, inference lifetime and render adapters | [iOS processing](references/ios/processing.md) |
| Model export, private fixtures, labeling and resolution diagnostics | [Tools](references/tools.md) |

## Maintenance rules

- Record stable ownership, data flow, invariants, interfaces, failure rules and required validation in the reference that owns them. Link there from other references.
- Rewrite changed contracts in place. Remove historical, speculative, transitional and duplicated prose. Keep observable lifecycle transitions and meaningful absence guarantees.
- Prefer positive wording, short sections and compact bullets. Use diagrams when they clarify relationships. Leave signatures, constants and local mechanics to source and tests.
- Verify changed claims against implementation, focused tests or an explicitly maintained contract. Delete unsupported claims. Update this index when routing changes.
- Keep private photos and reports under ignored `.local/`. Treat `.local/archive/` as retired experiments. Camera hardware captures in validation require a human shutter tap.
