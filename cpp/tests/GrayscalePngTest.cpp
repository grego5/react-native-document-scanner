#include <document_scanner/GrayscalePng.hpp>
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

using namespace document_scanner;
namespace {
using Bytes = std::vector<std::byte>;
void check(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
template <class F> void rejects(F f) {
  try {
    f();
  } catch (const std::invalid_argument &) {
    return;
  }
  throw std::runtime_error("Invalid PNG input accepted");
}
uint32_t u32(std::span<const std::byte> bytes) {
  check(bytes.size() >= 4, "Truncated PNG integer");
  uint32_t value = 0;
  for (int i = 0; i < 4; ++i)
    value = value * 256 + std::to_integer<unsigned>(bytes[i]);
  return value;
}
Bytes inflate(const Bytes &compressed, size_t length) {
  Bytes result(length);
  uLongf size = uLongf(length);
  check(uncompress(reinterpret_cast<Bytef *>(result.data()), &size, reinterpret_cast<const Bytef *>(compressed.data()),
                   uLong(compressed.size())) == Z_OK,
        "Invalid zlib stream");
  check(size == length, "Wrong predictor stream length");
  return result;
}
struct Image {
  uint32_t width = 0, height = 0;
  int chunks = 0;
  Bytes compressed, samples;
};
// Independent inverse PNG filters/packing, not the writer's forward predictor loop.
Image decode(const Bytes &png) {
  const std::array<unsigned, 8> signature{137, 80, 78, 71, 13, 10, 26, 10};
  check(png.size() >= 8, "Missing PNG signature");
  for (size_t i = 0; i < 8; ++i)
    check(std::to_integer<unsigned>(png[i]) == signature[i], "Wrong PNG signature");
  Image image;
  bool ended = false;
  size_t position = 8;
  while (position < png.size()) {
    check(png.size() - position >= 12, "Truncated chunk");
    const uint32_t length = u32(std::span(png).subspan(position));
    check(length <= png.size() - position - 12, "Chunk overrun");
    const auto type = std::span(png).subspan(position + 4, 4);
    const std::string name(reinterpret_cast<const char *>(type.data()), 4);
    const auto data = std::span(png).subspan(position + 8, length);
    const auto crc = crc32(0, reinterpret_cast<const Bytef *>(type.data()), length + 4);
    check(uint32_t(crc) == u32(std::span(png).subspan(position + 8 + length)), "Chunk CRC mismatch");
    if (position == 8)
      check(name == "IHDR", "IHDR must be first");
    if (name == "IHDR") {
      check(length == 13 && !image.width, "Wrong IHDR");
      image.width = u32(data);
      image.height = u32(data.subspan(4));
      check(data[8] == std::byte{4} && data[9] == std::byte{0} && data[10] == std::byte{0} &&
                data[11] == std::byte{0} && data[12] == std::byte{0},
            "Expected noninterlaced four-bit grayscale");
    } else if (name == "IDAT") {
      ++image.chunks;
      image.compressed.insert(image.compressed.end(), data.begin(), data.end());
    } else if (name == "IEND") {
      check(length == 0, "IEND has payload");
      ended = true;
    } else
      throw std::runtime_error("Unexpected encoder chunk (palette/alpha not allowed)");
    position += size_t(length) + 12;
    if (ended) {
      check(position == png.size(), "Output after IEND");
      break;
    }
  }
  check(ended && image.chunks > 0 && image.width && image.height, "Incomplete PNG");
  const size_t rowBytes = (size_t(image.width) + 1) / 2;
  const auto filtered = inflate(image.compressed, (rowBytes + 1) * image.height);
  Bytes previous(rowBytes), row(rowBytes);
  image.samples.resize(size_t(image.width) * image.height);
  for (uint32_t y = 0; y < image.height; ++y) {
    const unsigned kind = std::to_integer<unsigned>(filtered[y * (rowBytes + 1)]);
    check(kind <= 4, "Invalid PNG filter");
    for (size_t x = 0; x < rowBytes; ++x) {
      const int left = x ? std::to_integer<int>(row[x - 1]) : 0;
      const int up = std::to_integer<int>(previous[x]), corner = x ? std::to_integer<int>(previous[x - 1]) : 0;
      int prediction = 0;
      switch (kind) {
      case 1:
        prediction = left;
        break;
      case 2:
        prediction = up;
        break;
      case 3:
        prediction = (left + up) / 2;
        break;
      case 4: {
        const int estimate = left + up - corner;
        const std::array<int, 3> errors{std::abs(estimate - left), std::abs(estimate - up),
                                        std::abs(estimate - corner)};
        const std::array<int, 3> choices{left, up, corner};
        prediction = choices[std::min_element(errors.begin(), errors.end()) - errors.begin()];
        break;
      }
      }
      row[x] = std::byte((std::to_integer<unsigned>(filtered[y * (rowBytes + 1) + x + 1]) + prediction) & 255);
    }
    if (image.width % 2)
      check((std::to_integer<unsigned>(row.back()) & 15) == 0, "Odd trailing nibble not zero");
    for (uint32_t x = 0; x < image.width; ++x) {
      const unsigned packed = std::to_integer<unsigned>(row[x / 2]);
      image.samples[size_t(y) * image.width + x] = std::byte((x % 2 ? packed & 15 : packed >> 4) * 17);
    }
    previous = row;
  }
  return image;
}
OwnedPixels pixels(uint32_t w, uint32_t h, const Bytes &input, size_t padding = 0) {
  const size_t stride = w + padding;
  Bytes bytes(stride * h, std::byte{93});
  for (uint32_t y = 0; y < h; ++y)
    std::copy_n(input.begin() + size_t(y) * w, w, bytes.begin() + y * stride);
  return OwnedPixels(std::move(bytes), {PixelFormat::gray8, {w, h}, stride, 1, {0, 0, w, h}, Orientation::upright});
}
Bytes write(const PixelLease &input, bool compressed = false) {
  Bytes result;
  const ByteSink sink = [&](auto span) { result.insert(result.end(), span.begin(), span.end()); };
  if (compressed)
    writeCompressedGray4(input, sink);
  else
    writeGrayscalePng4(input, sink);
  return result;
}
void exactSamples() {
  // Migrated gray-png.tsv cases; dimensions are read so fixture changes cannot silently diverge.
  std::ifstream file(GRAY_PNG_FIXTURE);
  check(bool(file), "Missing gray-png fixture");
  std::string line;
  while (std::getline(file, line)) {
    if (line.empty() || line[0] == '#' || line.rfind("id\t", 0) == 0)
      continue;
    const auto tab = line.find('\t'), last = line.find('\t', tab + 1);
    const uint32_t w = uint32_t(std::stoul(line.substr(tab + 1, last - tab - 1))),
                   h = uint32_t(std::stoul(line.substr(last + 1)));
    Bytes input(size_t(w) * h);
    for (uint32_t y = 0; y < h; ++y)
      for (uint32_t x = 0; x < w; ++x)
        input[size_t(y) * w + x] = std::byte((13 * x + 7 * y) % 256);
    auto owner = pixels(w, h, input, 19);
    const auto lease = owner.lease();
    const auto encoded = write(lease);
    const auto image = decode(encoded);
    check(image.width == w && image.height == h, "Decoded dimensions differ");
    std::array<bool, 16> levels{};
    for (size_t i = 0; i < input.size(); ++i) {
      const unsigned original = std::to_integer<unsigned>(input[i]);
      const unsigned sample = std::to_integer<unsigned>(image.samples[i]);
      check(sample == ((original + 8) / 17) * 17, "Quantized sample differs");
      check(std::abs(int(original) - int(sample)) <= 8, "Quantization exceeds eight levels");
      if (i < w)
        levels[sample / 17] = true;
    }
    if (w == 33)
      check(std::all_of(levels.begin(), levels.end(), [](bool v) { return v; }), "Sixteen levels missing");
    check(write(lease, true) == image.compressed, "PDF predictor stream differs from concatenated IDAT");
  }
  Bytes input(256);
  for (unsigned i = 0; i < 256; ++i)
    input[i] = std::byte(i);
  auto owner = pixels(256, 1, input);
  const auto lease = owner.lease();
  const auto decoded = decode(write(lease));
  for (unsigned i = 0; i < 256; ++i)
    check(std::to_integer<unsigned>(decoded.samples[i]) == ((i + 8) / 17) * 17, "Quantization boundary differs");
}
void manyChunksAndRows() {
  constexpr uint32_t w = 1025, h = 400;
  Bytes input(size_t(w) * h);
  uint64_t state = (uint64_t(7) ^ 0x5deece66dULL) & ((uint64_t(1) << 48) - 1);
  // Java Random(7).nextBytes per row, matching the migrated JVM assertion exactly.
  for (uint32_t y = 0; y < h; ++y)
    for (uint32_t x = 0; x < w;) {
      state = (state * 0x5deece66dULL + 11) & ((uint64_t(1) << 48) - 1);
      const uint32_t bits = uint32_t(state >> 16);
      for (unsigned j = 0; j < 4 && x < w; ++j, ++x)
        input[size_t(y) * w + x] = std::byte((bits >> (8 * j)) & 255);
    }
  auto owner = pixels(w, h, input, 7);
  const auto lease = owner.lease();
  const auto before = Bytes(lease.view().bytes().begin(), lease.view().bytes().end());
  const auto png = write(lease);
  const auto decoded = decode(png);
  check(decoded.chunks > 1, "Multiple IDAT chunks not exercised");
  check(inflate(decoded.compressed, h * (513 + 1)).size() == 400 * (513 + 1), "Compressed length differs");
  for (uint32_t y = 0; y < h; ++y)
    for (uint32_t x : {0u, 127u, 1024u}) {
      const size_t i = size_t(y) * w + x;
      check(std::to_integer<unsigned>(decoded.samples[i]) == ((std::to_integer<unsigned>(input[i]) + 8) / 17) * 17,
            "Random decoded pixel differs");
    }
  check(std::equal(before.begin(), before.end(), lease.view().bytes().begin()), "Source was mutated");
  int next = 0;
  Bytes streamed;
  const GrayRowReader rows = [&](uint32_t y) {
    check(y == uint32_t(next++), "Rows requested out of order");
    auto row = pixels(w, 1, Bytes(input.begin() + size_t(y) * w, input.begin() + size_t(y + 1) * w), 11);
    return row.lease();
  };
  writeGrayscalePng4({w, h}, rows, [&](auto bytes) { streamed.insert(streamed.end(), bytes.begin(), bytes.end()); });
  check(streamed == png, "Row-lease and full-image writers differ");
}
void failures() {
  auto owner = pixels(33, 3, Bytes(99, std::byte{127}));
  const auto lease = owner.lease();
  int calls = 0;
  struct SinkError {};
  try {
    writeGrayscalePng4(lease, [&](auto) {
      ++calls;
      throw SinkError{};
    });
    throw std::runtime_error("Sink failure ignored");
  } catch (const SinkError &) {
    check(calls == 1, "Writer continued after failure");
  }
  // Fail in compressed output after at least one row; exact exception and no retry.
  int rows = 0, outputs = 0;
  const GrayRowReader reader = [&](uint32_t) {
    ++rows;
    return pixels(33, 1, Bytes(33, std::byte{127})).lease();
  };
  try {
    writeCompressedGray4({33, 1000}, reader, [&](auto) {
      ++outputs;
      throw SinkError{};
    });
    throw std::runtime_error("Compressed sink failure ignored");
  } catch (const SinkError &) {
    check(rows > 0 && outputs == 1, "Compressed sink retried");
  }
  int sinkCalls = 0;
  const ByteSink sink = [&](auto) { ++sinkCalls; };
  rejects([&] { writeGrayscalePng4({0, 1}, reader, sink); });
  rejects([&] { writeGrayscalePng4({1, 0}, reader, sink); });
  rejects([&] { writeGrayscalePng4({uint32_t(std::numeric_limits<int32_t>::max()) + 1, 1}, reader, sink); });
  check(sinkCalls == 0, "Invalid dimensions emitted output");
  rejects([&] { writeGrayscalePng4({1, 1}, GrayRowReader{}, sink); });
  rejects([&] { writeGrayscalePng4(lease, ByteSink{}); });
  rejects([&] { writeGrayscalePng4({34, 1}, reader, sink); });
  struct RowError {};
  try {
    writeGrayscalePng4({33, 3}, [](uint32_t) -> PixelLease { throw RowError{}; }, sink);
    throw std::runtime_error("Row exception ignored");
  } catch (const RowError &) {
  }
  // A fresh write after failures must be complete; failed output is caller-owned.
  check(decode(write(lease)).samples.size() == 99, "Writer did not recover after failure");
}
} // namespace
int main() {
  try {
    exactSamples();
    manyChunksAndRows();
    failures();
    std::cout << "Grayscale PNG contracts passed\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
