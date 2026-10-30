#include <fbjni/fbjni.h>
#include "ReactNativeDocumentScannerOnLoad.hpp"
#include "NativeTensorPreprocessor.hpp"
#include "PageEdgesRemap.hpp"
#include "FieldKernels.hpp"
#include "FastCores.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::documentscanner::registerAllNatives();
    margelo::nitro::documentscanner::NativeTensorPreprocessor::registerNatives();
    margelo::nitro::documentscanner::PageEdgesRemap::registerNatives();
    margelo::nitro::documentscanner::FieldKernels::registerNatives();
    margelo::nitro::documentscanner::FastCores::registerNatives();
  });
}
