#include "PageEdgesRemap.hpp"

#include "internal/Kernels.hpp"

namespace margelo::nitro::documentscanner {

using namespace facebook::jni;

void PageEdgesRemap::registerNatives() {
  javaClassStatic()->registerNatives({makeNativeMethod("remapNative", PageEdgesRemap::remapNative)});
}

/**
 * Writes [into] (already sized and typed) from [source]: output pixel (x, y) takes the source point that [inverse]
 * maps the bent canvas point to. Offsets are outward, per output row for [left] and [right] and per output column for
 * [top] and [bottom]; the bent point is the Coons patch x - (1 - u) left + u right, y - (1 - v) top + v bottom.
 */
void PageEdgesRemap::remapNative(alias_ref<jclass>, jlong sourceAddress, jlong intoAddress, alias_ref<JArrayDouble> inverse,
                                 alias_ref<JArrayFloat> left, alias_ref<JArrayFloat> right, alias_ref<JArrayFloat> top,
                                 alias_ref<JArrayFloat> bottom) {
  const cv::Mat& source = *reinterpret_cast<cv::Mat*>(sourceAddress);
  cv::Mat& into = *reinterpret_cast<cv::Mat*>(intoAddress);
  const int width = into.cols;
  const int height = into.rows;
  const auto h = inverse->getRegion(0, 9);
  const auto leftOffsets = left->getRegion(0, height);
  const auto rightOffsets = right->getRegion(0, height);
  const auto topOffsets = top->getRegion(0, width);
  const auto bottomOffsets = bottom->getRegion(0, width);
  document_scanner::internal::remap(source, into, &h[0], &leftOffsets[0], &rightOffsets[0], &topOffsets[0], &bottomOffsets[0]);
}

} // namespace margelo::nitro::documentscanner
