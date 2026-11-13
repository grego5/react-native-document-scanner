# iOS processing

- `SourceStore.shared` owns immutable originals and bounded decoded storage. File and pixel leases keep their resources alive through retirement. Startup recovery sweeps unowned generated originals and partial saves, preserving live sources and unrelated entries. The session claims ownership before publication.
- `CornerInference.shared` serializes model selection, interpreter and workspace. Replacement validates and warms a candidate before selection, and rejects a superseded request at installation. Frozen photo selections retain their model bytes. Running historical inference can switch the runtime without selecting that model for live guidance.
- Photo work takes a source lease before entering inference. Inference never calls back into source storage. The live preprocessor locks a retained pixel buffer only for its synchronous call. A late inference lease acquired after detector stop is released.
- Gallery imports decode with ImageIO thumbnailing, which samples while decoding and applies EXIF. They publish through `SourceStore` like a still.
- ImageIO supplies upright source pixels. The render and PDF adapters bridge to the [shared engine](../engine.md); platform code owns decode, encoding and transactional file output. TIFF inputs can use the PDF writer's JPEG fallback.
- Sample collection is unsupported on iOS. See [Architecture](../architecture.md) for page lifetime, image views and export.
