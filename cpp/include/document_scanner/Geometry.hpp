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
Corners toNormalized(const Corners &corners, Size image);
Corners toImage(const Corners &corners, Size image);

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
  Corners toSource(const Corners &normalizedRegion) const;
  Corners fromSource(const Corners &normalizedSource) const;
  const Region &region() const noexcept { return region_; }

private:
  Size source_;
  Region region_;
};
RegionTransform centeredSquare(Size source);
std::optional<Crop> photoInferenceCrop(const Corners &seed, Size photo);

Corners clampToFrame(const Corners &corners);
bool validCropCorners(const Corners &corners);
std::optional<Corners> moveCorner(const Corners &corners, size_t index, Point target);
std::optional<Corners> moveEdge(const Corners &corners, size_t index, Point displacement);

// Render validation intentionally permits corners outside the captured image.
// Throws invalid_argument with the Android public validation message on failure.
void validateRenderCorners(const Corners &normalizedCorners);
struct TargetCanvas final {
  const Size size;
  const Corners destination;
};
TargetCanvas targetCanvas(const Corners &pixelCorners, double originalScale,
                          std::optional<double> paperLongEdge = std::nullopt);
std::vector<Point> clipImageToCorners(const Corners &pixelCorners, Size image);
// Matches OpenCV's existing Point2f input/output rounding at the Android boundary.
std::vector<Point> normalizedOutputPolygon(std::span<const Point> clipped, const Matrix &homography, Size target);
} // namespace document_scanner::geometry
