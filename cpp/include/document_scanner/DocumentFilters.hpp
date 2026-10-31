#pragma once
#include <document_scanner/Pixels.hpp>

namespace document_scanner {
enum class DocumentFilter { none, enhance, grayscale, blackWhite };
// Synchronous full upright RGBA8 borrow, padded rows allowed, at most 4096 per side. Independent immutable
// RGB8 output for color filters, gray8 for monochrome. Shadows precede the filter.
OwnedPixels filterDocument(const PixelLease &source, DocumentFilter filter, bool removeShadows = false);
// Independent RGBA8 output; alpha is preserved. Used by platform stage diagnostics.
OwnedPixels removeDocumentShadows(const PixelLease &source);
} // namespace document_scanner
