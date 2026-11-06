#include <document_scanner/GrayscalePng.hpp>
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <string_view>

namespace document_scanner {
namespace {
constexpr std::array<std::byte, 8> signature{std::byte{137}, std::byte{80}, std::byte{78}, std::byte{71},
                                             std::byte{13},  std::byte{10}, std::byte{26}, std::byte{10}};
void dimensions(Size size) {
  if (!size.width || !size.height || size.width > uint32_t(std::numeric_limits<int32_t>::max()) ||
      size.height > uint32_t(std::numeric_limits<int32_t>::max()))
    throw std::invalid_argument("PNG dimensions must be positive 31-bit integers.");
}
void grayLayout(const PixelLayout &l) {
  if (l.format != PixelFormat::gray8 || l.pixelStride != 1 || l.orientation != Orientation::upright || l.crop.left ||
      l.crop.top || l.crop.width != l.size.width || l.crop.height != l.size.height)
    throw std::invalid_argument("PNG input must be full upright gray8 pixels (row padding allowed).");
}
void put32(std::span<std::byte> into, uint32_t value) {
  for (int i = 0; i < 4; ++i)
    into[i] = std::byte((value >> (24 - 8 * i)) & 255);
}
void chunk(const ByteSink &sink, std::string_view type, std::span<const std::byte> data) {
  std::array<std::byte, 8> header{};
  put32(header, uint32_t(data.size()));
  for (size_t i = 0; i < 4; ++i)
    header[i + 4] = std::byte(type[i]);
  uLong crc = crc32(0, reinterpret_cast<const Bytef *>(header.data() + 4), 4);
  // zlib treats a null buffer as CRC initialization, even when the length is zero.
  if (!data.empty())
    crc = crc32(crc, reinterpret_cast<const Bytef *>(data.data()), uInt(data.size()));
  std::array<std::byte, 4> footer{};
  put32(footer, uint32_t(crc));
  sink(header);
  if (!data.empty())
    sink(data);
  sink(footer);
}
class Deflater final {
public:
  Deflater() {
    if (deflateInit(&stream, 4) != Z_OK)
      throw std::runtime_error("PNG zlib initialization failed.");
  }
  ~Deflater() { deflateEnd(&stream); }
  Deflater(const Deflater &) = delete;
  Deflater &operator=(const Deflater &) = delete;
  z_stream stream{};
};
using ReadRow = std::function<void(uint32_t, std::span<std::byte>)>;
void encode(Size size, const ReadRow &readRow, const ByteSink &sink, bool png) {
  dimensions(size);
  if (!readRow || !sink)
    throw std::invalid_argument("PNG requires a row reader and output sink.");
  const size_t rowBytes = size.width / 2 + size.width % 2;
  std::vector<std::byte> gray(size.width), packed(rowBytes), previous(rowBytes);
  std::array<std::vector<std::byte>, 5> candidates;
  for (size_t i = 0; i < candidates.size(); ++i) {
    candidates[i].resize(rowBytes + 1);
    candidates[i][0] = std::byte(i);
  }
  std::array<std::byte, 64 * 1024> compressed{};
  Deflater deflater;
  auto &z = deflater.stream;
  if (png) {
    sink(signature);
    std::array<std::byte, 13> header{};
    put32(header, size.width);
    put32(std::span(header).subspan(4), size.height);
    header[8] = std::byte{4};
    chunk(sink, "IHDR", header);
  }
  const auto emit = [&] {
    const size_t count = compressed.size() - z.avail_out;
    if (!count)
      return;
    const auto data = std::span<const std::byte>(compressed).first(count);
    if (png)
      chunk(sink, "IDAT", data);
    else
      sink(data);
  };
  for (uint32_t y = 0; y < size.height; ++y) {
    readRow(y, gray);
    std::fill(packed.begin(), packed.end(), std::byte{0});
    for (size_t x = 0; x < size.width; ++x) {
      const unsigned level = (std::to_integer<unsigned>(gray[x]) + 8) / 17;
      packed[x / 2] |= std::byte(level << (x % 2 == 0 ? 4 : 0));
    }
    std::array<uint64_t, 5> scores{};
    for (size_t x = 0; x < rowBytes; ++x) {
      const int value = std::to_integer<int>(packed[x]), left = x ? std::to_integer<int>(packed[x - 1]) : 0;
      const int above = std::to_integer<int>(previous[x]), upperLeft = x ? std::to_integer<int>(previous[x - 1]) : 0;
      const int p = left + above - upperLeft;
      const int a = std::abs(p - left), b = std::abs(p - above), c = std::abs(p - upperLeft);
      const int paeth = a <= b && a <= c ? left : b <= c ? above : upperLeft;
      const std::array predictors{0, left, above, (left + above) / 2, paeth};
      for (size_t kind = 0; kind < candidates.size(); ++kind) {
        const unsigned residual = unsigned(value - predictors[kind]) & 255;
        candidates[kind][x + 1] = std::byte(residual);
        scores[kind] += unsigned(std::abs(residual < 128 ? int(residual) : int(residual) - 256));
      }
    }
    // First minimum wins, including ties, as Kotlin minBy did.
    const size_t best = size_t(std::min_element(scores.begin(), scores.end()) - scores.begin());
    z.next_in = reinterpret_cast<Bytef *>(candidates[best].data());
    z.avail_in = uInt(candidates[best].size());
    while (z.avail_in) {
      z.next_out = reinterpret_cast<Bytef *>(compressed.data());
      z.avail_out = uInt(compressed.size());
      if (deflate(&z, Z_NO_FLUSH) != Z_OK)
        throw std::runtime_error("PNG zlib compression failed.");
      emit();
    }
    std::copy(packed.begin(), packed.end(), previous.begin());
  }
  int status;
  do {
    z.next_out = reinterpret_cast<Bytef *>(compressed.data());
    z.avail_out = uInt(compressed.size());
    status = deflate(&z, Z_FINISH);
    if (status != Z_OK && status != Z_STREAM_END)
      throw std::runtime_error("PNG zlib finalization failed.");
    emit();
  } while (status != Z_STREAM_END);
  if (png)
    chunk(sink, "IEND", {});
}
void fromPixels(const PixelLease &source, const ByteSink &sink, bool png) {
  const auto &view = source.view();
  const auto &l = view.layout();
  grayLayout(l);
  encode(
      l.size,
      [&](uint32_t y, std::span<std::byte> row) {
        std::copy_n(view.bytes().begin() + size_t(y) * l.rowStride, row.size(), row.begin());
      },
      sink, png);
}
void fromRows(Size size, const GrayRowReader &readRow, const ByteSink &sink, bool png) {
  if (!readRow)
    throw std::invalid_argument("PNG requires a row reader.");
  encode(
      size,
      [&](uint32_t y, std::span<std::byte> row) {
        const auto lease = readRow(y);
        const auto &view = lease.view();
        const auto &l = view.layout();
        grayLayout(l);
        if (l.size.width != size.width || l.size.height != 1)
          throw std::invalid_argument("PNG row size differs from image width.");
        std::copy_n(view.bytes().begin(), row.size(), row.begin());
      },
      sink, png);
}
} // namespace
void writeGrayscalePng4(const PixelLease &source, const ByteSink &sink) { fromPixels(source, sink, true); }
void writeGrayscalePng4(Size size, const GrayRowReader &row, const ByteSink &sink) { fromRows(size, row, sink, true); }
void writeCompressedGray4(const PixelLease &source, const ByteSink &sink) { fromPixels(source, sink, false); }
void writeCompressedGray4(Size size, const GrayRowReader &row, const ByteSink &sink) {
  fromRows(size, row, sink, false);
}
} // namespace document_scanner
