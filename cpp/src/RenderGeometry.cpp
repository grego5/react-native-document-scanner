#include <document_scanner/RenderGeometry.hpp>
#include <document_scanner/Geometry.hpp>
#include "internal/PageEdges.hpp"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace document_scanner {
GeometricRenderResult renderGeometry(const PixelLease &lease, const Quad &normalized, double scale,
                                     std::optional<double> paper, bool follow) {
  namespace g = geometry;
  const auto &layout = lease.view().layout();
  const auto size = layout.size;
  if (layout.format != PixelFormat::rgba8 || layout.pixelStride != 4 || layout.orientation != Orientation::upright ||
      layout.crop.left != 0 || layout.crop.top != 0 || layout.crop.width != size.width ||
      layout.crop.height != size.height || size.width > 4096 || size.height > 4096)
    throw std::invalid_argument(
        "Geometric rendering requires a full upright RGBA8 source no larger than 4096 pixels per side.");
  g::validateRenderQuad(normalized);
  const auto q = g::toImage(normalized, size);
  const auto target = g::targetCanvas(q, scale, paper);
  std::vector<cv::Point2f> from, to;
  for (Point p : q)
    from.emplace_back(float(p.x), float(p.y));
  for (Point p : target.destination)
    to.emplace_back(float(p.x), float(p.y));
  const cv::Mat homography = cv::getPerspectiveTransform(from, to);
  cv::Mat inverse;
  if (!(cv::invert(homography, inverse) > 0) || !std::isfinite(cv::determinant(homography)))
    throw std::runtime_error("Document geometry produced an unstable perspective transform.");
  // PixelView validates layout; obtain the leased first pixel without exposing OpenCV in the public API.
  // Source storage remains read-only throughout every OpenCV call below.
  const auto sourceBytes = lease.view().bytes();
  cv::Mat source(int(size.height), int(size.width), CV_8UC4, const_cast<std::byte *>(sourceBytes.data()),
                 layout.rowStride);
  const auto output = target.size;
  const cv::Size outputSize(int(output.width), int(output.height));
  std::vector<std::byte> pixels(size_t(output.width) * output.height * 4);
  cv::Mat warped(outputSize, CV_8UC4, pixels.data());
  const bool inside = std::all_of(
      q.begin(), q.end(), [&](Point p) { return p.x >= 0 && p.x <= size.width && p.y >= 0 && p.y <= size.height; });
  internal::PageEdges edges;
  if (follow && inside)
    edges = internal::findPageEdges(source, homography, int(output.width), int(output.height));
  const bool curved = std::any_of(edges.begin(), edges.end(), [](const auto &edge) { return edge.has_value(); });
  if (curved)
    internal::remapPageEdges(source, warped, inverse, edges);
  else
    cv::warpPerspective(source, warped, homography, outputSize, cv::INTER_LINEAR, cv::BORDER_CONSTANT,
                        cv::Scalar(248, 248, 248, 255));
  cv::Mat sourceMask(source.rows, source.cols, CV_8UC1, cv::Scalar(255)), warpedMask;
  cv::warpPerspective(sourceMask, warpedMask, homography, outputSize, cv::INTER_NEAREST, cv::BORDER_CONSTANT,
                      cv::Scalar(0));
  if (cv::countNonZero(warpedMask) == 0)
    throw std::runtime_error("The selected document contains no captured image pixels.");
  g::Matrix h;
  std::copy_n(homography.ptr<double>(), 9, h.begin());
  auto polygon = g::normalizedOutputPolygon(g::clipImageToQuad(q, size), h, output);
  if (polygon.size() < 3)
    throw std::runtime_error("The selected document does not overlap the captured image.");
  return {OwnedPixels(std::move(pixels), {PixelFormat::rgba8,
                                          output,
                                          size_t(output.width) * 4,
                                          4,
                                          {0, 0, output.width, output.height},
                                          Orientation::upright}),
          std::move(polygon), curved};
}
} // namespace document_scanner
