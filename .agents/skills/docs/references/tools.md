# Maintainer tools

## Model export and private fixtures

- `example/assets/doccornernet/` is the repository's only working model: the upstream `mapo80/DocCornerNet-CoordClass` checkpoint `mobilenetv2_224_best` as float32 TFLite, with its MIT license. `provenance.json` pins the upstream revision and source, config, weights and TFLite hashes. Android instrumentation and iOS XCTest load it as their test model; Android instrumentation builds check its hash.
- Keep your own checkpoints and exports under `.local/`. `tools/models/export_adapted_doccornernet.py --checkpoint <keras> --layers <model.py>` exports one to `.local/doccornernet-export/` with a parity check and provenance. Keep the layer definitions beside the checkpoint.
- `tools/models/prepare_doccornernet_runtime.py` with `--photos`, `--checkpoint`, `--layers` and `--model` (each defaults under `.local/`) prepares private runtime assets under `.local/doccornernet-runtime/assets/`, with a copy of the model its references come from. Android instrumentation accepts `-PdocCornerFixtures=<absolute assets directory>` on the test build. Run its private-photo test with `-Measure`. The fixture compares deployment parity rather than detection accuracy. Keep photos and reports out of source control.

## Sample labeling

Run `./tools/label-samples/launch.ps1 -Images <directory>` on Windows with Python, Tkinter and Pillow. The labeler records upright normalized TL/TR/BR/BL corners in the existing JSON `quad` field. It edits metadata only. Skipping a photo leaves it unlabeled; explicitly saving no document records a reviewed negative. `-Action export` writes reviewed-only JSON without overwriting an existing export.

## Resolution diagnostics

Run `./tools/resolution-check.ps1 [-Device <serial>]` to build the standalone Android diagnostic APK under `.local/resolution-check/`. It uses the scanner's resolution and exposure policies. Compare ranked candidates with negotiated streams and separate shutter latency from file-save latency. Share timings for text-only output; diagnostic sharing includes images.
