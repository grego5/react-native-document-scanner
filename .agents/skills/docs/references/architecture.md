# Session architecture

```text
React host → JS ScanSession → Nitro → native ScanSessionState
   camera ── admits original ───────────────┘
   image views ── borrow page sources ──────┘
                        state → analysis → render → ready artifact
                        state → frozen export snapshot → host destination
                        state → versioned events → JS snapshot → React
```

## Ownership and publication

- The processor creates sessions and exposes sample controls. Native state owns ordered membership, settings, analysis, render revisions and files. The host owns the session lifetime. Views borrow it.
- Admission freezes page defaults and model selection. Analysis precedes initial render. Explicit crop edits win over later automatic corners. Only the current revision publishes readiness or failure. The last displayable artifact stays available during replacement and after a failed render.
- The shared image worker serializes session processing across sessions. Cleanup has a separate owner. A worker must not block waiting for work queued behind itself.
- Native registration takes an initial snapshot atomically with subscription. Ordered events carry changed and removed page IDs and a version. JS reconciles gaps from a full snapshot and coalesces background changes to animation-frame publications. Successful awaited commands synchronize the snapshot before resolving. Snapshots are immutable to consumers, with runtime freezing in development.
- `close()` rejects new work, cancels queued work and waits for running work, leases and claimed export publications before cleanup. Native registration protects owned storage through cleanup. Originals are immutable. Retired files stop granting leases immediately and are deleted after the last pin releases.

## Images and export

- Image views use session/page IDs. `SessionPageBinding` owns source deduplication and missing-page classification. A known page disappearing clears the image. An unknown ID reports `input/invalid`. A decode holds its own lease and may finish after removal.
- The pager follows session order by default. A complete host order can update the session. A crop editor owns its draft until submit. In rectangle mode the draft stays upright.
- Export freezes selected order and effective settings at admission, with duplicates preserved. It pins source data and publishes to a host destination outside protected storage. Pending work can be cancelled before publication. Published exports survive session close.
- Native result unions carry stable failure codes. JS maps superseded work to `ScannerCancellation` (`AbortError`) and other failures to `ScannerError`.
- React wrappers supply concrete native defaults where removing a prop would otherwise send a null that Nitro rejects. Public APIs use document, corners and guide terminology. Persisted compatibility fields keep their own schema.

See [Integration](integration.md) for host usage and [Shared engine](engine.md) for coordinates.
