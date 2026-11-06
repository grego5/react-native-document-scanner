#include "NativeGrayscalePng.hpp"
#include <document_scanner/GrayscalePng.hpp>
#include <opencv2/core.hpp>
#include <cstring>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
namespace {
using namespace facebook::jni;
using namespace document_scanner;
struct OutputStream : JavaClass<OutputStream> {
  static constexpr auto kJavaDescriptor = "Ljava/io/OutputStream;";
};
struct RowReader : JavaClass<RowReader> {
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/GrayPngRows;";
};
void writeToJava(alias_ref<OutputStream> output, alias_ref<JArrayByte> buffer, std::span<const std::byte> bytes) {
  static const auto write =
      OutputStream::javaClassStatic()->getMethod<void(alias_ref<JArrayByte>, jint, jint)>("write");
  buffer->setRegion(0, jsize(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
  write(output, buffer, 0, jint(bytes.size()));
}
void writeMatNative(alias_ref<jclass>, alias_ref<OutputStream> output, jlong address, jboolean compressedOnly) {
  if (!output || !address)
    throwNewJavaException("java/lang/IllegalArgumentException", "PNG requires a grayscale matrix and a sink.");
  // Retain native Mat storage for the synchronous writer, including padded rows/ROIs.
  auto owner = std::make_shared<cv::Mat>(*reinterpret_cast<const cv::Mat *>(address));
  if (owner->empty() || owner->type() != CV_8UC1)
    throwNewJavaException("java/lang/IllegalArgumentException", "PNG requires a nonempty gray8 matrix.");
  auto buffer = JArrayByte::newArray(64 * 1024);
  const ByteSink sink = [&](std::span<const std::byte> bytes) {
    writeToJava(output, buffer, bytes);
  };
  try {
    const Size size{uint32_t(owner->cols), uint32_t(owner->rows)};
    const PixelLease source(owner,
        {reinterpret_cast<const std::byte *>(owner->data), size_t(owner->rows - 1) * owner->step + size.width},
        {PixelFormat::gray8, size, owner->step, 1, {0, 0, size.width, size.height}, Orientation::upright});
    if (compressedOnly)
      writeCompressedGray4(source, sink);
    else
      writeGrayscalePng4(source, sink);
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("java/lang/IllegalArgumentException", error.what());
  } catch (const std::runtime_error &error) {
    throwNewJavaException("java/io/IOException", error.what());
  }
}
void writeNative(alias_ref<jclass>, alias_ref<OutputStream> output, jint width, jint height,
                 alias_ref<RowReader> reader, jboolean compressedOnly) {
  if (!output || !reader || width <= 0 || height <= 0)
    throwNewJavaException("java/lang/IllegalArgumentException",
                          "PNG requires positive dimensions, a reader and a sink.");
  // These references/arrays remain local to this synchronous caller. Java fills
  // a reusable row, copied into owned native storage before leasing it to core.
  auto input = JArrayByte::newArray(width);
  auto buffer = JArrayByte::newArray(64 * 1024);
  auto owner = std::make_shared<std::vector<std::byte>>(width);
  static const auto read = RowReader::javaClassStatic()->getMethod<void(jint, alias_ref<JArrayByte>)>("read");
  const GrayRowReader rows = [&](uint32_t y) {
    read(reader, jint(y), input);
    const auto values = input->getRegion(0, width);
    std::memcpy(owner->data(), &values[0], size_t(width));
    return PixelLease(
        owner, *owner,
        {PixelFormat::gray8, {uint32_t(width), 1}, size_t(width), 1, {0, 0, uint32_t(width), 1}, Orientation::upright});
  };
  const ByteSink sink = [&](std::span<const std::byte> bytes) {
    writeToJava(output, buffer, bytes);
  };
  try {
    const Size size{uint32_t(width), uint32_t(height)};
    if (compressedOnly)
      writeCompressedGray4(size, rows, sink);
    else
      writeGrayscalePng4(size, rows, sink);
  } catch (const std::invalid_argument &error) {
    throwNewJavaException("java/lang/IllegalArgumentException", error.what());
  } catch (const std::runtime_error &error) {
    throwNewJavaException("java/io/IOException", error.what());
  }
}
} // namespace
void NativeGrayscalePng::registerNatives() {
  javaClassStatic()->registerNatives({makeNativeMethod("writeNative", writeNative),
                                    makeNativeMethod("writeMatNative", writeMatNative)});
}
} // namespace margelo::nitro::documentscanner
