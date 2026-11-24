# Document session architecture

```text
React host → JS ScanSession → native ScanSessionState
                  ↑                    ├─ original → analysis → render
          versioned snapshots          └─ export snapshot → host destination
                                       ↑
                                  camera / import
```

## Admission and revisions

- Native state owns page order, defaults, analysis, render revisions and files. The host owns session lifetime. Views borrow the session.
- Camera and gallery operations admit originals on the image worker. Admission freezes page defaults. A capture also freezes its model selection. Library picks stay native.
- The picker request owns presentation and the one-picker slot. The import operation owns ordered admission and settlement. Close claims the outstanding call and waits for the picker slot to clear. See [Host integration](integration.md#document-workflow) for cancellation and partial admission.
- An import admitted during model load renders the full image and can be edited. Admission and the model observer coordinate under the session lock so pending analysis runs when the model becomes ready. Absent or failed selection leaves the full image. Completed analysis stays final across model changes.
- Explicit crop edits win over later automatic corners. Filter and shadow edits keep the page eligible for analysis. Only the current revision publishes a render outcome. The last displayable artifact remains available during rendering or after failure.

## Publication and lifetime

- Native registration takes the initial snapshot and subscription atomically. JS reconciles version gaps with a full snapshot and groups background changes by animation frame. Awaited mutations synchronize the snapshot before resolving.
- Close rejects new work, cancels waiting work and waits for active work, leases and claimed publications before cleaning owned storage. It claims registrations under the lock and invokes cancellations outside it. Retired immutable originals stop granting leases and are deleted after the last pin releases.
- The shared image worker serializes image work across sessions. Cleanup has a separate owner. A worker must never wait for work queued behind itself.
- Image views bind session/page IDs. Removed pages clear their image. Unknown IDs report `input/invalid`. A decode holds its own lease. The crop editor owns a draft until submission.
- Export freezes effective settings and page order, including duplicates, and pins the sources. It waits for automatic geometry only when analysis is already queued. A page waiting for a model exports its current geometry. Published files live at the host destination and survive session close.

See [Text reading](integration.md#text-reading) for the separate text session.
