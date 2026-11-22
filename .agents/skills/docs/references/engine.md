# Shared engine

## Boundary and coordinates

- `cpp/include/document_scanner/` defines portable C++20 interfaces. Platform adapters own camera and sensor state, decoding, codecs, inference runtime, file publication and scheduling. OpenCV types stay in the implementation.
- An immutable `PixelLease` pins storage and any platform lock. Calls borrow it synchronously. Pixel views and callback storage cannot escape. A caller allocator can supply writable output for one call. Thread-affine leases release on their owning worker.
- Public corners are normalized to the full upright image in TL/TR/BR/BL order. Geometry distinguishes image edges from pixel centers. Encoded crop, EXIF orientation and sampled decode regions are mapped explicitly before preprocessing or warping. A preview viewport is a separate transform.

## Processing and capture

- An imported original is downscaled to the smallest upright size that still covers the export paper at its dpi, in the image's own orientation. Without a paper size, the cap is the 4096 px render limit. It is never enlarged. A page that fills only part of the image renders below the target dpi. Transparency becomes white paper. Android keeps a fitting JPEG's bytes and EXIF, and re-encodes other images upright. iOS always re-encodes upright through ImageIO.
- Shared geometry and filters render from original pixels. Warp precedes shadow removal and the selected filter. The shared PDF writer owns placement and eligible compressed payload reuse. Platform adapters own fallback encoding and file publication.
- Keep `format: auto` as page policy through filter edits. It resolves to four-bit gray PNG for black-and-white and JPEG otherwise. Explicit PNG requires a grayscale or black-and-white filter.
- Hosts supply the [corner model](integration.md#corner-detection-model). Shared preprocessing applies crop and orientation before resize and interprets model geometry separately from score acceptance. Platform model owners serialize interpreter use.
## Live guidance and capture

- Shared flow measures regional motion. Missing evidence stays unknown. Platform adapters correlate clocks before combining sensor and camera evidence.
- Shared guide acceptance and platform pacing have separate owners. Reliable flow carries a confirmed outline and can pause inference. Lost tracking, regional loss or scene change requests model revalidation. Clean flow alone cannot clear a pending regional revalidation.
- Adaptive pacing permits early attempts on steady or low-blur evidence and guarantees a retry opportunity after a second of waiting. A held outline also gets a retry opportunity before expiry. A miss on that early retry can preserve the visible outline to its original deadline while clearing capture eligibility.
- Capture gates consume camera evidence and return wait, scan, shoot or reject. Required focus and page evidence precede light and steadiness. Fresh inference can bypass pacing after a capture focus scan. Reacquiring focus restarts the steady window. Motion can delay or reject capture. Focus and document failures use their respective timeout codes.
- Standard still-size ranking, adaptive pacing, torch and capture decisions have [shared parity vectors](development.md#parity-fixtures). Final stream negotiation and readiness evidence are platform-owned.
