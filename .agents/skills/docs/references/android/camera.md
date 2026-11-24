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
- For a press that requires managed focus, `ScannerPress` owns an 8 s uptime budget across cycle restarts and allows one final scan at give-up or near the deadline. An accepted lock gets at least 500 ms for post-lock frame evidence. Capture still needs fresh page evidence when required. A guide lock alone qualifies only when no page is required. Disabling managed focus returns a waiting press to the regular gate.

## Focus

- `FocusCycle` chooses the target and recovery step. `FocusController` owns the attempt from trigger to release for both Android cameras. On single-shot AF hardware, the lock requires CameraX success and matching frame evidence. A trigger lost in continuous AF is resent once without cancelling. A second loss fails the attempt. A release during a physical still waits for settlement. Continuous AF re-aims after cancellation completes.
- With managed focus and live corners enabled, a steady hold aims at print on a confirmed page or inside the static guide. The page aim follows the page in its own coordinates. `GuideSpot` waits for the detector's matching sample, retries a lost answer and uses the centre if picking fails. Motion, target changes and tap or capture takeover can cancel the request.
- A hold allows two accepted attempts. Refusal leaves the attempt available. Recovery can request auto torch before its next attempt and stops waiting when the torch is no longer wanted. A turn, deliberate move or new target can rearm a spent cycle.
- After motion checks, a page that appears under a guide lock inherits it if the aim is on the page. An off-page aim gets a counted page scan. A page lock survives brief guide dropouts and movement along the page. Page loss or a turn can release it. A sustained size change outside the depth-of-field tolerance schedules a relock on a steady, confirmed page while staying in single-shot AF. `DepthOfField` and `SizeWatch` own the tolerance and debounce. Missing lens data uses a fixed size-change limit.
- Optical flow carries the outline and informs inference pacing. Sensor motion governs focus and capture checks. Missing readings from a running sensor are unknown. Without an accelerometer, focus uses the capture steadiness fallback and framing checks rotation alone. Without a gyro, capture motion checks are disabled.

## Light and ZSL

- `CameraLightController` owns torch and boost commands and observations. Auto torch and focus recovery share one latched decision. With latency bound, auto torch can light during a waiting press. With ZSL bound, it waits for settlement. Manual torch and boost changes defer while a capture is pending.
- CameraX owns ZSL frame selection. Gated capture checks buffer eligibility even with optional focus, with a fixed-focus exception. Torch and sustained white-balance instability can bind latency. The experimental `darkLatency` option permits a darkness fallback. Buffer starvation can force latency once per view lifetime.

## Samples

`SampleCollection` records the admitted original, inference input and raw prediction before user crop edits. `SampleFolders` owns persisted SAF access. The flat image/JSON format retains `quad` for the [labeling tool](../tools.md#sample-labeling).

See [Android validation](validation.md) for device checks and traces.
