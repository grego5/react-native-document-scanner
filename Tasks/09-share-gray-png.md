# 09 — Share four-bit grayscale PNG output

[Back to task index](../TASKS.md)

Status: Completed
Complexity: Medium
Dependencies: [02](02-introduce-portable-core.md)

## Objective

Provide one portable four-bit grayscale PNG encoder.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- cpp/include/document_scanner/GrayscalePng.hpp and cpp/src/GrayscalePng.cpp — common writer
- android/src/main/cpp/NativeGrayscalePng.cpp — synchronous row/sink adapter
- android/src/main/java/com/margelo/nitro/documentscanner/processing/GrayscalePng.kt — Android adapter and retained reader
- android/src/test/java/com/margelo/nitro/documentscanner/processing/GrayscalePngTest.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentRenderer.kt — PNG output branch

## Current behavior and invariants

PNG is 16-level grayscale without dithering and is allowed only for grayscale/blackWhite rendering.

## Implementation steps

1. Move row quantization, packing, compression, and PNG serialization into the core behind an output sink.
2. Replace Android encoder use with the shared writer; keep temporary file ownership in the adapter.
3. Migrate pure encoder assertions atomically; preserve PDF-readable compressed image data.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public corners remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Check PNG signature, chunk integrity, bit depth, odd widths, row padding, quantization boundaries, and lossless decode of quantized values.
- Verify sink failure stops output and leaves publication to the platform adapter.

## Validation commands

- Run shared build and ctest, including all migrated GrayscalePngTest assertions.
- npm run check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android emits valid equivalent four-bit PNG through the common writer.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share four-bit grayscale PNG encoding


## Implementation report

- Added portable `GrayscalePng.hpp` / `GrayscalePng.cpp` with synchronous full-image gray8 pixel leases and streaming owned-row leases. The writer borrows source pixels without mutation; neither leases nor callbacks survive return. It owns nearest-level quantization `(value+8)/17`, high/low nibble packing with zero odd-width padding, all five adaptive PNG predictors with the original signed-residual score/first-minimum tie order, level-4 zlib compression, 64 KiB output buffers, chunk lengths/CRCs and serialization. Output stays true noninterlaced four-bit grayscale, without palette, transparency or dithering.
- `ByteSink` must consume its complete borrowed span or throw. Row/sink errors unwind immediately through RAII zlib cleanup, without further output or flush/close. The compressed-only variant emits the same concatenated IDAT payload for four-bit PDF Predictor 15. PNG 31-bit dimension checks and full-upright gray8 layout checks guard the boundary; padded rows are supported.
- Added `NativeGrayscalePng.*` C++/Kotlin adapters, registration and keep rules. Android retains row callbacks and stream/Mat/bitmap/file ownership; each reusable Java row is copied into native owned storage and leased only until that row is consumed. Sink bytes are copied into a reused local Java array and synchronously written on the caller's thread. Existing renderer/PDF calls now reach the shared writer through `GrayscalePng.write4` / `writeCompressed4`; the temporary-file, atomic publication and format/filter gates are unchanged. No generated/public API or camera behavior changed. No platform handles enter the portable header.
- Removed Kotlin encoding algorithms. Retained `GrayscalePng.read4` / `Image.copyCompressedTo` with the Android PDF path for Task 10; this task does not migrate PDF parsing/embedding. CMake now requires standard `ZLIB::ZLIB`: Android resolves the NDK system `libz.so` (API 24, arm64), while the host uses a development zlib library. No second Android OpenCV runtime was added.
- Moved encoder assertions from the JVM to `cpp/tests/GrayscalePngTest.cpp`: the existing TSV's 33x3/1x1/16x2 cases and sixteen first-row levels, every quantization boundary, signature/chunk/CRC metadata, independent inverse filtering/decoding, exact quantized samples, odd nibble and padded rows, Java Random(7) 1025x400 multi-IDAT data, PDF stream equality, read-only source and row lifetimes, invalid dimensions/rows/sinks, exception propagation and successful recovery. Retained all Kotlin reader assertions on the JVM using independent synthetic parser PNGs under `tests/fixtures/parity/gray-png/`; the multi-IDAT source retains the original Java Random(7) values and sample assertions. Removed assertions are exercised by CTest, not counted as JVM passes.
- Added Android `GrayscalePngRuntimeTest` for independent BitmapFactory decoding, stream ownership, row/sink exception identity, stopping on failure, invalid dimensions and recovery. Existing filter PNG-format and PDF passthrough/rendering assertions remain intact. Updated architecture/core/fixture documentation. C++ and Kotlin implementation stages only; no performance/optimization or cleanup pass.
- Required full-suite validation also exposed a Task 08 follow-up: its in-place 8U/16S/8U sharpness intermediate could yield allocation-dependent host ink maps (combined/separate output differed by 48). `DocumentFilters.cpp` now gives blur, signed Laplacian and magnitude independent storage, preserving parameters/order. Twenty final targeted host processes passed with that minimal correction; the original one-level parity budget was not widened. Task 08's report records that its earlier rounding explanation was incomplete. Fresh Android filter parity is part of this task's validation.

