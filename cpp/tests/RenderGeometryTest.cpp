#include <document_scanner/RenderGeometry.hpp>
#include <algorithm>
#include <cmath>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string_view>

using namespace document_scanner;
namespace {
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
  throw std::runtime_error("Invalid geometric input accepted");
}
Quad rect(double l, double t, double r, double b) { return {Point{l, t}, Point{r, t}, Point{r, b}, Point{l, b}}; }
const auto page = rect(.125, .125, .875, .875);
bool samePolygon(const std::vector<Point> &a, const std::vector<Point> &b) {
  if (a.size() != b.size())
    return false;
  for (size_t i = 0; i < a.size(); ++i)
    if (a[i].x != b[i].x || a[i].y != b[i].y)
      return false;
  return true;
}
bool samePixels(const OwnedPixels &a, const OwnedPixels &b) {
  const auto x = a.lease(), y = b.lease();
  const auto ax = x.view().bytes(), by = y.view().bytes();
  return ax.size() == by.size() && std::equal(ax.begin(), ax.end(), by.begin());
}
OwnedPixels fixture(std::string_view name) {
  const auto bend = [](double a, double b, double t) { return t * (1 - t) * (a + b * t); };
  const auto shade = [&](double sx, double sy) {
    double x = sx - 80, y = sy - 60;
    if (name == "bent")
      for (int i = 0; i < 30; ++i) {
        const double u = x / 480, v = y / 360;
        const double px = x - (1 - u) * bend(-12, -20, v) + u * bend(16, 0, v);
        const double py = y - (1 - v) * bend(-40, 0, u) + v * bend(-28, -16, u);
        x += sx - 80 - px;
        y += sy - 60 - py;
      }
    if (x < 0 || x > 480 || y < 0 || y > 360)
      return 64;
    if (name == "ambiguous" && x >= 130 && x <= 350 && y >= 9 && y <= 15)
      return 0;
    bool line = false;
    for (int i = 1; i <= 7; ++i)
      line |= std::abs(x - i * 60) <= 1.5;
    for (int i = 1; i <= 5; ++i)
      line |= std::abs(y - i * 60) <= 1.5;
    return x >= 20 && x <= 460 && y >= 20 && y <= 340 && line ? 0 : 255;
  };
  // Padding must not change geometric resampling.
  constexpr size_t stride = 640 * 4 + 20;
  std::vector<std::byte> bytes(stride * 480, std::byte{0xcd});
  for (int y = 0; y < 480; ++y)
    for (int x = 0; x < 640; ++x) {
      int sum = 0;
      for (double dx : {-.25, .25})
        for (double dy : {-.25, .25})
          sum += shade(x + dx, y + dy);
      auto *p = bytes.data() + y * stride + x * 4;
      p[0] = p[1] = p[2] = std::byte(sum / 4);
      p[3] = std::byte{255};
    }
  return OwnedPixels(std::move(bytes),
                     {PixelFormat::rgba8, {640, 480}, stride, 4, {0, 0, 640, 480}, Orientation::upright});
}
void identityAndOwnership() {
  const Size size{127, 95};
  const size_t stride = size.width * 4 + 17;
  std::vector<std::byte> bytes(stride * size.height);
  for (uint32_t y = 0; y < size.height; ++y)
    for (uint32_t x = 0; x < size.width; ++x) {
      auto *p = bytes.data() + y * stride + x * 4;
      p[0] = std::byte((x * 17 + y * 31) % 256);
      p[1] = std::byte((x * 53 + y * 7) % 256);
      p[2] = std::byte((x * 11 + y * 97) % 256);
      p[3] = std::byte{255};
    }
  const auto expected = bytes;
  const auto result = [&] {
    OwnedPixels source(std::move(bytes),
                       {PixelFormat::rgba8, size, stride, 4, {0, 0, size.width, size.height}, Orientation::upright});
    auto lease = source.lease();
    auto output = renderGeometry(lease, rect(0, 0, 1, 1), 1, std::nullopt, false);
    check(std::equal(expected.begin(), expected.end(), lease.view().bytes().begin()), "Source changed");
    return output;
  }(); // source gone; returned pixels must remain independent
  auto lease = result.pixels.lease();
  const auto &layout = lease.view().layout();
  check(layout.size.width == size.width && layout.size.height == size.height, "Identity size changed");
  for (uint32_t y = 0; y < size.height; ++y)
    for (uint32_t x = 0; x < size.width; ++x) {
      auto p = lease.view().at(x, y);
      const auto *e = expected.data() + y * stride + x * 4;
      check(p.red == std::to_integer<uint8_t>(e[0]) && p.green == std::to_integer<uint8_t>(e[1]) &&
                p.blue == std::to_integer<uint8_t>(e[2]),
            "Identity pixels changed");
    }
}
void grid(const GeometricRenderResult &result) {
  auto lease = result.pixels.lease();
  const auto &view = lease.view();
  const auto centre = [&](const std::function<int(int)> &level) {
    double weights = 0, sum = 0;
    for (int i = -6; i <= 6; ++i) {
      double weight = std::max(0, 200 - level(i));
      weights += weight;
      sum += i * weight;
    }
    check(weights > 0 && std::abs(sum / weights) <= 1.5, "Bent grid did not straighten");
  };
  for (int x = 60; x < 480; x += 60)
    for (int y = 30; y < 360; y += 60)
      centre([&](int i) { return view.at(x + i, y).red; });
  for (int y = 60; y < 360; y += 60)
    for (int x = 30; x < 480; x += 60)
      centre([&](int i) { return view.at(x, y + i).red; });
  for (int x = 3; x < 477; ++x)
    check(view.at(x, 3).red > 200 && view.at(x, 356).red > 200, "Background at bent border");
  for (int y = 3; y < 357; ++y)
    check(view.at(3, y).red > 200 && view.at(476, y).red > 200, "Background at bent border");
}
void renderFixtures() {
  for (auto name : {"flat", "bent", "ambiguous"}) {
    auto source = fixture(name);
    auto lease = source.lease();
    const auto original = std::vector(lease.view().bytes().begin(), lease.view().bytes().end());
    const auto curved = renderGeometry(lease, page, 1);
    const auto straight = renderGeometry(lease, page, 1, std::nullopt, false);
    const auto output = curved.pixels.lease();
    check(output.view().layout().size.width == 480 && output.view().layout().size.height == 360, "Page size changed");
    check(samePolygon(curved.validContentPolygon, straight.validContentPolygon), "Coverage followed bend");
    check(std::equal(original.begin(), original.end(), lease.view().bytes().begin()), "Render changed source");
    if (std::string_view(name) == "flat")
      check(!curved.curved && samePixels(curved.pixels, straight.pixels), "Flat edge bent");
    if (std::string_view(name) == "bent") {
      check(curved.curved && !samePixels(curved.pixels, straight.pixels), "Bend missing");
      grid(curved);
      const auto scaled = renderGeometry(lease, page, 2, 720);
      const auto scaledPixels = scaled.pixels.lease();
      check(scaledPixels.view().layout().size.width == 720 && scaledPixels.view().layout().size.height == 540,
            "Sampled sizing changed");
    }
  }
  auto source = fixture("bent");
  const Quad outside{Point{-.02, .125}, Point{.875, .125}, Point{.875, .875}, Point{.125, .875}};
  const auto a = renderGeometry(source.lease(), outside, 1),
             b = renderGeometry(source.lease(), outside, 1, std::nullopt, false);
  check(!a.curved && samePixels(a.pixels, b.pixels), "Outside corner did not disable bending");
  check(a.validContentPolygon.size() == 5 && samePolygon(a.validContentPolygon, b.validContentPolygon),
        "Clipped coverage changed");
}
void failuresAndUnsupported() {
  std::vector<std::byte> bytes(128 * 96 * 4, std::byte{120});
  for (size_t i = 3; i < bytes.size(); i += 4)
    bytes[i] = std::byte{255};
  // A short middle-only competing edge lacks enough stations and cannot reach both corners.
  for (int y = 10; y <= 15; ++y)
    for (int x = 40; x <= 88; ++x)
      for (size_t c = 0; c < 3; ++c)
        bytes[(y * 128 + x) * 4 + c] = std::byte{0};
  OwnedPixels source(std::move(bytes),
                     {PixelFormat::rgba8, {128, 96}, 128 * 4, 4, {0, 0, 128, 96}, Orientation::upright});
  const auto result = renderGeometry(source.lease(), rect(0, 0, 1, 1), 1);
  check(!result.curved, "Unsupported middle edge bent");
  auto invalid = rect(0, 0, 1, 1);
  rejects([&] { renderGeometry(source.lease(), invalid, 0); });
  rejects([&] { renderGeometry(source.lease(), invalid, 1, 4097); });
  rejects([&] { renderGeometry(source.lease(), rect(-.9, 0, 1, 1), 1); });
  bool noPixels = false;
  try {
    renderGeometry(source.lease(), rect(1.1, .1, 1.5, .5), 1);
  } catch (const std::runtime_error &) {
    noPixels = true;
  }
  check(noPixels, "Non-overlapping warp succeeded");
  auto owner = std::make_shared<std::vector<std::byte>>(4097 * 4, std::byte{255});
  PixelLease oversized(owner, *owner,
                       {PixelFormat::rgba8, {4097, 1}, 4097 * 4, 4, {0, 0, 4097, 1}, Orientation::upright});
  rejects([&] { renderGeometry(oversized, invalid, 1); });
  PixelLease rotated(owner, *owner,
                     {PixelFormat::rgba8, {4097, 1}, 4097 * 4, 4, {0, 0, 4097, 1}, Orientation::rotate90});
  rejects([&] { renderGeometry(rotated, invalid, 1); });
}
// Caller targets: padded rows receive the same curved and straight pixels as owned output, padding
// stays untouched, and a rejected render never allocates.
void callerTargets() {
  auto source = fixture("bent");
  const auto lease = source.lease();
  for (bool follow : {true, false}) {
    const auto expected = renderGeometry(lease, page, 1, std::nullopt, follow);
    const auto reference = expected.pixels.lease();
    const auto &layout = reference.view().layout();
    std::vector<std::byte> bytes;
    size_t stride = 0;
    const auto info = renderGeometry(lease, page, 1, std::nullopt, follow, [&](PixelFormat format, Size size) {
      check(bytes.empty() && format == PixelFormat::rgba8, "Render allocated twice or in the wrong format");
      stride = size.width * 4 + 12;
      bytes.assign(stride * size.height, std::byte{7});
      return PixelTarget{bytes, stride};
    });
    check(info.curved == expected.curved && samePolygon(info.validContentPolygon, expected.validContentPolygon),
          "Caller render metadata differs");
    const auto packed = reference.view().bytes();
    for (uint32_t y = 0; y < layout.size.height; ++y) {
      const auto row = layout.size.width * 4;
      check(std::equal(packed.begin() + y * layout.rowStride, packed.begin() + y * layout.rowStride + row,
                       bytes.begin() + y * stride),
            "Caller render pixels differ");
      check(bytes[y * stride + row] == std::byte{7}, "Render wrote row padding");
    }
  }
  bool allocated = false, rejected = false;
  try {
    renderGeometry(lease, rect(1.1, .1, 1.5, .5), 1, std::nullopt, true, [&](PixelFormat, Size) {
      allocated = true;
      return PixelTarget{};
    });
  } catch (const std::runtime_error &) {
    rejected = true;
  }
  check(rejected && !allocated, "Rejected render allocated caller storage");
}
} // namespace
int main() {
  try {
    identityAndOwnership();
    renderFixtures();
    failuresAndUnsupported();
    callerTargets();
    std::cout << "Geometric render contracts passed\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
