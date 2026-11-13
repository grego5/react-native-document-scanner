#include <document_scanner/PdfWriter.hpp>
#include <zlib.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>

using namespace document_scanner;
namespace p = document_scanner::pdf;
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
  throw std::runtime_error("Invalid PDF input accepted");
}
p::ByteSource source(Bytes data) {
  auto owner = std::make_shared<Bytes>(std::move(data));
  return {owner->size(), [owner](uint64_t offset, std::span<std::byte> out) {
            check(offset <= owner->size() && out.size() <= owner->size() - offset, "Read beyond encoded source");
            std::copy_n(owner->begin() + size_t(offset), out.size(), out.begin());
          }};
}
Bytes load(const char *name) {
  std::ifstream file(std::string(FIXTURE_ROOT) + name, std::ios::binary);
  check(bool(file), "Missing PDF fixture");
  const std::string text((std::istreambuf_iterator<char>(file)), {});
  return Bytes(reinterpret_cast<const std::byte *>(text.data()),
               reinterpret_cast<const std::byte *>(text.data() + text.size()));
}
Bytes jpeg(unsigned w = 400, unsigned h = 300, unsigned channels = 3, unsigned marker = 192) {
  Bytes b;
  auto byte = [&](unsigned x) { b.push_back(std::byte(x & 255)); };
  auto word = [&](unsigned x) {
    byte(x >> 8);
    byte(x);
  };
  byte(255);
  byte(216);
  byte(255);
  byte(marker);
  word(8 + 3 * channels);
  byte(8);
  word(h);
  word(w);
  byte(channels);
  for (unsigned i = 0; i < channels; ++i) {
    byte(i + 1);
    byte(17);
    byte(0);
  }
  byte(255);
  byte(217);
  return b;
}
void put32(Bytes &b, uint32_t x) {
  for (int s = 24; s >= 0; s -= 8)
    b.push_back(std::byte((x >> s) & 255));
}
Bytes chunk(const char *name, const Bytes &data) {
  Bytes b;
  put32(b, uint32_t(data.size()));
  for (int i = 0; i < 4; ++i)
    b.push_back(std::byte(name[i]));
  b.insert(b.end(), data.begin(), data.end());
  put32(b, uint32_t(crc32(0, reinterpret_cast<const Bytef *>(b.data() + 4), uInt(b.size() - 4))));
  return b;
}
Bytes gray4(bool transparent = false) {
  auto b = load("gray-png/transparency-source.png");
  if (transparent) {
    const auto extra = chunk("tRNS", Bytes{std::byte{0}, std::byte{7}});
    b.insert(b.begin() + 33, extra.begin(), extra.end());
  }
  return b;
}
Bytes compressed(const p::ByteSource &s, const p::ImageInfo &info) {
  Bytes out;
  for (auto r : info.ranges) {
    const size_t start = out.size();
    out.resize(start + size_t(r.length));
    s.read(r.offset, std::span(out).subspan(start, size_t(r.length)));
  }
  return out;
}
void probesAndMigratedReaderAssertions() {
  for (unsigned channels : {1u, 3u})
    for (unsigned marker : {192u, 193u, 194u}) {
      const auto s = source(jpeg(400, 300, channels, marker));
      const auto info = p::probe(s);
      check(bool(info), "Eligible JPEG rejected");
      check(info->kind == (channels == 1 ? p::ImageKind::jpegGray : p::ImageKind::jpegRgb) && info->size.width == 400 &&
                info->size.height == 300,
            "Wrong JPEG metadata");
      check(info->compressedLength == s.length && info->ranges.size() == 1, "JPEG payload altered");
    }
  check(!p::probe(source(jpeg(40, 30, 4))), "CMYK passthrough accepted");
  check(!p::probe(source(jpeg(40, 30, 3, 195))), "Unsupported JPEG frame accepted");
  auto broken = jpeg();
  broken.resize(7);
  check(!p::probe(source(broken)), "Truncated JPEG accepted");
  auto invalid = jpeg();
  invalid[4] = std::byte{0};
  invalid[5] = std::byte{1};
  check(!p::probe(source(invalid)), "Short JPEG segment accepted");
  const auto s = source(load("gray-png/multi-idat.png"));
  const auto info = p::probe(s);
  check(bool(info), "Multi-IDAT PNG rejected");
  check(info->size.width == 1025 && info->size.height == 400 && info->ranges.size() > 1,
        "Multi-IDAT dimensions/count differ");
  const auto payload = compressed(s, *info);
  check(payload.size() == info->compressedLength, "Compressed-length accounting differs");
  Bytes filtered(400 * (513 + 1));
  uLongf count = uLongf(filtered.size());
  check(uncompress(reinterpret_cast<Bytef *>(filtered.data()), &count, reinterpret_cast<const Bytef *>(payload.data()),
                   uLong(payload.size())) == Z_OK &&
            count == filtered.size(),
        "IDAT spans are not one complete zlib stream");
  uint64_t random = (7ULL ^ 0x5deece66dULL) & ((1ULL << 48) - 1);
  for (size_t y = 0; y < 400; ++y) {
    std::vector<unsigned> row(1025);
    for (size_t x = 0; x < row.size();) {
      random = (random * 0x5deece66dULL + 11) & ((1ULL << 48) - 1);
      const uint32_t bits = uint32_t(random >> 16);
      for (unsigned j = 0; j < 4 && x < row.size(); ++j, ++x)
        row[x] = (bits >> (8 * j)) & 255;
    }
    check(filtered[y * 514] == std::byte{0}, "Independent parser fixture filter changed");
    for (size_t x : {0u, 127u, 1024u}) {
      unsigned b = std::to_integer<unsigned>(filtered[y * 514 + 1 + x / 2]);
      unsigned sample = x % 2 ? b & 15 : b >> 4;
      check(sample == (row[x] + 8) / 17, "Migrated random PNG sample differs");
    }
  }
  const auto transparent = p::probe(source(gray4(true)));
  check(transparent && transparent->transparentSample == 7, "PNG transparency lost");
  for (unsigned bits : {1u, 8u}) {
    auto b = gray4();
    b[24] = std::byte(bits);
    check(!p::probe(source(b)), "Non-four-bit PNG accepted");
  }
  auto truncated = gray4();
  truncated.resize(truncated.size() - 12);
  check(!p::probe(source(truncated)), "Missing IEND accepted");
  auto malformed = gray4();
  malformed[16] = std::byte{128};
  check(!p::probe(source(malformed)), "PNG signed dimension overflow accepted");
  check(!p::probe(source(Bytes(7))), "Short source accepted");
}
void layout() {
  std::ifstream file(std::string(FIXTURE_ROOT) + "pdf-placement.tsv");
  check(bool(file), "Missing placement fixture");
  std::string line;
  while (std::getline(file, line)) {
    if (line.empty() || line[0] == '#' || line.rfind("id\t", 0) == 0)
      continue;
    std::istringstream fields(line);
    std::string id, orientation, fit, matrix;
    std::getline(fields, id, '\t');
    std::getline(fields, orientation, '\t');
    std::getline(fields, fit, '\t');
    std::getline(fields, matrix);
    p::Options o;
    o.pageWidth = 600;
    o.pageHeight = 800;
    o.fit = fit == "FILL" ? p::Fit::fill : fit == "COVER" ? p::Fit::cover : p::Fit::contain;
    const auto placed = p::place({400, 300}, Orientation(std::stoi(orientation)), p::validate(o));
    std::istringstream values(matrix);
    for (size_t i = 0; i < 6; ++i) {
      std::string value;
      std::getline(values, value, ',');
      check(std::abs(placed.matrix[i] - std::stod(value)) <= .0001, "Placement golden differs");
    }
  }
  p::Options o;
  o.pageWidth = 600;
  o.pageAspectRatio = .75;
  auto a = p::validate(o);
  check(a.page->height == 800 && a.targetDpi == 200, "Width/aspect defaults differ");
  o.pageWidth.reset();
  o.pageHeight = 800;
  check(p::validate(o).page->width == 600, "Height/aspect differs");
  const std::array<std::array<double, 6>, 8> exifMatrices{{{400, 0, 0, 300, 0, 0},
                                                           {-400, 0, 0, 300, 400, 0},
                                                           {-400, 0, 0, -300, 400, 300},
                                                           {400, 0, 0, -300, 0, 300},
                                                           {0, -400, -300, 0, 300, 400},
                                                           {0, -400, 300, 0, 0, 400},
                                                           {0, 400, 300, 0, 0, 0},
                                                           {0, 400, -300, 0, 300, 0}}};
  for (int exif = 1; exif <= 8; ++exif) {
    const auto q = p::place({400, 300}, Orientation(exif), p::validate({}));
    check(q.page.width == (exif >= 5 ? 300 : 400) && q.page.height == (exif >= 5 ? 400 : 300),
          "Natural EXIF dimensions differ");
    check(q.matrix == exifMatrices[size_t(exif - 1)], "EXIF placement matrix differs");
  }
  o = {};
  o.pageWidth = 72;
  o.pageHeight = 72;
  o.targetDpi = 72;
  o.fit = p::Fit::contain;
  const auto reduced = p::place({3000, 2000}, Orientation::upright, p::validate(o));
  check(reduced.encodedSize.width == 72 && reduced.encodedSize.height == 48, "DPI contain differs");
  o.fit = p::Fit::fill;
  auto fill = p::place({3000, 2000}, Orientation::rotate90, p::validate(o));
  check(fill.encodedSize.width == 72 && fill.encodedSize.height == 72, "Rotated fill differs");
  o.targetDpi = 1e300;
  auto cap = p::place({40, 30}, Orientation::upright, p::validate(o));
  check(cap.encodedSize.width == 40 && cap.encodedSize.height == 30, "DPI enlarged input");
  for (auto mutate : {0, 1, 2, 3, 4, 5, 6, 7}) {
    p::Options bad;
    switch (mutate) {
    case 0:
      bad.pageWidth = 0;
      bad.pageHeight = 200;
      break;
    case 1:
      bad.pageWidth = 200;
      break;
    case 2:
      bad.pageAspectRatio = 1;
      break;
    case 3:
      bad.targetDpi = 72;
      break;
    case 4:
      bad.pageWidth = 200;
      bad.pageHeight = 200;
      bad.pageAspectRatio = 1;
      break;
    case 5:
      bad.jpegQuality = std::numeric_limits<double>::quiet_NaN();
      break;
    case 6:
      bad.pageWidth = 200;
      bad.pageHeight = 200;
      bad.targetDpi = 0;
      break;
    case 7:
      bad.pageWidth = 14401;
      bad.pageHeight = 100;
      break;
    }
    rejects([&] { p::validate(bad); });
  }
  o = {};
  o.jpegQuality = 0;
  check(p::validate(o).jpegQuality == 0, "Explicit zero quality lost");
  o.jpegQuality = .725;
  check(p::validate(o).jpegQuality == 73, "Quality rounding differs");
}
struct Page {
  Bytes bytes;
  Size fallback;
  Orientation exif = Orientation::upright;
};
struct Adapter : p::ImageAdapter {
  std::vector<Page> pages;
  size_t next = 0, current = 0;
  int encodes = 0, orientationCalls = 0;
  bool copied = false, badLength = false, failPayload = false;
  std::optional<Size> requested;
  bool requestedGray = false;
  int quality = -1;
  std::weak_ptr<Bytes> encodedOwner;
  p::ByteSource open(size_t index) override {
    check(index == next++, "Page order changed");
    check(encodedOwner.expired(), "Previous encoded page retained across open");
    current = index;
    return source(pages.at(index).bytes);
  }
  Size dimensions() override { return pages[current].fallback; }
  Orientation orientation() override {
    ++orientationCalls;
    return pages[current].exif;
  }
  p::EncodedImage encode(Size size, bool gray, int q) override {
    ++encodes;
    requested.emplace(size);
    requestedGray = gray;
    quality = q;
    auto owner = std::make_shared<Bytes>(Bytes{std::byte{31}, std::byte{32}, std::byte{33}});
    encodedOwner = owner;
    return {size, gray ? p::ImageKind::gray4 : p::ImageKind::jpegRgb, badLength ? 4u : owner->size(),
            [this, owner](const ByteSink &sink) {
              copied = true;
              if (failPayload)
                throw std::runtime_error("payload failure");
              sink(*owner);
            }};
  }
};
std::string write(Adapter &a, p::Options o = {}) {
  std::string out;
  p::write(a.pages.size(), o, a,
           [&](auto bytes) { out.append(reinterpret_cast<const char *>(bytes.data()), bytes.size()); });
  return out;
}
std::string binary(const Bytes &b) { return {reinterpret_cast<const char *>(b.data()), b.size()}; }
void serializationAndReuse() {
  Adapter a;
  a.pages.push_back({jpeg(), {400, 300}});
  a.pages.push_back({gray4(true), {3, 1}});
  const auto text = write(a);
  check(a.encodes == 0 && text.find(binary(a.pages[0].bytes)) != std::string::npos,
        "Eligible JPEG not copied unchanged");
  const auto png = source(a.pages[1].bytes);
  const auto info = p::probe(png);
  check(text.find(binary(compressed(png, *info))) != std::string::npos && text.find("/Mask [7 7]") != std::string::npos,
        "PNG stream/mask not preserved");
  check(text.find("/MediaBox [0 0 400 300]") < text.find("/MediaBox [0 0 3 1]"), "Page order/natural sizes differ");
  const auto marker = text.rfind("startxref\n");
  check(marker != std::string::npos, "Missing startxref");
  const auto xref = std::stoull(text.substr(marker + 10));
  check(text.substr(xref, 5) == "xref\n", "Bad xref location");
  std::istringstream table(text.substr(xref));
  std::string line;
  std::getline(table, line);
  std::getline(table, line);
  check(line == "0 9", "Object count differs");
  std::getline(table, line);
  check(line == "0000000000 65535 f ", "Missing free xref entry");
  for (int i = 1; i <= 8; ++i) {
    std::getline(table, line);
    const auto offset = std::stoull(line);
    check(text.compare(offset, std::to_string(i).size() + 6, std::to_string(i) + " 0 obj") == 0,
          "Object offset incorrect");
  }
  Adapter forced;
  forced.pages.push_back({jpeg(), {400, 300}, Orientation::rotate90});
  p::Options o;
  o.jpegQuality = 0;
  o.autoRotateExif = false;
  const auto encoded = write(forced, o);
  check(forced.encodes == 1 && forced.quality == 0 && forced.orientationCalls == 0,
        "Quality forcing or EXIF opt-out differs");
  Adapter four;
  four.pages.push_back({gray4(true), {3, 1}});
  o.jpegQuality = .1;
  write(four, o);
  check(four.encodes == 0, "JPEG quality re-encoded four-bit PNG");
  Adapter reduced;
  reduced.pages.push_back({jpeg(3000, 2000), {3000, 2000}, Orientation::rotate90});
  o = {};
  o.pageWidth = 72;
  o.pageHeight = 72;
  o.targetDpi = 72;
  o.fit = p::Fit::contain;
  write(reduced, o);
  check(reduced.requested->width == 72 && reduced.requested->height == 48,
        "Stored target dimensions incorrect after rotation");
  Adapter gray;
  gray.pages.push_back({gray4(true), {3, 1}});
  o.pageWidth = 1;
  o.pageHeight = 1;
  o.targetDpi = 72;
  write(gray, o);
  check(gray.encodes == 1 && gray.requestedGray && gray.requested->width == 1, "Four-bit resize changed encoding");
  Adapter fallback;
  fallback.pages.push_back({Bytes(8), {20, 10}});
  fallback.pages.push_back({Bytes(8), {30, 15}});
  write(fallback);
  check(fallback.encodes == 2 && fallback.encodedOwner.expired(), "Pages did not stream/release");
}
void failures() {
  Adapter a;
  a.pages.push_back({jpeg(), {400, 300}});
  int calls = 0;
  struct SinkError {};
  try {
    p::write(1, {}, a, [&](auto) {
      ++calls;
      throw SinkError{};
    });
    throw std::runtime_error("Sink failure ignored");
  } catch (const SinkError &) {
    check(calls == 1 && a.next == 0, "Writer continued after sink failure");
  }
  p::Options bad;
  bad.targetDpi = 72;
  calls = 0;
  rejects([&] { p::write(1, bad, a, [&](auto) { ++calls; }); });
  check(calls == 0, "Invalid options emitted output");
  rejects([&] { p::write(0, {}, a, [](auto) {}); });
  Adapter shortPayload;
  shortPayload.pages.push_back({Bytes(8), {10, 10}});
  shortPayload.badLength = true;
  try {
    write(shortPayload);
    throw std::logic_error("Short payload accepted");
  } catch (const std::runtime_error &) {
    check(shortPayload.copied, "Payload not exercised");
  }
  Adapter broken;
  broken.pages.push_back({Bytes(8), {10, 10}});
  broken.pages.push_back({jpeg(), {400, 300}});
  broken.failPayload = true;
  try {
    write(broken);
    throw std::logic_error("Payload failure ignored");
  } catch (const std::runtime_error &) {
    check(broken.next == 1 && broken.encodedOwner.expired(), "Writer continued/retained failed page");
  }
}
} // namespace
int main() {
  try {
    probesAndMigratedReaderAssertions();
    layout();
    serializationAndReuse();
    failures();
    std::cout << "PDF contracts passed\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
