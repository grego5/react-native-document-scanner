#pragma once
#include <document_scanner/Pixels.hpp>
#include <opencv2/core.hpp>

namespace margelo::nitro::documentscanner {
// The core writes straight into Android's Mat. A matching existing Mat (in-place shadows) keeps its storage.
inline document_scanner::PixelAllocator matAllocator(cv::Mat &into) {
  return [&into](document_scanner::PixelFormat format, document_scanner::Size size) {
    into.create(int(size.height), int(size.width), CV_8UC(int(document_scanner::bytesPerPixel(format))));
    return document_scanner::PixelTarget{
        {reinterpret_cast<std::byte *>(into.data), size_t(into.rows - 1) * into.step + size_t(into.cols) * into.elemSize()},
        into.step};
  };
}
} // namespace margelo::nitro::documentscanner
