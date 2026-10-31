#pragma once
#include <opencv2/core.hpp>
#include <array>
#include <optional>
namespace document_scanner::internal {
struct EdgeCurve final {
  std::array<double, 3> coefficients;
  double at(double t) const;
};
// Top, right, bottom, left. Missing/unsupported sides remain straight.
using PageEdges = std::array<std::optional<EdgeCurve>, 4>;
PageEdges findPageEdges(const cv::Mat &rgba, const cv::Mat &homography, int width, int height);
void remapPageEdges(const cv::Mat &source, cv::Mat &into, const cv::Mat &inverse, const PageEdges &edges);
} // namespace document_scanner::internal
