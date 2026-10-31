#pragma once
#include <document_scanner/Pixels.hpp>
#include <array>
#include <optional>
#include <span>
#include <vector>

namespace document_scanner {
// Runtime adapters supply metadata; no interpreter handles enter the core.
Size validateCornerTensors(int inputCount, int outputCount, bool inputFloat32, bool outputFloat32,
                           std::span<const int> inputShape, std::span<const int> outputShape);
size_t cornerInputBytes(Size target);
bool validPredictionQuad(const Quad &quad);
struct CornerPrediction final {
  const Quad quad;
  const double score;
};
std::optional<CornerPrediction> decodeCornerOutput(std::span<const float> values);

// Serial-owner workspace. All source/output views are borrowed synchronously and never retained.
class TensorPreprocessor final {
public:
  explicit TensorPreprocessor(Size target);
  size_t inputBytes() const noexcept { return inputBytes_; }
  void prepare(const PixelLease &source, std::span<float> output);

private:
  struct Weights {
    uint32_t start;
    std::vector<uint32_t> coefficients;
  };
  static std::vector<Weights> weights(uint32_t source, uint32_t target);
  const uint32_t _targetWidth, _targetHeight;
  const size_t inputBytes_;
  uint32_t _width = 0, _height = 0;
  std::vector<Weights> _xWeights, _yWeights;
  std::vector<uint32_t> _horizontal;
  std::array<std::array<float, 256>, 3> _normalization;
};
} // namespace document_scanner
