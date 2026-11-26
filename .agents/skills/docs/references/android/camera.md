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
- A press that requires managed focus has no clock of its own: it waits while the focus cycle works, and for steadiness however long the hand moves. It shoots on an accepted lock, one whose own frames since it settled read the print sharp, or unclear for print too sparse to judge (frames exposed before focus changed hands never count), on a sharp passive focus, or, once the cycle finished without focus, on what the lens holds, its focus marked unconfirmed in the log. Capture still needs fresh page evidence when required, and a page still missing when the cycle finishes fails the press. A guide lock alone qualifies only when no page is required. Disabling managed focus returns a waiting press to the regular gate.

## Focus

- `FocusCycle` chooses the target and recovery step. `FocusController` owns the attempt from trigger to release for both Android cameras. On single-shot AF hardware, the lock requires CameraX success and matching frame evidence. A trigger lost in continuous AF is resent once without cancelling. A second loss fails the attempt. A release during a physical still waits for settlement. Continuous AF re-aims after cancellation completes.
- With managed focus and live corners enabled, a steady hold aims at print on a confirmed page or inside the static guide. The page aim follows the page in its own coordinates. `GuideSpot` waits for the detector's matching sample, retries a lost answer and uses the centre if picking fails. Motion, target changes and tap or capture takeover can cancel the request.
- Each analysis frame's print edges give a focus verdict (`FocusEvidence`): sharp, defocused, smeared or unknown, against a sharp floor learned per binding from frames the camera reports focused, at rest and still. A floor stays at its default until learned, and a focused report wider than the cap does not teach it, since continuous AF holds wrong distances while reporting focus. A steady hold whose print reads sharp for three frames keeps continuous AF, and a press then shoots on it. That sharp reading also anchors the reported lens distance: while later frames report the lens within the depth of field of it, focus counts as sharp whatever AF's state reports, since continuous AF flips between searching and focused on a still page. A defocused run, or the lens past the depth of field, drops the anchor. A hold whose print reads defocused, or never reads sharp over ten frames with the lens at rest, locks. The dark locks at once. A lock whose frames read defocused counts as one that found nothing.
- The cycle is a loop of events with no attempt count or timer. A lock that holds sharp ends it until a turn or deliberate move. A lock that fails, finds nothing or reads defocused requests the auto torch only after sustained darkness, when the torch is available and low-light boost is inactive. It locks again once lit. A pending press on a `zsl` binding defers auto torch and the switch to `latency`, so focus recovery ends for that press. The cycle also ends when the scene is bright or the torch is unavailable. A finished cycle starts over on a deliberate move followed by a steady hold, a turn, a new target, a change of light (dark scene or torch), or print coming into view (the edge count doubling). Motion shorter than a deliberate move only pauses it. Refusal leaves the attempt available, and the torch wait ends when the torch is no longer wanted.
- After motion checks, a page that appears under a guide lock inherits it if the aim is on the page. An off-page aim gets a counted page scan. A page lock survives brief guide dropouts and movement along the page. Page loss or a turn can release it. A sustained size change outside the depth-of-field tolerance schedules a relock on a steady, confirmed page while staying in single-shot AF. `DepthOfField` and `SizeWatch` own the tolerance and debounce. Missing lens data uses a fixed size-change limit.
- Optical flow carries the outline and informs inference pacing. Sensor motion governs focus and capture checks. Steadiness judges the phone's net turn over 300 ms, not the gyro's peak rate, since a hand's tremor peaks at a few tenths of a radian a second but turns back and barely moves the view. A held phone stays under about 1% of the view's width and 1 m/s² peak acceleration. Exposure smear is the gyro's estimate before optical stabilization, whose limit allows for what stabilization removes. Missing readings from a running sensor are unknown. Without an accelerometer, focus uses the capture steadiness fallback and framing checks rotation alone. Without a gyro, capture motion checks are disabled.

## Light and ZSL

- `CameraLightController` owns torch and boost commands and observations. Auto torch and focus recovery share one latched decision. With latency bound, auto torch can light during a waiting press. With ZSL bound, it waits for settlement. Manual torch and boost changes defer while a capture is pending.
- CameraX owns ZSL frame selection. Gated capture checks buffer eligibility even with optional focus, with a fixed-focus exception. Torch and sustained white-balance instability can bind latency. The experimental `darkLatency` option permits a darkness fallback. Buffer starvation can force latency once per view lifetime.

## Samples

`SampleCollection` records the admitted original, inference input and raw prediction before user crop edits. `SampleFolders` owns persisted SAF access. The flat image/JSON format retains `quad` for the [labeling tool](../tools.md#sample-labeling).

See [Android validation](validation.md) for device checks and traces.
