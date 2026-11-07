# iOS camera

## Owners

- `CameraRef.getCameraFeatures()` reads support from the bound configuration without opening a camera. It returns undefined before configuration. AVFoundation decides when automatic low-light boost engages. `onLowLightBoostActiveChange` reports observed engagement separately from support. Changes to the opt-in wait for capture settlement.

- The camera view owns admission and the capture phase. `AVCameraSession` owns device commands, focus observations and acknowledgements on its session queue. Frames arrive on a separate serial queue with late-frame discard. UIKit publication runs on main. Run IDs retire stale callbacks.
- Camera use requires an active, visible and sized view, foreground and permission. First-frame readiness is armed after the previous run drains. Square framing uses delivered buffer dimensions and can change without restarting. Overlay placement maps detection corners through raw frame space into the preview's framing.
- The detector owns its live inference lease for the run. Model generation changes invalidate live guide evidence. See [Processing](processing.md) for selection ownership.
- An AVFoundation interruption disarms the frame stream and invalidates focus, command acknowledgements and live guide evidence. The first frame after interruption end restores readiness. A resumed run reapplies settings after any submitted still settles. Auto torch keeps an established light decision on interruption and restarts partial darkness evidence. A full stop resets the decision.
- A media-services reset retires the pipeline. An eligible view tries to rebuild it once before the next first frame, after any physical still settles. Other runtime failures suspend the camera until an eligibility or request change retries it.

## Capture

- One admitted request moves through waiting, exposing and saving. Waiting cancellation releases the slot immediately. A cancelled physical capture retains the slot until its delegate and writer settle. The view borrows its session and never closes it.
- Camera reconfiguration and torch, exposure and frame-rate prop changes can defer until the capture finishes. Orientation changes while waiting invalidate steady evidence. The still uses shutter framing; the seed requires matching guide framing. A thumbnail frame with a different orientation stays upright and uncropped by that guide.
- Focus observations and command acknowledgements are ordered by the session queue. AVFoundation reports lens motion and scan settlement, with no success signal. Capture readiness uses subsequent frame evidence, with focus after page arrival when a page is required. The gate consumes optical-flow evidence; iOS has no gyro fallback or CameraX buffer-readiness check.
- Video presentation timestamps use the capture session clock. Exposure-bias completion uses the video input clock and identifies the first affected buffer. The adapter converts both to host time before comparison. If the bias timestamp cannot be converted, readiness uses the command acknowledgement as the cutoff.
- The gate controls when to request the still. AVFoundation owns the delivered exposure, including ZSL selection. The package accepts the delivered still without a post-capture timestamp check.
- Capture stages follow the admitted request. Focus scans stay in waiting, the still request starts shooting, and AVFoundation's exposure-complete callback reports captured. A subsequent camera or write error can report failed, and so does an interruption or runtime failure of the camera run; an invalidated request reports nothing after that. Feedback controls drawing and haptics independently of stage delivery.
- The bounded shutter sample supplies the camera freeze and seeds the native page viewer. A camera that stays active clears its freeze when the shot settles; an inactive one keeps it until the viewer installs the thumbnail or the camera stops. The viewer uses the retained sample when one exists, but the freeze covers the switch to review only when the host sets `active={false}` before settlement. The sample is best-effort, and the writer may proceed without one. The session releases its recent preview when the viewer installs the current thumbnail, a newer capture's sample replaces it, the page is removed, or the session closes. A displayed view holds its own image reference. Detach and session replacement invalidate the camera's presentation while the physical shot keeps its slot until settlement.

See [Shared engine](../engine.md) for gate and guide contracts and [iOS setup](setup.md) for validation.
