#pragma once
#include <fbjni/fbjni.h>
namespace margelo::nitro::documentscanner {
class NativePdfWriter final : public facebook::jni::JavaClass<NativePdfWriter> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativePdfWriter;";
  static void registerNatives();
};
} // namespace margelo::nitro::documentscanner
