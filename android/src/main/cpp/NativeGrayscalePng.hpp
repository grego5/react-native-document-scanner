#pragma once
#include <fbjni/fbjni.h>
namespace margelo::nitro::documentscanner {
class NativeGrayscalePng final : public facebook::jni::JavaClass<NativeGrayscalePng> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeGrayscalePng;";
  static void registerNatives();
};
} // namespace margelo::nitro::documentscanner
