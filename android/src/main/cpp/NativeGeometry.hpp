#pragma once
#include <fbjni/fbjni.h>

namespace margelo::nitro::documentscanner {
class NativeGeometry final : public facebook::jni::JavaClass<NativeGeometry> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeGeometry;";
  static void registerNatives();
};
} // namespace margelo::nitro::documentscanner
