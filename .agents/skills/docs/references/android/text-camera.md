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
- Continuous AF starts at the region. Managed focus waits for persistent blur on a steady hold, then tries one texture-aimed single-shot scan per hold through the [shared controller](camera.md#focus). Sharp print under continuous AF can be read. Turns, deliberate movement, a new region and recovery to sharp print can rearm the cycle.
- Image edge width gates readability because the camera can report a lock on blurred print. A lock that's soft from the start can hold at the near-focus limit, or release to continuous AF elsewhere. A sharp lock that goes soft releases and rearms. Steadiness checks and measurable edges determine when the cycle can judge blur.
- `ViewWatch` uses image change alongside sensors. A page moving under a steady phone can affect the focus cycle and invalidate the background read. Gradient energy ranks candidate frames. Edge width determines whether a frame is readable.

## Capture selection

- `FrameRing` retains recent crops ranked within each focus generation. Scan frames are skipped. `FocusedRead` reads the best of the first usable frames once per focus in the background.
- A press uses a recent readable background read unless a kept frame is clearly sharper. Otherwise it tries the sharpest kept frame if its edges are sharp. Without a usable result it waits for a readable result from a new focus. The managed cycle can request one lock if a scan or tap hold does not already own the lens. With the cycle off, the press waits for continuous AF or the deadline.
- At timeout (default 2 s) it reads the sharpest kept frame even if blurred, and may return a crop with no words. No frame yields `capture/not-ready`. When the camera stops during a wait, or the view is given another session, it tries the fallback before dropping the frames, or returns `camera/unavailable` if there are none. Session close settles pending requests independently of the view's work.

See [Android validation](validation.md#focus-and-ocr-checks) for device checks.
