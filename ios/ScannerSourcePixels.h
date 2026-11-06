#pragma once
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

// The portable geometry owns EXIF transforms. Invalid dimensions/orientation fail.
FOUNDATION_EXPORT BOOL ScannerSourceTransform(uint32_t width, uint32_t height, uint8_t orientation,
                                              CGAffineTransform * _Nonnull transform);

#if defined(__cplusplus) && !defined(DOCUMENT_SCANNER_SWIFT_IMPORT)
#include <document_scanner/Pixels.hpp>
// The supplied owner must retain BOTH stable bytes and their store/file pin.
// A copied C++ lease retains that owner, so accounting outlives native consumers.
document_scanner::PixelLease ScannerBorrowSourcePixels(NSObject * _Nonnull owner,
    const void * _Nonnull bytes, size_t byteCount, uint32_t width, uint32_t height, size_t rowStride);
#endif
