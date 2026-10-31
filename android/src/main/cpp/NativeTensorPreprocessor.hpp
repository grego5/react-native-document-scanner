#pragma once

#include <fbjni/ByteBuffer.h>
#include <fbjni/fbjni.h>
#include <document_scanner/Tensor.hpp>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace margelo::nitro::documentscanner {

class JBitmap final : public facebook::jni::JavaClass<JBitmap> {
public:
  static constexpr auto kJavaDescriptor = "Landroid/graphics/Bitmap;";
};

class NativeTensorPreprocessor final : public facebook::jni::HybridClass<NativeTensorPreprocessor> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeTensorPreprocessor;";
  static void registerNatives();

private:
  friend HybridBase;
  NativeTensorPreprocessor(jint targetWidth, jint targetHeight);
  static facebook::jni::local_ref<jhybriddata> initHybrid(facebook::jni::alias_ref<jhybridobject>, jint, jint);
  void prepareNative(facebook::jni::alias_ref<JBitmap>, facebook::jni::alias_ref<facebook::jni::JByteBuffer>);
  void prepareRgbaNative(facebook::jni::alias_ref<facebook::jni::JByteBuffer>, jint, jint, jint, jint, jint, jint, jint,
                         jint, jint, jint, jint, facebook::jni::alias_ref<facebook::jni::JByteBuffer>);
  void prepareTensor(const document_scanner::PixelLease &, facebook::jni::alias_ref<facebook::jni::JByteBuffer>);
  document_scanner::TensorPreprocessor _preprocessor;
};

} // namespace margelo::nitro::documentscanner
