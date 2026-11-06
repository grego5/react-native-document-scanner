# Inference contract models

These tiny synthetic TFLite v3 FlatBuffers contain an unused input and a constant
nine-float output. They contain no trained weights or private images.

- `rectangular`: float32 [1,192,320,3] -> [1,9], corners (.1,.2)/(.8,.2)/(.8,.9)/(.1,.9), score .1.
- `bad-output`: [1,8] output.
- `two-inputs`: two input indices.
- `byte-input`: uint8 input instead of float32.
- `nonfinite-warmup`: infinite score, rejected after blank invocation by the shared decoder.

Only the CocoaPods XCTest target copies these resources. Production uses the canonical
bundled model or a caller-selected model, never these fixtures.
