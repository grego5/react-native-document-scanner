#include <document_scanner/PdfWriter.hpp>
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <sstream>
#include <stdexcept>

namespace document_scanner::pdf {
namespace {
void require(bool value, const char *message) {
  if (!value)
    throw std::invalid_argument(message);
}
double points(double value, const char *message) {
  require(std::isfinite(value) && value >= 1 && value <= 14400, message);
  return value;
}
void validSize(Size s) {
  require(s.width && s.height && s.width <= INT32_MAX && s.height <= INT32_MAX,
          "Image dimensions must be positive 31-bit integers.");
}
uint32_t rounded(double value) { return uint32_t(std::min(double(INT32_MAX), std::max(1., std::floor(value + .5)))); }
std::string number(double value) {
  std::ostringstream text;
  text.imbue(std::locale::classic());
  text << std::fixed << std::setprecision(4) << value;
  auto out = text.str();
  while (out.back() == '0')
    out.pop_back();
  if (out.back() == '.')
    out.pop_back();
  return out;
}
uint32_t u32(std::span<const std::byte> bytes) {
  uint32_t out = 0;
  for (int i = 0; i < 4; ++i)
    out = out * 256 + std::to_integer<unsigned>(bytes[i]);
  return out;
}
struct Reader {
  const ByteSource &source;
  bool range(uint64_t offset, uint64_t size) const { return offset <= source.length && size <= source.length - offset; }
  std::vector<std::byte> read(uint64_t offset, size_t size) const {
    if (!range(offset, size))
      throw std::runtime_error("Encoded image ended early.");
    std::vector<std::byte> bytes(size);
    source.read(offset, bytes);
    return bytes;
  }
};
void copy(const ByteSource &source, const std::vector<Range> &ranges, const ByteSink &sink) {
  std::array<std::byte, 64 * 1024> buffer;
  for (auto range : ranges)
    while (range.length) {
      const size_t count = size_t(std::min<uint64_t>(buffer.size(), range.length));
      auto span = std::span(buffer).first(count);
      source.read(range.offset, span);
      sink(span);
      range.offset += count;
      range.length -= count;
    }
}
std::string dictionary(ImageKind kind, uint32_t width, std::optional<unsigned> transparent = {}) {
  if (kind != ImageKind::gray4)
    return std::string("/ColorSpace ") + (kind == ImageKind::jpegGray ? "/DeviceGray" : "/DeviceRGB") +
           " /BitsPerComponent 8 /Filter /DCTDecode";
  return "/ColorSpace /DeviceGray /BitsPerComponent 4 /Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 1 "
         "/BitsPerComponent 4 /Columns " +
         std::to_string(width) + " >>" +
         (transparent ? " /Mask [" + std::to_string(*transparent) + " " + std::to_string(*transparent) + "]" : "");
}
class Output {
public:
  explicit Output(const ByteSink &sink) : sink_(sink) { require(bool(sink), "PDF requires an output sink."); }
  uint64_t count = 0;
  void bytes(std::span<const std::byte> data) {
    if (data.size() > std::numeric_limits<uint64_t>::max() - count)
      throw std::runtime_error("PDF output size overflow.");
    sink_(data);
    count += data.size();
  }
  void ascii(const std::string &text) { bytes({reinterpret_cast<const std::byte *>(text.data()), text.size()}); }

private:
  const ByteSink &sink_;
};
} // namespace
Layout validate(const Options &o) {
  require(o.fit == Fit::none || o.fit == Fit::fill || o.fit == Fit::contain || o.fit == Fit::cover,
          "Unknown PDF image fit.");
  std::optional<PageSize> page;
  if (!o.pageWidth && !o.pageHeight) {
    require(!o.pageAspectRatio, "pageAspectRatio requires exactly one of pageWidth or pageHeight.");
    require(!o.targetDpi, "targetDpi requires explicit PDF page dimensions.");
  } else if (o.pageWidth && o.pageHeight) {
    require(!o.pageAspectRatio, "pageAspectRatio cannot be combined with both pageWidth and pageHeight.");
    page = PageSize{points(*o.pageWidth, "pageWidth must be between 1.0 and 14400.0 PDF points."),
                    points(*o.pageHeight, "pageHeight must be between 1.0 and 14400.0 PDF points.")};
  } else {
    require(
        o.pageAspectRatio && std::isfinite(*o.pageAspectRatio) && *o.pageAspectRatio > 0,
        "pageAspectRatio must be a finite positive width-to-height ratio when only one page dimension is provided.");
    const double width = o.pageWidth.value_or(o.pageHeight.value_or(0) * *o.pageAspectRatio);
    const double height = o.pageHeight.value_or(o.pageWidth.value_or(0) / *o.pageAspectRatio);
    page = PageSize{points(width, "Derived pageWidth must be between 1.0 and 14400.0 PDF points."),
                    points(height, "Derived pageHeight must be between 1.0 and 14400.0 PDF points.")};
  }
  std::optional<double> dpi;
  if (page) {
    dpi = o.targetDpi.value_or(200.);
    require(std::isfinite(*dpi) && *dpi > 0, "targetDpi must be a finite positive number.");
  }
  std::optional<int> quality;
  if (o.jpegQuality) {
    require(std::isfinite(*o.jpegQuality) && *o.jpegQuality >= 0 && *o.jpegQuality <= 1,
            "jpegQuality must be between 0 and 1.");
    quality = int(std::floor(*o.jpegQuality * 100 + .5));
  }
  return {page, o.fit, o.autoRotateExif, dpi, quality};
}
Placement place(Size stored, Orientation orientation, const Layout &layout) {
  validSize(stored);
  const int exif = int(orientation);
  const bool swaps = exif >= 5 && exif <= 8;
  const double width = swaps ? stored.height : stored.width, height = swaps ? stored.width : stored.height;
  const auto page = layout.page.value_or(PageSize{width, height});
  const Fit fit = layout.page ? layout.fit : Fit::none;
  double w = width, h = height;
  if (fit == Fit::fill) {
    w = page.width;
    h = page.height;
  } else if (fit == Fit::contain || fit == Fit::cover) {
    const double scale = fit == Fit::contain ? std::min(page.width / width, page.height / height)
                                             : std::max(page.width / width, page.height / height);
    w *= scale;
    h *= scale;
  }
  // Preserve Android RectF storage/subtraction rounding before matrix and DPI math.
  const float left = float((page.width - w) / 2), bottom = float((page.height - h) / 2);
  const float right = float((page.width - w) / 2 + w), top = float((page.height - h) / 2 + h);
  const double bw = float(right - left), bh = float(top - bottom);
  uint32_t tw = uint32_t(width), th = uint32_t(height);
  if (layout.targetDpi) {
    const double p = *layout.targetDpi / 72.;
    if (fit == Fit::fill) {
      tw = std::min(tw, rounded(page.width * p));
      th = std::min(th, rounded(page.height * p));
    } else {
      const double scale = std::min(1., std::max(std::abs(bw) * p / width, std::abs(bh) * p / height));
      tw = rounded(width * scale);
      th = rounded(height * scale);
    }
  }
  std::array<double, 6> m{bw, 0, 0, bh, 0, 0};
  switch (exif) {
  case 2:
    m = {-bw, 0, 0, bh, bw, 0};
    break;
  case 3:
    m = {-bw, 0, 0, -bh, bw, bh};
    break;
  case 4:
    m = {bw, 0, 0, -bh, 0, bh};
    break;
  case 5:
    m = {0, -bh, -bw, 0, bw, bh};
    break;
  case 6:
    m = {0, -bh, bw, 0, 0, bh};
    break;
  case 7:
    m = {0, bh, bw, 0, 0, 0};
    break;
  case 8:
    m = {0, bh, -bw, 0, bw, 0};
    break;
  }
  m[4] += left;
  m[5] += bottom;
  return {page, {swaps ? th : tw, swaps ? tw : th}, m};
}
std::optional<ImageInfo> probe(const ByteSource &source) {
  require(bool(source.read), "Image source requires a reader.");
  Reader r{source};
  if (source.length < 8)
    return {};
  const auto signature = r.read(0, 8);
  if (signature[0] == std::byte{255} && signature[1] == std::byte{216}) {
    uint64_t offset = 2;
    while (r.range(offset, 4)) {
      auto marker = r.read(offset++, 1)[0];
      if (marker != std::byte{255})
        return {};
      do {
        if (!r.range(offset, 1))
          return {};
        marker = r.read(offset++, 1)[0];
      } while (marker == std::byte{255});
      const unsigned kind = std::to_integer<unsigned>(marker);
      if (kind == 217 || kind == 218)
        return {};
      if ((kind >= 208 && kind <= 215) || kind == 1)
        continue;
      if (!r.range(offset, 2))
        return {};
      const auto len = r.read(offset, 2);
      const unsigned length = std::to_integer<unsigned>(len[0]) * 256 + std::to_integer<unsigned>(len[1]);
      if (length < 2 || !r.range(offset, length))
        return {};
      if (kind >= 192 && kind <= 207 && kind != 196 && kind != 200 && kind != 204) {
        if (kind > 194 || length < 8)
          return {};
        const auto header = r.read(offset + 2, 6);
        if (header[0] != std::byte{8})
          return {};
        const uint32_t h = std::to_integer<unsigned>(header[1]) * 256 + std::to_integer<unsigned>(header[2]);
        const uint32_t w = std::to_integer<unsigned>(header[3]) * 256 + std::to_integer<unsigned>(header[4]);
        const unsigned channels = std::to_integer<unsigned>(header[5]);
        if (!w || !h || (channels != 1 && channels != 3))
          return {};
        return ImageInfo{
            {w, h}, channels == 1 ? ImageKind::jpegGray : ImageKind::jpegRgb, {}, {{0, source.length}}, source.length};
      }
      offset += length;
    }
    return {};
  }
  const std::array<std::byte, 8> png{std::byte{137}, std::byte{80}, std::byte{78}, std::byte{71},
                                     std::byte{13},  std::byte{10}, std::byte{26}, std::byte{10}};
  if (!std::equal(signature.begin(), signature.end(), png.begin()))
    return {};
  uint64_t offset = 8, total = 0;
  uint32_t width = 0, height = 0;
  std::optional<unsigned> transparent;
  std::vector<Range> ranges;
  while (r.range(offset, 12)) {
    const auto header = r.read(offset, 8);
    const uint32_t length = u32(header);
    const uint64_t start = offset + 8;
    if (length > INT32_MAX || !r.range(start, uint64_t(length) + 4))
      return {};
    const std::string type(reinterpret_cast<const char *>(header.data() + 4), 4);
    if (!width && type != "IHDR")
      return {};
    if (type == "IHDR") {
      if (length != 13 || width)
        return {};
      const auto data = r.read(start, 13);
      width = u32(data);
      height = u32(std::span(data).subspan(4));
      if (!width || !height || width > INT32_MAX || height > INT32_MAX || data[8] != std::byte{4} ||
          std::any_of(data.begin() + 9, data.end(), [](auto b) { return b != std::byte{0}; }))
        return {};
    } else if (type == "tRNS") {
      if (length != 2 || transparent)
        return {};
      const auto data = r.read(start, 2);
      const unsigned sample = std::to_integer<unsigned>(data[0]) * 256 + std::to_integer<unsigned>(data[1]);
      if (sample > 15)
        return {};
      transparent = sample;
    } else if (type == "IDAT") {
      ranges.push_back({start, length});
      total += length;
    } else if (type == "IEND") {
      if (length || !total)
        return {};
      return ImageInfo{{width, height}, ImageKind::gray4, transparent, std::move(ranges), total};
    } else if (type[0] >= 'A' && type[0] <= 'Z')
      return {};
    offset = start + length + 4;
  }
  return {};
}
void write(size_t pageCount, const Options &options, ImageAdapter &images, const ByteSink &sink) {
  require(pageCount > 0, "No pages provided.");
  require(pageCount <= (size_t(INT32_MAX) - 2) / 3, "Too many PDF pages.");
  const auto layout = validate(options);
  Output out(sink);
  std::vector<uint64_t> offsets;
  auto object = [&] {
    offsets.push_back(out.count);
    out.ascii(std::to_string(offsets.size()) + " 0 obj\n");
  };
  out.ascii("%PDF-1.4\n%\342\343\317\323\n");
  object();
  out.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
  object();
  out.ascii("<< /Type /Pages /Kids [");
  for (size_t i = 0; i < pageCount; ++i)
    out.ascii((i ? " " : "") + std::to_string(3 + i * 3) + " 0 R");
  out.ascii("] /Count " + std::to_string(pageCount) + " >>\nendobj\n");
  for (size_t index = 0; index < pageCount; ++index) {
    const auto source = images.open(index);
    const auto direct = probe(source);
    const Size size = direct ? direct->size : images.dimensions();
    validSize(size);
    const Orientation orientation = layout.autoRotateExif ? images.orientation() : Orientation::upright;
    const auto placement = place(size, orientation, layout);
    const bool unscaled = placement.encodedSize.width == size.width && placement.encodedSize.height == size.height;
    const bool gray = direct && direct->kind == ImageKind::gray4;
    std::optional<EncodedImage> encoded;
    const bool reuse = direct && unscaled && (gray || !layout.jpegQuality);
    if (!reuse) {
      encoded.emplace(images.encode(placement.encodedSize, gray, layout.jpegQuality.value_or(72)));
      require(
          encoded->size.width == placement.encodedSize.width && encoded->size.height == placement.encodedSize.height &&
              encoded->kind == (gray ? ImageKind::gray4 : ImageKind::jpegRgb) && encoded->length && bool(encoded->copy),
          "Image encoder returned incompatible PDF payload.");
    }
    const auto imageSize = reuse ? direct->size : encoded->size;
    const auto kind = reuse ? direct->kind : encoded->kind;
    const uint64_t length = reuse ? direct->compressedLength : encoded->length;
    const std::string width = number(placement.page.width), height = number(placement.page.height);
    object();
    out.ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height +
              "] /Resources << /XObject << /Im0 " + std::to_string(5 + index * 3) + " 0 R >> >> /Contents " +
              std::to_string(4 + index * 3) + " 0 R >>\nendobj\n");
    std::string content = "q 0 0 " + width + " " + height + " re W n";
    for (auto value : placement.matrix)
      content += " " + number(value);
    content += " cm /Im0 Do Q\n";
    object();
    out.ascii("<< /Length " + std::to_string(content.size()) + " >>\nstream\n" + content + "\nendstream\nendobj\n");
    object();
    out.ascii("<< /Type /XObject /Subtype /Image /Width " + std::to_string(imageSize.width) + " /Height " +
              std::to_string(imageSize.height) + " " +
              dictionary(kind, imageSize.width, reuse ? direct->transparentSample : std::nullopt) + " /Length " +
              std::to_string(length) + " >>\nstream\n");
    const auto start = out.count;
    const ByteSink payload = [&](auto bytes) {
      if (bytes.size() > length - (out.count - start))
        throw std::runtime_error("PDF image exceeded its declared length.");
      out.bytes(bytes);
    };
    if (reuse)
      copy(source, direct->ranges, payload);
    else
      encoded->copy(payload);
    if (out.count - start != length)
      throw std::runtime_error("PDF image ended before its declared length.");
    out.ascii("\nendstream\nendobj\n");
  }
  const auto xref = out.count;
  out.ascii("xref\n0 " + std::to_string(offsets.size() + 1) + "\n0000000000 65535 f \n");
  for (auto offset : offsets) {
    if (offset > 9999999999ULL)
      throw std::runtime_error("PDF exceeds classic xref offset limit.");
    std::ostringstream value;
    value.imbue(std::locale::classic());
    value << std::setw(10) << std::setfill('0') << offset;
    out.ascii(value.str() + " 00000 n \n");
  }
  out.ascii("trailer\n<< /Size " + std::to_string(offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" +
            std::to_string(xref) + "\n%%EOF\n");
}
} // namespace document_scanner::pdf
