#pragma once

#include "Pixels.hpp"
#include <optional>

namespace document_scanner::geometry {
// Coordinates use image edges [0,width] x [0,height], unless explicitly pixel centers.
using Matrix = std::array<double, 9>;
Size uprightSize(Size encoded, Orientation orientation);
Matrix orientationMatrix(Size encoded, Orientation orientation, bool pixelCenters = false);
Point encodedToUpright(Point point, Size encoded, Size sampled, Orientation orientation);
Point uprightToEncoded(Point point, Size encoded, Size sampled, Orientation orientation);
Point toNormalized(Point point, Size image);
Point toImage(Point point, Size image);
Quad toNormalized(const Quad &quad, Size image);
Quad toImage(const Quad &quad, Size image);

// Region selection is independent of any subsequent tensor resize. Fractional edges
// keep square framing centered even when the source dimensions differ by an odd pixel.
struct Region final {
  const double left, top, width, height;
};
class RegionTransform final {
public:
  RegionTransform(Size source, Region region);
  Point toSource(Point normalizedRegion) const;
  Point fromSource(Point normalizedSource) const;
  Quad toSource(const Quad &normalizedRegion) const;
  Quad fromSource(const Quad &normalizedSource) const;
  const Region &region() const noexcept { return region_; }

private:
  Size source_;
  Region region_;
};
RegionTransform centeredSquare(Size source);
std::optional<Crop> photoInferenceCrop(const Quad &seed, Size photo);

Quad clampToFrame(const Quad &quad);
bool validCropQuad(const Quad &quad);
std::optional<Quad> moveCorner(const Quad &quad, size_t index, Point target);
std::optional<Quad> moveEdge(const Quad &quad, size_t index, Point displacement);

// Render validation intentionally permits corners outside the captured image.
// Throws invalid_argument with the Android public validation message on failure.
void validateRenderQuad(const Quad &normalizedQuad);
struct TargetCanvas final {
  const Size size;
  const Quad destination;
};
TargetCanvas targetCanvas(const Quad &pixelQuad, double originalScale,
                          std::optional<double> paperLongEdge = std::nullopt);
std::vector<Point> clipImageToQuad(const Quad &pixelQuad, Size image);
// Matches OpenCV's existing Point2f input/output rounding at the Android boundary.
std::vector<Point> normalizedOutputPolygon(std::span<const Point> clipped, const Matrix &homography, Size target);
} // namespace document_scanner::geometry
