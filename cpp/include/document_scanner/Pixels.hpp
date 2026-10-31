#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

namespace document_scanner {
// TIFF/EXIF orientation of encoded pixels. Crop is in encoded pixel coordinates.
enum class Orientation : uint8_t { upright = 1, mirrorHorizontal, rotate180, mirrorVertical,
                                   transpose, rotate90, transverse, rotate270 };
enum class PixelFormat { gray8, rgb8, rgba8, bgra8 };
struct Size final { const uint32_t width, height; };
struct Crop final { const uint32_t left, top, width, height; };
struct Point final { const double x, y; };
// Public quads are normalized upright TL/TR/BR/BL; no transform is implied here.
using Quad = std::array<Point, 4>;
struct PixelLayout final {
  const PixelFormat format;
  const Size size;
  const size_t rowStride, pixelStride; // bytes, positive; padding allowed
  const Crop crop;
  const Orientation orientation;
};
struct Rgba final { const uint8_t red, green, blue, alpha; };

// Validated read-only encoded view. Never retain it beyond its PixelLease.
// No orientation/crop/resizing algorithm is introduced at this boundary.
class PixelView final {
public:
  PixelView(std::span<const std::byte> bytes, PixelLayout layout);
  const PixelLayout& layout() const noexcept { return layout_; }
  // Read-only storage for native library adapters; valid only within the owning lease.
  std::span<const std::byte> bytes() const noexcept { return bytes_; }
  Rgba at(uint32_t x, uint32_t y) const;
private:
  std::span<const std::byte> bytes_;
  PixelLayout layout_;
};

// The token must retain stable storage AND any platform lock until its last copy dies.
// Adapters construct the lease on their declared serial owner; never transfer a
// thread-affine unlock token across owners. Algorithms borrow const PixelLease&.
class PixelLease final {
public:
  PixelLease(std::shared_ptr<const void> owner, std::span<const std::byte> bytes, PixelLayout layout);
  const PixelView& view() const & noexcept { return view_; }
  const PixelView& view() const && = delete;
private:
  std::shared_ptr<const void> owner_;
  PixelView view_;
};

// Immutable publication: storage/allocation is private, leases survive this handle.
class OwnedPixels final {
public:
  OwnedPixels(std::vector<std::byte> bytes, PixelLayout layout);
  PixelLease lease() const;
private:
  std::shared_ptr<const std::vector<std::byte>> bytes_;
  PixelLayout layout_;
};
} // namespace document_scanner
