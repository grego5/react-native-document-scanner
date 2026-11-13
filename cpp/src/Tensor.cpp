#include <document_scanner/Tensor.hpp>
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace document_scanner {
namespace {
constexpr uint32_t kPrecision = 1u << 22;
constexpr uint32_t kRounding = 1u << 21;
uint32_t rounded(uint32_t value) { return std::min(value >> 22, 255u); }
} // namespace
size_t cornerInputBytes(Size target) {
  if (!target.width || !target.height ||
      static_cast<uint64_t>(target.width) * target.height > std::numeric_limits<int32_t>::max() / (3 * sizeof(float)))
    throw std::invalid_argument("Invalid corner model input dimensions.");
  return static_cast<size_t>(target.width) * target.height * 3 * sizeof(float);
}
Size validateCornerTensors(int inputs, int outputs, bool inputFloat, bool outputFloat, std::span<const int> in,
                           std::span<const int> out) {
  if (inputs != 1 || outputs != 1)
    throw std::invalid_argument("Unexpected DocCornerNet tensor count.");
  if (!inputFloat || in.size() != 4 || in[0] != 1 || in[3] != 3 || in[1] <= 0 || in[2] <= 0)
    throw std::invalid_argument("Expected a float32 [1, height, width, 3] corners-model input.");
  if (!outputFloat || out.size() != 2 || out[0] != 1 || out[1] != 9)
    throw std::invalid_argument("Unexpected DocCornerNet output.");
  const Size result{static_cast<uint32_t>(in[2]), static_cast<uint32_t>(in[1])};
  cornerInputBytes(result);
  return result;
}
bool validPredictionCorners(const Corners &q) {
  for (Point p : q)
    if (!std::isfinite(p.x) || !std::isfinite(p.y) || p.x < 0 || p.x > 1 || p.y < 0 || p.y > 1)
      return false;
  for (size_t i = 0; i < 4; ++i) {
    const auto a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4];
    if (!((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 1e-6))
      return false;
  }
  return true;
}
std::optional<CornerPrediction> decodeCornerOutput(std::span<const float> values) {
  if (values.size() != 9)
    throw std::invalid_argument("Unexpected DocCornerNet output size.");
  for (float v : values)
    if (!std::isfinite(v))
      throw std::invalid_argument("DocCornerNet returned non-finite output.");
  const Corners q{Point{values[0], values[1]}, Point{values[2], values[3]}, Point{values[4], values[5]},
               Point{values[6], values[7]}};
  if (!validPredictionCorners(q))
    return std::nullopt;
  return CornerPrediction{q, values[8]};
}
TensorPreprocessor::TensorPreprocessor(Size target)
    : _targetWidth(target.width), _targetHeight(target.height), inputBytes_(cornerInputBytes(target)) {
  constexpr std::array<float, 3> means{0.485f, 0.456f, 0.406f};
  constexpr std::array<float, 3> deviations{0.229f, 0.224f, 0.225f};
  for (size_t channel = 0; channel < 3; ++channel) {
    for (size_t value = 0; value < 256; ++value) {
      _normalization[channel][value] = (static_cast<float>(value) / 255.f - means[channel]) / deviations[channel];
    }
  }
}

std::vector<TensorPreprocessor::Weights> TensorPreprocessor::weights(uint32_t source, uint32_t target) {
  const double scale = static_cast<double>(source) / target;
  const double filterScale = std::max(1.0, scale);
  std::vector<Weights> result;
  result.reserve(target);
  for (uint32_t index = 0; index < target; ++index) {
    const double center = (index + 0.5) * scale;
    const uint32_t start = static_cast<uint32_t>(std::max(0, static_cast<int>(center - filterScale + 0.5)));
    const uint32_t end = std::min(source, static_cast<uint32_t>(center + filterScale + 0.5));
    std::vector<double> raw(end - start);
    double sum = 0;
    for (uint32_t offset = 0; offset < raw.size(); ++offset) {
      raw[offset] = std::max(0.0, 1.0 - std::abs((start + offset - center + 0.5) / filterScale));
      sum += raw[offset];
    }
    Weights kernel{start, std::vector<uint32_t>(raw.size())};
    for (size_t offset = 0; offset < raw.size(); ++offset) {
      kernel.coefficients[offset] = static_cast<uint32_t>((raw[offset] / sum) * kPrecision + 0.5);
    }
    result.push_back(std::move(kernel));
  }
  return result;
}

void TensorPreprocessor::prepare(const PixelLease &source, std::span<float> output) {
  if (output.size_bytes() < inputBytes_ || !output.data())
    throw std::invalid_argument("Tensor buffer is too small.");
  const auto &view = source.view();
  const auto &layout = view.layout();
  const auto crop = layout.crop;
  const auto o = layout.orientation;
  if (crop.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
      crop.height > static_cast<uint32_t>(std::numeric_limits<int>::max()))
    throw std::invalid_argument("Source dimensions exceed resize index bounds.");
  const bool swap = static_cast<unsigned>(o) >= 5;
  const uint32_t width = swap ? crop.height : crop.width, height = swap ? crop.width : crop.height;
  const auto pixel = [&](uint32_t x, uint32_t y) {
    uint32_t sx = x, sy = y;
    switch (o) {
    case Orientation::upright:
      break;
    case Orientation::mirrorHorizontal:
      sx = crop.width - 1 - x;
      break;
    case Orientation::rotate180:
      sx = crop.width - 1 - x;
      sy = crop.height - 1 - y;
      break;
    case Orientation::mirrorVertical:
      sy = crop.height - 1 - y;
      break;
    case Orientation::transpose:
      sx = y;
      sy = x;
      break;
    case Orientation::rotate90:
      sx = y;
      sy = crop.height - 1 - x;
      break;
    case Orientation::transverse:
      sx = crop.width - 1 - y;
      sy = crop.height - 1 - x;
      break;
    case Orientation::rotate270:
      sx = crop.width - 1 - y;
      sy = x;
      break;
    }
    return view.at(crop.left + sx, crop.top + sy);
  };
  if (_width != width) {
    auto kernels = weights(width, _targetWidth);
    _xWeights = std::move(kernels);
    _width = width;
  }
  if (_height != height) {
    auto kernels = weights(height, _targetHeight);
    if (height > _horizontal.max_size() / _targetWidth) {
      throw std::length_error("Corner model resize workspace is too large.");
    }
    _horizontal.resize(static_cast<size_t>(_targetWidth) * height);
    _yWeights = std::move(kernels);
    _height = height;
  }
  for (uint32_t y = 0; y < height; ++y) {
    for (uint32_t x = 0; x < _targetWidth; ++x) {
      const auto &kernel = _xWeights[x];
      uint32_t red = kRounding, green = kRounding, blue = kRounding;
      uint32_t sourceX = kernel.start;
      for (const auto weight : kernel.coefficients) {
        const auto pixels = pixel(sourceX++, y);
        if (pixels.alpha != 255) {
          throw std::invalid_argument("DocCornerNet preprocessing requires opaque source pixels.");
        }
        red += pixels.red * weight;
        green += pixels.green * weight;
        blue += pixels.blue * weight;
      }
      _horizontal[static_cast<size_t>(y) * _targetWidth + x] =
          (rounded(red) << 16) | (rounded(green) << 8) | rounded(blue);
    }
  }
  auto *tensor = output.data();
  for (uint32_t y = 0; y < _targetHeight; ++y) {
    const auto &kernel = _yWeights[y];
    for (uint32_t x = 0; x < _targetWidth; ++x) {
      uint32_t red = kRounding, green = kRounding, blue = kRounding;
      size_t offset = static_cast<size_t>(kernel.start) * _targetWidth + x;
      for (const auto weight : kernel.coefficients) {
        const auto pixel = _horizontal[offset];
        red += ((pixel >> 16) & 255) * weight;
        green += ((pixel >> 8) & 255) * weight;
        blue += (pixel & 255) * weight;
        offset += _targetWidth;
      }
      *tensor++ = _normalization[0][rounded(red)];
      *tensor++ = _normalization[1][rounded(green)];
      *tensor++ = _normalization[2][rounded(blue)];
    }
  }
}
} // namespace document_scanner
