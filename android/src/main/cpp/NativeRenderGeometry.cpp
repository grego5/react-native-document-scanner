#include "NativeRenderGeometry.hpp"
#include <document_scanner/RenderGeometry.hpp>
#include <opencv2/core.hpp>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
using namespace facebook::jni;
using namespace document_scanner;
// Mat headers retain their owned storage for the entire synchronous core call.
// No Java address or OpenCV type crosses the portable interface.
local_ref<JArrayDouble> renderNative(alias_ref<jclass>, jlong sourceAddress, jlong intoAddress,
                                     alias_ref<JArrayDouble> values, jdouble scale, jdouble paper, jboolean follow) {
  if (!sourceAddress || !intoAddress || sourceAddress == intoAddress || !values || values->size() != 8)
    throwNewJavaException("java/lang/IllegalArgumentException",
                          "Expected distinct source/output matrices and eight quad coordinates.");
  auto owner = std::make_shared<cv::Mat>(*reinterpret_cast<const cv::Mat *>(sourceAddress));
  if (owner->empty() || owner->type() != CV_8UC4)
    throwNewJavaException("java/lang/IllegalArgumentException", "Expected an upright RGBA8 source matrix.");
  auto &into = *reinterpret_cast<cv::Mat *>(intoAddress);
  try {
    const auto a = values->getRegion(0, 8);
    const Quad quad{Point{a[0], a[1]}, Point{a[2], a[3]}, Point{a[4], a[5]}, Point{a[6], a[7]}};
    const Size size{static_cast<uint32_t>(owner->cols), static_cast<uint32_t>(owner->rows)};
    PixelLease source(
        owner,
        {reinterpret_cast<const std::byte *>(owner->data),
         size_t(owner->rows - 1) * owner->step + size_t(owner->cols) * 4},
        {PixelFormat::rgba8, size, owner->step, 4, {0, 0, size.width, size.height}, Orientation::upright});
    const auto result =
        renderGeometry(source, quad, scale, paper == -1 ? std::nullopt : std::optional<double>(paper), follow);
    const auto pixels = result.pixels.lease();
    const auto &layout = pixels.view().layout();
    const cv::Mat output(int(layout.size.height), int(layout.size.width), CV_8UC4,
                         const_cast<std::byte *>(pixels.view().bytes().data()), layout.rowStride);
    output.copyTo(into);
    std::vector<double> metadata{double(layout.size.width), double(layout.size.height), result.curved ? 1.0 : 0.0};
    for (Point p : result.validContentPolygon) {
      metadata.push_back(p.x);
      metadata.push_back(p.y);
    }
    auto returned = JArrayDouble::newArray(metadata.size());
    returned->setRegion(0, metadata.size(), metadata.data());
    return returned;
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("com/margelo/nitro/documentscanner/InvalidInputException", error.what());
  } catch (const std::runtime_error &error) {
    throwNewJavaException("java/lang/IllegalStateException", error.what());
  }
}
} // namespace
void NativeRenderGeometry::registerNatives() {
  javaClassStatic()->registerNatives({makeNativeMethod("renderNative", renderNative)});
}
} // namespace margelo::nitro::documentscanner
