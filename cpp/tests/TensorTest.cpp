#include <document_scanner/Tensor.hpp>
#include <bit>
#include <cmath>
#include <fstream>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>
using namespace document_scanner;
namespace {
void check(bool value) {
  if (!value)
    throw std::runtime_error("Tensor contract failed");
}
template <class F> void rejects(F f) {
  try {
    f();
  } catch (const std::invalid_argument &) {
    return;
  }
  throw std::runtime_error("Malformed tensor accepted");
}
void metadata() {
  const std::array<int, 4> in{1, 192, 320, 3};
  const std::array<int, 2> out{1, 9};
  auto s = validateCornerTensors(1, 1, true, true, in, out);
  check(s.width == 320 && s.height == 192 && cornerInputBytes(s) == 320 * 192 * 12);
  rejects([&] { validateCornerTensors(2, 1, true, true, in, out); });
  rejects([&] { validateCornerTensors(1, 2, true, true, in, out); });
  rejects([&] { validateCornerTensors(1, 1, false, true, in, out); });
  rejects([&] { validateCornerTensors(1, 1, true, false, in, out); });
  for (auto bad : {std::vector<int>{},
                   {1, 3, 192, 320},
                   {2, 192, 320, 3},
                   {1, 0, 320, 3},
                   {1, -1, 320, 3},
                   {1, 2147483647, 2147483647, 3}})
    rejects([&] { validateCornerTensors(1, 1, true, true, bad, out); });
  for (auto bad : {std::vector<int>{}, {9}, {1, 8}, {2, 9}})
    rejects([&] { validateCornerTensors(1, 1, true, true, in, bad); });
  rejects([] { TensorPreprocessor p({0, 224}); });
}
void predictions() {
  std::ifstream file(QUAD_FIXTURE);
  check(file.good());
  std::string line;
  std::getline(file, line);
  std::getline(file, line);
  while (std::getline(file, line)) {
    if (!line.empty() && line.back() == '\r')
      line.pop_back();
    std::istringstream row(line);
    std::string id, raw, expected;
    std::getline(row, id, '\t');
    std::getline(row, raw, '\t');
    std::getline(row, expected);
    std::istringstream numbers(raw);
    std::vector<float> values;
    std::string n;
    while (std::getline(numbers, n, ','))
      values.push_back(std::stof(n));
    if (expected == "error") {
      rejects([&] { decodeCornerOutput(values); });
      continue;
    }
    auto result = decodeCornerOutput(values);
    check(bool(result) == (expected == "accept"));
    if (result) {
      for (size_t i = 0; i < 4; ++i)
        check(result->quad[i].x == double(values[2 * i]) && result->quad[i].y == double(values[2 * i + 1]));
      check(result->score == double(values[8]));
    }
  }
  std::array<float, 9> good{.1f, .2f, .8f, .1f, .9f, .8f, .2f, .9f, .7f};
  rejects([&] { decodeCornerOutput(std::span(good).first(8)); });
  for (size_t i = 0; i < 9; ++i)
    for (float bad : {INFINITY, -INFINITY, NAN}) {
      auto values = good;
      values[i] = bad;
      rejects([&] { decodeCornerOutput(values); });
    }
  for (float score : {0.f, -10.f, 10.f}) {
    auto v = good;
    v[8] = score;
    check(decodeCornerOutput(v).has_value());
  }
  // Prediction acceptance deliberately has no crop editor area threshold.
  check(decodeCornerOutput(std::array<float, 9>{.1f, .1f, .102f, .1f, .102f, .102f, .1f, .102f, 0.f}).has_value());
}
OwnedPixels pattern(uint32_t w, uint32_t h, PixelFormat format = PixelFormat::rgba8,
                    Orientation o = Orientation::upright, Crop crop = {0, 0, 7, 5}, size_t padding = 0) {
  const size_t stride = w * 6 + padding;
  std::vector<std::byte> bytes(stride * h, std::byte{0xcd});
  for (uint32_t y = 0; y < h; ++y)
    for (uint32_t x = 0; x < w; ++x) {
      auto *p = bytes.data() + y * stride + x * 6;
      uint8_t r = (x * 17 + y * 31) % 256, g = (x * 53 + y * 7) % 256, b = (x * 11 + y * 97) % 256;
      p[0] = std::byte(format == PixelFormat::bgra8 ? b : r);
      p[1] = std::byte(g);
      p[2] = std::byte(format == PixelFormat::bgra8 ? r : b);
      p[3] = std::byte{255};
    }
  return OwnedPixels(std::move(bytes), {format, {w, h}, stride, 6, crop, o});
}
void resamplingGoldens() {
  // Independent Pillow RGB goldens formerly asserted by the JVM suite.
  for (auto c : {std::array<uint32_t, 5>{7, 5, 3, 2, 1805764405u},
                 {3, 2, 7, 5, 1837232507u},
                 {15, 9, 1, 1, uint32_t(-8422243)},
                 {224, 224, 224, 224, 1761361665u},
                 {641, 479, 224, 224, 952188093u}}) {
    auto pixels = pattern(c[0], c[1], PixelFormat::rgba8, Orientation::upright, {0, 0, c[0], c[1]}, 11);
    TensorPreprocessor p({c[2], c[3]});
    std::vector<float> output(p.inputBytes() / 4);
    p.prepare(pixels.lease(), output);
    constexpr float means[]{.485f, .456f, .406f}, deviations[]{.229f, .224f, .225f};
    uint32_t hash = 1;
    for (size_t i = 0; i < output.size(); i += 3) {
      uint32_t rgb = 0xff000000u;
      for (size_t k = 0; k < 3; ++k) {
        int v = int(std::lround((output[i + k] * deviations[k] + means[k]) * 255.f));
        check(v >= 0 && v <= 255);
        const float expected = (float(v) / 255.f - means[k]) / deviations[k];
        check(std::bit_cast<uint32_t>(output[i + k]) == std::bit_cast<uint32_t>(expected));
        rgb |= uint32_t(v) << (16 - 8 * k);
      }
      hash = 31 * hash + rgb;
    }
    check(hash == c[4]);
  }
}
void channelNormalization() {
  TensorPreprocessor p({3, 2});
  std::vector<float> result(p.inputBytes() / 4);
  constexpr float means[]{.485f, .456f, .406f}, deviations[]{.229f, .224f, .225f};
  for (unsigned value = 0; value < 256; ++value)
    for (auto format : {PixelFormat::gray8, PixelFormat::rgb8, PixelFormat::rgba8, PixelFormat::bgra8}) {
      std::vector<std::byte> bytes(4, std::byte(value));
      bytes[3] = std::byte{255};
      OwnedPixels source(std::move(bytes), {format, {1, 1}, 4, 4, {0, 0, 1, 1}, Orientation::upright});
      p.prepare(source.lease(), result);
      for (size_t i = 0; i < result.size(); ++i) {
        float expected = (float(value) / 255.f - means[i % 3]) / deviations[i % 3];
        check(std::bit_cast<uint32_t>(result[i]) == std::bit_cast<uint32_t>(expected));
      }
    }
}
void orientationsAndStrides() {
  const Crop crop{1, 1, 5, 3};
  TensorPreprocessor p({9, 6});
  std::vector<float> actual(p.inputBytes() / 4), expected(actual.size());
  // Independent per-orientation source index tables, preserving padded rows/pixel strides and crop origin.
  constexpr int indices[8][15] = {
      {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}, {4, 3, 2, 1, 0, 9, 8, 7, 6, 5, 14, 13, 12, 11, 10},
      {14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}, {10, 11, 12, 13, 14, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4},
      {0, 5, 10, 1, 6, 11, 2, 7, 12, 3, 8, 13, 4, 9, 14}, {10, 5, 0, 11, 6, 1, 12, 7, 2, 13, 8, 3, 14, 9, 4},
      {14, 9, 4, 13, 8, 3, 12, 7, 2, 11, 6, 1, 10, 5, 0}, {4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11, 0, 5, 10}};
  for (unsigned o = 1; o <= 8; ++o)
    for (auto format : {PixelFormat::rgba8, PixelFormat::bgra8}) {
      auto source = pattern(7, 5, format, Orientation(o), crop, 13);
      uint32_t w = o >= 5 ? 3 : 5, h = o >= 5 ? 5 : 3;
      std::vector<std::byte> upright(w * h * 4);
      auto lease = source.lease();
      for (size_t i = 0; i < 15; ++i) {
        auto n = indices[o - 1][i];
        auto pixel = lease.view().at(1 + n % 5, 1 + n / 5);
        upright[i * 4] = std::byte(pixel.red);
        upright[i * 4 + 1] = std::byte(pixel.green);
        upright[i * 4 + 2] = std::byte(pixel.blue);
        upright[i * 4 + 3] = std::byte{255};
      }
      OwnedPixels reference(std::move(upright),
                            {PixelFormat::rgba8, {w, h}, w * 4, 4, {0, 0, w, h}, Orientation::upright});
      p.prepare(source.lease(), actual);
      p.prepare(reference.lease(), expected);
      check(actual == expected);
    }
  auto source = pattern(7, 5);
  rejects([&] { p.prepare(source.lease(), std::span(actual).first(1)); });
  std::vector<std::byte> transparent(4);
  OwnedPixels alpha(std::move(transparent), {PixelFormat::rgba8, {1, 1}, 4, 4, {0, 0, 1, 1}, Orientation::upright});
  rejects([&] { p.prepare(alpha.lease(), actual); });
  p.prepare(source.lease(), actual); // workspace remains usable after failure
}
} // namespace
int main() {
  try {
    metadata();
    predictions();
    resamplingGoldens();
    channelNormalization();
    orientationsAndStrides();
    std::cout << "Tensor contracts passed\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
