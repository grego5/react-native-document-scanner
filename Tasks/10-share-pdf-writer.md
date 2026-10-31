# 10 — Share streaming PDF layout and serialization

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [05](05-share-image-geometry.md), [09](09-share-gray-png.md)

## Objective

Preserve PDF contracts with one portable layout and serialization implementation.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- cpp/include/document_scanner/PdfWriter.hpp and cpp/src/PdfWriter.cpp — shared validation/layout/probing/serialization
- android/src/main/cpp/NativePdfWriter.cpp — synchronous JNI resource adapter
- android/src/main/java/com/margelo/nitro/documentscanner/processing/PdfWriter.kt — file/codec/publication adapter
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/PdfWriterTest.kt
- src/DocumentScannerProcessor.nitro.ts — CreatePdfOptions and createPdfResult

## Current behavior and invariants

Eligible JPEG/gray PNG payloads embed unchanged; EXIF changes placement, cover clips at the page, targetDpi never enlarges, and pages stream one at a time.

## Implementation steps

1. Move page-option validation, placement matrices, PDF objects/xref, direct embedding, and grayscale handling into C++.
2. Define platform image probe/decode/encode and file-stream adapters; keep destination replacement and sandbox access platform-owned.
3. Replace Android writer orchestration with the shared serializer and existing image/file adapters.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Verify page order, fit modes, orientations, page-size combinations, JPEG-quality forcing, DPI scaling, and four-bit preservation.
- Compare embedded eligible payloads byte-for-byte; inject stream failure and confirm existing destination survives.

## Validation commands

- Run shared build and ctest.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.PdfWriterTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android PDFs retain existing semantics and shared tests cover layout/serialization failures.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share streaming scanner PDF generation


## Implementation report

- Added portable `PdfWriter.hpp` / `PdfWriter.cpp`: page/aspect/DPI/JPEG-quality validation, defaults, all fit modes and eight EXIF placement matrices, structural JPEG/gray-PNG probing, unchanged payload selection, image dictionaries/masks, clipped page content, PDF objects/stream lengths, xref and trailer. RectF-compatible float storage/subtraction preserves existing matrix and DPI results. JPEG-quality presence forces JPEG re-encoding, including zero; eligible unscaled gray PNGs retain compressed data despite that override. DPI targets never enlarge source pixels, and rotated stored dimensions are passed to the codec correctly.
- `pdf::ImageAdapter` supplies synchronous random reads, fallback dimensions/EXIF and re-encoding. Core consumes/releases each page before opening the next. Direct JPEG bytes and PNG IDAT ranges stream through bounded buffers; one encoded image is retained at a time. No source/encoded callbacks or platform handles escape the synchronous call. Sink/source/declared-length errors stop output. The adapter owns final resource cleanup, flush/sync/close and atomic replacement. Passthrough preserves the prior no-CRC-check PNG behavior while checking structural bounds. Classic xref offsets above ten digits fail before platform publication.
- Added `NativePdfWriter.*` JNI/Kotlin registration and keep rules. Local JNI references/arrays cover the synchronous call; the encoded Java array is retained only through that page's payload copy. Android `PdfWriter.kt` now owns local-path/directory access, one RandomAccessFile, EXIF lookup, BitmapFactory sampled decode, white-background Canvas scaling, JPEG/shared-gray encoding and temporary-file publication. A stream decorator permits real failure injection without changing the default file flow. Existing serial processor execution, public/Nitro APIs and generated bindings are unchanged.
- Replaced Kotlin PDF layout/probe/serialization logic with direct engine calls. `GrayscalePng.read4` is now a native-probe metadata adapter; its `Image` retains platform file copying only. Removed the four remaining pure JVM reader tests only after moving their assertions to `cpp/tests/PdfWriterTest.cpp`: multi-IDAT dimensions/length/inflate and Java Random(7) samples, transparency sample, unsupported bit depths and truncated IEND. They are exercised by CTest and are not reported as JVM passes.
- Portable tests also cover existing placement TSV, every EXIF matrix, page-size/aspect combinations, DPI/defaults/no enlargement, quality rounding and forcing, unchanged RGB/gray JPEG and gray-PNG payloads, transparency, page order, object/xref offsets, one-page release, malformed metadata, invalid options, sink failure and short/failing payloads. Existing nine Android PDF assertions remain intact; added three tests for early/metadata/payload sink failures preserving existing output, input release/recovery, explicit quality/EXIF opt-out/aspect sizing, and invalid-option atomicity. Updated architecture/core/fixture guidance. C++ and Kotlin implementation stages only; no optimization or cleanup pass.

