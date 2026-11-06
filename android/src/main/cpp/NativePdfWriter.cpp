#include "NativePdfWriter.hpp"
#include <document_scanner/PdfWriter.hpp>
#include <algorithm>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
using namespace facebook::jni;
namespace p = document_scanner::pdf;
using document_scanner::ByteSink;
struct JavaImages : JavaClass<JavaImages> {
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/PdfImageAdapter;";
};
struct JavaOutput : JavaClass<JavaOutput> {
  static constexpr auto kJavaDescriptor = "Ljava/io/OutputStream;";
};
class Images final : public p::ImageAdapter {
public:
  explicit Images(alias_ref<JavaImages> image) : image_(image), buffer_(JArrayByte::newArray(64 * 1024)) {}
  p::ByteSource source(jlong length) {
    if (length < 0)
      throw std::invalid_argument("Invalid encoded file length.");
    return {uint64_t(length), [this](uint64_t offset, std::span<std::byte> into) {
              static const auto read =
                  JavaImages::javaClassStatic()->getMethod<void(jlong, alias_ref<JArrayByte>, jint)>("readAt");
              if (into.size() > 64 * 1024)
                throw std::invalid_argument("Encoded read exceeds adapter buffer.");
              read(image_, jlong(offset), buffer_, jint(into.size()));
              buffer_->getRegion(0, jsize(into.size()), reinterpret_cast<jbyte *>(into.data()));
            }};
  }
  p::ByteSource open(size_t index) override {
    static const auto open = JavaImages::javaClassStatic()->getMethod<jlong(jint)>("open");
    return source(open(image_, jint(index)));
  }
  document_scanner::Size dimensions() override {
    static const auto dimensions = JavaImages::javaClassStatic()->getMethod<JArrayInt()>("dimensions");
    auto array = dimensions(image_);
    if (!array || array->size() != 2)
      throw std::invalid_argument("Image dimensions are missing.");
    const auto values = array->getRegion(0, 2);
    return {uint32_t(values[0]), uint32_t(values[1])};
  }
  document_scanner::Orientation orientation() override {
    static const auto orientation = JavaImages::javaClassStatic()->getMethod<jint()>("orientation");
    int value = orientation(image_);
    return document_scanner::Orientation(value >= 1 && value <= 8 ? value : 1);
  }
  p::EncodedImage encode(document_scanner::Size size, bool gray, int quality) override {
    static const auto encode =
        JavaImages::javaClassStatic()->getMethod<JArrayByte(jint, jint, jboolean, jint)>("encode");
    auto value = encode(image_, jint(size.width), jint(size.height), gray, quality);
    if (!value)
      throw std::invalid_argument("Image encoder returned no bytes.");
    const size_t length = value->size();
    auto owner = std::make_shared<local_ref<JArrayByte>>(std::move(value));
    return {size, gray ? p::ImageKind::gray4 : p::ImageKind::jpegRgb, length, [owner, length](const ByteSink &sink) {
              std::array<jbyte, 64 * 1024> buffer;
              for (size_t offset = 0; offset < length;) {
                const size_t count = std::min(buffer.size(), length - offset);
                (*owner)->getRegion(jsize(offset), jsize(count), buffer.data());
                sink({reinterpret_cast<const std::byte *>(buffer.data()), count});
                offset += count;
              }
            }};
  }

private:
  alias_ref<JavaImages> image_;
  local_ref<JArrayByte> buffer_;
};
p::Options options(alias_ref<JArrayDouble> values, jint mask, jint fit, jboolean exif) {
  if (!values || values->size() != 5 || mask < 0 || mask > 31 || fit < 0 || fit > 3)
    throw std::invalid_argument("Invalid PDF options bridge.");
  const auto a = values->getRegion(0, 5);
  p::Options o;
  std::optional<double> *fields[] = {&o.pageWidth, &o.pageHeight, &o.pageAspectRatio, &o.targetDpi, &o.jpegQuality};
  for (size_t i = 0; i < 5; ++i)
    if (mask & (1 << i))
      *fields[i] = a[i];
  o.fit = static_cast<p::Fit>(fit);
  o.autoRotateExif = exif;
  return o;
}
void writeNative(alias_ref<jclass>, alias_ref<JavaOutput> output, alias_ref<JavaImages> image, jint count,
                 alias_ref<JArrayDouble> values, jint mask, jint fit, jboolean exif) {
  if (!output || !image || count <= 0)
    throwNewJavaException("com/margelo/nitro/documentscanner/InvalidInputException", "No pages provided.");
  try {
    Images adapter(image);
    auto buffer = JArrayByte::newArray(64 * 1024);
    static const auto write =
        JavaOutput::javaClassStatic()->getMethod<void(alias_ref<JArrayByte>, jint, jint)>("write");
    const ByteSink sink = [&](std::span<const std::byte> bytes) {
      while (!bytes.empty()) {
        const auto part = bytes.first(std::min<size_t>(bytes.size(), 64 * 1024));
        buffer->setRegion(0, jsize(part.size()), reinterpret_cast<const jbyte *>(part.data()));
        write(output, buffer, 0, jint(part.size()));
        bytes = bytes.subspan(part.size());
      }
    };
    p::write(size_t(count), options(values, mask, fit, exif), adapter, sink);
  } catch (const std::invalid_argument &e) {
    throwNewJavaException("com/margelo/nitro/documentscanner/InvalidInputException", e.what());
  } catch (const std::runtime_error &e) {
    throwNewJavaException("java/io/IOException", e.what());
  }
}
local_ref<JArrayLong> probeNative(alias_ref<jclass>, alias_ref<JavaImages> image, jlong length) {
  if (!image)
    throwNewJavaException("java/lang/IllegalArgumentException", "Missing image reader.");
  Images adapter(image);
  const auto info = p::probe(adapter.source(length));
  if (!info || info->kind != p::ImageKind::gray4)
    return JArrayLong::newArray(0);
  std::vector<jlong> data{jlong(info->size.width), jlong(info->size.height),
                          info->transparentSample ? jlong(*info->transparentSample) : -1};
  for (const auto range : info->ranges) {
    data.push_back(jlong(range.offset));
    data.push_back(jlong(range.length));
  }
  auto out = JArrayLong::newArray(data.size());
  out->setRegion(0, data.size(), data.data());
  return out;
}
} // namespace
void NativePdfWriter::registerNatives() {
  javaClassStatic()->registerNatives(
      {makeNativeMethod("writeNative", writeNative), makeNativeMethod("probeNative", probeNative)});
}
} // namespace margelo::nitro::documentscanner
