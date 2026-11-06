#include "internal/PageEdges.hpp"
#include "internal/Kernels.hpp"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <vector>

namespace document_scanner::internal {
double EdgeCurve::at(double t) const {
  return t * (1 - t) * (coefficients[0] + t * (coefficients[1] + t * coefficients[2]));
}
namespace {
constexpr double detectionLongSide = 800, bandFraction = .06, blurSigma = 1.2;
constexpr int minBand = 8, stepWindow = 3, minAgreeing = 16;
constexpr double minStep = 10, fitTolerance = 1.5, endZone = .12, endTolerance = 4, minBend = fitTolerance;
using Offsets = std::array<std::optional<int>, 31>;
using Coefficients = std::array<double, 3>;
const std::array<double, 31> stations = [] {
  std::array<double, 31> values{};
  for (size_t i = 0; i < values.size(); ++i)
    values[i] = .05 + .9 * i / 30;
  return values;
}();
const std::array<std::array<double, 31>, 3> terms = [] {
  std::array<std::array<double, 31>, 3> values{};
  for (size_t p = 0; p < 3; ++p)
    for (size_t i = 0; i < 31; ++i)
      values[p][i] = stations[i] * (1 - stations[i]) * std::pow(stations[i], double(p));
  return values;
}();
int rounded(double value) { return static_cast<int>(std::floor(value + .5)); }
std::optional<int> strongestStep(const unsigned char *start, ptrdiff_t outward, int band) {
  const int length = 2 * band + 1;
  std::array<std::vector<int>, 3> sums;
  for (auto &channel : sums)
    channel.resize(length + 1);
  for (int i = 0; i < length; ++i) {
    const auto *pixel = start + (i - band) * outward;
    for (size_t c = 0; c < 3; ++c)
      sums[c][i + 1] = sums[c][i] + pixel[c];
  }
  std::optional<int> best;
  double bestStep = minStep * stepWindow;
  for (int i = stepWindow; i < length - stepWindow; ++i) {
    double squared = 0;
    for (size_t c = 0; c < 3; ++c) {
      const int inside = sums[c][i] - sums[c][i - stepWindow];
      const int outside = sums[c][i + 1 + stepWindow] - sums[c][i + 1];
      squared += double((outside - inside) * (outside - inside));
    }
    const double step = std::sqrt(squared);
    if (step >= bestStep) {
      bestStep = step;
      best = i - band;
    }
  }
  return best;
}
double curveAt(const Coefficients &curve, int station) {
  double sum = 0;
  for (size_t i = 0; i < 3; ++i)
    sum += terms[i][station] * curve[i];
  return sum;
}
std::optional<Coefficients> solve(const std::vector<int> &rows, const Offsets &offsets) {
  std::array<std::array<double, 4>, 3> m{};
  for (size_t r = 0; r < 3; ++r)
    for (size_t c = 0; c < 4; ++c)
      for (int i : rows)
        m[r][c] += terms[r][i] * (c < 3 ? terms[c][i] : double(*offsets[i]));
  for (size_t col = 0; col < 3; ++col) {
    size_t pivot = col;
    for (size_t r = col + 1; r < 3; ++r)
      if (std::abs(m[r][col]) > std::abs(m[pivot][col]))
        pivot = r;
    if (std::abs(m[pivot][col]) < 1e-12)
      return std::nullopt;
    std::swap(m[col], m[pivot]);
    for (size_t r = 0; r < 3; ++r)
      if (r != col) {
        const double f = m[r][col] / m[col][col];
        for (size_t c = col; c < 4; ++c)
          m[r][c] -= f * m[col][c];
      }
  }
  return Coefficients{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
}
std::optional<EdgeCurve> fit(const Offsets &offsets, int band) {
  std::vector<int> found;
  bool meetsStart = false, meetsEnd = false;
  for (size_t i = 0; i < 31; ++i)
    if (offsets[i]) {
      found.push_back(int(i));
      if (std::abs(*offsets[i]) <= endTolerance) {
        if (stations[i] < endZone)
          meetsStart = true;
        if (stations[i] > 1 - endZone)
          meetsEnd = true;
      }
    }
  if (found.size() < minAgreeing || !meetsStart || !meetsEnd)
    return std::nullopt;
  std::vector<double> t, basis, y, q;
  std::array<std::vector<int>, 3> thirds;
  for (int i : found) {
    const int n = int(t.size());
    t.push_back(stations[i]);
    basis.push_back(terms[0][i]);
    y.push_back(*offsets[i]);
    q.push_back(y.back() / basis.back());
    thirds[std::min(2, int(3 * t.back()))].push_back(n);
  }
  const auto agrees = [&](int m, int i, int j, double d1, double d2) {
    return std::abs(basis[m] * (q[i] + (t[m] - t[i]) * (d1 + (t[m] - t[j]) * d2)) - y[m]) <= fitTolerance;
  };
  int bestCount = 0, bestI = 0, bestJ = 0;
  double bestD1 = 0, bestD2 = 0;
  for (int i : thirds[0])
    for (int j : thirds[1]) {
      const double d1 = (q[j] - q[i]) / (t[j] - t[i]);
      for (int k : thirds[2]) {
        const double d2 = ((q[k] - q[j]) / (t[k] - t[j]) - d1) / (t[k] - t[i]);
        int count = 0;
        for (size_t m = 0; m < t.size(); ++m)
          if (agrees(int(m), i, j, d1, d2))
            ++count;
        if (count > bestCount) {
          bestCount = count;
          bestI = i;
          bestJ = j;
          bestD1 = d1;
          bestD2 = d2;
        }
      }
    }
  if (bestCount < minAgreeing)
    return std::nullopt;
  std::vector<int> inliers;
  for (size_t m = 0; m < t.size(); ++m)
    if (agrees(int(m), bestI, bestJ, bestD1, bestD2))
      inliers.push_back(found[m]);
  const auto initial = solve(inliers, offsets);
  if (!initial)
    return std::nullopt;
  std::vector<int> agree;
  bool reachesStart = false, reachesEnd = false;
  for (int i : found)
    if (std::abs(curveAt(*initial, i) - *offsets[i]) <= fitTolerance) {
      agree.push_back(i);
      if (stations[i] < endZone)
        reachesStart = true;
      if (stations[i] > 1 - endZone)
        reachesEnd = true;
    }
  if (agree.size() < minAgreeing || !reachesStart || !reachesEnd)
    return std::nullopt;
  const auto coefficients = solve(agree, offsets);
  if (!coefficients)
    return std::nullopt;
  EdgeCurve curve{*coefficients};
  double largest = 0;
  for (int i = 0; i <= 20; ++i)
    largest = std::max(largest, std::abs(curve.at(i / 20.0)));
  return largest >= minBend && largest <= band - stepWindow ? std::optional(curve) : std::nullopt;
}
void keepInside(PageEdges &edges, const cv::Mat &homography, int width, int height, int sourceWidth, int sourceHeight) {
  cv::Mat inverse;
  cv::invert(homography, inverse);
  const double *h = inverse.ptr<double>();
  for (size_t side = 0; side < 4; ++side)
    if (edges[side]) {
      for (int i = 0; i <= 40; ++i) {
        const double t = i / 40.0, o = edges[side]->at(t);
        const double x = side == 0 || side == 2 ? t * width : side == 1 ? width + o : -o;
        const double y = side == 1 || side == 3 ? t * height : side == 0 ? -o : height + o;
        const double d = h[6] * x + h[7] * y + h[8];
        const double sx = (h[0] * x + h[1] * y + h[2]) / d, sy = (h[3] * x + h[4] * y + h[5]) / d;
        if (!(sx >= 0 && sx <= sourceWidth - 1.0 && sy >= 0 && sy <= sourceHeight - 1.0)) {
          edges[side].reset();
          break;
        }
      }
    }
}
} // namespace
PageEdges findPageEdges(const cv::Mat &source, const cv::Mat &homography, int width, int height) {
  const double scale = detectionLongSide / std::max(width, height);
  const int w = std::max(1, rounded(width * scale)), h = std::max(1, rounded(height * scale));
  const int bandX = std::max(minBand, rounded(bandFraction * w)), bandY = std::max(minBand, rounded(bandFraction * h));
  cv::Mat toCanvas(3, 3, CV_64F), canvas;
  const auto *m = homography.ptr<double>();
  auto *out = toCanvas.ptr<double>();
  for (size_t i = 0; i < 9; ++i)
    out[i] = i >= 6 ? m[i] : m[i] * scale + m[6 + i % 3] * (i < 3 ? bandX : bandY);
  cv::warpPerspective(source, canvas, toCanvas, {w + 2 * bandX + 1, h + 2 * bandY + 1}, cv::INTER_LINEAR,
                      cv::BORDER_REPLICATE);
  cv::GaussianBlur(canvas, canvas, {0, 0}, blurSigma);
  PageEdges edges;
  for (size_t side = 0; side < 4; ++side) {
    const bool alongWidth = side == 0 || side == 2;
    const int band = alongWidth ? bandY : bandX;
    Offsets offsets;
    for (size_t i = 0; i < 31; ++i) {
      const int x = alongWidth ? rounded(bandX + stations[i] * w) : side == 1 ? bandX + w : bandX;
      const int y = !alongWidth ? rounded(bandY + stations[i] * h) : side == 0 ? bandY : bandY + h;
      const ptrdiff_t outward = side == 0   ? -ptrdiff_t(canvas.step)
                                : side == 1 ? 4
                                : side == 2 ? ptrdiff_t(canvas.step)
                                            : -4;
      offsets[i] = strongestStep(canvas.ptr(y) + x * 4, outward, band);
    }
    edges[side] = fit(offsets, band);
    if (edges[side])
      for (double &c : edges[side]->coefficients)
        c /= scale;
  }
  keepInside(edges, homography, width, height, source.cols, source.rows);
  return edges;
}
void remapPageEdges(const cv::Mat &source, cv::Mat &into, const cv::Mat &inverse, const PageEdges &edges) {
  std::vector<float> left(into.rows), right(into.rows), top(into.cols), bottom(into.cols);
  for (int y = 0; y < into.rows; ++y) {
    const double t = double(y) / into.rows;
    left[y] = float(edges[3] ? edges[3]->at(t) : 0);
    right[y] = float(edges[1] ? edges[1]->at(t) : 0);
  }
  for (int x = 0; x < into.cols; ++x) {
    const double t = double(x) / into.cols;
    top[x] = float(edges[0] ? edges[0]->at(t) : 0);
    bottom[x] = float(edges[2] ? edges[2]->at(t) : 0);
  }
  remap(source, into, inverse.ptr<double>(), left.data(), right.data(), top.data(), bottom.data());
}
} // namespace document_scanner::internal
