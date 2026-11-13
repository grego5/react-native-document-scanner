#pragma once
#include <fbjni/fbjni.h>
namespace margelo::nitro::documentscanner {
class NativeDocumentFilters final : public facebook::jni::JavaClass<NativeDocumentFilters> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeDocumentFilters;";
  static void registerNatives();
};
} // namespace margelo::nitro::documentscanner
