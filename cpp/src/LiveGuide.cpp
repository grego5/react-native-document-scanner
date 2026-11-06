#include "document_scanner/LiveGuide.hpp"
#include <cmath>
#include <limits>
namespace document_scanner::guide {
bool scoreRejected(std::optional<double> score, double minimum) { return minimum > 0 && score && *score < minimum; }
bool keepScorePause(bool current, std::optional<double> speed, int width) {
  return current && !(speed && *speed / width > .01);
}

void Stabilizer::reset() {
  accepted_.reset();
  deadlineNs_ = 0;
  lastTimestamp_.reset();
}
std::optional<Decision> Stabilizer::shape(const Corners &q, const Input &f) const {
  double area = 0;
  for (size_t i = 0; i < 4; ++i)
    area += q[i].x * q[(i + 1) % 4].y - q[(i + 1) % 4].x * q[i].y;
  if (std::abs(area) / 2 * f.width * f.height < config_.minArea * f.width * f.height)
    return Decision::tooSmall;
  for (size_t i = 0; i < 4; ++i) {
    const auto &a = q[i], &b = q[(i + 1) % 4], &c = q[(i + 2) % 4];
    if (!std::isfinite(a.x) || !std::isfinite(a.y) || a.x < 0 || a.x > 1 || a.y < 0 || a.y > 1 ||
        !((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 1e-6))
      return Decision::invalid;
  }
  return std::nullopt;
}
void Stabilizer::accept(const Corners &q, const Input &f) {
  accepted_.emplace(q);
  acceptedSensorNs_ = f.sensorNs;
  ++acceptanceId_;
  deadlineNs_ = f.arrivalNs + config_.holdNs;
}
Output Stabilizer::output(Decision d, bool tracking, bool expired) const {
  return {accepted_ ? (tracking ? Status::tracking : Status::held) : Status::searching,
          d,
          accepted_,
          acceptanceId_,
          deadlineNs_,
          tracking ? 1 : 0,
          expired};
}
std::optional<Output> Stabilizer::retain(const Input &f) {
  if (!accepted_ || f.arrivalNs >= deadlineNs_ || !f.corners || shape(*f.corners, f))
    return std::nullopt;
  accept(*f.corners, f);
  return output(Decision::agreed, true, false);
}
Output Stabilizer::update(const Input &f) {
  if (lastTimestamp_ && f.sensorNs == *lastTimestamp_)
    return output(Decision::duplicateTimestamp, false, false);
  const bool backward = lastTimestamp_ && f.sensorNs < *lastTimestamp_;
  if (backward)
    reset();
  lastTimestamp_ = f.sensorNs;
  const bool expired = accepted_ && f.arrivalNs >= deadlineNs_;
  if (expired)
    accepted_.reset();
  auto invalid = f.corners ? shape(*f.corners, f) : std::optional{Decision::missing};
  Decision d;
  bool tracking = false;
  if (invalid) {
    if (acceptedSensorNs_ != f.sensorNs)
      accepted_.reset();
    d = *invalid;
  } else {
    d = accepted_ ? Decision::replaced : Decision::acquired;
    accept(*f.corners, f);
    tracking = true;
  }
  return output(backward ? Decision::timestampBackward : d, tracking, expired);
}
} // namespace document_scanner::guide
