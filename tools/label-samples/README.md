# Windows document-corner labeler

A local Tkinter tool that saves **JSON metadata only**. It does not copy, export or modify images, run inference, create training data, or upload anything.

## Open on Windows

From the project root:

```powershell
./tools/label-samples/launch.ps1
```

It uses the installed Windows Python with Tkinter and Pillow. If Pillow is missing:

```powershell
python -m pip install Pillow
```

Defaults work from any working directory:

- Photos: `%USERPROFILE%/Downloads/samples`
- Progress: `<project>/.local/label-samples/annotations.json` (ignored by Git)
- Optional reviewed-only export: `<project>/.local/label-samples/labels.json`

Override locations with `-Images`, `-Annotations`, `-Output`, or choose another interpreter with `-Python`.

The cross-platform Python entry point also works directly:

```powershell
python tools/label-samples/label_samples.py label --images C:/Users/User/Downloads/samples
```

## Label a page

1. Click **top-left → top-right → bottom-right → bottom-left** on the actual paper boundary, not a table printed on it.
2. Drag points to adjust them. Mouse wheel zooms around the pointer; right-drag pans. **Fit image** restores the full view.
3. Choose **Save document + next**. **Next / skip** leaves the image unlabeled. **No document + save/next** explicitly records that no page is present.

Each save persists immediately. Reopening resumes at the first unlabeled image. EXIF orientation is applied before displaying and normalizing coordinates. Invalid/self-crossing quads are rejected. Source hashes detect images changed after labeling.

## Scanner-compatible JSON

Each filename in `images` has a `quad` with the exact `DocumentQuad` shape accepted by this library's crop/render API:

```json
{
  "originalUri": "file:///C:/Users/User/Downloads/samples/14.jpg",
  "sourceWidth": 3456,
  "sourceHeight": 4608,
  "sha256": "...",
  "status": "document",
  "quad": {
    "topLeft": { "x": 0.1, "y": 0.1 },
    "topRight": { "x": 0.9, "y": 0.1 },
    "bottomRight": { "x": 0.9, "y": 0.9 },
    "bottomLeft": { "x": 0.1, "y": 0.9 }
  }
}
```

Coordinates are 0–1 relative to the upright original image. `quad` is null for `no-document` entries and for `unlabeled` ones without a starting outline. The wrapper records `version: 2`, `imageRoot` and `coordinateSpace: "exifUprightNormalized"`.

## Scanner samples

Point `-Images` at a folder the scanner saved samples to. Each image is `<timestamp>-<long side>.png`, the photo crop the corner model received. Its `<stem>.json` holds the model's outline, which the labeler shows as `unlabeled` for you to correct; saving writes your label back into that JSON.

## Export and status

The progress JSON is usable directly; no export step is needed. To write a separate JSON containing only reviewed entries:

```powershell
./tools/label-samples/launch.ps1 -Action export
```

An existing export is not overwritten. Choose a new `-Output` filename when exporting updated labels. No Parquet, image bytes or image copies are written.

Check progress or run the focused tests:

```powershell
./tools/label-samples/launch.ps1 -Action status
python -m unittest discover -s tools/label-samples -p 'test_*.py'
```
