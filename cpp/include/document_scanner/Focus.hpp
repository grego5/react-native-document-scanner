#pragma once
#include "Pixels.hpp"
#include <optional>
#include <mutex>

namespace document_scanner::focus {
using Time = int64_t; // Monotonic uptime nanoseconds supplied by the adapter.
inline constexpr Time second = 1000000000;
enum class Action { none, start, release };
enum class Owner { tap, document };
// Unknown completion is settled, but never a proven focus lock.
enum class Completion { unknown, failure, success };
class DocumentPolicy {
public:
  void reset();
  void settled(Completion success, Time now);
  Action observe(const std::optional<Quad> &, bool trusted, bool failed, Time now, bool blocked, bool acquireTrusted);
  std::optional<Time> deadline(Time now) const;
  Action wake(Time now, bool blocked);

private:
  enum class Phase { seeking, scanning, locked };
  Action start();
  Phase phase_ = Phase::seeking;
  Time retryAfter_ = 0, lastSeen_ = 0;
  std::optional<double> referenceArea_;
  int scaleDirection_ = 0, scaleVotes_ = 0;
  std::optional<Time> failedSince_;
  bool missing_ = false;
};
struct Snapshot {
  int64_t generation = 0, scanToken = 0;
  std::optional<Owner> owner;
  std::optional<bool> locked;
  Time heldUntil = 0;
  bool pending = false;
};
struct Deadlines {
  std::optional<Time> tap, scan, document;
};
// Main/serial camera owner. The adapter holds hardware resources keyed by scanToken.
class Policy {
public:
  const Snapshot &snapshot() const { return snapshot_; }
  DocumentPolicy &document() { return document_; }
  Action observe(const std::optional<Quad> &, bool trusted, bool failed, Time now, bool shot, bool parked);
  int64_t start(Owner owner, Time now);
  bool complete(int64_t token, Completion, Time now);
  bool releaseDocument(bool shot);
  bool expireTap(int64_t token, Time now, bool shot);
  bool afterCapture(Time now, bool wanted);
  bool reset(bool keepDocument, bool shot);
  void unbind();
  Deadlines deadlines(Time now) const;
  Action wakeDocument(Time now, bool shot);

private:
  void release();
  Snapshot snapshot_;
  Time scanDeadline_ = 0;
  DocumentPolicy document_;
};
// Adapter-selected process/session lifetime and unit; Android uses diopters, iOS may use lens positions.
// Safe for metadata callbacks and main-thread readers. No hardware unit conversion is done here.
class LearnedPosition {
public:
  void learn(std::optional<double> value);
  std::optional<double> value() const;

private:
  mutable std::mutex mutex_;
  std::optional<double> value_;
};
} // namespace document_scanner::focus
