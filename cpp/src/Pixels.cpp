#include <document_scanner/Pixels.hpp>
#include <limits>
#include <stdexcept>
#include <utility>

namespace document_scanner {
size_t bytesPerPixel(PixelFormat format) {
  switch (format) {
    case PixelFormat::gray8: return 1;
    case PixelFormat::rgb8: case PixelFormat::bgr8: return 3;
    case PixelFormat::rgba8: case PixelFormat::bgra8: return 4;
  }
  throw std::invalid_argument("Unknown pixel format");
}
namespace {
size_t add(size_t a, size_t b) {
  if (b > std::numeric_limits<size_t>::max() - a) throw std::invalid_argument("Pixel span overflow");
  return a + b;
}
size_t multiply(size_t a, size_t b) {
  if (a != 0 && b > std::numeric_limits<size_t>::max() / a) throw std::invalid_argument("Pixel span overflow");
  return a * b;
}
}
PixelView::PixelView(std::span<const std::byte> bytes, PixelLayout layout) : bytes_(bytes), layout_(layout) {
  const auto& s = layout.size;
  const auto& c = layout.crop;
  const auto n = bytesPerPixel(layout.format);
  const auto o = static_cast<unsigned>(layout.orientation);
  if (!bytes.data() || !s.width || !s.height || layout.pixelStride < n ||
      !c.width || !c.height || c.left > s.width || c.top > s.height ||
      c.width > s.width - c.left || c.height > s.height - c.top || o < 1 || o > 8)
    throw std::invalid_argument("Invalid pixel layout");
  const auto rowBytes = add(multiply(s.width - 1, layout.pixelStride), n);
  if (layout.rowStride < rowBytes || add(multiply(s.height - 1, layout.rowStride), rowBytes) > bytes.size())
    throw std::invalid_argument("Pixel span does not cover strides");
}
Rgba PixelView::at(uint32_t x, uint32_t y) const {
  if (x >= layout_.size.width || y >= layout_.size.height) throw std::out_of_range("Pixel coordinate");
  const auto* p = bytes_.data() + y * layout_.rowStride + x * layout_.pixelStride;
  const auto value = [p](size_t i) { return std::to_integer<uint8_t>(p[i]); };
  switch (layout_.format) {
    case PixelFormat::gray8: return {value(0), value(0), value(0), 255};
    case PixelFormat::rgb8: return {value(0), value(1), value(2), 255};
    case PixelFormat::bgr8: return {value(2), value(1), value(0), 255};
    case PixelFormat::rgba8: return {value(0), value(1), value(2), value(3)};
    case PixelFormat::bgra8: return {value(2), value(1), value(0), value(3)};
  }
  throw std::invalid_argument("Unknown pixel format");
}
PixelLease::PixelLease(std::shared_ptr<const void> owner, std::span<const std::byte> bytes, PixelLayout layout)
    : owner_(std::move(owner)), view_(bytes, layout) {
  if (!owner_) throw std::invalid_argument("Pixels require an owner lease");
}
OwnedPixels::OwnedPixels(std::vector<std::byte> bytes, PixelLayout layout)
    : bytes_(std::make_shared<const std::vector<std::byte>>(std::move(bytes))), layout_(layout) {
  const PixelView validated(*bytes_, layout_);
}
PixelLease OwnedPixels::lease() const { return PixelLease(bytes_, *bytes_, layout_); }
} // namespace document_scanner
