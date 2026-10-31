# 02 — Introduce the portable C++ build and pixel boundary

[Back to task index](../TASKS.md)

Status: Planned
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

