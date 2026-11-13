#pragma once
#include <document_scanner/GrayscalePng.hpp>
#include <array>
#include <optional>

namespace document_scanner::pdf {
enum class Fit { none, fill, contain, cover };
struct Options final {
  std::optional<double> pageWidth, pageHeight, pageAspectRatio, targetDpi, jpegQuality;
  Fit fit = Fit::none;
  bool autoRotateExif = true;
};
struct PageSize final {
  double width, height;
};
struct Layout final {
  std::optional<PageSize> page;
  Fit fit;
  bool autoRotateExif;
  std::optional<double> targetDpi;
  std::optional<int> jpegQuality;
};
Layout validate(const Options &options);
struct Placement final {
  PageSize page;
  Size encodedSize;
  std::array<double, 6> matrix;
};
Placement place(Size stored, Orientation orientation, const Layout &layout);

// Read the entire requested range synchronously or throw. The adapter retains
// encoded storage/file access through the page, until the next open(). No pixels
// cross this interface; pixel decode/encode adapters must retain their own leases.
struct ByteSource final {
  uint64_t length;
  std::function<void(uint64_t, std::span<std::byte>)> read;
};
struct Range final {
  uint64_t offset, length;
};
enum class ImageKind { jpegGray, jpegRgb, gray4 };
struct ImageInfo final {
  Size size;
  ImageKind kind;
  std::optional<unsigned> transparentSample;
  std::vector<Range> ranges;
  uint64_t compressedLength;
};
// Eligible JPEG or noninterlaced four-bit grayscale PNG; no pixel decode.
// Passthrough does not verify PNG CRCs, matching the previous reader. Structural
// bounds are checked here; other image formats use the platform decoder.
std::optional<ImageInfo> probe(const ByteSource &source);
struct EncodedImage final {
  Size size;
  ImageKind kind;
  uint64_t length;
  std::function<void(const ByteSink &)> copy;
};
// Serial/synchronous adapter, one open source/page at a time. The core consumes
// the complete page payload before open(next). Final source cleanup, destination
// replacement, file access and stream flush/close remain with the caller.
class ImageAdapter {
public:
  virtual ~ImageAdapter() = default;
  virtual ByteSource open(size_t index) = 0;
  virtual Size dimensions() = 0; // Fallback for formats not eligible for passthrough.
  virtual Orientation orientation() = 0;
  virtual EncodedImage encode(Size storedTarget, bool gray4, int jpegQuality) = 0;
};
void write(size_t pageCount, const Options &options, ImageAdapter &images, const ByteSink &sink);
} // namespace document_scanner::pdf
