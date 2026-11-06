#pragma once

#include "Pixels.hpp"
#include <array>
#include <deque>
#include <memory>
#include <optional>
#include <span>

namespace document_scanner::motion {
using Matrix = std::array<double, 9>;
struct Track {
  double x, y, u, v;
};
struct Transform {
  double xx, xy, yx, yy, tx, ty, px = 0, py = 0;
  std::optional<Corners> warp(const Corners &) const;
};
std::optional<Transform> fit(std::span<const Track>, int width, int height, double regionArea, double tolerance);

enum class Status { measured, noReference, tooFewFeatures, trackingFailed, poorCoverage };
struct Estimate {
  Status status;
  std::optional<double> speed;
  int sourceWidth;
  int64_t fromNs, toNs;
  double confidence = 0;
  int validTracks = 0, lostRegions = 0;
  std::optional<Transform> transform;
  std::optional<int64_t> transformProcessingNs;
};
// Analysis-serial, bounded exact intervals; missing intervals are never interpolated.
class FlowHistory final {
public:
  void clear() { steps_.clear(); }
  void add(const Estimate &);
  std::optional<Corners> warp(const Corners &, int64_t fromNs, int64_t toNs) const;

private:
  struct Step {
    int64_t from, to;
    Transform transform;
  };
  std::deque<Step> steps_;
};
// One camera's analysis-serial owner. Only downscaled owned gray pixels survive update.
class FlowEstimator final {
public:
  explicit FlowEstimator(int maxEdge = 224, int maxFeatures = 50, int64_t maxGapNs = 200000000);
  ~FlowEstimator();
  FlowEstimator(const FlowEstimator &) = delete;
  FlowEstimator &operator=(const FlowEstimator &) = delete;
  void reset();
  Estimate update(const PixelLease &, int64_t sensorNs, const std::optional<Corners> &region, bool fitTransform = false);

private:
  struct State;
  std::unique_ptr<State> state_;
};

struct GyroSample {
  int64_t timeNs;
  double x, y, z;
};
// gyroTime = cameraTime + offset. No value means clocks are not correlated.
struct ClockCorrelation {
  int64_t gyroMinusCameraNs;
};
std::optional<Matrix> rotation(std::span<const GyroSample>, int64_t fromNs, int64_t toNs,
                               std::optional<ClockCorrelation>);
std::optional<double> peakRate(std::span<const GyroSample>, int64_t fromNs, int64_t toNs,
                               std::optional<ClockCorrelation>);
struct FrameGeometry {
  int width, height;
  double focalPx, centerX, centerY;
  int axisRotationDegrees;
};
FrameGeometry frameGeometry(double focalPx, int activeWidth, int activeHeight, int sensorOrientation, int bufferWidth,
                            int bufferHeight, Crop, int rotationDegrees);
std::optional<Corners> rotationWarp(const Corners &, FrameGeometry, const Matrix &);
std::optional<double> gyroBlur(std::span<const GyroSample>, double focalPx, int activeWidth, int64_t fromNs,
                               int64_t toNs, int64_t exposureNs, std::optional<ClockCorrelation>);
std::optional<double> flowBlur(std::optional<double> speed, int sourceWidth, int64_t exposureNs);
struct GateMotion {
  double blur;
  bool fromFlow;
  int lostRegions;
};
std::optional<GateMotion> gateMotion(std::optional<double> speed, int sourceWidth, int lostRegions, int64_t exposureNs,
                                     std::optional<double> gyroBlur);
} // namespace document_scanner::motion
