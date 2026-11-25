# iOS camera

## Ownership and recovery

- The view owns capture admission and UIKit publication. `AVCameraSession` owns device commands, observations and acknowledgements on its session queue. Frames arrive on a separate serial queue with late-frame discard. Run IDs retire stale callbacks.
- Camera use requires an active, visible, sized foreground view and permission. First-frame readiness is armed after the previous run drains. The detector owns its inference lease for the run. Model replacement invalidates guide evidence. See [iOS processing](processing.md).
- An interruption disarms frames and invalidates focus, command acknowledgements and guide evidence. The first frame after interruption restores readiness. Settings are reapplied after any submitted still settles. Auto torch retains its latched decision through interruption and resets on full stop.
- A media-services reset can rebuild an eligible pipeline once after any physical still settles. Other runtime failures suspend it until eligibility or configuration changes.
- `getCameraFeatures()` reports the bound configuration without opening a camera. AVFoundation controls boost engagement, reported separately by `onLowLightBoostActiveChange`. Auto torch uses exposure × ISO and latches when lit. Light changes can defer until capture settlement.

## Capture

- One admitted request owns the waiting, exposing and saving slot. Waiting cancellation frees it immediately. Once submitted, the slot remains until the camera delegate and writer settle. The view borrows its session.
- Orientation changes during waiting invalidate steady evidence. The still uses shutter framing. The guide seed is used only with matching framing. See [Shared engine](../engine.md#live-guidance-and-capture) for gate behavior.
- AVFoundation reports lens motion and scan settlement, without a focus-success signal. Readiness uses later frame evidence and requires focus after page arrival when a page is required. The gate uses optical flow for motion evidence.
- Video presentation timestamps and exposure-bias completion start on different clocks. The adapter converts both to host time to identify the first affected frame. If conversion fails, the command acknowledgement is the cutoff.
- The gate decides when to request the still. AVFoundation owns exposure selection, including ZSL. No post-capture timestamp check rejects its delivered exposure. Exposure completion reports `captured`, while subsequent camera or write failure can still report `failed`.
- The bounded shutter sample can freeze preview and seed the page viewer. An active view clears the freeze at settlement. An inactive view can retain it until the viewer installs the thumbnail or the camera stops. For a freeze through review, the host must make the camera inactive before settlement. The sample is best-effort.
- The session releases the retained sample when the current thumbnail installs, a newer sample replaces it, the page is removed or the session closes. A displayed view retains its own image reference.

## Text camera

The view runs a separate `AVCaptureSession`. Video frames establish readiness and native autofocus aims at the region. The guide and session follow [Host integration](../integration.md#text-reading). Interruptions clear readiness until a fresh frame arrives. Configuration or runtime failure stops the pipeline.

- Video buffers keep the sensor's orientation. On main, region, layout and rotation changes map the crop through the preview layer. Rotation also re-aims autofocus and exposure. Only frames exposed since the current crop began count toward readiness and capture. The four sharpest crops of the last second are kept as upright grayscale images. A new crop drops them.
- `TextCaptureWork` serves captures on the video queue. A press reads the sharpest kept frame not yet read with Vision's accurate recognizer, without language correction, then each new frame while it waits. The first read that finds text serves it. At timeout (default 2 s) the sharpest kept frame serves it, even with no words. Only presses past their own deadline take that fallback; a read with text serves every waiting press. No frame yields `capture/not-ready`, and a recognizer failure `capture/failed`.
- When the camera stops or the view is given another session, waiting captures take the sharpest frame kept for the crop at that moment, or fail with `camera/unavailable`; frames kept for a newer crop stay. Presses read the current crop on the video queue. A press that reaches the view after its session was swapped out is refused with `camera/unavailable`. There is no focus lock, blur measure or zoom yet; `status` is `capturing` while a capture waits.

See [iOS setup](setup.md#permission-and-validation) for validation.
