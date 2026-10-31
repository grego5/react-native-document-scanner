#include "document_scanner/Focus.hpp"
#include <cmath>
#include <limits>
#include <algorithm>
namespace document_scanner::focus {
void DocumentPolicy::reset() {
  phase_ = Phase::seeking;
  retryAfter_ = 0;
  referenceArea_.reset();
  scaleVotes_ = 0;
  scaleDirection_ = 0;
  failedSince_.reset();
  missing_ = false;
}
void DocumentPolicy::settled(Completion success, Time now) {
  if (phase_ != Phase::scanning)
    return;
  phase_ = success == Completion::success ? Phase::locked : Phase::seeking;
  retryAfter_ = now + second;
  referenceArea_.reset();
  scaleVotes_ = 0;
  failedSince_.reset();
}
Action DocumentPolicy::start() {
  phase_ = Phase::scanning;
  referenceArea_.reset();
  scaleVotes_ = 0;
  failedSince_.reset();
  return Action::start;
}
Action DocumentPolicy::observe(const std::optional<Quad> &quad, bool trusted, bool failed, Time now, bool blocked,
                               bool acquireTrusted) {
  if (blocked)
    return Action::none;
  double area = 0;
  if (quad)
    for (size_t i = 0; i < 4; ++i)
      area += (*quad)[i].x * (*quad)[(i + 1) % 4].y - (*quad)[(i + 1) % 4].x * (*quad)[i].y;
  area = std::abs(area) / 2;
  missing_ = !quad || !std::isfinite(area) || area < .03;
  if (missing_) {
    if (phase_ == Phase::locked && now - lastSeen_ >= second) {
      reset();
      return Action::release;
    }
    return Action::none;
  }
  lastSeen_ = now;
  if (phase_ == Phase::scanning)
    return Action::none;
  if (phase_ == Phase::seeking)
    return now >= retryAfter_ && (trusted || !acquireTrusted) ? start() : Action::none;
  if (failed) {
    if (!failedSince_)
      failedSince_ = now;
    if (now >= retryAfter_ && now - *failedSince_ >= second / 2)
      return start();
  } else
    failedSince_.reset();
  if (!trusted)
    return Action::none;
  if (!referenceArea_) {
    referenceArea_ = area;
    return Action::none;
  }
  const double ratio = std::sqrt(area / *referenceArea_);
  const int direction = ratio > 1.08 ? 1 : ratio < 1 / 1.08 ? -1 : 0;
  scaleVotes_ = direction && direction == scaleDirection_ ? scaleVotes_ + 1 : direction ? 1 : 0;
  scaleDirection_ = direction;
  return scaleVotes_ >= 2 ? start() : Action::none;
}
std::optional<Time> DocumentPolicy::deadline(Time now) const {
  std::optional<Time> next;
  auto include = [&](Time t) {
    if (t > now && (!next || t < *next))
      next = t;
  };
  if (phase_ == Phase::seeking)
    include(retryAfter_);
  if (phase_ == Phase::locked && missing_)
    include(lastSeen_ + second);
  if (phase_ == Phase::locked && failedSince_)
    include(std::max(retryAfter_, *failedSince_ + second / 2));
  return next;
}
Action DocumentPolicy::wake(Time now, bool blocked) {
  // Retry/scale/sharpness decisions require a fresh candidate; a timer must not add votes or revive old geometry.
  if (!blocked && phase_ == Phase::locked && missing_ && now - lastSeen_ >= second) {
    reset();
    return Action::release;
  }
  return Action::none;
}
Action Policy::observe(const std::optional<Quad> &q, bool trusted, bool failed, Time now, bool shot, bool parked) {
  const bool tapHolds = snapshot_.owner == Owner::tap && now < snapshot_.heldUntil;
  return document_.observe(q, trusted, failed, now, shot || tapHolds, parked);
}
int64_t Policy::start(Owner owner, Time now) {
  if (owner == Owner::tap)
    document_.reset();
  ++snapshot_.generation;
  snapshot_.scanToken = snapshot_.generation;
  snapshot_.owner = owner;
  snapshot_.locked.reset();
  snapshot_.pending = true;
  snapshot_.heldUntil = owner == Owner::tap ? now + 5 * second : std::numeric_limits<Time>::max();
  scanDeadline_ = now + 2 * second;
  return snapshot_.scanToken;
}
bool Policy::complete(int64_t token, Completion result, Time now) {
  if (!snapshot_.owner || token != snapshot_.scanToken || !snapshot_.pending)
    return false;
  snapshot_.pending = false;
  snapshot_.locked = result == Completion::unknown ? std::nullopt : std::optional{result == Completion::success};
  ++snapshot_.generation;
  if (snapshot_.owner == Owner::document)
    document_.settled(result, now);
  return true;
}
void Policy::release() {
  snapshot_.owner.reset();
  snapshot_.locked.reset();
  snapshot_.heldUntil = 0;
  snapshot_.pending = false;
  ++snapshot_.generation;
}
bool Policy::releaseDocument(bool shot) {
  if (shot || snapshot_.owner != Owner::document)
    return false;
  release();
  return true;
}
bool Policy::expireTap(int64_t token, Time now, bool shot) {
  if (shot || snapshot_.owner != Owner::tap || token != snapshot_.scanToken || now < snapshot_.heldUntil)
    return false;
  release();
  return true;
}
bool Policy::afterCapture(Time now, bool wanted) {
  if (snapshot_.owner == Owner::tap)
    return expireTap(snapshot_.scanToken, now, false);
  return (!wanted || (!snapshot_.pending && snapshot_.locked != true)) && releaseDocument(false);
}
bool Policy::reset(bool keep, bool shot) {
  document_.reset();
  return !keep && releaseDocument(shot);
}
void Policy::unbind() {
  document_.reset();
  release();
}
Deadlines Policy::deadlines(Time now) const {
  return {snapshot_.owner == Owner::tap ? std::optional{snapshot_.heldUntil} : std::nullopt,
          snapshot_.owner == Owner::document && snapshot_.pending ? std::optional{scanDeadline_} : std::nullopt,
          document_.deadline(now)};
}
Action Policy::wakeDocument(Time now, bool shot) {
  return document_.wake(now, shot || (snapshot_.owner == Owner::tap && now < snapshot_.heldUntil));
}
} // namespace document_scanner::focus
