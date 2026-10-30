cmake_minimum_required(VERSION 3.22.1)
project(ReactNativeDocumentScanner)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(OpenCV REQUIRED CONFIG)

add_library(ReactNativeDocumentScanner SHARED
  src/main/cpp/cpp-adapter.cpp
  src/main/cpp/NativeTensorPreprocessor.cpp
  src/main/cpp/PageEdgesRemap.cpp
  src/main/cpp/FieldKernels.cpp
  src/main/cpp/FastCores.cpp
)

target_link_libraries(ReactNativeDocumentScanner
  android
  jnigraphics
  log
  c++_shared
  OpenCV::opencv_java4
)

# Keep the validated float normalization order; do not fuse or reassociate arithmetic.
set_source_files_properties(src/main/cpp/NativeTensorPreprocessor.cpp PROPERTIES COMPILE_OPTIONS "-O2;-ffp-contract=off")

include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/ReactNativeDocumentScanner+autolinking.cmake)

# A per-pixel loop: optimized in debug builds too, so they time and run like release.
set_source_files_properties(src/main/cpp/PageEdgesRemap.cpp PROPERTIES COMPILE_OPTIONS "-O2")
# No fused multiply-add: the kernels round as the OpenCV passes they replaced did, separately per operation.
set_source_files_properties(src/main/cpp/FieldKernels.cpp PROPERTIES COMPILE_OPTIONS "-O2;-ffp-contract=off")
