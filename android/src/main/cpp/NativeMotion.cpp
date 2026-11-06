#include "NativeMotion.hpp"
#include <limits>
#include <cmath>
#include <algorithm>
#include <stdexcept>
#include <vector>

namespace margelo::nitro::documentscanner {
using namespace facebook::jni;
using namespace document_scanner;
namespace m = document_scanner::motion;
namespace {
std::vector<double> values(alias_ref<JArrayDouble> a, size_t size) {
  if (!a || a->size() != size)
    throw std::invalid_argument("Invalid motion snapshot size.");
  auto r = a->getRegion(0, size);
  return {r.get(), r.get() + size};
}
local_ref<JArrayDouble> array(std::span<const double> data) {
  auto a = JArrayDouble::newArray(data.size());
  a->setRegion(0, data.size(), data.data());
  return a;
}
Corners corners(alias_ref<JArrayDouble> a) {
  const auto v = values(a, 8);
  return {Point{v[0], v[1]}, Point{v[2], v[3]}, Point{v[4], v[5]}, Point{v[6], v[7]}};
}
local_ref<JArrayDouble> cornersArray(const std::optional<Corners> &q) {
  if (!q)
    return nullptr;
  const double v[]{(*q)[0].x, (*q)[0].y, (*q)[1].x, (*q)[1].y, (*q)[2].x, (*q)[2].y, (*q)[3].x, (*q)[3].y};
  return array(v);
}
std::array<double, 8> coefficients(const m::Transform &t) { return {t.xx, t.xy, t.yx, t.yy, t.tx, t.ty, t.px, t.py}; }
local_ref<JArrayDouble> fitNative(alias_ref<jclass>, alias_ref<JArrayDouble> input, jint w, jint h, jdouble area,
                                  jdouble tolerance) {
  if (!input || input->size() % 4)
    throw std::invalid_argument("Invalid flow tracks.");
  const auto v = values(input, input->size());
  std::vector<m::Track> tracks;
  for (size_t i = 0; i < v.size(); i += 4)
    tracks.push_back({v[i], v[i + 1], v[i + 2], v[i + 3]});
  const auto t = m::fit(tracks, w, h, area, tolerance);
  return t ? array(coefficients(*t)) : nullptr;
}
local_ref<JArrayDouble> warpNative(alias_ref<jclass>, alias_ref<JArrayDouble> transform, alias_ref<JArrayDouble> q) {
  const auto t = values(transform, 8);
  return cornersArray(m::Transform{t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7]}.warp(corners(q)));
}
local_ref<JArrayDouble> gyroNative(alias_ref<jclass>, alias_ref<JArrayLong> times, alias_ref<JArrayDouble> rates,
                                   jlong from, jlong to, alias_ref<JArrayLong> correlation, jint operation,
                                   jdouble focal, jint width, jlong exposure) {
  if (!times)
    throw std::invalid_argument("Missing gyro times.");
  const auto data = values(rates, times->size() * 3);
  const auto ts = times->getRegion(0, times->size());
  std::vector<m::GyroSample> samples;
  for (size_t i = 0; i < times->size(); ++i)
    samples.push_back({ts[i], data[i * 3], data[i * 3 + 1], data[i * 3 + 2]});
  std::optional<m::ClockCorrelation> clock;
  if (correlation) {
    if (correlation->size() != 1)
      throw std::invalid_argument("Invalid gyro clock correlation.");
    auto c = correlation->getRegion(0, 1);
    clock = m::ClockCorrelation{c[0]};
  }
  if (operation == 0) {
    auto r = m::rotation(samples, from, to, clock);
    return r ? array(*r) : nullptr;
  }
  const auto r = operation == 1 ? m::peakRate(samples, from, to, clock)
                                : m::gyroBlur(samples, focal, width, from, to, exposure, clock);
  if (!r)
    return nullptr;
  return array(std::span<const double>(&*r, 1));
}
local_ref<JArrayDouble> geometryNative(alias_ref<jclass>, alias_ref<JArrayDouble> optics,
                                       alias_ref<JArrayInt> dimensions) {
  const auto o = values(optics, 4);
  if (!dimensions || dimensions->size() != 7)
    throw std::invalid_argument("Invalid frame geometry.");
  auto d = dimensions->getRegion(0, 7);
  if (o[1] <= 0 || o[2] <= 0 || d[0] <= 0 || d[1] <= 0 || d[2] < 0 || d[3] < 0 || d[4] <= d[2] || d[5] <= d[3] ||
      d[4] > d[0] || d[5] > d[1])
    throw std::invalid_argument("Invalid frame geometry.");
  const auto f = m::frameGeometry(o[0], int(o[1]), int(o[2]), int(o[3]), d[0], d[1],
                                  {uint32_t(d[2]), uint32_t(d[3]), uint32_t(d[4] - d[2]), uint32_t(d[5] - d[3])}, d[6]);
  const double v[]{double(f.width), double(f.height), f.focalPx, f.centerX, f.centerY, double(f.axisRotationDegrees)};
  return array(v);
}
local_ref<JArrayDouble> rotationWarpNative(alias_ref<jclass>, alias_ref<JArrayDouble> q, alias_ref<JArrayDouble> frame,
                                           alias_ref<JArrayDouble> rotation) {
  const auto f = values(frame, 6), r = values(rotation, 9);
  m::Matrix matrix;
  std::copy(r.begin(), r.end(), matrix.begin());
  return cornersArray(m::rotationWarp(corners(q), {int(f[0]), int(f[1]), f[2], f[3], f[4], int(f[5])}, matrix));
}
local_ref<JArrayDouble> gateNative(alias_ref<jclass>, alias_ref<JArrayDouble> speed, jint width, jint lost,
                                   jlong exposure, alias_ref<JArrayDouble> gyro) {
  std::optional<double> s, g;
  if (speed)
    s = values(speed, 1)[0];
  if (gyro)
    g = values(gyro, 1)[0];
  const auto result = m::gateMotion(s, width, lost, exposure, g);
  if (!result)
    return nullptr;
  const double v[]{result->blur, result->fromFlow ? 0.0 : 1.0, double(result->lostRegions)};
  return array(v);
}
class NativeMotion : public JavaClass<NativeMotion> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeMotion;";
};
} // namespace
void registerMotionMath() {
  NativeFlowHistory::registerNatives();
  NativeMotion::javaClassStatic()->registerNatives(
      {makeNativeMethod("fitNative", fitNative), makeNativeMethod("warpNative", warpNative),
       makeNativeMethod("gyroNative", gyroNative), makeNativeMethod("geometryNative", geometryNative),
       makeNativeMethod("rotationWarpNative", rotationWarpNative), makeNativeMethod("gateNative", gateNative)});
}
local_ref<NativeFlowHistory::jhybriddata> NativeFlowHistory::initHybrid(alias_ref<jhybridobject>) {
  return makeCxxInstance();
}
void NativeFlowHistory::registerNatives() {
  registerHybrid({makeNativeMethod("initHybrid", initHybrid),
                  makeNativeMethod("clearNative", NativeFlowHistory::clearNative),
                  makeNativeMethod("addNative", NativeFlowHistory::addNative),
                  makeNativeMethod("warpNative", NativeFlowHistory::warpNative)});
}
void NativeFlowHistory::addNative(jlong from, jlong to, alias_ref<JArrayDouble> transform) {
  if (!transform) {
    history_.clear();
    return;
  }
  const auto t = values(transform, 8);
  m::Estimate e{m::Status::measured, {}, 0, from, to};
  e.transform = m::Transform{t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7]};
  history_.add(e);
}
local_ref<JArrayDouble> NativeFlowHistory::warpNative(alias_ref<JArrayDouble> q, jlong from, jlong to) {
  return cornersArray(history_.warp(corners(q), from, to));
}
local_ref<NativeFlowEstimator::jhybriddata> NativeFlowEstimator::initHybrid(alias_ref<jhybridobject>, jint edge,
                                                                            jint features, jlong gap) {
  return makeCxxInstance(edge, features, gap);
}
void NativeFlowEstimator::registerNatives() {
  registerHybrid({makeNativeMethod("initHybrid", initHybrid),
                  makeNativeMethod("resetNative", NativeFlowEstimator::resetNative),
                  makeNativeMethod("updateNative", NativeFlowEstimator::updateNative)});
}
local_ref<JArrayDouble> NativeFlowEstimator::updateNative(alias_ref<JByteBuffer> plane, jint position, jint limit,
                                                          jint width, jint height, jint rowStride, jint pixelStride,
                                                          jint left, jint top, jint right, jint bottom, jint rotation,
                                                          jlong sensor, alias_ref<JArrayDouble> region, jboolean fit,
                                                          alias_ref<JArrayLong> times) {
  if (!times || times->size() != 2)
    throw std::invalid_argument("Invalid flow timestamp output.");
  if (!plane || !plane->isDirect() || !plane->getDirectBytes() || position < 0 || limit < position ||
      uint64_t(limit) > plane->getDirectSize() || width <= 0 || height <= 0 || rowStride <= 0 || pixelStride < 4 ||
      left < 0 || top < 0 || right > width || bottom > height || right <= left || bottom <= top ||
      (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270))
    throw std::invalid_argument("Invalid RGBA flow frame.");
  auto owner = std::make_shared<global_ref<JByteBuffer>>(make_global(plane));
  const auto orientation = rotation == 90    ? Orientation::rotate90
                           : rotation == 180 ? Orientation::rotate180
                           : rotation == 270 ? Orientation::rotate270
                                             : Orientation::upright;
  PixelLease lease(owner,
                   {reinterpret_cast<const std::byte *>(plane->getDirectBytes() + position), size_t(limit - position)},
                   {PixelFormat::rgba8,
                    {uint32_t(width), uint32_t(height)},
                    size_t(rowStride),
                    size_t(pixelStride),
                    {uint32_t(left), uint32_t(top), uint32_t(right - left), uint32_t(bottom - top)},
                    orientation});
  std::optional<Corners> q;
  if (region)
    q.emplace(corners(region));
  const auto r = estimator_.update(lease, sensor, q, fit);
  const jlong timestamps[]{r.fromNs, r.toNs};
  times->setRegion(0, 2, timestamps);
  // Timestamps stay Kotlin Longs; only status, numeric evidence and relative timing cross as doubles.
  std::vector<double> v{double(r.status),      r.speed.value_or(NAN),
                        r.confidence,          double(r.validTracks),
                        double(r.lostRegions), r.transformProcessingNs ? double(*r.transformProcessingNs) : NAN};
  if (r.transform) {
    auto t = coefficients(*r.transform);
    v.insert(v.end(), t.begin(), t.end());
  }
  return array(v);
}
} // namespace margelo::nitro::documentscanner
