# Android document camera

```text
CameraX frames → detector → guide, page and focus target
Camera2 results + sensors → focus and capture evidence
FocusCycle → FocusController → CameraX autofocus
Capture gate → CameraX still → borrowed ScanSession
```

## Binding and capture

- The view owns CameraX binding, capture admission and preview presentation. Preview, analysis and still capture share a viewport. Camera use requires an active, attached foreground view.
- Admission freezes processing and output settings. Live focus and guide settings govern readiness. Rebinding waits for capture settlement. See [Shared engine](../engine.md#live-guidance-and-capture) for gate and guide contracts.
- A press that requires managed focus waits for the pending scan and its own post-lock frames. Sharp print confirms the lock. Sparse print can qualify as unclear once the lens rests. An ended focus attempt rejects the press. A document-required press still needs fresh page evidence. With a guide-only lock it gives inference time to find the page before rejecting. Motion, light and ZSL readiness still apply.

## Focus

- On supported cameras, managed focus starts the preview at a manual lens distance. The scan session keeps the last verified page-lock distance for the same camera ID in memory until close. The initial default is 4.0 diopters, limited to the lens's supported range. The managed binding defaults to idle single-shot mode until the manual seed is applied. Three steady, sharp page frames at the reported manual setting verify it as a lock. A manual-lock shot uses a fresh exposure before the binding has supplied any AF-focused frames to the ZSL buffer. If the manual seed fails verification, the scanner switches directly to single-shot mode and waits for that mode in camera results before triggering. Unsupported manual seeds use idle single-shot mode when available.
- With managed focus, live corners and single-shot AF support, the first steady hold requests one scan if it needs focus. It targets print inside a confirmed page, then texture near the preview centre if page texture is unavailable, then the preview centre. The central guide can focus a page the detector cannot yet see.
- `FocusCycle` coalesces focus requests. A turn, deliberate movement, sustained page loss, size change past the lock's depth of field, or torch change arms one scan at the next steady hold. A guide lock can become a page lock if its point is on the page. A page discovered off the guide point requests a scan at its own target. A lock that was sharp and becomes defocused can request one correction per scene. A scan that fails or starts out defocused keeps its position and waits for a scene change. Each scan sends one trigger. A managed guide lock keeps its position beyond the tap-hold duration.
- `FocusController` owns the single-shot scan and confirms it with CameraX and frame metadata. Replacing a settled scanner lock starts the new scan in place without cancelling it first. Wind-down, tap takeover and explicit configuration changes use the controller's normal lifecycle. Lens changes wait for any physical still in flight.
- Analysis measures print blur over the page or the central guide. `PrintFocusRun` uses only frames exposed after the focus generation changed. A single fresh defocused frame vetoes confirmation at the capture gate, while a sustained verdict drives the focus policy. `DepthOfField` and `SizeWatch` set and debounce the distance-change threshold.
- Optical flow carries the outline and informs inference pacing. Sensor motion governs focus and capture checks. Steadiness judges the phone's net turn over 300 ms, not the gyro's peak rate, since a hand's tremor peaks at a few tenths of a radian a second but turns back and barely moves the view. A held phone stays under about 1% of the view's width and 1 m/s² peak acceleration. Exposure smear is the gyro's estimate before optical stabilization, whose limit allows for what stabilization removes. Missing readings from a running sensor are unknown. Without an accelerometer, focus uses the capture steadiness fallback and framing checks rotation alone. Without a gyro, capture motion checks are disabled.

## Light and ZSL

- `CameraLightController` owns torch and boost commands and observations. Auto torch uses a sustained exposure and ISO threshold. With latency bound, auto torch can light during a waiting press. With ZSL bound, it waits for settlement. Manual torch and boost changes defer while a capture is pending.
- CameraX owns ZSL frame selection. Gated capture checks buffer eligibility even with optional focus, with a fixed-focus exception. Torch and sustained white-balance instability can bind latency. The experimental `darkLatency` option permits a darkness fallback. Buffer starvation can force latency once per view lifetime.

## Samples

`SampleCollection` records the admitted original, inference input and raw prediction before user crop edits. `SampleFolders` owns persisted SAF access. The flat image/JSON format retains `quad` for the [labeling tool](../tools.md#sample-labeling).

See [Android validation](validation.md) for device checks and traces.
