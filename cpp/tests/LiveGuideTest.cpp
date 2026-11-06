#include "document_scanner/LiveGuide.hpp"
#include <iostream>
#include <stdexcept>
#include <limits>
using namespace document_scanner;
using namespace document_scanner::guide;
void check(bool v) {
  if (!v)
    throw std::runtime_error("Live guide contract failed");
}
Corners rect(double l, double t, double r, double b) { return {Point{l, t}, Point{r, t}, Point{r, b}, Point{l, b}}; }
Input frame(std::optional<Corners> q, int64_t ms) { return {q, 1000, 1000, ms * 1000000, ms * 1000000}; }
int main() {
  const auto page = rect(.2, .2, .8, .8);
  Stabilizer s;
  auto first = s.update(frame(page, 0));
  check(first.status == Status::tracking && first.acceptanceId == 1 && first.chainLength == 1);
  check(s.update(frame(page, 0)).decision == Decision::duplicateTimestamp);
  const Corners perspective{Point{.2, .2}, Point{.8, .2}, Point{.9, .8}, Point{.45, .6}};
  check(s.update(frame(perspective, 33)).accepted.has_value());
  auto expired = s.update(frame(page, 470));
  check(expired.expired && expired.status == Status::tracking);
  check(s.update(frame(rect(-.1, .2, .8, .8), 500)).decision == Decision::invalid);
  check(s.update(frame(rect(.2, .2, .21, .21), 533)).decision == Decision::tooSmall);
  check(!s.update(frame(std::nullopt, 566)).accepted);
  s.update(frame(page, 600));
  check(s.retain(frame(page, 633)).has_value());
  check(s.update(frame(std::nullopt, 633)).accepted.has_value());
  check(!s.update(frame(std::nullopt, 666)).accepted);
  check(!s.retain(frame(page, 700)));
  s.update(frame(page, 733));
  check(!s.retain(frame(page, 1133)));
  const auto old = s.update(frame(page, 1200)).acceptanceId;
  s.configure({.03, 200000000});
  auto fresh = s.update(frame(page, 1233));
  check(fresh.acceptanceId > old && fresh.deadlineNs == 1433000000);
  check(s.update(frame(page, 50)).decision == Decision::timestampBackward);
  const auto id = s.update(frame(page, 83)).acceptanceId;
  s.reset();
  check(s.update(frame(page, 100)).acceptanceId > id);
  // Replays mirror detector resets for generation/geometry changes and tracked heartbeats.
  Stabilizer replay;
  for (auto ms : {0, 33, 66})
    check(replay.update(frame(page, ms)).status == Status::tracking);
  for (auto ms : {300, 600, 900})
    check(replay.retain(frame(page, ms)).has_value());
  check(replay.update(frame(page, 1066)).decision == Decision::replaced);
  replay.reset();
  check(replay.update(frame(page, 1100)).acceptanceId == 8);
  check(!replay.update(frame(std::nullopt, 1133)).accepted);
  // Nonfinite/crossed candidates and missing tracks cannot retain a guide.
  replay.update(frame(page, 1200));
  check(!replay.retain(frame(rect(-.1, .2, .8, .8), 1233)));
  check(!replay.retain(frame(std::nullopt, 1233)));
  const double nan = std::numeric_limits<double>::quiet_NaN();
  check(replay.update(frame(rect(nan, .2, .8, .8), 1233)).decision == Decision::invalid);
  check(scoreRejected(.9, .95));
  check(!scoreRejected(.95, .95));
  check(!scoreRejected(std::nullopt, .95));
  check(!scoreRejected(.1, 0));
  check(keepScorePause(true, std::nullopt, 320));
  check(keepScorePause(true, 3.2, 320));
  check(!keepScorePause(true, 3.21, 320));
  check(!keepScorePause(false, std::nullopt, 320));
  std::cout << "Live guide/replay contracts passed\n";
}
