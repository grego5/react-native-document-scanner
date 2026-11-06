#include "internal/Kernels.hpp"
#include <opencv2/core/utility.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
namespace document_scanner::internal {
namespace { constexpr int kStripRows = 128; }
void remap(const cv::Mat& source, cv::Mat& into, const double* h, const float* leftOffsets, const float* rightOffsets, const float* topOffsets, const float* bottomOffsets) {
  const int width = into.cols;
  const int height = into.rows;
  // Strips run in parallel, each building its own map and remapping into its own rows.
  const int strips = (height + kStripRows - 1) / kStripRows;
  cv::parallel_for_(cv::Range(0, strips), [&](const cv::Range& range) {
    cv::Mat map(kStripRows, width, CV_32FC2);
    for (int strip = range.start; strip < range.end; ++strip) {
      const int first = strip * kStripRows;
      const int rows = std::min(kStripRows, height - first);
      for (int r = 0; r < rows; ++r) {
        const int y = first + r;
        const double v = static_cast<double>(y) / height;
        const double l = leftOffsets[y];
        // px = x - (1 - u) l + u r = -l + x (1 + (l + r) / width), linear along the row.
        const double slope = 1.0 + (l + rightOffsets[y]) / width;
        auto* out = map.ptr<float>(r);
        for (int x = 0; x < width; ++x) {
          const double px = -l + x * slope;
          const double py = y - (1.0 - v) * topOffsets[x] + v * bottomOffsets[x];
          const double d = h[6] * px + h[7] * py + h[8];
          out[2 * x] = static_cast<float>((h[0] * px + h[1] * py + h[2]) / d);
          out[2 * x + 1] = static_cast<float>((h[3] * px + h[4] * py + h[5]) / d);
        }
      }
      cv::Mat rowsOut = into.rowRange(first, first + rows);
      cv::remap(source, rowsOut, map.rowRange(0, rows), cv::noArray(), cv::INTER_LINEAR, cv::BORDER_CONSTANT,
                cv::Scalar(248, 248, 248, 255));
    }
  });
}

}
