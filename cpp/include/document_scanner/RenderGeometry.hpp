#pragma once
#include "Pixels.hpp"
#include <optional>

namespace document_scanner {
struct GeometricRenderInfo final {
  const std::vector<Point> validContentPolygon;
  const bool curved;
};
struct GeometricRenderResult final {
  OwnedPixels pixels;
  const std::vector<Point> validContentPolygon;
  const bool curved;
};
// Borrows a full upright RGBA8 source (packed pixels, padded rows allowed) only
// until return. Pixels and coverage are independently owned immutable values.
// Framing/decode/filters/encoding remain with the caller. Coverage always uses
// the straight homography; bends are disabled if any submitted corner is outside.
GeometricRenderResult renderGeometry(const PixelLease &source, const Quad &normalizedQuad, double originalScale,
                                     std::optional<double> paperLongEdge = std::nullopt, bool followPageEdges = true);
// Same render, writing RGBA8 straight into caller storage. The allocator runs only after coverage
// checks pass, so a rejected render leaves the caller's storage untouched.
GeometricRenderInfo renderGeometry(const PixelLease &source, const Quad &normalizedQuad, double originalScale,
                                   std::optional<double> paperLongEdge, bool followPageEdges,
                                   const PixelAllocator &allocate);
} // namespace document_scanner
