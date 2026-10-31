#include <fbjni/fbjni.h>
#include "ReactNativeDocumentScannerOnLoad.hpp"
#include "NativeTensorPreprocessor.hpp"
#include "NativeRenderGeometry.hpp"
#include "NativeDocumentFilters.hpp"
#include "NativeGrayscalePng.hpp"
#include "NativePdfWriter.hpp"
#include "FieldKernels.hpp"
#include "FastCores.hpp"
#include "NativeGeometry.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::documentscanner::registerAllNatives();
    margelo::nitro::documentscanner::NativeTensorPreprocessor::registerNatives();
    margelo::nitro::documentscanner::NativeRenderGeometry::registerNatives();
    margelo::nitro::documentscanner::NativeDocumentFilters::registerNatives();
    margelo::nitro::documentscanner::NativeGrayscalePng::registerNatives();
    margelo::nitro::documentscanner::NativePdfWriter::registerNatives();
    margelo::nitro::documentscanner::FieldKernels::registerNatives();
    margelo::nitro::documentscanner::FastCores::registerNatives();
    margelo::nitro::documentscanner::NativeGeometry::registerNatives();
  });
}
