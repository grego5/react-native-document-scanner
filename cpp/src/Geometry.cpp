#include <document_scanner/Tensor.hpp>
#include <document_scanner/Geometry.hpp>
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <opencv2/core.hpp>

namespace document_scanner::geometry {
namespace {
void dimensions(Size size) {
  if (!size.width || !size.height)
    throw std::invalid_argument("Image dimensions must be positive.");
}
void finite(Point p) {
  if (!std::isfinite(p.x) || !std::isfinite(p.y))
    throw std::invalid_argument("Image coordinates must be finite.");
}
double cross(Point a, Point b, Point c) { return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x); }
double area(const Quad &q) {
  double doubled = 0;
  for (size_t i = 0; i < 4; ++i)
    doubled += q[i].x * q[(i + 1) % 4].y - q[(i + 1) % 4].x * q[i].y;
  return std::abs(doubled) / 2;
}
bool clockwise(const Quad &q, double minTurn) {
  for (size_t i = 0; i < 4; ++i) {
    if (!std::isfinite(q[i].x) || !std::isfinite(q[i].y) || !(cross(q[i], q[(i + 1) % 4], q[(i + 2) % 4]) > minTurn))
      return false;
  }
  return true;
}
template <typename F> Quad mapQuad(const Quad &q, F f) { return {f(q[0]), f(q[1]), f(q[2]), f(q[3])}; }
Point apply(const Matrix &m, Point p) { return {m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5]}; }
std::optional<Point> intersection(Point a, Point b, Point c, Point d) {
  const double ax = b.x - a.x, ay = b.y - a.y, cx = d.x - c.x, cy = d.y - c.y;
  const double determinant = ax * cy - ay * cx;
  if (std::abs(determinant) < 1e-10)
    return std::nullopt;
  const double t = ((c.x - a.x) * cy - (c.y - a.y) * cx) / determinant;
  return Point{a.x + t * ax, a.y + t * ay};
}
} // namespace

Size uprightSize(Size encoded, Orientation orientation) {
  dimensions(encoded);
  const auto value = static_cast<unsigned>(orientation);
  if (value < 1 || value > 8)
    throw std::invalid_argument("EXIF orientation must be between 1 and 8.");
  return value >= 5 ? Size{encoded.height, encoded.width} : encoded;
}
Matrix orientationMatrix(Size encoded, Orientation orientation, bool pixelCenters) {
  uprightSize(encoded, orientation);
  const double w = encoded.width - (pixelCenters ? 1.0 : 0.0);
  const double h = encoded.height - (pixelCenters ? 1.0 : 0.0);
  switch (orientation) {
  case Orientation::upright:
    return {1, 0, 0, 0, 1, 0, 0, 0, 1};
  case Orientation::mirrorHorizontal:
    return {-1, 0, w, 0, 1, 0, 0, 0, 1};
  case Orientation::rotate180:
    return {-1, 0, w, 0, -1, h, 0, 0, 1};
  case Orientation::mirrorVertical:
    return {1, 0, 0, 0, -1, h, 0, 0, 1};
  case Orientation::transpose:
    return {0, 1, 0, 1, 0, 0, 0, 0, 1};
  case Orientation::rotate90:
    return {0, -1, h, 1, 0, 0, 0, 0, 1};
  case Orientation::transverse:
    return {0, -1, h, -1, 0, w, 0, 0, 1};
  case Orientation::rotate270:
    return {0, 1, 0, -1, 0, w, 0, 0, 1};
  }
  throw std::invalid_argument("EXIF orientation must be between 1 and 8.");
}
Point encodedToUpright(Point p, Size encoded, Size sampled, Orientation orientation) {
  dimensions(encoded);
  finite(p);
  return apply(orientationMatrix(sampled, orientation),
               {p.x * sampled.width / encoded.width, p.y * sampled.height / encoded.height});
}
Point uprightToEncoded(Point p, Size encoded, Size sampled, Orientation orientation) {
  dimensions(encoded);
  finite(p);
  const auto m = orientationMatrix(sampled, orientation);
  // Every orientation is an orthogonal signed permutation, so its inverse is its transpose.
  const double x = p.x - m[2], y = p.y - m[5];
  return {(m[0] * x + m[3] * y) * encoded.width / sampled.width,
          (m[1] * x + m[4] * y) * encoded.height / sampled.height};
}
Point toNormalized(Point p, Size image) {
  dimensions(image);
  finite(p);
  return {p.x / image.width, p.y / image.height};
}
Point toImage(Point p, Size image) {
  dimensions(image);
  finite(p);
  return {p.x * image.width, p.y * image.height};
}
Quad toNormalized(const Quad &q, Size image) {
  return mapQuad(q, [&](Point p) { return toNormalized(p, image); });
}
Quad toImage(const Quad &q, Size image) {
  return mapQuad(q, [&](Point p) { return toImage(p, image); });
}

