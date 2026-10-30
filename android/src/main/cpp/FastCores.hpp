#pragma once

#include <fbjni/fbjni.h>

namespace margelo::nitro::documentscanner {

/**
 * The native half of FastCores: holds the calling thread and OpenCV's workers to the fast cores, or releases them, or
 * holds the calling thread alone to the prime cores.
 */
class FastCores final : public facebook::jni::JavaClass<FastCores> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/FastCores;";
  static void registerNatives();

private:
  static jboolean pinNative(facebook::jni::alias_ref<jclass>, jboolean fast);
  static jboolean primeNative(facebook::jni::alias_ref<jclass>);
};

} // namespace margelo::nitro::documentscanner
