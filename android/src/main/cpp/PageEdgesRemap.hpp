#pragma once

#include <fbjni/fbjni.h>

namespace margelo::nitro::documentscanner {

/** The native half of PageEdges.remap: an exact coordinate map per strip of rows, then cv::remap. */
class PageEdgesRemap final : public facebook::jni::JavaClass<PageEdgesRemap> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/PageEdges;";
  static void registerNatives();

private:
  static void remapNative(facebook::jni::alias_ref<jclass>, jlong source, jlong into,
                          facebook::jni::alias_ref<facebook::jni::JArrayDouble> inverse,
                          facebook::jni::alias_ref<facebook::jni::JArrayFloat> left,
                          facebook::jni::alias_ref<facebook::jni::JArrayFloat> right,
                          facebook::jni::alias_ref<facebook::jni::JArrayFloat> top,
                          facebook::jni::alias_ref<facebook::jni::JArrayFloat> bottom);
};

} // namespace margelo::nitro::documentscanner