RegionTransform::RegionTransform(Size source, Region region) : source_(source), region_(region) {
  dimensions(source);
  if (!std::isfinite(region.left) || !std::isfinite(region.top) || !std::isfinite(region.width) ||
      !std::isfinite(region.height) || region.left < 0 || region.top < 0 || region.width <= 0 || region.height <= 0 ||
      region.left + region.width > source.width || region.top + region.height > source.height)
    throw std::invalid_argument("Region must lie inside the source image.");
}
Point RegionTransform::toSource(Point p) const {
  finite(p);
  return {(region_.left + p.x * region_.width) / source_.width, (region_.top + p.y * region_.height) / source_.height};
}
Point RegionTransform::fromSource(Point p) const {
  finite(p);
  return {(p.x * source_.width - region_.left) / region_.width, (p.y * source_.height - region_.top) / region_.height};
}
Quad RegionTransform::toSource(const Quad &q) const {
  return mapQuad(q, [&](Point p) { return toSource(p); });
}
Quad RegionTransform::fromSource(const Quad &q) const {
  return mapQuad(q, [&](Point p) { return fromSource(p); });
}
RegionTransform centeredSquare(Size source) {
  dimensions(source);
  const double side = std::min(source.width, source.height);
  return {source, {(source.width - side) / 2, (source.height - side) / 2, side, side}};
}
std::optional<Crop> photoInferenceCrop(const Quad &q, Size photo) {
  dimensions(photo);
  if (!validPredictionQuad(q)) return std::nullopt;
  const auto [lx, rx] = std::minmax_element(q.begin(), q.end(), [](Point a, Point b) { return a.x < b.x; });
  const auto [ty, by] = std::minmax_element(q.begin(), q.end(), [](Point a, Point b) { return a.y < b.y; });
  const double x0 = lx->x * photo.width, x1 = rx->x * photo.width, y0 = ty->y * photo.height, y1 = by->y * photo.height;
  const double minX = std::max(0.0, x0 - (x1 - x0) * .10), maxX = std::min(double(photo.width), x1 + (x1 - x0) * .10);
  const double minY = std::max(0.0, y0 - (y1 - y0) * .10), maxY = std::min(double(photo.height), y1 + (y1 - y0) * .10);
  const double ratio = double(photo.width) / photo.height;
  const double w = std::min(std::max(maxX - minX, (maxY - minY) * ratio), double(photo.width));
  const double h = std::min(w / ratio, double(photo.height));
  const double x = std::clamp((minX + maxX - w) / 2, 0.0, photo.width - w);
  const double y = std::clamp((minY + maxY - h) / 2, 0.0, photo.height - h);
  const auto left = static_cast<uint32_t>(std::floor(x)), top = static_cast<uint32_t>(std::floor(y));
  return Crop{left, top, std::clamp(static_cast<uint32_t>(std::ceil(x + w)) - left, 1u, photo.width - left),
              std::clamp(static_cast<uint32_t>(std::ceil(y + h)) - top, 1u, photo.height - top)};
}
Quad clampToFrame(const Quad &q) {
  return mapQuad(q, [](Point p) { return Point{std::clamp(p.x, 0.0, 1.0), std::clamp(p.y, 0.0, 1.0)}; });
}
bool validCropQuad(const Quad &q) { return clockwise(q, 1e-8) && area(q) >= 1e-4; }
std::optional<Quad> moveCorner(const Quad &q, size_t index, Point target) {
  if (index >= 4)
    throw std::invalid_argument("Corner index must be between 0 and 3.");
  const auto next = mapQuad(q, [&, i = size_t{0}](Point p) mutable {
    return i++ == index ? Point{std::clamp(target.x, 0.0, 1.0), std::clamp(target.y, 0.0, 1.0)} : p;
  });
  return validCropQuad(next) ? std::optional<Quad>(next) : std::nullopt;
}
std::optional<Quad> moveEdge(const Quad &q, size_t index, Point d) {
  if (index >= 4)
    throw std::invalid_argument("Edge index must be between 0 and 3.");
  const auto b = (index + 1) % 4;
  const double x = std::clamp(d.x, -std::min(q[index].x, q[b].x), 1 - std::max(q[index].x, q[b].x));
  const double y = std::clamp(d.y, -std::min(q[index].y, q[b].y), 1 - std::max(q[index].y, q[b].y));
  const auto next = mapQuad(q, [&, i = size_t{0}](Point p) mutable {
    const bool moves = i == index || i == b;
    ++i;
    return moves ? Point{p.x + x, p.y + y} : p;
  });
  return validCropQuad(next) ? std::optional<Quad>(next) : std::nullopt;
}
void validateRenderQuad(const Quad &q) {
  for (Point p : q)
    if (!std::isfinite(p.x) || !std::isfinite(p.y))
      throw std::invalid_argument("Document corners must be finite.");
  for (Point p : q)
    if (p.x < -.8 || p.x > 1.8 || p.y < -.8 || p.y > 1.8)
      throw std::invalid_argument("Document corners exceed the supported extrapolation range.");
  if (!clockwise(q, 1e-8))
    throw std::invalid_argument("Document corners must form a convex clockwise quadrilateral.");
  const double a = area(q);
  if (a < 1e-4 || a > 4)
    throw std::invalid_argument("Document quadrilateral area is outside the supported range.");
}
TargetCanvas targetCanvas(const Quad &q, double originalScale, std::optional<double> paperLongEdge) {
  const auto distance = [](Point a, Point b) { return std::hypot(a.x - b.x, a.y - b.y); };
  const double w = (distance(q[0], q[1]) + distance(q[2], q[3])) / 2;
  const double h = (distance(q[1], q[2]) + distance(q[3], q[0])) / 2;
  if (!std::isfinite(w) || !std::isfinite(h) || w <= 1 || h <= 1)
    throw std::invalid_argument("Document side lengths are unstable.");
  const double ratio = w / h;
  if (ratio < .20 || ratio > 5)
    throw std::invalid_argument("Document aspect ratio is outside the supported range.");
  if (!std::isfinite(originalScale) || originalScale <= 0 ||
      (paperLongEdge && (!std::isfinite(*paperLongEdge) || *paperLongEdge <= 0 || *paperLongEdge > 4096)))
    throw std::invalid_argument("Invalid output scale or paper size.");
  const double longEdge = std::min(paperLongEdge.value_or(4096), std::max(w, h) * originalScale);
  const auto width = static_cast<uint32_t>(std::max(1.0, std::floor((ratio >= 1 ? longEdge : longEdge * ratio) + .5)));
  const auto height = static_cast<uint32_t>(std::max(1.0, std::floor((ratio >= 1 ? longEdge / ratio : longEdge) + .5)));
  if (uint64_t(width) * height > 16777216)
    throw std::invalid_argument("Requested output exceeds the maximum pixel count.");
  return {{width, height},
          {Point{0, 0}, Point{double(width), 0}, Point{double(width), double(height)}, Point{0, double(height)}}};
}
std::vector<Point> clipImageToQuad(const Quad &q, Size image) {
  dimensions(image);
  std::vector<Point> clipped{
      {0, 0}, {double(image.width), 0}, {double(image.width), double(image.height)}, {0, double(image.height)}};
  for (size_t i = 0; i < 4 && !clipped.empty(); ++i) {
    auto input = std::move(clipped);
    clipped.clear();
    const Point *previous = &input.back();
    bool previousInside = cross(q[i], q[(i + 1) % 4], *previous) >= -1e-7;
    for (const Point &current : input) {
      const bool inside = cross(q[i], q[(i + 1) % 4], current) >= -1e-7;
      if (inside != previousInside) {
        if (auto point = intersection(*previous, current, q[i], q[(i + 1) % 4]))
          clipped.push_back(*point);
      }
      if (inside)
        clipped.push_back(current);
      previous = &current;
      previousInside = inside;
    }
  }
  return clipped;
}
std::vector<Point> normalizedOutputPolygon(std::span<const Point> clipped, const Matrix &m, Size target) {
  dimensions(target);
  for (double value : m)
    if (!std::isfinite(value))
      throw std::invalid_argument("Homography must be finite.");
  std::vector<cv::Point2f> source, projected;
  for (Point p : clipped) {
    finite(p);
    source.emplace_back(static_cast<float>(p.x), static_cast<float>(p.y));
  }
  if (source.empty())
    return {};
  cv::perspectiveTransform(source, projected, cv::Matx33d(m.data()));
  std::vector<Point> output;
  output.reserve(projected.size());
  for (const auto &p : projected)
    output.push_back(
        {std::clamp(p.x / double(target.width), 0.0, 1.0), std::clamp(p.y / double(target.height), 0.0, 1.0)});
  return output;
}
} // namespace document_scanner::geometry
