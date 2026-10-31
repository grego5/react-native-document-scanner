cmake_minimum_required(VERSION 3.22.1)
project(ReactNativeDocumentScanner)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(OpenCV REQUIRED CONFIG)

add_subdirectory(../cpp ${CMAKE_CURRENT_BINARY_DIR}/scanner-core)

add_library(ReactNativeDocumentScanner SHARED
  src/main/cpp/cpp-adapter.cpp
  src/main/cpp/NativeTensorPreprocessor.cpp
  src/main/cpp/NativeRenderGeometry.cpp
  src/main/cpp/NativeDocumentFilters.cpp
  src/main/cpp/NativeGrayscalePng.cpp
  src/main/cpp/NativePdfWriter.cpp
  src/main/cpp/NativeFocus.cpp
  src/main/cpp/NativeLiveGuide.cpp
  src/main/cpp/NativeMotion.cpp
  src/main/cpp/FieldKernels.cpp
  src/main/cpp/FastCores.cpp
  src/main/cpp/NativeGeometry.cpp
)

target_include_directories(ReactNativeDocumentScanner PRIVATE ../cpp/src)

target_link_libraries(ReactNativeDocumentScanner
  document_scanner_core
  android
  jnigraphics
  log
  c++_shared
  OpenCV::opencv_java4
)

# Keep the validated float normalization order; do not fuse or reassociate arithmetic.
set_source_files_properties(src/main/cpp/NativeTensorPreprocessor.cpp PROPERTIES COMPILE_OPTIONS "-O2;-ffp-contract=off")

include(${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/ReactNativeDocumentScanner+autolinking.cmake)
