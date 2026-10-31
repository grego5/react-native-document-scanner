#include "NativeDocumentFilters.hpp"
#include <document_scanner/DocumentFilters.hpp>
#include <opencv2/imgproc.hpp>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
using namespace facebook::jni;
using namespace document_scanner;
// Synchronous borrowing only. The retained Mat header owns storage through the
// lease; the portable result is copied into Android's independently owned Mat.
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
    const auto result = filter == -1 ? removeDocumentShadows(source)
                                     : filterDocument(source, static_cast<DocumentFilter>(filter), shadows);
    const auto lease = result.lease();
    const auto &l = lease.view().layout();
    const int type = l.format == PixelFormat::gray8 ? CV_8UC1 : l.format == PixelFormat::rgb8 ? CV_8UC3 : CV_8UC4;
    const cv::Mat pixels(int(l.size.height), int(l.size.width), type,
                         const_cast<std::byte *>(lease.view().bytes().data()), l.rowStride);
    auto &into = *reinterpret_cast<cv::Mat *>(intoAddress);
    if (l.format == PixelFormat::rgb8)
      cv::cvtColor(pixels, into, cv::COLOR_RGB2BGR);
    else
      pixels.copyTo(into);
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("java/lang/IllegalArgumentException", error.what());
  }
}
} // namespace
void NativeDocumentFilters::registerNatives() {
  javaClassStatic()->registerNatives({makeNativeMethod("applyNative", applyNative)});
}
} // namespace margelo::nitro::documentscanner
