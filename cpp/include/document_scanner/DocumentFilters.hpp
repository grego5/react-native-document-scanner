#pragma once
#include <document_scanner/Pixels.hpp>

namespace document_scanner {
enum class DocumentFilter { none, enhance, grayscale, blackWhite };
// Synchronous full upright RGBA8 borrow, padded rows allowed, at most 4096 per side. Color filters write
// `color` (rgb8 or bgr8), monochrome filters gray8, straight into the allocated target. Shadows precede the filter.
void filterDocument(const PixelLease &source, DocumentFilter filter, bool removeShadows, PixelFormat color,
                    const PixelAllocator &allocate);
// Independent immutable RGB8 or gray8 output.
OwnedPixels filterDocument(const PixelLease &source, DocumentFilter filter, bool removeShadows = false);
// RGBA8 output; alpha is preserved. The target may be the source storage itself (in-place correction).
// Used by platform stage diagnostics.
void removeDocumentShadows(const PixelLease &source, const PixelAllocator &allocate);
OwnedPixels removeDocumentShadows(const PixelLease &source);
} // namespace document_scanner
