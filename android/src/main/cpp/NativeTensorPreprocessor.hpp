#pragma once

#include <fbjni/ByteBuffer.h>
#include <fbjni/fbjni.h>
#include <array>
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
  static constexpr auto kJavaDescriptor =
      "Lcom/margelo/nitro/documentscanner/processing/NativeTensorPreprocessor;";
  static void registerNatives();

private:
  friend HybridBase;
  NativeTensorPreprocessor(jint targetWidth, jint targetHeight);
  static facebook::jni::local_ref<jhybriddata> initHybrid(facebook::jni::alias_ref<jhybridobject>, jint, jint);
  void prepareNative(facebook::jni::alias_ref<JBitmap>, facebook::jni::alias_ref<facebook::jni::JByteBuffer>);
  void prepareRgbaNative(facebook::jni::alias_ref<facebook::jni::JByteBuffer>, jint, jint, jint, jint,
                         jint, jint, jint, jint, jint, jint, jint,
                         facebook::jni::alias_ref<facebook::jni::JByteBuffer>);
  template <typename Pixel> void preparePixels(uint32_t width, uint32_t height, Pixel pixel,
                                              facebook::jni::alias_ref<facebook::jni::JByteBuffer> input);

  struct Weights {
    uint32_t start;
    std::vector<uint32_t> coefficients;
  };
  static std::vector<Weights> weights(uint32_t source, uint32_t target);
  const uint32_t _targetWidth;
  const uint32_t _targetHeight;
  const size_t _inputBytes;
  uint32_t _width = 0;
  uint32_t _height = 0;
  std::vector<Weights> _xWeights;
  std::vector<Weights> _yWeights;
  std::vector<uint32_t> _horizontal;
  std::array<std::array<float, 256>, 3> _normalization;
};

} // namespace margelo::nitro::documentscanner
