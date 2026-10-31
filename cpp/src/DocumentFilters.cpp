#include <document_scanner/DocumentFilters.hpp>
#include "internal/Kernels.hpp"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace document_scanner {
namespace {
int roundPositive(double value) { return int(std::floor(value + .5)); }
int odd(double value) { return std::max(3, roundPositive(value)) | 1; }
cv::Mat sourceMat(const PixelLease &source) {
  const auto &view = source.view();
  const auto &l = view.layout();
  if (l.format != PixelFormat::rgba8 || l.pixelStride != 4 || l.orientation != Orientation::upright || l.crop.left ||
      l.crop.top || l.crop.width != l.size.width || l.crop.height != l.size.height || l.size.width > 4096 ||
      l.size.height > 4096)
    throw std::invalid_argument("Document filters require full upright RGBA8 pixels up to 4096 per side.");
  return cv::Mat(int(l.size.height), int(l.size.width), CV_8UC4, const_cast<std::byte *>(view.bytes().data()),
                 l.rowStride);
}
OwnedPixels publish(const cv::Mat &mat, PixelFormat format) {
  const Size size{uint32_t(mat.cols), uint32_t(mat.rows)};
  const size_t stride = size_t(mat.cols) * mat.elemSize();
  std::vector<std::byte> bytes(stride * mat.rows);
  for (int r = 0; r < mat.rows; ++r)
    std::memcpy(bytes.data() + r * stride, mat.ptr(r), stride);
  return OwnedPixels(std::move(bytes),
                     {format, size, stride, mat.elemSize(), {0, 0, size.width, size.height}, Orientation::upright});
}
void shadows(cv::Mat &rgba) {
  if (std::min(rgba.cols, rgba.rows) < 8)
    return;
  const double scale = std::min(1., 512. / std::max(rgba.cols, rgba.rows));
  cv::Mat small, paper;
  cv::resize(rgba, small, {}, scale, scale, cv::INTER_AREA);
  std::vector<cv::Mat> channels;
  cv::split(small, channels);
  cv::max(channels[0], channels[1], paper);
  cv::max(paper, channels[2], paper);
  const int side = odd(std::min(small.cols, small.rows) * .025);
  cv::morphologyEx(paper, paper, cv::MORPH_CLOSE, cv::getStructuringElement(cv::MORPH_RECT, {side, side}));
  paper.convertTo(paper, CV_32F);
  cv::GaussianBlur(paper, paper, {}, 1.5);
  const int left = int(paper.cols * .05), top = int(paper.rows * .05);
  const cv::Mat inner =
      paper(cv::Range(top, std::max(top + 1, paper.rows - top)), cv::Range(left, std::max(left + 1, paper.cols - left)))
          .clone();
  std::vector<float> values(inner.ptr<float>(), inner.ptr<float>() + inner.total());
  std::sort(values.begin(), values.end());
  const double reference = values[roundPositive((values.size() - 1) * .9)];
  cv::max(paper, reference * .05, paper);
  cv::Mat gain;
  cv::divide(reference, paper, gain);
  cv::min(gain, 2., gain);
  cv::max(gain, 1., gain);
  double maximum;
  cv::minMaxLoc(gain, nullptr, &maximum);
  if (maximum > 1. + 1. / 255)
    internal::gain(rgba, gain);
}
struct Paper {
  cv::Mat level;
  std::array<double, 3> gains;
};
Paper evenLighting(const cv::Mat &bgr) {
  const double scale = std::min(1., 400. / std::min(bgr.cols, bgr.rows));
  cv::Mat small, luma, bright;
  cv::resize(bgr, small, {}, scale, scale, cv::INTER_AREA);
  small.convertTo(small, CV_32FC3);
  cv::cvtColor(small, luma, cv::COLOR_BGR2GRAY);
  double maximum;
  cv::minMaxLoc(luma, nullptr, &maximum);
  cv::threshold(luma, bright, maximum * .8, 255., cv::THRESH_BINARY);
  bright.convertTo(bright, CV_8U);
  const auto means = cv::mean(small, bright);
  const double neutral = (means[0] + means[1] + means[2]) / 3.;
  const cv::Scalar gains(neutral / std::max(means[0], 1.), neutral / std::max(means[1], 1.),
                         neutral / std::max(means[2], 1.));
  cv::multiply(small, gains, small);
  cv::cvtColor(small, luma, cv::COLOR_BGR2GRAY);
  std::vector<cv::Mat> channels;
  cv::split(small, channels);
  cv::Mat high, low, chroma, neutralMask;
  cv::max(channels[0], channels[1], high);
  cv::max(high, channels[2], high);
  cv::min(channels[0], channels[1], low);
  cv::min(low, channels[2], low);
  cv::subtract(high, low, chroma);
  cv::multiply(high, .12, high);
  cv::compare(chroma, high, neutralMask, cv::CMP_LE);
  const int shortSide = std::min(small.cols, small.rows);
  const int wideSide = odd(shortSide * .25), strokeSide = odd(shortSide * .05);
  cv::Mat nearby, brightMask, paperMask;
  cv::resize(luma, nearby, {}, .25, .25, cv::INTER_AREA);
  const int nearbySide = odd(std::min(nearby.cols, nearby.rows));
  cv::dilate(nearby, nearby, cv::Mat::ones(nearbySide, nearbySide, CV_8U));
  cv::resize(nearby, nearby, luma.size(), 0, 0, cv::INTER_LINEAR);
  cv::multiply(nearby, .6, nearby);
  cv::compare(luma, nearby, brightMask, cv::CMP_GE);
  cv::bitwise_and(neutralMask, brightMask, paperMask);
  cv::Mat weight, weighted;
  paperMask.convertTo(weight, CV_32F, 1. / 255);
  cv::multiply(luma, weight, weighted);
  double global = cv::mean(luma, paperMask)[0];
  if (!(global > 0))
    cv::minMaxLoc(luma, nullptr, &global);
  cv::Mat level(luma.size(), CV_32F, cv::Scalar(global)), sum, coverage, covered;
  // Coarse to fine: the nearest scale with enough neutral, bright paper wins.
  for (int side : {shortSide | 1, wideSide, strokeSide}) {
    cv::blur(weighted, sum, {side, side});
    cv::blur(weight, coverage, {side, side});
    cv::threshold(coverage, covered, .05, 255., cv::THRESH_BINARY);
    covered.convertTo(covered, CV_8U);
    cv::divide(sum, coverage, sum);
    sum.copyTo(level, covered);
  }
  cv::blur(level, level, {strokeSide, strokeSide});
  cv::minMaxLoc(level, nullptr, &maximum);
  cv::max(level, maximum * .5, level);
  return {level, {gains[0], gains[1], gains[2]}};
}
float quantile(const cv::Mat &region) {
  cv::Mat histogram;
  const int channel = 0, bins = 256;
  const float range[] = {0, 256};
  const float *ranges[] = {range};
  cv::calcHist(&region, 1, &channel, {}, histogram, 1, &bins, ranges);
  float count = 0;
  for (int i = 0; i < 256; ++i)
    count += histogram.at<float>(i);
  const double limit = count * .99;
  float total = 0;
  for (int i = 0; i < 256; ++i) {
    total += histogram.at<float>(i);
    if (total >= limit)
      return float(i);
  }
  return 255;
}
float percentile(std::vector<float> values) {
  std::sort(values.begin(), values.end());
  return values[roundPositive((values.size() - 1) * .9)];
}
struct InkDetail {
  cv::Mat ink, deepen, amount;
};
InkDetail measure(const cv::Mat &gray) {
  const auto uniform = [] {
    return InkDetail{{}, cv::Mat(1, 1, CV_32F, cv::Scalar(0)), cv::Mat(1, 1, CV_32F, cv::Scalar(.6f))};
  };
  if (std::min(gray.cols, gray.rows) < 64)
    return uniform();
  cv::Mat ink, half, smooth, signedEdges, edges;
  const int side = odd(std::min(gray.cols, gray.rows) * .01);
  cv::morphologyEx(gray, ink, cv::MORPH_BLACKHAT, cv::getStructuringElement(cv::MORPH_RECT, {side, side}));
  cv::resize(gray, half, {}, .5, .5, cv::INTER_AREA);
  // Keep depth-changing sharpness intermediates independently owned. Reusing
  // one Mat across 8U/16S/8U produced allocation-dependent host sharpness maps.
  cv::GaussianBlur(half, smooth, {}, 1.);
  cv::Laplacian(smooth, signedEdges, CV_16S);
  cv::convertScaleAbs(signedEdges, edges);
  const double tileSide = std::min(gray.cols, gray.rows) / 8.;
  const int rows = std::max(1, roundPositive(gray.rows / tileSide));
  const int cols = std::max(1, roundPositive(gray.cols / tileSide));
  std::vector<float> depth(rows * cols), sharpness(rows * cols), validDepth, validRelative;
  for (int r = 0; r < rows; ++r)
    for (int c = 0; c < cols; ++c) {
      const int y0 = r * gray.rows / rows, y1 = (r + 1) * gray.rows / rows;
      const int x0 = c * gray.cols / cols, x1 = (c + 1) * gray.cols / cols;
      const int i = r * cols + c;
      depth[i] = quantile(ink(cv::Range(y0, y1), cv::Range(x0, x1)));
      sharpness[i] = quantile(
          edges(cv::Range(y0 / 2, std::max(y0 / 2 + 1, y1 / 2)), cv::Range(x0 / 2, std::max(x0 / 2 + 1, x1 / 2))));
      if (depth[i] >= 24) {
        validDepth.push_back(depth[i]);
        validRelative.push_back(sharpness[i] / std::max(depth[i], 1.f));
      }
    }
  if (validDepth.empty())
    return uniform();
  const float target = percentile(validDepth), best = percentile(validRelative);
  // Keep these calculations float32, including neighbour sums, as in Kotlin's FloatArray maps.
  std::vector<float> stretch(depth.size(), 1.f), extra(depth.size(), 0.f);
  for (size_t i = 0; i < depth.size(); ++i)
    if (depth[i] >= 24) {
      stretch[i] = std::clamp(target / depth[i], 1.f, 2.5f);
      if (best > 0)
        extra[i] = std::clamp(1.f - (sharpness[i] / std::max(depth[i], 1.f)) / best, 0.f, .6f) / .6f * 1.2f;
    }
  cv::Mat deepen(rows, cols, CV_32F), amount(rows, cols, CV_32F);
  for (int r = 0; r < rows; ++r)
    for (int c = 0; c < cols; ++c) {
      const int i = r * cols + c;
      float s = stretch[i], e = extra[i];
      if (depth[i] < 24) {
        int count = 0;
        float sumS = 0, sumExtra = 0;
        for (int nr = std::max(0, r - 1); nr <= std::min(rows - 1, r + 1); ++nr)
          for (int nc = std::max(0, c - 1); nc <= std::min(cols - 1, c + 1); ++nc) {
            const int n = nr * cols + nc;
            if (depth[n] >= 24) {
              ++count;
              sumS += stretch[n];
              sumExtra += extra[n];
            }
          }
        if (count > 0) {
          s = sumS / count;
          e = sumExtra / count;
        }
      }
      deepen.at<float>(r, c) = s - 1.f;
      amount.at<float>(r, c) = .6f + e;
    }
  return {ink, deepen, amount};
}
cv::Mat toneCurve(bool document) {
  const double black = document ? .12 : .08, white = document ? .96 : .88, gamma = document ? 1.6 : 1.3;
  cv::Mat curve(1, 256, CV_8U);
  for (int i = 0; i < 256; ++i)
    curve.at<uchar>(i) =
        uchar(roundPositive(std::pow(std::clamp((i / 255. - black) / (white - black), 0., 1.), gamma) * 255.));
  return curve;
}
void finish(std::vector<cv::Mat> &channels, const InkDetail &detail, bool document) {
  std::vector<cv::Mat> blurred(channels.size());
  std::vector<cv::Mat *> channelPointers, blurredPointers;
  for (size_t i = 0; i < channels.size(); ++i) {
    cv::GaussianBlur(channels[i], blurred[i], {}, 1.);
    channelPointers.push_back(&channels[i]);
    blurredPointers.push_back(&blurred[i]);
  }
  internal::ink(channelPointers, blurredPointers, detail.ink.empty() ? nullptr : &detail.ink, detail.deepen,
                detail.amount);
  const auto curve = toneCurve(document);
  for (auto &channel : channels)
    cv::LUT(channel, curve, channel);
}
} // namespace
OwnedPixels removeDocumentShadows(const PixelLease &source) {
  auto rgba = sourceMat(source).clone();
  shadows(rgba);
  return publish(rgba, PixelFormat::rgba8);
}
OwnedPixels filterDocument(const PixelLease &source, DocumentFilter filter, bool removeShadows) {
  if (filter != DocumentFilter::none && filter != DocumentFilter::enhance && filter != DocumentFilter::grayscale &&
      filter != DocumentFilter::blackWhite)
    throw std::invalid_argument("Unknown document filter.");
  cv::Mat rgba = sourceMat(source);
  if (removeShadows) {
    rgba = rgba.clone();
    shadows(rgba);
  }
  cv::Mat page;
  cv::cvtColor(rgba, page, cv::COLOR_RGBA2BGR);
  if (filter == DocumentFilter::none) {
    cv::cvtColor(page, page, cv::COLOR_BGR2RGB);
    return publish(page, PixelFormat::rgb8);
  }
  const auto paper = evenLighting(page);
  // The JNI kernel boundary previously narrowed gains and scales separately.
  const std::array<float, 3> gains{float(paper.gains[0]), float(paper.gains[1]), float(paper.gains[2])};
  if (filter == DocumentFilter::enhance) {
    std::vector<cv::Mat> channels(3);
    std::vector<cv::Mat *> into;
    for (auto &channel : channels) {
      channel.create(page.size(), CV_8U);
      into.push_back(&channel);
    }
    const float scales[]{float(255. * paper.gains[0]), float(255. * paper.gains[1]), float(255. * paper.gains[2])};
    internal::divide(page, into, paper.level, scales);
    cv::merge(channels, page);
    cv::Mat gray;
    cv::cvtColor(page, gray, cv::COLOR_BGR2GRAY);
    finish(channels, measure(gray), false);
    cv::merge(channels, page);
    cv::cvtColor(page, page, cv::COLOR_BGR2RGB);
    return publish(page, PixelFormat::rgb8);
  }
  const float weights[]{.114f, .587f, .299f};
  cv::Mat gray(page.size(), CV_8U);
  internal::grayDivide(page, gray, paper.level, weights, gains.data(), 255.f);
  std::vector<cv::Mat> channels{gray};
  finish(channels, measure(gray), filter == DocumentFilter::blackWhite);
  return publish(gray, PixelFormat::gray8);
}
} // namespace document_scanner
