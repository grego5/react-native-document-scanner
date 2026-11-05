#include <document_scanner/Motion.hpp>
#include <opencv2/imgproc.hpp>
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>

using namespace document_scanner;
namespace m = document_scanner::motion;
namespace {
void check(bool b, const char *message) {
  if (!b)
    throw std::runtime_error(message);
}
void near(double a, double b, double eps = 1e-9) {
  if (std::abs(a - b) > eps || !std::isfinite(a))
    throw std::runtime_error("Motion value mismatch: " + std::to_string(a) + " vs " + std::to_string(b));
}
Quad rect(double l = .2, double t = .2, double r = .8, double b = .8) {
  return {Point{l, t}, Point{r, t}, Point{r, b}, Point{l, b}};
}
std::vector<m::Track> grid(const std::function<m::Track(double, double)> &f) {
  std::vector<m::Track> out;
  for (int y = 0; y < 5; ++y)
    for (int x = 0; x < 5; ++x)
      out.push_back(f(60 + x * 50, 40 + y * 40));
  return out;
}
void fittedTransforms() {
  const double c = std::cos(.06) * 1.04, s = std::sin(.06) * 1.04;
  auto tracks = grid([&](double x, double y) { return m::Track{x, y, c * x - s * y + 7, s * x + c * y - 3}; });
  auto fit = m::fit(tracks, 320, 240, 32000, 1);
  check(bool(fit), "Similarity missing");
  const auto q = fit->warp(rect());
  check(bool(q), "Warp missing");
  near((*q)[0].x, (c * 64 - s * 48 + 7) / 320);
  near((*q)[0].y, (s * 64 + c * 48 - 3) / 240);
  auto perspective = [](double x, double y) {
    const double w = 1e-4 * x - 5e-5 * y + 1;
    return m::Track{x, y, (1.01 * x + .02 * y + 3) / w, (-.01 * x + y - 2) / w};
  };
  fit = m::fit(grid(perspective), 320, 240, 32000, 1);
  check(fit && fit->px != 0, "Tilt homography missing");
  const auto moved = fit->warp(rect());
  check(bool(moved), "Tilt warp missing");
  auto a = perspective(64, 48), b = perspective(256, 192);
  near((*moved)[0].x, a.u / 320, 1e-6);
  near((*moved)[0].y, a.v / 240, 1e-6);
  near((*moved)[2].x, b.u / 320, 1e-6);
  near((*moved)[2].y, b.v / 240, 1e-6);
  tracks = grid([](double x, double y) { return m::Track{x, y, x + 5, y - 2}; });
  fit = m::fit(tracks, 320, 240, 32000, 1);
  check(bool(fit), "Translation missing");
  near(fit->px, 0);
  near(fit->py, 0);
  for (int i : {1, 4, 9, 16, 23}) {
    tracks[i].u = 300;
    tracks[i].v = -100;
  }
  fit = m::fit(tracks, 320, 240, 32000, 1);
  check(bool(fit), "Outlier consensus missing");
  near(fit->tx, 5.0 / 320);
  near(fit->ty, -2.0 / 240);
  check(!m::fit(std::span<const m::Track>(tracks.data(), 7), 320, 240, 76800, 1), "Sparse fit accepted");
  check(!m::fit(tracks, 320, 240, 7680000, 1), "Narrow fit accepted");
  std::vector<m::Track> line;
  for (int i = 0; i <= 20; ++i)
    line.push_back({i * 10., i * 10., i * 10. + 5, i * 10.});
  check(!m::fit(line, 320, 240, 32000, 1), "Collinear fit accepted");
  tracks = grid([](double x, double y) { return m::Track{x, y, x + 5, y - 2}; });
  for (size_t i = 0; i < tracks.size(); ++i) {
    tracks[i].u += (i % 3) * 30;
    tracks[i].v += (i % 4) * 40;
  }
  check(!m::fit(tracks, 320, 240, 32000, 1), "Inconsistent fit accepted");
  check(!m::Transform{1, 0, 0, 1, 0, 0, -5, 0}.warp(rect()), "Horizon accepted");
  const m::Transform shift{1, 0, 0, 1, .02, -.01};
  const auto once = shift.warp(rect());
  const auto twice = shift.warp(*once);
  near((*twice)[0].x, .24);
}
void intervalHistory() {
  m::FlowHistory history;
  const m::Transform shift{1, 0, 0, 1, .02, -.01}, identity{1, 0, 0, 1, 0, 0};
  auto estimate = [](int64_t from, int64_t to, std::optional<m::Transform> t) {
    m::Estimate e{m::Status::measured, 0.0, 320, from * 1000000, to * 1000000, 1, 25};
    e.transform = t;
    return e;
  };
  history.add(estimate(0, 33, shift));
  history.add(estimate(33, 66, shift));
  const auto moved = history.warp(rect(), 0, 66000000);
  check(bool(moved), "History composition");
  near((*moved)[0].x, .24);
  check(!history.warp(rect(), 10000000, 66000000), "Partial interval accepted");
  check(!history.warp(rect(), 0, 100000000), "Missing interval accepted");
  check(!history.warp(rect(), 66000000, 0), "Reverse history accepted");
  history.add(estimate(66, 100, {}));
  check(!history.warp(rect(), 0, 66000000), "Unknown did not clear history");
  history.add(estimate(100, 133, shift));
  near((*history.warp(rect(), 100000000, 133000000))[0].x, .22);
  history.add(estimate(200, 233, shift));
  check(!history.warp(rect(), 100000000, 233000000), "Broken chain accepted");
  history.clear();
  check(!history.warp(rect(), 200000000, 233000000), "Clear retained history");
  for (int i = 0; i <= 20; ++i)
    history.add(estimate(i * 33, (i + 1) * 33, identity));
  check(!history.warp(rect(), 0, 693000000), "History time bound");
  auto last = history.warp(rect(), 660000000, 693000000);
  check(bool(last), "Recent history evicted");
  near((*last)[0].x, .2);
  history.clear();
  for (int i = 0; i < 40; ++i) {
    auto e = estimate(0, 0, identity);
    e.fromNs = i;
    e.toNs = i + 1;
    history.add(e);
  }
  check(!history.warp(rect(), 0, 40) && bool(history.warp(rect(), 8, 40)), "History count bound");
  check(bool(history.warp(rect(), 45, 45)), "Equal timestamp warp");
}
void gyroscope() {
  const std::optional<m::ClockCorrelation> same = m::ClockCorrelation{0};
  std::vector<m::GyroSample> samples;
  for (int i = 0; i <= 20; ++i)
    samples.push_back({i * 5000000LL, 0, 0, 2});
  const auto r = m::rotation(samples, 10000000, 60000000, same);
  check(bool(r), "Rotation missing");
  near(std::acos((*r)[0]), .1);
  near(std::asin((*r)[3]), .1);
  const auto inverse = m::rotation(samples, 60000000, 10000000, same);
  check(bool(inverse), "Inverse missing");
  for (int i = 0; i < 3; ++i)
    for (int j = 0; j < 3; ++j)
      near((*inverse)[i * 3 + j], (*r)[j * 3 + i], 1e-12);
  check(bool(m::rotation(samples, 90000000, 150000000, same)), "Extrapolation endpoint");
  check(!m::rotation(samples, 90000000, 150000001, same), "Extrapolation overrun");
  check(!m::rotation(samples, -1, 10000000, same), "Before history accepted");
  check(!m::rotation({}, 0, 0, same), "Empty history accepted");
  check(!m::rotation(samples, 10000000, 60000000, {}), "Unknown clocks accepted");
  const auto offset = m::rotation(samples, 0, 50000000, m::ClockCorrelation{10000000});
  check(bool(offset), "Clock offset missing");
  for (int i = 0; i < 9; ++i)
    near((*offset)[i], (*r)[i]);
  check(!m::rotation(samples, std::numeric_limits<int64_t>::max(), std::numeric_limits<int64_t>::max(),
                     m::ClockCorrelation{1}),
        "Clock overflow accepted");
  samples = {{0, 3, 0, 0}, {10000000, 0, .3, .4}, {20000000, .1, 0, 0}, {30000000, 0, 0, 9}};
  near(*m::peakRate(samples, 5000000, 25000000, same), .5, 1e-12);
  near(*m::peakRate(samples, 0, 30000000, same), 9, 1e-12);
  check(!m::peakRate(samples, 31000000, 40000000, same), "Outside samples measured");
  samples = {{0, 0, .6, .8}, {5000000, .1, 0, 0}};
  near(*m::gyroBlur(samples, 3000, 4000, 0, 5000000, 10000000, same), .0075, 1e-12);
  check(!m::gyroBlur(samples, 3000, 4000, 6000000, 9000000, 10000000, same), "Empty exposure measured");
  auto portrait = m::frameGeometry(2791.6665, 4000, 3000, 90, 640, 480, {0, 0, 640, 480}, 90);
  check(portrait.width == 480 && portrait.height == 640 && portrait.axisRotationDegrees == 0, "Portrait geometry");
  near(portrait.focalPx, 2791.6665 * .16);
  near(portrait.centerX, 240);
  near(portrait.centerY, 320);
  auto cropped = m::frameGeometry(2791.6665, 4000, 3000, 90, 640, 480, {80, 0, 480, 480}, 90);
  check(cropped.width == 480 && cropped.height == 480, "Crop size");
  near(cropped.centerX, 240);
  near(cropped.centerY, 240);
  auto landscape = m::frameGeometry(2791.6665, 4000, 3000, 90, 640, 480, {0, 0, 640, 480}, 0);
  check(landscape.axisRotationDegrees == 270, "Landscape axes");
  samples = {{0, .5, 0, 0}, {100000000, .5, 0, 0}};
  auto rotation = m::rotation(samples, 0, 100000000, same);
  const auto tiny = rect(.499, .499, .501, .501);
  auto moved = m::rotationWarp(tiny, portrait, *rotation);
  check(bool(moved), "Pitch warp");
  near(((*moved)[0].y + (*moved)[2].y - tiny[0].y - tiny[2].y) / 2 * portrait.height, portrait.focalPx * std::tan(.05),
       .05);
  near((*moved)[0].x, tiny[0].x, 1e-4);
  const auto turned = m::rotationWarp(tiny, landscape, *rotation);
  check(bool(turned), "Rotated pitch warp");
  near(std::abs(((*turned)[0].x + (*turned)[2].x - tiny[0].x - tiny[2].x) / 2 * landscape.width),
       landscape.focalPx * std::tan(.05), .05);
  near(((*turned)[0].y + (*turned)[2].y - tiny[0].y - tiny[2].y) / 2 * landscape.height, 0, .05);
  auto kept = m::rotationWarp(rect(), portrait, {1, 0, 0, 0, 1, 0, 0, 0, 1});
  check(bool(kept), "Still warp");
  near((*kept)[0].x, .2);
  near((*kept)[2].y, .8);
}
void blurGates() {
  constexpr int64_t exposure = 25000000;
  auto speed = [](double blur) { return blur * 320 / (exposure * 1e-9); };
  for (double b : {.0031, .0097}) {
    auto g = m::gateMotion(speed(b), 320, 0, exposure, .0005);
    check(g && g->fromFlow && g->blur > .002, "Moving flow gate");
    near(g->blur, b, 1e-12);
  }
  auto g = m::gateMotion(speed(.0006), 320, 0, exposure, .0187);
  check(g && g->fromFlow && g->blur <= .002, "Still flow gate");
  g = m::gateMotion({}, 320, 0, exposure, .0097);
  check(g && !g->fromFlow, "Gyro fallback");
  near(g->blur, .0097);
  g = m::gateMotion(speed(.0006), 320, 1, exposure, .0005);
  check(g && g->fromFlow && g->lostRegions == 1 && g->blur <= .002, "Lost region gate");
  check(!m::gateMotion({}, 320, 0, exposure, {}), "Unknown classified still");
  near(*m::flowBlur(speed(.001), 320, 2 * exposure), .002, 1e-12);
}
struct Image {
  std::shared_ptr<std::vector<std::byte>> bytes;
  uint32_t stride = 640 * 4;
  PixelLease lease(Orientation o = Orientation::upright, Crop crop = {0, 0, 640, 480}) const {
    return PixelLease(bytes, *bytes, {PixelFormat::rgba8, {640, 480}, stride, 4, crop, o});
  }
};
cv::Mat page() {
  cv::Mat out(960, 1280, CV_8UC1, cv::Scalar(225));
  uint32_t random = 7;
  auto next = [&](int n) {
    random = random * 1664525 + 1013904223;
    return int((random >> 8) % n);
  };
  for (int y = 12; y < 940; y += 22 + next(6))
    for (int x = 10; x < 1240;) {
      const int word = 12 + next(48), h = 6 + next(8);
      cv::rectangle(out, {x, y}, {x + word, y + h}, cv::Scalar(30 + next(60)), cv::FILLED);
      x += word + 6 + next(10);
    }
  return out;
}
Image image(const cv::Mat &gray, bool padded = false) {
  Image out;
  out.stride = 640 * 4 + (padded ? 28 : 0);
  out.bytes = std::make_shared<std::vector<std::byte>>(out.stride * 480);
  for (int y = 0; y < 480; ++y)
    for (int x = 0; x < 640; ++x) {
      auto *p = out.bytes->data() + y * out.stride + x * 4;
      const auto v = std::byte(gray.at<uint8_t>(y, x));
      p[0] = p[1] = p[2] = v;
      p[3] = std::byte(255);
    }
  return out;
}
Image frame(double dx = 0, double angle = 0, double scale = 1) {
  const auto source = page();
  auto transform = cv::getRotationMatrix2D({640, 480}, angle, scale);
  transform.at<double>(0, 2) -= 320 - dx;
  transform.at<double>(1, 2) -= 240;
  cv::Mat out;
  cv::warpAffine(source, out, transform, {640, 480}, cv::INTER_LINEAR);
  return image(out);
}
void tracking() {
  constexpr int64_t start = 1000000000, interval = 33333333;
  m::FlowEstimator estimator;
  auto first = frame(), second = frame(6);
  auto update = [&](const Image &i, int64_t t, Orientation o = Orientation::upright, bool fit = false) {
    auto lease = i.lease(o);
    return estimator.update(lease, t, {}, fit);
  };
  auto initial = update(first, start);
  check(initial.status == m::Status::noReference && !initial.speed, "First frame measured");
  auto still = update(first, start + interval);
  check(still.status == m::Status::measured && still.speed && *still.speed < 3 && still.validTracks >= 8,
        "Still frame");
  estimator.reset();
  update(first, start);
  auto moved = update(second, start + interval);
  near(*moved.speed, 180, 18);
  check(moved.sourceWidth == 640 && moved.fromNs == start && moved.toNs == start + interval, "Source evidence");
  const double fast = *moved.speed;
  estimator.reset();
  update(first, start);
  near(*update(second, start + 2 * interval).speed / fast, .5, 1e-6);
  for (auto o : {Orientation::upright, Orientation::rotate90, Orientation::rotate180, Orientation::rotate270}) {
    estimator.reset();
    update(first, start, o, true);
    const auto r = update(second, start + interval, o, true);
    check(r.transform && r.transformProcessingNs && *r.transformProcessingNs > 0, "Fitted frame transform");
    const auto q = r.transform->warp(rect());
    check(bool(q), "Fitted warp");
    near((*q)[0].x, .2 + (o == Orientation::upright ? 6. / 640 : o == Orientation::rotate180 ? -6. / 640 : 0), .005);
    near((*q)[0].y, .2 + (o == Orientation::rotate90 ? 6. / 640 : o == Orientation::rotate270 ? -6. / 640 : 0), .005);
  }
  for (const auto &i : {frame(0, 2), frame(0, 0, 1.03), frame(0, 0, 1 / 1.03)}) {
    estimator.reset();
    update(first, start);
    auto r = update(i, start + interval);
    check(r.status == m::Status::measured && *r.speed > 60, "Rotation/approach motion");
  }
  auto blank = image(cv::Mat(480, 640, CV_8UC1, cv::Scalar(200)));
  estimator.reset();
  update(blank, start);
  auto r = update(blank, start + interval);
  check(r.status == m::Status::tooFewFeatures && !r.speed, "Sparse texture known");
  estimator.reset();
  update(first, start);
  r = update(blank, start + interval);
  check(r.status != m::Status::measured && !r.speed, "Failed tracks known");
  r = update(first, start + 2 * interval);
  check(r.status == m::Status::tooFewFeatures, "Failure must replace reference");
  check(update(first, start + 2 * interval).status == m::Status::noReference, "Repeated timestamp accepted");
  check(update(first, start + 2 * interval + 200000001).status == m::Status::noReference, "Gap accepted");
  check(update(first, start + 2 * interval + 200000001 + interval, Orientation::rotate90).status ==
            m::Status::noReference,
        "Geometry accepted");
  // Destroy/overwrite the borrowed source between calls; tracking must retain only its own gray copy.
  estimator.reset();
  std::weak_ptr<std::vector<std::byte>> weak;
  {
    auto temporary = frame();
    weak = temporary.bytes;
    update(temporary, start);
    std::fill(temporary.bytes->begin(), temporary.bytes->end(), std::byte(0));
  }
  check(weak.expired(), "Frame owner retained");
  r = update(second, start + interval);
  near(*r.speed, 180, 18);
  // BGRA frames with the red and blue channels swapped measure exactly what their RGBA originals do.
  auto bgra = [](const Image &i) {
    Image out{std::make_shared<std::vector<std::byte>>(*i.bytes), i.stride};
    for (size_t row = 0; row < 480; ++row)
      for (size_t x = 0; x < 640; ++x)
        std::swap((*out.bytes)[row * out.stride + x * 4], (*out.bytes)[row * out.stride + x * 4 + 2]);
    return out;
  };
  auto tinted = [](const Image &i) {
    Image out{std::make_shared<std::vector<std::byte>>(*i.bytes), i.stride};
    for (size_t row = 0; row < 480; ++row)
      for (size_t x = 0; x < 640; ++x)
        (*out.bytes)[row * out.stride + x * 4] = std::byte(uint8_t((*out.bytes)[row * out.stride + x * 4]) / 2);
    return out;
  };
  {
    const auto a = tinted(first), b = tinted(second), swappedA = bgra(a), swappedB = bgra(b);
    m::FlowEstimator rgba, swapped;
    auto la = a.lease(Orientation::rotate90), lb = b.lease(Orientation::rotate90);
    auto sa = PixelLease(swappedA.bytes, *swappedA.bytes,
                         {PixelFormat::bgra8, {640, 480}, swappedA.stride, 4, {0, 0, 640, 480}, Orientation::rotate90});
    auto sb = PixelLease(swappedB.bytes, *swappedB.bytes,
                         {PixelFormat::bgra8, {640, 480}, swappedB.stride, 4, {0, 0, 640, 480}, Orientation::rotate90});
    rgba.update(la, start, {}, true);
    swapped.update(sa, start, {}, true);
    const auto expected = rgba.update(lb, start + interval, {}, true);
    const auto actual = swapped.update(sb, start + interval, {}, true);
    check(expected.status == m::Status::measured && actual.status == expected.status && actual.speed == expected.speed &&
              actual.validTracks == expected.validTracks && actual.lostRegions == expected.lostRegions &&
              bool(actual.transform) == bool(expected.transform),
          "BGRA flow differs from RGBA");
  }
  auto cropUpdate = [&](const Image &i, int64_t t) {
    auto lease = i.lease(Orientation::upright, {80, 0, 480, 480});
    return estimator.update(lease, t, {});
  };
  estimator.reset();
  cropUpdate(first, start);
  near(*cropUpdate(second, start + interval).speed, 180, 18);
  cv::Mat gray, shifted;
  cv::cvtColor(cv::Mat(480, 640, CV_8UC4, first.bytes->data(), first.stride), gray, cv::COLOR_RGBA2GRAY);
  cv::cvtColor(cv::Mat(480, 640, CV_8UC4, second.bytes->data(), second.stride), shifted, cv::COLOR_RGBA2GRAY);
  const cv::Rect corner(390, 0, 250, 190);
  auto patch = gray.clone();
  shifted(corner).copyTo(patch(corner));
  estimator.reset();
  update(first, start);
  r = update(image(patch), start + interval);
  check(r.status == m::Status::measured && *r.speed > 120 && r.lostRegions == 0, "Worst moving region");
  patch = gray.clone();
  patch(corner).setTo(200);
  estimator.reset();
  update(first, start);
  r = update(image(patch), start + interval);
  check(r.status == m::Status::measured && r.lostRegions >= 1, "Occlusion region loss");
  cv::Mat tilted;
  const cv::Mat h = (cv::Mat_<double>(3, 3) << 1.01, .01, 2, -.005, 1, -1, .00005, -.000025, 1);
  cv::warpPerspective(gray, tilted, h, {640, 480});
  estimator.reset();
  update(first, start, Orientation::upright, true);
  r = update(image(tilted), start + interval, Orientation::upright, true);
  check(r.status == m::Status::measured && r.transform, "Tilted pixel tracking");
  auto target = r.transform->warp(rect());
  check(bool(target), "Tilted tracked quad");
  const double denominator = .00005 * 128 - .000025 * 96 + 1;
  near((*target)[0].x, (1.01 * 128 + .01 * 96 + 2) / denominator / 640, .005);
  near((*target)[0].y, (-.005 * 128 + 96 - 1) / denominator / 480, .005);
  for (const auto &region : {rect(.01, .01, .02, .02), rect(2, 2, 3, 3)}) {
    estimator.reset();
    auto a = first.lease(), b = second.lease();
    estimator.update(a, start, region);
    r = estimator.update(b, start + interval, region);
    check(r.status == m::Status::tooFewFeatures && !r.speed, "Unusable page tracked background");
  }
  const int64_t precise = 9007199254740993;
  estimator.reset();
  update(first, precise);
  r = update(second, precise + interval);
  check(r.fromNs == precise && r.toNs == precise + interval, "Timestamp precision lost");
  auto padded = image(cv::Mat(480, 640, CV_8UC1, cv::Scalar(200)), true);
  estimator.reset();
  update(padded, start);
  check(update(padded, start + interval).status == m::Status::tooFewFeatures, "Padded rows");
}
} // namespace
int main() {
  try {
    fittedTransforms();
    intervalHistory();
    gyroscope();
    blurGates();
    tracking();
    std::cout << "Motion contracts passed\n";
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
