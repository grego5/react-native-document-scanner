#include "NativeTensorPreprocessor.hpp"

#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
constexpr uint32_t kPrecision = 1u << 22;
constexpr uint32_t kRounding = 1u << 21;
size_t inputBytes(jint width, jint height) {
  if (width <= 0 || height <= 0 ||
      static_cast<uint64_t>(width) * height > std::numeric_limits<jint>::max() / (3 * sizeof(float))) {
    throw std::invalid_argument("Invalid corner model input dimensions.");
  }
  return static_cast<size_t>(width) * height * 3 * sizeof(float);
}

// Lock exactly once, release even when validation/filtering throws. No pointer escapes prepareNative.
class BitmapPixels final {
public:
  BitmapPixels(JNIEnv* env, jobject bitmap) : _env(env), _bitmap(bitmap) {
    void* address = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &address) != ANDROID_BITMAP_RESULT_SUCCESS) {
      throw std::runtime_error("Unable to lock source bitmap pixels.");
    }
    _pixels = static_cast<const uint8_t*>(address);
  }
  ~BitmapPixels() { AndroidBitmap_unlockPixels(_env, _bitmap); }
  BitmapPixels(const BitmapPixels&) = delete;
  BitmapPixels& operator=(const BitmapPixels&) = delete;
  const uint8_t* data() const { return _pixels; }
private:
  JNIEnv* _env;
  jobject _bitmap;
  const uint8_t* _pixels;
};

uint32_t rounded(uint32_t value) { return std::min(value >> 22, 255u); }
} // namespace

NativeTensorPreprocessor::NativeTensorPreprocessor(jint targetWidth, jint targetHeight)
    : _targetWidth(targetWidth), _targetHeight(targetHeight), _inputBytes(inputBytes(targetWidth, targetHeight)) {
  constexpr std::array<float, 3> means{0.485f, 0.456f, 0.406f};
  constexpr std::array<float, 3> deviations{0.229f, 0.224f, 0.225f};
  for (size_t channel = 0; channel < 3; ++channel) {
    for (size_t value = 0; value < 256; ++value) {
      _normalization[channel][value] = (static_cast<float>(value) / 255.f - means[channel]) / deviations[channel];
    }
  }
}

facebook::jni::local_ref<NativeTensorPreprocessor::jhybriddata>
NativeTensorPreprocessor::initHybrid(facebook::jni::alias_ref<jhybridobject>, jint width, jint height) {
  return makeCxxInstance(width, height);
}

void NativeTensorPreprocessor::registerNatives() {
  registerHybrid({makeNativeMethod("initHybrid", initHybrid), makeNativeMethod("prepareNative", NativeTensorPreprocessor::prepareNative),
                  makeNativeMethod("prepareRgbaNative", NativeTensorPreprocessor::prepareRgbaNative)});
}

std::vector<NativeTensorPreprocessor::Weights> NativeTensorPreprocessor::weights(uint32_t source, uint32_t target) {
  const double scale = static_cast<double>(source) / target;
  const double filterScale = std::max(1.0, scale);
  std::vector<Weights> result;
  result.reserve(target);
  for (uint32_t index = 0; index < target; ++index) {
    const double center = (index + 0.5) * scale;
    const uint32_t start = static_cast<uint32_t>(std::max(0, static_cast<int>(center - filterScale + 0.5)));
    const uint32_t end = std::min(source, static_cast<uint32_t>(center + filterScale + 0.5));
    std::vector<double> raw(end - start);
    double sum = 0;
    for (uint32_t offset = 0; offset < raw.size(); ++offset) {
      raw[offset] = std::max(0.0, 1.0 - std::abs((start + offset - center + 0.5) / filterScale));
      sum += raw[offset];
    }
    Weights kernel{start, std::vector<uint32_t>(raw.size())};
    for (size_t offset = 0; offset < raw.size(); ++offset) {
      kernel.coefficients[offset] = static_cast<uint32_t>((raw[offset] / sum) * kPrecision + 0.5);
    }
    result.push_back(std::move(kernel));
  }
  return result;
}

