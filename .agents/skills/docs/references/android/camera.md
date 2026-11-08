# Android camera

## CameraX and focus

- The camera view binds against the host Activity and suspends when inactive, detached or backgrounded. The permission owner works independently of views, refreshes on Activity resume and shares one foreground prompt. CameraX negotiates preview, analysis and still capture with a shared viewport.
- Admission reads one resolved prop snapshot. Hardware-dependent decisions use the bound configuration. Rebinding waits for the active capture. The view borrows its session.
- `FocusController` owns continuous, tap and capture focus. Continuous AF targets the center. The scanner claims tap ownership before requesting CameraX metering. A held tap lock counts even if the page arrived later. Other focus evidence is frame-scoped and must follow page arrival when a page is required.
- `quality` requests a shutter scan. Other modes can request one for stale focus or poor light. A failed capture scan gets at most one retry. A pending owned scan extends the wait; stale passive scanning frames expire. Motion checks begin once focus is accepted. On Camera2 REALTIME devices the gyro may supplement optical flow.

## Light and capture

- `CameraLightController` owns torch/boost requests and confirmations. It applies deferred settings after capture and ignores late completions after unbind. Exposure compensation also defers and uses the latest prop.
- Auto torch uses exposure × ISO and latches once lit. On supported cameras the boost setting is sent at bind and when changed. CameraX handles the interaction between torch and boost. For requested ZSL, a lit torch, sustained unsettled white balance or sustained darkness without boost can bind latency. The reason is attached to the binding. Recovery returns to ZSL on a later bind, and incomplete darkness/white-balance intervals do not count across camera stops.
- For gated ZSL, readiness includes a buffer-eligible frame even when focus is optional, with a fixed-focus exception. CameraX owns buffered frame selection. Skipping the gate skips the buffer check. See [Shared engine](../engine.md) for policy decisions.

## Samples

`SampleCollection` owns recording and `SampleFolders` owns persisted SAF access. Capture samples pin their admitted original and analysis provenance, saving the inference input and its raw prediction. User crop edits do not enter the prediction. The flat image/JSON format retains its legacy `quad` field for the [labeling tool](../tools.md).
