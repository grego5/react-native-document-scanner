#pragma once
#include <document_scanner/Pixels.hpp>
#include <functional>

namespace document_scanner {
// Consume the complete borrowed span synchronously or throw. Exceptions stop the
// writer immediately. The caller owns flush/close, temporary files and publication.
using ByteSink = std::function<void(std::span<const std::byte>)>;
// Supply one full upright gray8 row through its owner. The writer releases the
// lease before requesting the next row and never retains it after return/failure.
using GrayRowReader = std::function<PixelLease(uint32_t)>;

// Sixteen evenly spaced gray levels, no palette/alpha/dithering. Nearest sample
// is (input + 8) / 17. Odd-width trailing low nibble is zero. Source is read-only.
void writeGrayscalePng4(const PixelLease &source, const ByteSink &sink);
void writeGrayscalePng4(Size size, const GrayRowReader &readRow, const ByteSink &sink);
// The same zlib/PNG-predictor stream without PNG chunks, for PDF Predictor 15,
// Colors 1, BitsPerComponent 4 and Columns equal to the input width.
void writeCompressedGray4(const PixelLease &source, const ByteSink &sink);
void writeCompressedGray4(Size size, const GrayRowReader &readRow, const ByteSink &sink);
} // namespace document_scanner
