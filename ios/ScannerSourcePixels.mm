#import "ScannerSourcePixels.h"
#include <document_scanner/Geometry.hpp>
#include <exception>
#include <utility>

BOOL ScannerSourceTransform(uint32_t width, uint32_t height, uint8_t orientation, CGAffineTransform *transform) {
  if (!transform) return NO;
  try {
    const auto m = document_scanner::geometry::orientationMatrix(
        {width, height}, static_cast<document_scanner::Orientation>(orientation));
    *transform = CGAffineTransformMake(m[0], m[3], m[1], m[4], m[2], m[5]);
    return YES;
  } catch (const std::exception &) {
    return NO;
  }
}

document_scanner::PixelLease ScannerBorrowSourcePixels(NSObject *owner, const void *bytes, size_t byteCount,
                                                       uint32_t width, uint32_t height, size_t rowStride) {
  std::shared_ptr<const void> token((__bridge_retained void *)owner, [](const void *value) {
    CFRelease(value);
  });
  return document_scanner::PixelLease(std::move(token),
      {static_cast<const std::byte *>(bytes), byteCount},
      {document_scanner::PixelFormat::rgba8, {width, height}, rowStride, 4,
       {0, 0, width, height}, document_scanner::Orientation::upright});
}
