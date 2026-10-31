# 02 â€” Introduce the portable C++ build and pixel boundary

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [01](01-establish-parity-fixtures.md)

## Objective

Create a compilable C++20 engine target without platform headers in its public interface.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/CMakeLists.txt
- android/src/main/cpp/NativeTensorPreprocessor.hpp
- android/src/main/cpp/FieldKernels.hpp
- android/src/main/cpp/PageEdgesRemap.hpp

## Current behavior and invariants

Android JNI registration and its existing algorithms continue working until their individual migrations replace them.

## Implementation steps

1. Add cpp/CMakeLists.txt and a standalone test target; connect Android CMake to the shared library without removing current JNI bindings.
2. Define portable pixel views with format, width/height, strides, crop, orientation, and bounded borrowed lifetime; define owned buffers and immutable geometry values separately.
3. Keep cv::Mat and implementation allocation internal. No JNI, generated Nitro, Android, or UIKit types enter public engine headers.
4. Separate platform wrappers from reusable kernel bodies and preserve -ffp-contract=off for validated normalization and field arithmetic.
5. On Android, link the core against the existing OpenCV::opencv_java4 runtime; introduce no second OpenCV runtime, static or shared. FastCores pins that runtime's worker threads, so a second runtime would leave its pool unpinned.
6. Document the policy timing convention later migrations follow: callers pass time explicitly as monotonic nanoseconds in one documented clock domain per camera; the core reads no clock and owns no thread or timer. A decision that waits returns its deadline, as CaptureDecision.Wait does today; the adapter owns the wakeup and cancels it by generation. Policies need not share one generic interface or schedule a timer when they have no deadline.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Exercise padded rows, BGRA/RGBA channel order, invalid spans, and source buffer lifetime.
- Build portable headers without platform SDK includes; confirm Android still links JNI registrations.
- Confirm the Android library loads exactly one OpenCV runtime (inspect linked libraries and exported symbols).

## Validation commands

- cmake -S cpp -B .local/build/core-tests -DDOCUMENT_SCANNER_BUILD_TESTS=ON
- cmake --build .local/build/core-tests --config Debug
- ctest --test-dir .local/build/core-tests -C Debug --output-on-failure

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Core builds with a documented verified OpenCV/compiler setup; Android retains its original capture and processing behavior.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

build: add portable scanner core and pixel contracts


## Implementation and evidence

- Added `cpp/CMakeLists.txt`, portable `include/document_scanner/Pixels.hpp`, validated `src/Pixels.cpp`, standalone `tests/PixelsTest.cpp`, and boundary/timing documentation in `cpp/README.md`.
- Moved the existing field and page-edge kernel bodies into `cpp/src/`, with OpenCV-only declarations in private `src/internal/Kernels.hpp`. Android wrappers retain JNI registration, extraction and synchronous array leases. Algorithms, thresholds, strip sizing and OpenCV parallel execution remain unchanged. Tensor preprocessing remains Android-owned until Task 06; its existing normalization flag is retained.
- Android links the static core into its existing shared library and requires its existing `OpenCV::opencv_java4` target. Public headers contain only standard C++20 types. Pixel views validate byte strides, complete spans, overflow, crop and EXIF orientation; owner tokens retain backing storage/locks. Owned publication is immutable. Crop/orientation transforms belong to Task 05.
- Policy timing is documented as caller-supplied monotonic nanoseconds with explicit camera clock domains, returned absolute deadlines, and generation-cancelled adapter wakeups. Android policy migration uses uptime (preserving current capture/focus semantics); sensor elapsed-realtime requires explicit mapping.

Verified host setup: Windows x64, CMake 3.30.3, Ninja from Android SDK CMake 4.1.2, MSVC 19.51.36256.0 / VS Build Tools 18.9.2, OpenCV 4.12.0 from `.local/opencv-4.12.0/opencv/build/x64/vc16/lib`. VS 2026 generator is unsupported by installed CMake; Ninja works. Initial MSVC `/Zi` probes failed with C1902 database-manager mismatch; embedded `/Z7` debug information resolved it. No repository toolchain setting was weakened.

Host commands (from the repository, in `VsDevCmd.bat -arch=x64 -host_arch=x64` environment):

```text
cmake -S cpp -B .local/build/core-tests -G "Ninja Multi-Config" -DCMAKE_MAKE_PROGRAM=C:/Users/User/AppData/Local/Android/Sdk/cmake/4.1.2/bin/ninja.exe -DCMAKE_POLICY_DEFAULT_CMP0141=NEW -DCMAKE_MSVC_DEBUG_INFORMATION_FORMAT=Embedded -DOpenCV_DIR=C:/dev/react-native-document-scanner/.local/opencv-4.12.0/opencv/build/x64/vc16/lib -DDOCUMENT_SCANNER_BUILD_TESTS=ON
cmake --build .local/build/core-tests --config Debug
ctest --test-dir .local/build/core-tests -C Debug --output-on-failure
```

Build and CTest passed (1 executable covering padded rows, RGBA/BGRA/RGB/gray order, invalid spans/overflow/crop/orientation, coordinate bounds, retained owner lifetime and owned-buffer release). The public header compiles without platform SDK or OpenCV includes. `npm run check` passed both typechecks and all 16 JS contracts.

Android setup: NDK 27.1.12297006 / Clang 18.0.2, CMake 4.1.2, compile SDK/build tools 37, existing OpenCV 4.12.0 prefab runtime. Device `192.168.1.55:5555`, Samsung SM-G998U, API 35, arm64-v8a. The user explicitly authorized fixture transfer and device tests. Initial build/install and field/filter/preprocessor run passed 13 tests (`.local/task02-device.log`).

ELF inspection with NDK `llvm-readelf -d` found exactly one OpenCV NEEDED entry, `libopencv_java4.so`. `llvm-nm -D -C` showed field, page-edge and tensor JNI registrations retained, with OpenCV resize/remap/parallel_for_ imported rather than defined. The arm64 test APK contains only `lib/arm64-v8a/libopencv_java4.so` as its OpenCV runtime.

C++ implementation stage scope: pixel ownership/validation and build/kernel boundary. No optimization or cleanup stage was performed. C++ LSP was unavailable; focused source reads and native compilation were used. No physical camera capture was automated. iOS/Xcode and later algorithm migration evidence remain outside this task.

Final device command (PATH includes `C:/Users/User/AppData/Local/Android/Sdk/platform-tools`; `ORG_GRADLE_PROJECT_docCornerFixtures=C:/dev/react-native-document-scanner/.local/doccornernet-runtime/assets`):

```powershell
./tools/test-device.ps1 -Device '192.168.1.55:5555' -JavaHome 'C:\Program Files\Android\Android Studio\jbr' -Class @('com.margelo.nitro.documentscanner.processing.FieldKernelsTest','com.margelo.nitro.documentscanner.processing.DocumentFiltersTest','com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessorTest','com.margelo.nitro.documentscanner.processing.DocumentRendererTest')
```

Passed 22 tests, zero failures or skips, including bent-page flattening and render geometry (`.local/task02-device-final.log`). The CMake flag-owner adjustment removed obsolete flags from JNI wrapper translation units; optimized kernel flags reside on the core sources. Final host build/CTest and `git diff --check` passed after that adjustment.

Final Android build after the flag-owner adjustment passed: from `example/android`, with `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`, `./gradlew.bat :react-native-document-scanner:assembleDebugAndroidTest -PreactNativeArchitectures=arm64-v8a --console=plain -q` (exit 0, `.local/task02-final-build.log`). Completion covers this portable build/pixel boundary only; Tasks 03–33 remain planned.
