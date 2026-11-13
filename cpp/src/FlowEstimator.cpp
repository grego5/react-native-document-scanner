#include <document_scanner/Motion.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>
#include <limits>
#include <vector>

namespace document_scanner::motion {
struct FlowEstimator::State {
  int maxEdge, maxFeatures;
  int64_t maxGap;
  // Raw luma selects features; its light-normalised copy is tracked.
  cv::Mat previous, current, previousTracked, currentTracked, mask, level, mean, spread;
  bool hasPrevious = false;
  int64_t previousNs = 0;
  std::array<int64_t, 7> geometry{};
  State(int edge, int features, int64_t gap) : maxEdge(edge), maxFeatures(features), maxGap(gap) {}

  std::array<double, 4> selectRegion(const std::optional<Corners> &region, int width, int height, int rotation) {
    mask = cv::Mat::zeros(height, width, CV_8UC1);
    if (region) {
      double cx = 0, cy = 0;
      for (auto p : *region) {
        cx += p.x;
        cy += p.y;
      }
      cx /= 4;
      cy /= 4;
      std::vector<cv::Point> polygon;
      double left = INFINITY, top = INFINITY, right = -INFINITY, bottom = -INFINITY;
      for (auto p : *region) {
        const double x = cx + (p.x - cx) * .9, y = cy + (p.y - cy) * .9;
        const double bx = (rotation == 90 ? y : rotation == 180 ? 1 - x : rotation == 270 ? 1 - y : x) * width;
        const double by = (rotation == 90 ? 1 - x : rotation == 180 ? 1 - y : rotation == 270 ? x : y) * height;
        // MatOfPoint stores integer points by narrowing to int, not cvRound.
        polygon.emplace_back(static_cast<int>(bx), static_cast<int>(by));
        left = std::min(left, bx);
        top = std::min(top, by);
        right = std::max(right, bx);
        bottom = std::max(bottom, by);
      }
      if (cv::contourArea(polygon) >= .05 * width * height) {
        cv::fillConvexPoly(mask, polygon, cv::Scalar(255));
        return {left, top, right, bottom};
      }
      return {0, 0, double(width), double(height)};
    }
    const double left = width * (1 - .6) / 2, top = height * (1 - .6) / 2;
    cv::rectangle(mask, {static_cast<int>(left), static_cast<int>(top)},
                  {static_cast<int>(width - left), static_cast<int>(height - top)}, cv::Scalar(255), cv::FILLED);
    return {left, top, width - left, height - top};
  }
  Estimate track(int bufferWidth, int cropWidth, int cropHeight, int rotation, int64_t gap, int64_t sensor,
                 const std::optional<Corners> &region, bool fitTransform) {
    auto unknown = [&](Status status, double confidence = 0, int tracks = 0) {
      return Estimate{status, {}, bufferWidth, previousNs, sensor, confidence, tracks};
    };
    const int width = previous.cols, height = previous.rows;
    const auto bounds = selectRegion(region, width, height, rotation);
    std::vector<cv::Point2f> from;
    cv::goodFeaturesToTrack(previous, from, maxFeatures, .01, std::max(width, height) * .05, mask);
    const int detected = static_cast<int>(from.size());
    if (detected < 8)
      return unknown(Status::tooFewFeatures);
    std::vector<cv::Point2f> to, back;
    std::vector<unsigned char> ok, backOk;
    std::vector<float> error, backError;
    const cv::TermCriteria criteria(cv::TermCriteria::COUNT + cv::TermCriteria::EPS, 20, .03);
    cv::calcOpticalFlowPyrLK(previousTracked, currentTracked, from, to, ok, error, {15, 15}, 3, criteria);
    cv::calcOpticalFlowPyrLK(currentTracked, previousTracked, to, back, backOk, backError, {15, 15}, 3, criteria);
    const double sx = double(cropWidth) / width, sy = double(cropHeight) / height, seconds = gap / 1e9;
    std::vector<double> speeds;
    std::array<int, 9> detectedIn{};
    std::array<std::vector<double>, 9> speedsIn;
    std::vector<Track> tracks;
    auto upright = [&](double x, double y) -> std::array<double, 2> {
      if (rotation == 90)
        return {cropHeight - y, x};
      if (rotation == 180)
        return {cropWidth - x, cropHeight - y};
      if (rotation == 270)
        return {y, cropWidth - x};
      return {x, y};
    };
    for (int i = 0; i < detected; ++i) {
      const int col = std::clamp(int((from[i].x - bounds[0]) * 3 / (bounds[2] - bounds[0])), 0, 2);
      const int row = std::clamp(int((from[i].y - bounds[1]) * 3 / (bounds[3] - bounds[1])), 0, 2),
                cell = row * 3 + col;
      ++detectedIn[cell];
      if (!ok[i] || !backOk[i])
        continue;
      if (std::hypot(double(back[i].x) - from[i].x, double(back[i].y) - from[i].y) > 1)
        continue;
      const double speed = std::hypot((double(to[i].x) - from[i].x) * sx, (double(to[i].y) - from[i].y) * sy) / seconds;
      speeds.push_back(speed);
      speedsIn[cell].push_back(speed);
      if (fitTransform) {
        const auto a = upright(from[i].x * sx, from[i].y * sy), b = upright(to[i].x * sx, to[i].y * sy);
        tracks.push_back({a[0], a[1], b[0], b[1]});
      }
    }
    const double confidence = double(speeds.size()) / detected;
    const int valid = static_cast<int>(speeds.size());
    if (valid < 8)
      return unknown(Status::trackingFailed, confidence, valid);
    if (std::count_if(speedsIn.begin(), speedsIn.end(), [](const auto &s) { return !s.empty(); }) < 3)
      return unknown(Status::poorCoverage, confidence, valid);
    int lost = 0;
    for (int i = 0; i < 9; ++i)
      if (detectedIn[i] >= 3 && speedsIn[i].size() < detectedIn[i] * .5)
        ++lost;
    std::sort(speeds.begin(), speeds.end());
    double speed = speeds[std::clamp(int(std::ceil(.9 * speeds.size())) - 1, 0, valid - 1)];
    for (auto &s : speedsIn)
      if (s.size() >= 2) {
        std::sort(s.begin(), s.end());
        const size_t n = s.size();
        speed = std::max(speed, n % 2 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2);
      }
    Estimate result{Status::measured, speed, bufferWidth, previousNs, sensor, confidence, valid, lost};
    if (fitTransform) {
      const auto start = std::chrono::steady_clock::now();
      const bool turned = rotation % 180 != 0;
      result.transform = fit(tracks, turned ? cropHeight : cropWidth, turned ? cropWidth : cropHeight,
                             (bounds[2] - bounds[0]) * sx * (bounds[3] - bounds[1]) * sy, 2 * std::max(sx, sy));
      result.transformProcessingNs =
          std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
    }
    return result;
  }
};
FlowEstimator::FlowEstimator(int edge, int features, int64_t gap)
    : state_(std::make_unique<State>(edge, features, gap)) {
  if (edge <= 0 || features <= 0 || gap <= 0)
    throw std::invalid_argument("Invalid flow configuration.");
}
FlowEstimator::~FlowEstimator() = default;
void FlowEstimator::reset() { state_->hasPrevious = false; }
Estimate FlowEstimator::update(const PixelLease &lease, int64_t sensor, const std::optional<Corners> &region,
                               bool fitTransform) {
  auto &s = *state_;
  const bool hadPrevious = s.hasPrevious;
  s.hasPrevious = false;
  const auto &view = lease.view();
  const auto &l = view.layout();
  if (l.size.width > uint32_t(std::numeric_limits<int>::max()) ||
      l.size.height > uint32_t(std::numeric_limits<int>::max()) ||
      (l.format != PixelFormat::rgba8 && l.format != PixelFormat::bgra8) ||
      (l.orientation != Orientation::upright && l.orientation != Orientation::rotate90 &&
       l.orientation != Orientation::rotate180 && l.orientation != Orientation::rotate270))
    throw std::invalid_argument("Expected RGBA or BGRA flow pixels and a camera rotation.");
  if (region)
    for (auto p : *region)
      if (!std::isfinite(p.x) || !std::isfinite(p.y) || std::abs(p.x) > 1000 || std::abs(p.y) > 1000)
        throw std::invalid_argument("Invalid flow region.");
  const int rotation = l.orientation == Orientation::rotate90    ? 90
                       : l.orientation == Orientation::rotate180 ? 180
                       : l.orientation == Orientation::rotate270 ? 270
                                                                 : 0;
  const auto &crop = l.crop;
  const std::array<int64_t, 7> geometry{l.size.width, l.size.height, crop.left, crop.top,
                                        crop.width,   crop.height,   rotation};
  const bool tracking = hadPrevious && geometry == s.geometry && sensor > s.previousNs &&
                        uint64_t(sensor) - uint64_t(s.previousNs) <= uint64_t(s.maxGap);
  s.geometry = geometry;
  cv::Mat full(crop.height, crop.width, CV_8UC1);
  const auto bytes = view.bytes();
  // The same luma weights for either channel order.
  const int red = l.format == PixelFormat::bgra8 ? 2 : 0, blue = 2 - red;
  for (uint32_t y = 0; y < crop.height; ++y)
    for (uint32_t x = 0; x < crop.width; ++x) {
      const auto offset = (crop.top + y) * l.rowStride + (crop.left + x) * l.pixelStride;
      const auto *p = reinterpret_cast<const uint8_t *>(bytes.data() + offset);
      full.at<uint8_t>(y, x) = static_cast<uint8_t>((77 * p[red] + 150 * p[1] + 29 * p[blue]) >> 8);
    }
  const double scale = std::min(1.0, double(s.maxEdge) / std::max(crop.width, crop.height));
  const int w = std::max(1, int(std::floor(crop.width * scale + .5))),
            h = std::max(1, int(std::floor(crop.height * scale + .5)));
  if (w == crop.width && h == crop.height)
    full.copyTo(s.current);
  else
    cv::resize(full, s.current, {w, h}, 0, 0, cv::INTER_AREA);
  // Track texture, not light: removing each neighbourhood's mean and spread cancels moving shadows, flicker and
  // exposure changes, which LK would read as motion. Features are still chosen on raw luma, so flat or noisy areas do
  // not gain features; the spread offset bounds the gain there.
  s.current.convertTo(s.level, CV_32F);
  cv::GaussianBlur(s.level, s.mean, {0, 0}, 2);
  cv::subtract(s.level, s.mean, s.level);
  cv::multiply(s.level, s.level, s.spread);
  cv::GaussianBlur(s.spread, s.spread, {0, 0}, 2);
  cv::sqrt(s.spread, s.spread);
  cv::add(s.spread, cv::Scalar(8), s.spread);
  cv::divide(s.level, s.spread, s.level, 40);
  s.level.convertTo(s.currentTracked, CV_8U, 1, 128);
  auto result = tracking ? s.track(l.size.width, crop.width, crop.height, rotation, sensor - s.previousNs, sensor,
                                   region, fitTransform)
                         : Estimate{Status::noReference, {}, int(l.size.width), sensor, sensor};
  std::swap(s.previous, s.current);
  std::swap(s.previousTracked, s.currentTracked);
  s.previousNs = sensor;
  s.hasPrevious = true;
  return result;
}
} // namespace document_scanner::motion
