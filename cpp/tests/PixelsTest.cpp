#include <document_scanner/Pixels.hpp>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>
using namespace document_scanner;
namespace {
void require(bool condition) { if (!condition) throw std::runtime_error("Pixel contract failed"); }
void rejects(const std::function<void()>& operation) {
  try { operation(); } catch (const std::invalid_argument&) { return; }
  throw std::runtime_error("Invalid span accepted");
}
PixelLayout layout(PixelFormat format = PixelFormat::rgba8, size_t row = 16, size_t pixel = 5) {
  return {format, {2, 2}, row, pixel, {0, 0, 2, 2}, Orientation::rotate90};
}
void stridesAndChannels() {
  std::vector<std::byte> bytes(25, std::byte{99});
  for (size_t offset : {0u, 5u, 16u, 21u}) {
    bytes[offset] = std::byte{10}; bytes[offset+1] = std::byte{20};
    bytes[offset+2] = std::byte{30}; bytes[offset+3] = std::byte{40};
  }
  for (auto format : {PixelFormat::rgba8, PixelFormat::bgra8}) {
    PixelView view(bytes, layout(format));
    const auto p = view.at(1, 1);
    require(p.red == (format == PixelFormat::rgba8 ? 10 : 30) && p.green == 20 &&
            p.blue == (format == PixelFormat::rgba8 ? 30 : 10) && p.alpha == 40);
    require(view.layout().orientation == Orientation::rotate90);
  }
  require(PixelView(bytes, layout(PixelFormat::rgb8)).at(1,1).alpha == 255);
  require(PixelView(bytes, layout(PixelFormat::gray8)).at(1,1).blue == 10);
  try { PixelView(bytes, layout()).at(2,0); throw std::runtime_error("Bounds accepted"); }
  catch (const std::out_of_range&) {}
}
void invalidSpans() {
  std::vector<std::byte> bytes(25);
  rejects([&] { PixelView v(std::span(bytes).first(24), layout()); });
  rejects([&] { PixelView v(bytes, layout(PixelFormat::rgba8, 8)); });
  rejects([&] { PixelView v(bytes, layout(PixelFormat::rgba8, 16, 3)); });
  rejects([&] { PixelView v(bytes, layout(PixelFormat::rgba8, std::numeric_limits<size_t>::max())); });
  rejects([&] { PixelView v(bytes, {PixelFormat::rgba8, {2,2},16,5,{1,0,2,2},Orientation::upright}); });
  rejects([&] { PixelView v(bytes, {PixelFormat::rgba8, {0,2},16,5,{0,0,2,2},Orientation::upright}); });
  rejects([&] { PixelView v(bytes, {PixelFormat::rgba8, {2,2},16,5,{0,0,2,2},static_cast<Orientation>(9)}); });
  rejects([&] { PixelLease v({}, bytes, layout()); });
}
void ownerLifetime() {
  int releases = 0;
  {
    auto storage = std::shared_ptr<std::vector<std::byte>>(new std::vector<std::byte>(25, std::byte{42}),
      [&](auto* value) { ++releases; delete value; });
    PixelLease lease(storage, *storage, layout());
    storage.reset();
    { auto copy = lease; require(copy.view().at(1,1).red == 42); }
    require(releases == 0 && lease.view().at(0,0).red == 42);
  }
  require(releases == 1);
  auto lease = [] { OwnedPixels owned(std::vector<std::byte>(25, std::byte{7}), layout()); return owned.lease(); }();
  require(lease.view().at(1,1).red == 7);
}
}
int main() {
  try { stridesAndChannels(); invalidSpans(); ownerLifetime(); }
  catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
  std::cout << "Pixel stride, channel, span and lease tests passed\n";
}
