#include <document_scanner/Motion.hpp>
#include <algorithm>
#include <cmath>
#include <limits>
#include <numbers>
#include <vector>

namespace document_scanner::motion {
namespace {
Matrix identity() { return {1, 0, 0, 0, 1, 0, 0, 0, 1}; }
Matrix transpose(const Matrix &m) { return {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]}; }
Matrix multiply(const Matrix &a, const Matrix &b) {
  Matrix r{};
  for (int i = 0; i < 3; ++i)
    for (int j = 0; j < 3; ++j)
      r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
  return r;
}
Matrix exponential(double x, double y, double z) {
  const double angle = std::sqrt(x * x + y * y + z * z);
  if (angle == 0)
    return identity();
  const double a = x / angle, b = y / angle, d = z / angle, s = std::sin(angle), c = 1 - std::cos(angle);
  return {1 - c * (b * b + d * d), -s * d + c * a * b,      s * b + c * a * d,
          s * d + c * a * b,       1 - c * (a * a + d * d), -s * a + c * b * d,
          -s * b + c * a * d,      s * a + c * b * d,       1 - c * (a * a + b * b)};
}
using Model = std::array<double, 4>;
double residual(const Track &p, const Model &m) {
  return std::hypot(m[0] * p.x - m[1] * p.y + m[2] - p.u, m[1] * p.x + m[0] * p.y + m[3] - p.v);
}
std::optional<Model> leastSquares(std::span<const Track> ps) {
  double x = 0, y = 0, u = 0, v = 0;
  for (auto p : ps) {
    x += p.x;
    y += p.y;
    u += p.u;
    v += p.v;
  }
  x /= ps.size();
  y /= ps.size();
  u /= ps.size();
  v /= ps.size();
  double energy = 0, dot = 0, cross = 0;
  for (auto p : ps) {
    energy += (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y);
    dot += (p.x - x) * (p.u - u) + (p.y - y) * (p.v - v);
    cross += (p.x - x) * (p.v - v) - (p.y - y) * (p.u - u);
  }
  if (!std::isfinite(energy) || energy <= 0)
    return {};
  const double c = dot / energy, s = cross / energy;
  return Model{c, s, u - c * x + s * y, v - s * x - c * y};
}
bool spread(std::span<const Track> ps, double regionArea) {
  double minx = ps[0].x, maxx = minx, miny = ps[0].y, maxy = miny, x = 0, y = 0;
  for (auto p : ps) {
    minx = std::min(minx, p.x);
    maxx = std::max(maxx, p.x);
    miny = std::min(miny, p.y);
    maxy = std::max(maxy, p.y);
    x += p.x;
    y += p.y;
  }
  if ((maxx - minx) * (maxy - miny) < regionArea * .3)
    return false;
  x /= ps.size();
  y /= ps.size();
  double xx = 0, yy = 0, xy = 0;
  for (auto p : ps) {
    xx += (p.x - x) * (p.x - x);
    yy += (p.y - y) * (p.y - y);
    xy += (p.x - x) * (p.y - y);
  }
  return xx * yy - xy * xy > .01 * (xx + yy) * (xx + yy);
}
struct Similarity {
  Model model;
  std::vector<Track> inliers;
};
std::optional<Similarity> similarity(std::span<const Track> ps, double area, double tolerance, int required) {
  std::vector<Track> best;
  const int n = static_cast<int>(ps.size()), stride = std::max(1, static_cast<int>(std::ceil(n / 8.0)));
  for (int i = 0; i < std::min(8, n); ++i)
    for (int j = i + 1; j < n; j += stride) {
      const auto a = ps[i], b = ps[j];
      const double dx = b.x - a.x, dy = b.y - a.y, energy = dx * dx + dy * dy;
      if (energy < tolerance * tolerance * 4)
        continue;
      const double c = ((b.u - a.u) * dx + (b.v - a.v) * dy) / energy,
                   s = ((b.v - a.v) * dx - (b.u - a.u) * dy) / energy;
      const Model m{c, s, a.u - c * a.x + s * a.y, a.v - s * a.x - c * a.y};
      if (!(std::hypot(c, s) >= .5 && std::hypot(c, s) <= 2))
        continue;
      std::vector<Track> inliers;
      for (auto p : ps)
        if (residual(p, m) <= tolerance)
          inliers.push_back(p);
      if (inliers.size() > best.size())
        best = std::move(inliers);
    }
  if (best.size() < required)
    return {};
  auto m = leastSquares(best);
  if (!m)
    return {};
  std::vector<Track> inliers;
  for (auto p : ps)
    if (residual(p, *m) <= tolerance)
      inliers.push_back(p);
  if (inliers.size() < required || !spread(inliers, area))
    return {};
  auto refined = leastSquares(inliers);
  if (!refined)
    return {};
  if (std::any_of(inliers.begin(), inliers.end(), [&](auto p) { return residual(p, *refined) > tolerance; }) ||
      !(std::hypot((*refined)[0], (*refined)[1]) >= .5 && std::hypot((*refined)[0], (*refined)[1]) <= 2))
    return {};
  return Similarity{*refined, std::move(inliers)};
}
std::optional<std::array<double, 8>> dlt(std::span<const Track> ps) {
  if (ps.size() < 8)
    return {};
  auto normalizer = [&](bool target) -> std::optional<std::array<double, 3>> {
    double x = 0, y = 0;
    for (auto p : ps) {
      x += target ? p.u : p.x;
      y += target ? p.v : p.y;
    }
    x /= ps.size();
    y /= ps.size();
    double mean = 0;
    for (auto p : ps)
      mean += std::hypot((target ? p.u : p.x) - x, (target ? p.v : p.y) - y);
    mean /= ps.size();
    if (!(mean > 0) || !std::isfinite(mean))
      return {};
    return std::array<double, 3>{x, y, std::sqrt(2.0) / mean};
  };
  auto a = normalizer(false), b = normalizer(true);
  if (!a || !b)
    return {};
  std::array<std::array<double, 9>, 8> m{};
  for (auto p : ps) {
    const double x = (p.x - (*a)[0]) * (*a)[2], y = (p.y - (*a)[1]) * (*a)[2], u = (p.u - (*b)[0]) * (*b)[2],
                 v = (p.v - (*b)[1]) * (*b)[2];
    for (const auto &row : {std::array<double, 9>{x, y, 1, 0, 0, 0, -x * u, -y * u, u},
                            std::array<double, 9>{0, 0, 0, x, y, 1, -x * v, -y * v, v}})
      for (int i = 0; i < 8; ++i)
        for (int j = 0; j < 9; ++j)
          m[i][j] += row[i] * row[j];
  }
  for (int col = 0; col < 8; ++col) {
    int pivot = col;
    for (int i = col + 1; i < 8; ++i)
      if (std::abs(m[i][col]) > std::abs(m[pivot][col]))
        pivot = i;
    if (std::abs(m[pivot][col]) < 1e-12)
      return {};
    std::swap(m[col], m[pivot]);
    for (int row = 0; row < 8; ++row)
      if (row != col) {
        const double f = m[row][col] / m[col][col];
        if (f != 0)
          for (int j = col; j < 9; ++j)
            m[row][j] -= f * m[col][j];
      }
  }
  std::array<double, 8> n{};
  for (int i = 0; i < 8; ++i)
    n[i] = m[i][8] / m[i][i];
  const Matrix hn{n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], 1};
  const Matrix an{(*a)[2], 0, -(*a)[2] * (*a)[0], 0, (*a)[2], -(*a)[2] * (*a)[1], 0, 0, 1};
  const Matrix bi{1 / (*b)[2], 0, (*b)[0], 0, 1 / (*b)[2], (*b)[1], 0, 0, 1};
  const auto h = multiply(bi, multiply(hn, an));
  if (std::abs(h[8]) < 1e-12)
    return {};
  for (int i = 0; i < 8; ++i) {
    n[i] = h[i] / h[8];
    if (!std::isfinite(n[i]))
      return {};
  }
  return n;
}
double projected(Track p, const std::array<double, 8> &h) {
  const double w = h[6] * p.x + h[7] * p.y + 1;
  return std::hypot((h[0] * p.x + h[1] * p.y + h[2]) / w - p.u, (h[3] * p.x + h[4] * p.y + h[5]) / w - p.v);
}
std::optional<Transform> homography(std::span<const Track> ps, std::span<const Track> seed, int width, int height,
                                    double area, double tolerance, int required) {
  auto first = dlt(seed);
  if (!first)
    return {};
  std::vector<Track> inliers;
  for (auto p : ps)
    if (projected(p, *first) <= tolerance)
      inliers.push_back(p);
  if (inliers.size() < required || !spread(inliers, area))
    return {};
  auto h = dlt(inliers);
  if (!h)
    return {};
  for (auto p : inliers)
    if (projected(p, *h) > tolerance)
      return {};
  const double w = width, v = height;
  const Transform t{(*h)[0],     (*h)[1] * v / w, (*h)[3] * w / v, (*h)[4],
                    (*h)[2] / w, (*h)[5] / v,     (*h)[6] * w,     (*h)[7] * v};
  for (double c : {1.0, 1 + t.px, 1 + t.py, 1 + t.px + t.py})
    if (!(c >= .9 && c <= 1.1))
      return {};
  const double scale = std::sqrt(std::abs(t.xx * t.yy - t.xy * t.yx));
  if (!(scale >= .5 && scale <= 2))
    return {};
  return t;
}
std::optional<int64_t> correlated(int64_t t, ClockCorrelation c) {
  const auto d = c.gyroMinusCameraNs;
  if ((d > 0 && t > std::numeric_limits<int64_t>::max() - d) || (d < 0 && t < std::numeric_limits<int64_t>::min() - d))
    return {};
  return t + d;
}
} // namespace
std::optional<Quad> Transform::warp(const Quad &q) const {
  auto point = [&](Point p) -> std::optional<Point> {
    const double w = px * p.x + py * p.y + 1;
    if (!(w > 0))
      return {};
    const double x = (xx * p.x + xy * p.y + tx) / w, y = (yx * p.x + yy * p.y + ty) / w;
    if (!std::isfinite(x) || !std::isfinite(y))
      return {};
    return Point{x, y};
  };
  auto a = point(q[0]), b = point(q[1]), c = point(q[2]), d = point(q[3]);
  if (!a || !b || !c || !d)
    return {};
  return Quad{*a, *b, *c, *d};
}
std::optional<Transform> fit(std::span<const Track> ps, int width, int height, double area, double tolerance) {
  if (ps.size() < 8 || width <= 0 || height <= 0 || !(area > 0) || !(tolerance > 0))
    return {};
  for (auto p : ps)
    if (!std::isfinite(p.x) || !std::isfinite(p.y) || !std::isfinite(p.u) || !std::isfinite(p.v))
      return {};
  const int required = std::max(8, static_cast<int>(std::ceil(ps.size() * .7)));
  if (auto seed = similarity(ps, area, tolerance * 4, required))
    if (auto h = homography(ps, seed->inliers, width, height, area, tolerance, required))
      return h;
  auto sim = similarity(ps, area, tolerance, required);
  if (!sim)
    return {};
  const auto &m = sim->model;
  return Transform{m[0], -m[1] * height / width, m[1] * width / height, m[0], m[2] / width, m[3] / height};
}
void FlowHistory::add(const Estimate &e) {
  if (e.status != Status::measured || !e.transform || e.toNs <= e.fromNs) {
    clear();
    return;
  }
  if (!steps_.empty() && steps_.back().to != e.fromNs)
    clear();
  steps_.push_back({e.fromNs, e.toNs, *e.transform});
  while (steps_.size() > 32 || (!steps_.empty() && uint64_t(e.toNs) - uint64_t(steps_.front().from) > 500000000))
    steps_.pop_front();
}
std::optional<Quad> FlowHistory::warp(const Quad &q, int64_t from, int64_t to) const {
  if (from == to)
    return q;
  if (from > to)
    return {};
  int64_t time = from;
  std::optional<Quad> result(q);
  for (const auto &step : steps_) {
    if (step.to <= time)
      continue;
    if (step.from != time || step.to > to)
      return {};
    const auto next = step.transform.warp(*result);
    if (!next)
      return {};
    result.emplace(*next);
    time = step.to;
    if (time == to)
      return result;
  }
  return {};
}
std::optional<Matrix> rotation(std::span<const GyroSample> samples, int64_t from, int64_t to,
                               std::optional<ClockCorrelation> clock) {
  if (!clock || samples.empty())
    return {};
  auto a = correlated(from, *clock), b = correlated(to, *clock);
  if (!a || !b)
    return {};
  if (*b < *a) {
    auto r = rotation(samples, to, from, clock);
    return r ? std::optional<Matrix>(transpose(*r)) : std::nullopt;
  }
  if (samples.front().timeNs > *a ||
      (*b > samples.back().timeNs && uint64_t(*b) - uint64_t(samples.back().timeNs) > 50000000))
    return {};
  auto result = identity();
  auto t = *a;
  for (size_t i = 0; i < samples.size() && t < *b; ++i) {
    const auto end = i + 1 < samples.size() ? std::min(samples[i + 1].timeNs, *b) : *b;
    if (end <= t)
      continue;
    const double dt = static_cast<double>(uint64_t(end) - uint64_t(t)) * 1e-9;
    result = multiply(result, exponential(samples[i].x * dt, samples[i].y * dt, samples[i].z * dt));
    t = end;
  }
  return result;
}
std::optional<double> peakRate(std::span<const GyroSample> samples, int64_t from, int64_t to,
                               std::optional<ClockCorrelation> clock) {
  if (!clock)
    return {};
  auto a = correlated(from, *clock), b = correlated(to, *clock);
  if (!a || !b)
    return {};
  std::optional<double> peak;
  for (auto it = samples.rbegin(); it != samples.rend(); ++it) {
    if (it->timeNs > *b)
      continue;
    if (it->timeNs < *a)
      break;
    const double r = std::sqrt(it->x * it->x + it->y * it->y + it->z * it->z);
    if (!peak || r > *peak)
      peak = r;
  }
  return peak;
}
FrameGeometry frameGeometry(double focal, int aw, int ah, int sensor, int bw, int bh, Crop crop, int degrees) {
  const double scale = std::max(double(bw) / aw, double(bh) / ah), x = bw / 2.0 - crop.left, y = bh / 2.0 - crop.top;
  const bool turned = degrees % 180 != 0;
  const double cx = degrees == 90 ? crop.height - y : degrees == 180 ? crop.width - x : degrees == 270 ? y : x;
  const double cy = degrees == 90 ? x : degrees == 180 ? crop.height - y : degrees == 270 ? crop.width - x : y;
  return {int(turned ? crop.height : crop.width), int(turned ? crop.width : crop.height), focal * scale, cx, cy,
          ((degrees - sensor) % 360 + 360) % 360};
}
std::optional<Quad> rotationWarp(const Quad &q, FrameGeometry f, const Matrix &device) {
  const double a = f.axisRotationDegrees * std::numbers::pi / 180, c = std::cos(a), s = std::sin(a);
  const auto axes = multiply({c, -s, 0, s, c, 0, 0, 0, 1}, {1, 0, 0, 0, -1, 0, 0, 0, -1});
  const auto r = multiply(multiply(axes, transpose(device)), transpose(axes));
  auto point = [&](Point p) -> std::optional<Point> {
    const double x = (p.x * f.width - f.centerX) / f.focalPx, y = (p.y * f.height - f.centerY) / f.focalPx,
                 z = r[6] * x + r[7] * y + r[8];
    if (z <= 1e-6)
      return {};
    return Point{(f.focalPx * (r[0] * x + r[1] * y + r[2]) / z + f.centerX) / f.width,
                 (f.focalPx * (r[3] * x + r[4] * y + r[5]) / z + f.centerY) / f.height};
  };
  auto p = point(q[0]), b = point(q[1]), d = point(q[2]), e = point(q[3]);
  if (!p || !b || !d || !e)
    return {};
  return Quad{*p, *b, *d, *e};
}
std::optional<double> gyroBlur(std::span<const GyroSample> samples, double focal, int width, int64_t from, int64_t to,
                               int64_t exposure, std::optional<ClockCorrelation> clock) {
  auto rate = peakRate(samples, from, to, clock);
  if (!rate)
    return {};
  return *rate * exposure * 1e-9 * focal / width;
}
std::optional<double> flowBlur(std::optional<double> speed, int width, int64_t exposure) {
  if (!speed)
    return {};
  return *speed * exposure * 1e-9 / width;
}
std::optional<GateMotion> gateMotion(std::optional<double> speed, int width, int lost, int64_t exposure,
                                     std::optional<double> gyro) {
  if (auto blur = flowBlur(speed, width, exposure))
    return GateMotion{*blur, true, lost};
  if (gyro)
    return GateMotion{*gyro, false, 0};
  return {};
}
} // namespace document_scanner::motion