### Validation

- Host setup was verified before compilation: Windows x64, Visual Studio toolset 14.51.36231 (compiler 19.51.36256), C++20, OpenCV 4.12 from the existing `.local/opencv-4.12.0` distribution. That distribution contains zlib 1.3.1 sources. The ignored `.local/task09/setup-host.cmd` built those sources as a static development library and configured the existing core build with `-DZLIB_INCLUDE_DIR=C:/dev/react-native-document-scanner/.local/task09/zlib-build/include -DZLIB_LIBRARY=C:/dev/react-native-document-scanner/.local/task09/zlib-build/Release/zlibstatic.lib`. Machine-specific setup remains ignored; production CMake only uses standard ZLIB discovery.
- `cmd /c .local\task05\build-core.cmd` builds the configured core with MSVC. With `.local/opencv-4.12.0/opencv/build/x64/vc16/bin` on PATH, final `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`: all 6 suites passed (1.63 seconds), including the migrated encoder assertions and Task 08 filter correction. Twenty additional targeted host runs verified the previously intermittent ownership path.
- `npm run check`: both TypeScript checks and all 16 JS contract tests passed.
- `npm run test:native -- -Test '*GrayscalePngTest'` initially hit stale Expo plugin output (no valid test result). The documented `npm run test:native -- -Test '*GrayscalePngTest' -RecompilePlugins` rebuilt it successfully; XML confirms 4 tests, 0 failures/errors/skips. Those four cover the retained parser/compressed-span behavior; they do not claim to exercise the shared encoder.
- `adb devices` verified `192.168.1.55:5555` online (Samsung SM-G998U, Android 15, arm64). Android setup: RN 0.88.1, Nitro 0.37.1, AGP 9.2.1, Kotlin 2.2.10, NDK 27.1.12297006, CMake 4.1.2, compile/target/min SDK 37/36/24. CMake resolves ZLIB headers in the NDK sysroot and `usr/lib/aarch64-linux-android/24/libz.so`. An initial JNI build caught const array references passed to mutable fbjni array APIs; corrected before installation/testing.
- Initial device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.DocumentFiltersTest,com.margelo.nitro.documentscanner.processing.PdfWriterTest'`: APK built/installed; all 16 tests passed (0.910 seconds), including true four-bit PNG, unchanged compressed-data PDF embedding, sixteen displayed gray levels, transparency masking and DPI-resize encoding.
- No camera captures, private-photo transfers, Mac/iOS execution or new performance measurements occurred. Per user direction, iOS CI execution remains pending until integration/release validation; no physical iOS readiness is claimed.
- Final device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.GrayscalePngRuntimeTest,com.margelo.nitro.documentscanner.processing.DocumentFiltersTest,com.margelo.nitro.documentscanner.processing.PdfWriterTest,com.margelo.nitro.documentscanner.processing.FilterParityTest,com.margelo.nitro.documentscanner.processing.ShadowRemovalTest'`: APK built/installed; 25 tests ran, 24 passed, 1 failed (1.784 seconds). All 3 new PNG tests and all 16 PNG/filter-format/PDF tests passed, including sink/row exception identity. The failure was Task 08's original filter baseline: corrected sharpness changed the page enhance output by 73 levels. This was not accepted as parity. Original OpenCV 4.12 source (`modules/imgproc/src/deriv.cpp`, Laplacian) recreates the output before using input; with the same Mat passed for both and a different depth, the old path reads replaced storage. The old Kotlin generator also passes Mat references. An independently corrected reference and new baselines require resolving this observable scope change; user choice requested. Task 09 status remains In progress pending that resolution, not a claim that its PNG tests failed.
- User approved fixing sharpening. Independent corrected Kotlin reference verification passed (2 Android tests, 0.803 seconds) before refreshing six affected baselines. The final recorder was removed; the persistent test compares corrected reference bytes exactly to fixtures and shared output within the unchanged one-level budget. The new portable allocation-reuse regression and all 6 CTest suites passed (2.22 seconds). An initial regression-test compile attempted assignment to immutable OwnedPixels; it was corrected to retain constructed outputs in a vector, rebuilt, and only the subsequent fresh CTest run is accepted as evidence.
- Final corrected device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.GrayscalePngRuntimeTest,com.margelo.nitro.documentscanner.processing.DocumentFiltersTest,com.margelo.nitro.documentscanner.processing.PdfWriterTest,com.margelo.nitro.documentscanner.processing.FilterParityTest,com.margelo.nitro.documentscanner.processing.ShadowRemovalTest,com.margelo.nitro.documentscanner.processing.FieldKernelsTest,com.margelo.nitro.documentscanner.processing.DocumentRendererTest'`: final APK built/installed; all 37 tests passed (14.278 seconds). This resolves the earlier filter-baseline failure without widening tolerance. The Kotlin oracle is byte-for-byte the original algorithms after symbol renaming/test comments and the explicit sharpness-intermediate correction; a focused source comparison verified that provenance. All 16 final fixture lengths/hashes and final C++ formatting/diff checks passed. No validation blockers remain for this Android migration slice; iOS/release validation stays deferred as stated above.
