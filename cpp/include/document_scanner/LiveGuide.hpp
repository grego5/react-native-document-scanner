#pragma once
#include "Pixels.hpp"
#include <optional>
#include <string>
#include <cstdint>

namespace document_scanner::guide {
// Runtime score is optional; absent scores preserve the existing acceptance behavior.
bool scoreRejected(std::optional<double> score, double minimum);
bool keepScorePause(bool generationCurrent, std::optional<double> measuredSpeed, int sourceWidth);
enum class Status { searching, tracking, held };
enum class Decision {
  acquiring,
  acquired,
  agreed,
  challenging,
  replaced,
  missing,
  tooSmall,
  invalid,
  notRectangle,
  tooTilted,
  gapRestart,
  duplicateTimestamp,
  timestampBackward
};
struct Config {
  double minArea = .03;
  int64_t holdNs = 400000000;
};
struct Input {
  std::optional<Corners> corners;
  int width, height;
  int64_t sensorNs, arrivalNs;
};
struct Output {
  Status status;
  Decision decision;
  std::optional<Corners> accepted;
  int64_t acceptanceId, deadlineNs;
  int chainLength;
  bool expired;
};
// Confined to the analysis owner. No platform handles or wall-clock reads.
class Stabilizer {
public:
  void configure(Config config) {
    config_ = config;
    reset();
  }
  void reset();
  Output update(const Input &input);
  std::optional<Output> retain(const Input &input);

private:
  std::optional<Decision> shape(const Corners &, const Input &) const;
  Output output(Decision, bool tracking, bool expired) const;
  void accept(const Corners &, const Input &);
  Config config_;
  std::optional<Corners> accepted_;
  int64_t acceptedSensorNs_ = 0, acceptanceId_ = 0, deadlineNs_ = 0;
  std::optional<int64_t> lastTimestamp_;
};
} // namespace document_scanner::guide
