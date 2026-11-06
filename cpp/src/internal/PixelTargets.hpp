#pragma once
#include <document_scanner/Pixels.hpp>
#include <opencv2/core.hpp>
#include <optional>
#include <stdexcept>

namespace document_scanner::internal {
// Caller storage wrapped as a Mat header. OpenCV writes into it only while size and type match,
// so every write path ends with kept() to reject a silent reallocation.
struct Target {
  cv::Mat mat;
  void kept(const cv::Mat &written) const {
    if (written.data != mat.data)
      throw std::runtime_error("Pixel output left the caller target.");
  }
};
inline Target target(const PixelAllocator &allocate, PixelFormat format, const cv::Size &size) {
  const Size extent{uint32_t(size.width), uint32_t(size.height)};
  const auto into = allocate(format, extent);
  const auto n = bytesPerPixel(format);
  const PixelView validated(into.bytes, {format, extent, into.rowStride, n, {0, 0, extent.width, extent.height},
                                         Orientation::upright});
  return {cv::Mat(size, CV_8UC(int(n)), into.bytes.data(), into.rowStride)};
}
// Packed storage behind an allocator, for the overloads that publish OwnedPixels.
class OwnedTarget final {
public:
  PixelAllocator allocator() {
    return [this](PixelFormat format, Size size) {
      const size_t stride = size_t(size.width) * bytesPerPixel(format);
      bytes_.resize(stride * size.height);
      layout_.emplace(PixelLayout{format, size, stride, bytesPerPixel(format), {0, 0, size.width, size.height},
                                  Orientation::upright});
      return PixelTarget{bytes_, stride};
    };
  }
  OwnedPixels publish() && { return OwnedPixels(std::move(bytes_), layout_.value()); }
private:
  std::vector<std::byte> bytes_;
  std::optional<PixelLayout> layout_;
};
} // namespace document_scanner::internal
