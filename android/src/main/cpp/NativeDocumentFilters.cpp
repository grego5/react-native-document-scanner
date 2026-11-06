#include "NativeDocumentFilters.hpp"
#include "MatPixels.hpp"
#include <document_scanner/DocumentFilters.hpp>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
using namespace facebook::jni;
using namespace document_scanner;
// Synchronous borrowing only. The retained Mat header owns storage through the
// lease; the core writes BGR/gray/RGBA results directly into Android's output Mat.
void applyNative(alias_ref<jclass>, jlong sourceAddress, jlong intoAddress, jint filter, jboolean shadows) {
  if (!sourceAddress || !intoAddress)
    throwNewJavaException("java/lang/IllegalArgumentException", "Expected source and output matrices.");
  auto owner = std::make_shared<cv::Mat>(*reinterpret_cast<const cv::Mat *>(sourceAddress));
  if (owner->empty() || owner->type() != CV_8UC4 || filter < -1 || filter > 3)
    throwNewJavaException("java/lang/IllegalArgumentException", "Expected upright RGBA8 pixels and a known filter.");
  try {
    const Size size{uint32_t(owner->cols), uint32_t(owner->rows)};
    const PixelLease source(
        owner,
        {reinterpret_cast<const std::byte *>(owner->data),
         size_t(owner->rows - 1) * owner->step + size_t(owner->cols) * 4},
        {PixelFormat::rgba8, size, owner->step, 4, {0, 0, size.width, size.height}, Orientation::upright});
    const auto allocate = matAllocator(*reinterpret_cast<cv::Mat *>(intoAddress));
    if (filter == -1)
      removeDocumentShadows(source, allocate);
    else
      filterDocument(source, static_cast<DocumentFilter>(filter), shadows, PixelFormat::bgr8, allocate);
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("java/lang/IllegalArgumentException", error.what());
  } catch (const std::runtime_error &error) {
    throwNewJavaException("java/lang/IllegalStateException", error.what());
  }
}
} // namespace
void NativeDocumentFilters::registerNatives() {
  javaClassStatic()->registerNatives({makeNativeMethod("applyNative", applyNative)});
}
} // namespace margelo::nitro::documentscanner
