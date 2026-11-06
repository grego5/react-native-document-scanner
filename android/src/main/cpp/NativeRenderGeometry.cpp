#include "NativeRenderGeometry.hpp"
#include "MatPixels.hpp"
#include <document_scanner/RenderGeometry.hpp>
#include <document_scanner/DocumentFilters.hpp>
#include <opencv2/imgproc.hpp>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
using namespace facebook::jni;
using namespace document_scanner;
// Mat headers retain their owned storage for the entire synchronous core call.
// No Java address or OpenCV type crosses the portable interface.
local_ref<JArrayDouble> renderNative(alias_ref<jclass>, jlong sourceAddress, jlong intoAddress,
                                     alias_ref<JArrayDouble> values, jdouble scale, jdouble paper, jboolean follow,
                                     jint filter, jboolean shadows, alias_ref<JArrayDouble> regionValues) {
  if (!sourceAddress || !intoAddress || sourceAddress == intoAddress || !values || values->size() != 8 || filter < -1 ||
      filter > 3)
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
    std::optional<RenderSourceRegion> region;
    if (regionValues) {
      if (regionValues->size() != 5)
        throw std::invalid_argument("Expected five decoded-region coordinates.");
      const auto r = regionValues->getRegion(0, 5);
      region = RenderSourceRegion{r[0], r[1], r[2], r[3], r[4]};
    }
    const std::optional<double> paperEdge = paper == -1 ? std::nullopt : std::optional<double>(paper);
    // Write straight into the output Mat. A filtered render keeps the warped RGBA lease alive for the
    // filter, which then writes BGR/gray into the output Mat: no intermediate Java Mat or copy.
    const auto info = [&]() -> GeometricRenderInfo {
      if (filter < 0)
        return renderGeometry(source, quad, scale, paperEdge, follow, matAllocator(into), region);
      const auto result = renderGeometry(source, quad, scale, paperEdge, follow, region);
      filterDocument(result.pixels.lease(), static_cast<DocumentFilter>(filter), shadows, PixelFormat::bgr8,
                     matAllocator(into));
      return {result.validContentPolygon, result.curved};
    }();
    std::vector<double> metadata{double(into.cols), double(into.rows), info.curved ? 1.0 : 0.0};
    for (Point p : info.validContentPolygon) {
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
