#pragma once
#include "Pixels.hpp"
#include <optional>

namespace document_scanner {
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
} // namespace document_scanner
