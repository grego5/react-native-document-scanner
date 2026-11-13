# Shared engine

## Boundary and coordinates

- `cpp/include/document_scanner/` defines portable C++20 interfaces. Platform adapters own camera and sensor state, decoding, codecs, inference runtime, file publication and scheduling. OpenCV types stay in the implementation.
- An immutable `PixelLease` pins storage and any platform lock. Calls borrow it synchronously. Pixel views and callback storage cannot escape. A caller allocator can supply writable output for one call. Thread-affine leases release on their owning worker.
- Public corners are normalized to the full upright image in TL/TR/BR/BL order. Geometry distinguishes image edges from pixel centers. Encoded crop, EXIF orientation and sampled decode regions are mapped explicitly before preprocessing or warping. A preview viewport is a separate transform.

## Processing and capture

- An imported original is downscaled to the smallest upright size that still covers the export paper at its dpi, in the image's own orientation. Without a paper size, the cap is the 4096 px render limit. It is never enlarged. A page that fills only part of the image renders below the target dpi. Transparency becomes white paper. Android keeps a fitting JPEG's bytes and EXIF, and re-encodes other images upright. iOS always re-encodes upright through ImageIO.
- Shared geometry and filters render from original pixels. Warp precedes shadow removal and the selected filter. The shared PDF writer owns placement and eligible compressed payload reuse. Platform adapters own fallback encoding and file publication.
- Keep `format: auto` as page policy through filter edits. It resolves to four-bit gray PNG for black-and-white and JPEG otherwise. Explicit PNG requires a grayscale or black-and-white filter.
- The canonical model lives in `resources/doccornernet/`. Shared preprocessing applies crop and orientation before resize and interprets model geometry separately from score acceptance. Platform model owners serialize interpreter use.
- Shared flow measures regional tracking and motion. Missing evidence stays unknown. Adapters establish the clock relationship before combining camera and sensor evidence.
- Guide acceptance is shared. Adaptive pacing is platform-owned. A trusted guide can pause inference while flow carries the corners. Lost or degraded tracking, regional loss or displacement from the confirmed outline requests revalidation.
- With adaptive pacing, four steady analysis frames can trigger an early attempt when search starts, movement rearms it, or tracking needs revalidation. Low estimated blur can also trigger the first attempt of a pending search or revalidation. A pending search or revalidation gets an attempt on the first frame at least one second after it began or last ran, even if motion never settles. Guide acceptance and capture steadiness use their own checks.
- Region loss requests a new model confirmation even if the next flow estimate looks clean. Flow selects features again each frame, so new features alone cannot confirm that the obscured page region returned.
- A model score below the live threshold clears the tracked outline and its capture eligibility, even when flow can still track the region. Adaptive search resumes on its normal cadence.
- Capture gates return wait, scan, shoot or reject from camera evidence. Focus and any required page precede light and steadiness. After the shutter's focus scan settles, a missing page waits for document inference on a fresh frame. The detector bypasses normal pacing for that check. Reacquiring focus restarts the steady window. Motion control needs four qualified frames. Motion can delay or reject a shot and never requests a focus retry.
- Standard still-size ranking, adaptive pacing, torch and capture decisions have [shared parity vectors](development.md#parity-fixtures). Final stream negotiation and readiness evidence are platform-owned.
