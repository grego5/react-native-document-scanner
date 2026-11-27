# Android text camera

```text
CameraX YUV → region luma
               ├─ focus/view evidence → TextFocusCycle → FocusController
               └─ recent frame ring → ML Kit Latin → view-owned background read
                               └─ frames + background read → capture selection
                                                                   ↓
                                      view status ──────────→ TextSession
```

## Frames and focus

- The view owns preview, analysis and capture work. The shared viewport maps `textRegion` into the YUV crop. OCR receives upright grayscale. [Host integration](../integration.md#text-reading) owns the session and guide contracts.
- Each frame reads a snapshot of region, revision, zoom admission and focus. Region, zoom and binding changes invalidate kept frames and reads, checked again before a capture reads them. `TextZoom` pauses admission and the focus cycle until the current request settles. Matching capture metadata establishes the boundary when available. The fallback admits frames after the latest observed result. Refusal uses the measured ratio or last confirmed ratio.
- Managed focus starts on supported lenses with a manual preview seed of 5 diopters, limited by the lens range. Reopening uses the last verified distance stored in the current text session when the camera ID and zoom match. A supported managed binding starts in idle single-shot mode until the seed is applied, then switches directly back to single-shot before the first trigger. Motion sensors govern when the managed scan can run.
- The first steady hold scans on texture inside the read region, or its centre. A turn, deliberate movement, region change or changed text view arms a replacement once steady. A verified lock whose text becomes soft can scan once more per scene. A failed or initially soft lock holds the lens position until a scene change. A replacement starts in place without cancelling the held scan.
- Fresh image edges verify a scanner lock before its distance is remembered. A new scan and a stale lock awaiting replacement clear the current read's eligibility.
- `ViewWatch` uses image change alongside sensors. A page moving under a steady phone can affect the focus cycle and invalidate the background read. Gradient energy ranks candidate frames. Edge width determines whether a frame is readable.

## Capture selection

- `FrameRing` retains recent crops ranked within each focus generation. Scan frames are skipped. `FocusedRead` reads the best of the first usable frames once per focus in the background.
- A press uses a recent readable background read unless a kept frame is clearly sharper. Otherwise it tries the sharpest kept frame if its edges are sharp. Without a usable result it waits for a readable result from a new focus. The managed cycle runs independently of the press. With managed focus disabled, the press can use frames from continuous autofocus.
- At timeout (default 2 s) it reads the sharpest kept frame even if blurred, and may return a crop with no words. Only presses past their own deadline take that fallback; a readable read serves every waiting press. No frame yields `capture/not-ready`. When the camera stops during a wait, or the view is given another session, it tries the fallback before dropping the frames, or returns `camera/unavailable` if there are none. Session close settles pending requests independently of the view's work.

See [Android validation](validation.md#focus-and-ocr-checks) for device checks.