### Validation

- Worktree: `C:/dev/react-native-document-scanner-parity`, branch `codex/ios-scanner-parity`; started clean. `npm ci --no-audit --no-fund` installed root dependencies. Example install initially failed on Windows sandbox symlink permission; the same command with the required filesystem escalation succeeded. `npm run android:prebuild` in example generated the ignored Android project, with an SDK path in ignored local.properties. No tracked package/lock/config changes were introduced by setup. Build tools/configuration came from installed dependencies and the actual build, not guessed substitutions.
- Verified host toolchain: MSVC 19.51.36256 / Visual Studio toolset 14.51.36231, C++20, Windows x64, OpenCV 4.12.0, zlib 1.3.1. Ignored `.local/task10/build-core.cmd` configures `cmake -S cpp -B .local/build/core-tests -G "Ninja Multi-Config" -DDOCUMENT_SCANNER_BUILD_TESTS=ON -DOpenCV_DIR=C:/dev/react-native-document-scanner/.local/opencv-4.12.0/opencv/build/x64/vc16/lib -DZLIB_INCLUDE_DIR=C:/dev/react-native-document-scanner/.local/task09/zlib-build/include -DZLIB_LIBRARY=C:/dev/react-native-document-scanner/.local/task09/zlib-build/Release/zlibstatic.lib`, then builds Release using the installed VS developer environment. Those existing ignored dependency caches were reused read-only; no machine-specific path entered production CMake. An initial compile caught assignment to immutable encoded-image metadata; corrected to construction before successful builds.
- `cmd /c .local\task10\build-core.cmd`: final shared build passed. With the verified OpenCV DLL folder on PATH, `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`: all 7 suites passed (2.20 seconds), including all migrated parser assertions and PDF contracts.
- `npm run check`: both TypeScript checks and all 16 JS contracts passed.
- `adb devices` verified authorized `192.168.1.55:5555` online (Samsung SM-G998U, Android 15, arm64). The new-worktree Android build verified RN 0.88.1, Nitro 0.37.1, AGP 9.2.1, Kotlin 2.2.10, compile/target/min SDK 37/36/24, NDK 27.1.12297006 and CMake 4.1.2.
- Required initial device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.PdfWriterTest'`: APK built/installed; all 9 unchanged tests passed (0.272 seconds), including independent PdfRenderer visual checks, unchanged embedded payloads, gray depth/masks, DPI, page order and placement.
- Final expanded command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.PdfWriterTest,com.margelo.nitro.documentscanner.processing.GrayscalePngRuntimeTest'`: final APK built/installed; all 15 tests passed (0.284 seconds): 12 PDF tests and 3 PNG runtime tests. Sink exceptions preserve their original identity, stop writes, remove the temporary output and leave the existing destination unchanged; retry replaces it with a valid independently rendered PDF. No retired JVM parser assertions were counted as device tests.
- Final C++ formatting and diff whitespace checks passed. No new JVM run was required/performed: removed parser assertions now execute in portable tests, and rg found no remaining JVM GrayscalePng consumers. No camera capture, private-photo transfer, Mac/iOS execution or performance measurement was run. iOS integration/release evidence remains deferred per user direction; no cross-platform release-readiness claim is made. Encoded-array/buffer copies are explicit ownership adaptation, not an optimization.