void NativeTensorPreprocessor::prepareNative(facebook::jni::alias_ref<JBitmap> bitmap,
                                            facebook::jni::alias_ref<facebook::jni::JByteBuffer> input) {
  auto* env = facebook::jni::Environment::current();
  AndroidBitmapInfo info{};
  if (AndroidBitmap_getInfo(env, bitmap.get(), &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.width == 0 || info.height == 0 || info.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0 ||
      static_cast<uint64_t>(info.width) * 4 > info.stride) {
    throw std::invalid_argument("Expected a software ARGB_8888 source bitmap with valid dimensions and stride.");
  }
  BitmapPixels source(env, bitmap.get());
  preparePixels(info.width, info.height, [&](uint32_t x, uint32_t y) {
    return source.data() + static_cast<size_t>(y) * info.stride + static_cast<size_t>(x) * 4;
  }, input);
}

void NativeTensorPreprocessor::prepareRgbaNative(
    facebook::jni::alias_ref<facebook::jni::JByteBuffer> plane,
    jint position, jint limit, jint width, jint height, jint rowStride, jint pixelStride,
    jint left, jint top, jint right, jint bottom, jint rotation,
    facebook::jni::alias_ref<facebook::jni::JByteBuffer> input) {
  if (!plane->isDirect() || !plane->getDirectBytes() || position < 0 || limit < position ||
      static_cast<uint64_t>(limit) > plane->getDirectSize() || width <= 0 || height <= 0 ||
      pixelStride < 4 || rowStride <= 0 || left < 0 || top < 0 || right > width || bottom > height ||
      right <= left || bottom <= top ||
      (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
    throw std::invalid_argument("Invalid direct RGBA plane, crop or rotation.");
  }
  const auto rowBytes = static_cast<uint64_t>(width - 1) * pixelStride + 4;
  const auto required = static_cast<uint64_t>(height - 1) * rowStride + rowBytes;
  if (rowBytes > static_cast<uint64_t>(rowStride) || required > static_cast<uint64_t>(limit - position)) {
    throw std::invalid_argument("RGBA plane position/limit does not cover the image strides.");
  }
  const uint32_t cropWidth = right - left, cropHeight = bottom - top;
  const auto* base = plane->getDirectBytes() + position;
  const bool swap = rotation == 90 || rotation == 270;
  preparePixels(swap ? cropHeight : cropWidth, swap ? cropWidth : cropHeight,
    [&](uint32_t x, uint32_t y) {
      uint32_t sx = x, sy = y;
      switch (rotation) {
        case 90: sx = y; sy = cropHeight - 1 - x; break;
        case 180: sx = cropWidth - 1 - x; sy = cropHeight - 1 - y; break;
        case 270: sx = cropWidth - 1 - y; sy = x; break;
      }
      return reinterpret_cast<const uint8_t*>(base) + static_cast<size_t>(top + sy) * rowStride +
             static_cast<size_t>(left + sx) * pixelStride;
    }, input);
}

template <typename Pixel>
void NativeTensorPreprocessor::preparePixels(uint32_t width, uint32_t height, Pixel pixel,
                                             facebook::jni::alias_ref<facebook::jni::JByteBuffer> input) {
  if (!input->isDirect() || input->getDirectSize() < _inputBytes || input->getDirectBytes() == nullptr ||
      reinterpret_cast<uintptr_t>(input->getDirectBytes()) % alignof(float) != 0) {
    throw std::invalid_argument("Expected a direct float32 tensor buffer of the model input dimensions.");
  }
  if (_width != width) {
    auto kernels = weights(width, _targetWidth);
    _xWeights = std::move(kernels);
    _width = width;
  }
  if (_height != height) {
    auto kernels = weights(height, _targetHeight);
    if (height > _horizontal.max_size() / _targetWidth) {
      throw std::length_error("Corner model resize workspace is too large.");
    }
    _horizontal.resize(static_cast<size_t>(_targetWidth) * height);
    _yWeights = std::move(kernels);
    _height = height;
  }
  for (uint32_t y = 0; y < height; ++y) {
    for (uint32_t x = 0; x < _targetWidth; ++x) {
      const auto& kernel = _xWeights[x];
      uint32_t red = kRounding, green = kRounding, blue = kRounding;
      uint32_t sourceX = kernel.start;
      for (const auto weight : kernel.coefficients) {
        const auto* pixels = pixel(sourceX++, y);
        if (pixels[3] != 255) {
          throw std::invalid_argument("DocCornerNet preprocessing requires opaque source pixels.");
        }
        red += pixels[0] * weight;
        green += pixels[1] * weight;
        blue += pixels[2] * weight;
      }
      _horizontal[static_cast<size_t>(y) * _targetWidth + x] =
          (rounded(red) << 16) | (rounded(green) << 8) | rounded(blue);
    }
  }
  auto* tensor = reinterpret_cast<float*>(input->getDirectBytes());
  for (uint32_t y = 0; y < _targetHeight; ++y) {
    const auto& kernel = _yWeights[y];
    for (uint32_t x = 0; x < _targetWidth; ++x) {
      uint32_t red = kRounding, green = kRounding, blue = kRounding;
      size_t offset = static_cast<size_t>(kernel.start) * _targetWidth + x;
      for (const auto weight : kernel.coefficients) {
        const auto pixel = _horizontal[offset];
        red += ((pixel >> 16) & 255) * weight;
        green += ((pixel >> 8) & 255) * weight;
        blue += (pixel & 255) * weight;
        offset += _targetWidth;
      }
      *tensor++ = _normalization[0][rounded(red)];
      *tensor++ = _normalization[1][rounded(green)];
      *tensor++ = _normalization[2][rounded(blue)];
    }
  }
}
} // namespace margelo::nitro::documentscanner
