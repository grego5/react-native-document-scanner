#pragma once

#include <fbjni/fbjni.h>

namespace margelo::nitro::documentscanner {

/** The native halves of FieldKernels: small maps applied over a full-size page in one pass, rows in parallel. */
class FieldKernels final : public facebook::jni::JavaClass<FieldKernels> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/FieldKernels;";
  static void registerNatives();

private:
  static void inkNative(facebook::jni::alias_ref<jclass>, facebook::jni::alias_ref<facebook::jni::JArrayLong> channels,
                        facebook::jni::alias_ref<facebook::jni::JArrayLong> blurred, jlong ink, jlong deepen, jlong amount);
  static void gainNative(facebook::jni::alias_ref<jclass>, jlong rgba, jlong gain);
  static void divideNative(facebook::jni::alias_ref<jclass>, jlong source, facebook::jni::alias_ref<facebook::jni::JArrayLong> into,
                           jlong level, facebook::jni::alias_ref<facebook::jni::JArrayFloat> scales);
  static void grayDivideNative(facebook::jni::alias_ref<jclass>, jlong source, jlong into, jlong level,
                               facebook::jni::alias_ref<facebook::jni::JArrayFloat> weights,
                               facebook::jni::alias_ref<facebook::jni::JArrayFloat> gains, jfloat scale);
};

} // namespace margelo::nitro::documentscanner
