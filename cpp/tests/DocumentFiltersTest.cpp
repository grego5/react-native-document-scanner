#include <document_scanner/DocumentFilters.hpp>
#include <algorithm>
#include <iostream>
#include <stdexcept>

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
  throw std::runtime_error("Invalid filter boundary accepted");
}
OwnedPixels page() {
  constexpr int w = 800, h = 600, stride = w * 4 + 17;
  std::vector<std::byte> bytes(stride * h, std::byte{73});
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      const double light = 255. - 135. * x / (w - 1);
      const bool ink = y >= 100 && y < 200 && x >= 60 && x < 740 && (x - 60) % 12 < 4;
      const bool color = y >= 300 && y < 500 && x >= 100 && x < 300;
      const bool gray = y >= 300 && y < 500 && x >= 520 && x < 720;
      for (int c = 0; c < 3; ++c) {
        const double factor = ink ? .25 : color ? (c == 1 ? 220. : 160.) / 255. : gray ? .6 : 1.;
        bytes[y * stride + x * 4 + c] = std::byte(int(light * factor));
      }
      bytes[y * stride + x * 4 + 3] = std::byte(128 + y % 128);
    }
  return OwnedPixels(std::move(bytes), {PixelFormat::rgba8, {w, h}, stride, 4, {0, 0, w, h}, Orientation::upright});
}
bool equal(const PixelLease &a, const PixelLease &b) {
  const auto x = a.view().bytes(), y = b.view().bytes();
  return x.size() == y.size() && std::equal(x.begin(), x.end(), y.begin());
}
void filtersAndOwnership() {
  auto source = page();
  const auto lease = source.lease();
  const auto original = std::vector<std::byte>(lease.view().bytes().begin(), lease.view().bytes().end());
  for (auto filter :
       {DocumentFilter::none, DocumentFilter::enhance, DocumentFilter::grayscale, DocumentFilter::blackWhite}) {
    for (bool shadow : {false, true}) {
      const auto result = filterDocument(lease, filter, shadow);
      const auto pixels = result.lease();
      const auto &view = pixels.view();
      check(view.layout().size.width == 800 && view.layout().size.height == 600, "Filter resized source");
      check(view.layout().format == ((filter == DocumentFilter::none || filter == DocumentFilter::enhance)
                                         ? PixelFormat::rgb8
                                         : PixelFormat::gray8),
            "Wrong output format");
      if (filter == DocumentFilter::none && !shadow) {
        for (uint32_t y = 0; y < 600; y += 17)
          for (uint32_t x = 0; x < 800; x += 13) {
            const auto a = lease.view().at(x, y), b = view.at(x, y);
            check(a.red == b.red && a.green == b.green && a.blue == b.blue, "None altered color");
          }
      }
      if (filter != DocumentFilter::none) {
        check(view.at(40, 50).red >= 240 && view.at(760, 50).red >= 240, "Paper not evened");
        check(view.at(61, 150).red < 100, "Ink lost");
        if (!shadow)
          check(view.at(600, 400).red > 20 && view.at(600, 400).red < 230, "Broad gray lost");
        if (filter == DocumentFilter::enhance) {
          const auto c = view.at(200, 400);
          check(c.green > c.red + 15 && c.green > c.blue + 15, "Pastel color lost");
        }
        if (filter == DocumentFilter::blackWhite) {
          bool intermediate = false;
          for (auto b : view.bytes()) {
            const auto v = std::to_integer<unsigned>(b);
            if (v > 0 && v < 255)
              intermediate = true;
          }
          check(intermediate, "BlackWhite became binary");
        }
      }
      if (shadow) {
        const auto corrected = removeDocumentShadows(lease);
        const auto correctedLease = corrected.lease();
        const auto separate = filterDocument(correctedLease, filter);
        const auto separateLease = separate.lease();
        int worst = 0;
        const auto a = pixels.view().bytes(), b = separateLease.view().bytes();
        check(a.size() == b.size(), "Combined stage size differs");
        for (size_t i = 0; i < a.size(); ++i)
          worst =
              std::max(worst, std::abs(int(std::to_integer<unsigned>(a[i])) - int(std::to_integer<unsigned>(b[i]))));
        std::cout << "Combined/separate filter " << int(filter) << " max difference " << worst << '\n';
        check(worst <= 1, "Shadow/filter ordering differs by more than one intensity level");
      }
    }
  }
  check(std::equal(original.begin(), original.end(), lease.view().bytes().begin()), "Borrowed source modified");
  const auto corrected = removeDocumentShadows(lease);
  const auto correctedLease = corrected.lease();
  for (uint32_t y = 0; y < 600; ++y)
    for (uint32_t x = 0; x < 800; x += 19)
      check(correctedLease.view().at(x, y).alpha == lease.view().at(x, y).alpha, "Shadow changed alpha");
  // Publication survives both source handle and result destruction.
  const auto surviving = [&] {
    auto temporary = page();
    auto l = temporary.lease();
    return filterDocument(l, DocumentFilter::none).lease();
  }();
  check(surviving.view().at(0, 0).red == 255, "Result ownership escaped");
}
void sharpnessIsRepeatableAcrossOwnedOutputs() {
  auto source = page();
  const auto input = source.lease();
  for (auto filter : {DocumentFilter::enhance, DocumentFilter::grayscale, DocumentFilter::blackWhite}) {
    const auto baseline = filterDocument(input, filter, true);
    const auto expected = baseline.lease();
    // Retain the previous output while allocating the next. The old depth-changing
    // alias read replacement storage, making sharpness depend on allocation reuse.
    std::vector<OwnedPixels> previous;
    previous.push_back(filterDocument(input, filter, true));
    for (int run = 0; run < 6; ++run) {
      auto result = filterDocument(input, filter, true);
      const auto actual = result.lease();
      const auto a = expected.view().bytes(), b = actual.view().bytes();
      check(a.size() == b.size(), "Repeated filter dimensions changed");
      for (size_t i = 0; i < a.size(); ++i)
        check(std::abs(int(std::to_integer<unsigned>(a[i])) - int(std::to_integer<unsigned>(b[i]))) <= 1,
              "Sharpness depends on intermediate allocation reuse");
      previous.push_back(std::move(result));
    }
  }
}
void boundaries() {
  auto source = page();
  const auto lease = source.lease();
  rejects([&] { filterDocument(lease, static_cast<DocumentFilter>(99)); });
  auto owner = std::make_shared<std::vector<std::byte>>(16 * 16 * 4, std::byte{180});
  const PixelLease rotated(owner, *owner, {PixelFormat::rgba8, {16, 16}, 64, 4, {0, 0, 16, 16}, Orientation::rotate90});
  rejects([&] { filterDocument(rotated, DocumentFilter::none); });
  const PixelLease cropped(owner, *owner, {PixelFormat::rgba8, {16, 16}, 64, 4, {1, 0, 15, 16}, Orientation::upright});
  rejects([&] { removeDocumentShadows(cropped); });
  for (int side : {1, 96}) {
    std::vector<std::byte> bytes(size_t(side) * side * 4);
    for (size_t i = 0; i < bytes.size(); i += 4) {
      bytes[i] = std::byte{170};
      bytes[i + 1] = std::byte{180};
      bytes[i + 2] = std::byte{190};
      bytes[i + 3] = std::byte{81};
    }
    OwnedPixels uniform(std::move(bytes), {PixelFormat::rgba8,
                                           {uint32_t(side), uint32_t(side)},
                                           size_t(side) * 4,
                                           4,
                                           {0, 0, uint32_t(side), uint32_t(side)},
                                           Orientation::upright});
    const auto input = uniform.lease();
    const auto corrected = removeDocumentShadows(input);
    const auto output = corrected.lease();
    check(equal(input, output), "Empty page shadow changed");
  }
}
} // namespace
int main() {
  try {
    filtersAndOwnership();
    sharpnessIsRepeatableAcrossOwnedOutputs();
    boundaries();
    std::cout << "Document filter contracts passed\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
