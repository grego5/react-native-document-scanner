# Development

- `npm run check` runs library and example typechecks and JS contracts. It does not build native code or touch devices. `npm run check -- --list` lists the steps.
- After changing a Nitro spec, run `npm run nitrogen`. Edit the TypeScript source and generator wrapper, rather than generated bindings. Keep the package's generated registrations in sync with `nitro.json`.
- Native validation is platform-specific. See [Android validation](android/validation.md) and [iOS setup](ios/setup.md). Keep build, simulator and hardware outcomes separate.
- Portable C++ tests use `cpp/CMakeLists.txt` with OpenCV 4.12 (core, imgproc and video) and zlib. Android supplies its existing OpenCV target to the same core.

## Parity fixtures

`tests/fixtures/parity/` owns public synthetic fixtures. TSV files carry format, coordinate and tolerance metadata. Ordered rows with the same case ID share one policy instance. Keep independent expected outputs and update them with an intentional contract change.

| Evidence | Focused consumers |
| --- | --- |
| Adaptive pacing, torch, standard resolution, capture events | Android JVM policy/parity tests and iOS live guidance, controls, camera session and gate tests |
| Orientation, padded RGB and model corners | Android source-loader and parity tests |
| Warp, filters, gray PNG and PDF | Android render/export tests and portable C++ contract suites |
| Synthetic TFLite signatures and warm-up output | iOS inference tests using `ios/Tests/Fixtures/` |

Private model photos are opt-in. A missing input causes a skip and supplies no parity evidence. See [Tools](tools.md#model-export-and-private-fixtures).
