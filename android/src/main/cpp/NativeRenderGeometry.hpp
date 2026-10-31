#pragma once
#include <fbjni/fbjni.h>
namespace margelo::nitro::documentscanner {
class NativeRenderGeometry final : public facebook::jni::JavaClass<NativeRenderGeometry> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeRenderGeometry;";
  static void registerNatives();
};
} // namespace margelo::nitro::documentscanner
