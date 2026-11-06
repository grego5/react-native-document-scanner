#include "NativeTensorPreprocessor.hpp"

#include <android/bitmap.h>
#include <limits>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
// Lock exactly once, release even when validation/filtering throws. No pointer escapes prepareNative.
class BitmapPixels final {
public:
  BitmapPixels(JNIEnv *env, jobject bitmap) : _env(env), _bitmap(bitmap) {
    void *address = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &address) != ANDROID_BITMAP_RESULT_SUCCESS) {
      throw std::runtime_error("Unable to lock source bitmap pixels.");
    }
    _pixels = static_cast<const uint8_t *>(address);
  }
  ~BitmapPixels() { AndroidBitmap_unlockPixels(_env, _bitmap); }
  BitmapPixels(const BitmapPixels &) = delete;
  BitmapPixels &operator=(const BitmapPixels &) = delete;
  const uint8_t *data() const { return _pixels; }

private:
  JNIEnv *_env;
  jobject _bitmap;
  const uint8_t *_pixels;
};

} // namespace
NativeTensorPreprocessor::NativeTensorPreprocessor(jint width, jint height)
    : _preprocessor({static_cast<uint32_t>(width), static_cast<uint32_t>(height)}) {}
facebook::jni::local_ref<NativeTensorPreprocessor::jhybriddata>
NativeTensorPreprocessor::initHybrid(facebook::jni::alias_ref<jhybridobject>, jint width, jint height) {
  return makeCxxInstance(width, height);
}

namespace {
using namespace facebook::jni;
local_ref<JArrayDouble> decodeOutputNative(alias_ref<jclass>, alias_ref<JArrayFloat> values) {
  try {
    const auto array = values->getRegion(0, values->size());
    const auto result = document_scanner::decodeCornerOutput({array.get(), values->size()});
    if (!result)
      return nullptr;
    const auto &q = result->corners;
    const double data[]{q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y, result->score};
    auto output = JArrayDouble::newArray(9);
    output->setRegion(0, 9, data);
    return output;
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("java/lang/IllegalStateException", error.what());
  }
}
local_ref<JArrayInt> validateTensorsNative(alias_ref<jclass>, jint inputs, jint outputs, jboolean inFloat,
                                           jboolean outFloat, alias_ref<JArrayInt> inputShape,
                                           alias_ref<JArrayInt> outputShape) {
  try {
    const auto in = inputShape->getRegion(0, inputShape->size());
    const auto out = outputShape->getRegion(0, outputShape->size());
    const auto size = document_scanner::validateCornerTensors(
        inputs, outputs, inFloat, outFloat, {in.get(), inputShape->size()}, {out.get(), outputShape->size()});
    const jint data[]{static_cast<jint>(size.width), static_cast<jint>(size.height)};
    auto result = JArrayInt::newArray(2);
    result->setRegion(0, 2, data);
    return result;
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("java/lang/IllegalStateException", error.what());
  }
}
} // namespace
void NativeTensorPreprocessor::registerNatives() {
  registerHybrid({makeNativeMethod("decodeOutputNative", decodeOutputNative),
                  makeNativeMethod("validateTensorsNative", validateTensorsNative),
                  makeNativeMethod("initHybrid", initHybrid),
                  makeNativeMethod("prepareNative", NativeTensorPreprocessor::prepareNative),
                  makeNativeMethod("prepareRgbaNative", NativeTensorPreprocessor::prepareRgbaNative)});
}

void NativeTensorPreprocessor::prepareNative(facebook::jni::alias_ref<JBitmap> bitmap,
                                             facebook::jni::alias_ref<facebook::jni::JByteBuffer> input) {
  auto *env = facebook::jni::Environment::current();
  AndroidBitmapInfo info{};
  if (AndroidBitmap_getInfo(env, bitmap.get(), &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.width == 0 ||
      info.height == 0 || info.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0 ||
      static_cast<uint64_t>(info.width) * 4 > info.stride) {
    throw std::invalid_argument("Expected a software ARGB_8888 source bitmap with valid dimensions and stride.");
  }
  auto owner = std::make_shared<BitmapPixels>(env, bitmap.get());
  document_scanner::PixelLease source(
      owner, {reinterpret_cast<const std::byte *>(owner->data()), static_cast<size_t>(info.stride) * info.height},
      {document_scanner::PixelFormat::rgba8,
       {info.width, info.height},
       info.stride,
       4,
       {0, 0, info.width, info.height},
       document_scanner::Orientation::upright});
  prepareTensor(source, input);
}

void NativeTensorPreprocessor::prepareRgbaNative(facebook::jni::alias_ref<facebook::jni::JByteBuffer> plane,
                                                 jint position, jint limit, jint width, jint height, jint rowStride,
                                                 jint pixelStride, jint left, jint top, jint right, jint bottom,
                                                 jint rotation,
                                                 facebook::jni::alias_ref<facebook::jni::JByteBuffer> input) {
  if (!plane->isDirect() || !plane->getDirectBytes() || position < 0 || limit < position ||
      static_cast<uint64_t>(limit) > plane->getDirectSize() || width <= 0 || height <= 0 || pixelStride < 4 ||
      rowStride <= 0 || left < 0 || top < 0 || right > width || bottom > height || right <= left || bottom <= top ||
      (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
    throw std::invalid_argument("Invalid direct RGBA plane, crop or rotation.");
  }
  using namespace document_scanner;
  const auto orientation = rotation == 90    ? Orientation::rotate90
                           : rotation == 180 ? Orientation::rotate180
                           : rotation == 270 ? Orientation::rotate270
                                             : Orientation::upright;
  auto owner =
      std::make_shared<facebook::jni::global_ref<facebook::jni::JByteBuffer>>(facebook::jni::make_global(plane));
  PixelLease source(
      owner,
      {reinterpret_cast<const std::byte *>(plane->getDirectBytes() + position), static_cast<size_t>(limit - position)},
      {PixelFormat::rgba8,
       {static_cast<uint32_t>(width), static_cast<uint32_t>(height)},
       static_cast<size_t>(rowStride),
       static_cast<size_t>(pixelStride),
       {static_cast<uint32_t>(left), static_cast<uint32_t>(top), static_cast<uint32_t>(right - left),
        static_cast<uint32_t>(bottom - top)},
       orientation});
  prepareTensor(source, input);
}
void NativeTensorPreprocessor::prepareTensor(const document_scanner::PixelLease &source,
                                             facebook::jni::alias_ref<facebook::jni::JByteBuffer> input) {
  if (!input->isDirect() || input->getDirectSize() < _preprocessor.inputBytes() || !input->getDirectBytes() ||
      reinterpret_cast<uintptr_t>(input->getDirectBytes()) % alignof(float) != 0)
    throw std::invalid_argument("Expected a direct float32 tensor buffer of the model input dimensions.");
  _preprocessor.prepare(
      source, {reinterpret_cast<float *>(input->getDirectBytes()), _preprocessor.inputBytes() / sizeof(float)});
}
} // namespace margelo::nitro::documentscanner
