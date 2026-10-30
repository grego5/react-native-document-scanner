---
name: docs
description: Develop or maintain the React Native Nitro document-scanner camera module and its Android scanner app.
---

# Document Scanner maintainer guidance

Code is the source of truth; these references hold what code does not show (decisions, reasons, evidence, procedures).

- [Architecture](references/architecture.md): ownership, invariants, and why things are built the way they are.
- [Development](references/development.md): commands, device validation, live quad traces, evidence.

Rules:

- Use `npm run check` for routine local checks: library/example typechecks and JS contracts, with no native build or device work. Native JVM tests have a separate `npm run test:native` script; it may compile Kotlin/plugins. A no-build instruction also excludes compile-only Gradle targets.
- Camera test captures are human-triggered. Never tap the shutter by automation or call `takePhoto` from a test without the user's tap.
- `.local/archive/` holds retired experiments (earlier detectors, content-aligned crop, Windows evaluators). Never import it into active code.
- Private photos and reports stay in ignored `.local/`.
- Ask about genuine scope conflicts after inspecting the affected code and callers.
