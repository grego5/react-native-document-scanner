#include "FieldKernels.hpp"

#include <opencv2/core/hal/intrin.hpp>
#include <opencv2/core/utility.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <vector>

namespace margelo::nitro::documentscanner {

using namespace facebook::jni;

namespace {
// Rows per parallel task.
constexpr int kStripRows = 16;

/**
 * A small float map enlarged bilinearly to width x height as cv::resize with INTER_LINEAR would, handed out a row at a
 * time: stretched to full width once, each row then blends the two nearest stretched rows.
 */
class Field {
public:
  Field(const cv::Mat& small, int width, int height) : height_(height) {
    cv::resize(small, wide_, cv::Size(width, small.rows), 0, 0, cv::INTER_LINEAR);
  }

  /** Writes row [y] of the enlarged map into [out], one float per column. */
  void row(int y, float* out) const {
    const int last = wide_.rows - 1;
    const double source = std::clamp((y + .5) * wide_.rows / height_ - .5, 0.0, static_cast<double>(last));
    const int low = static_cast<int>(std::floor(source));
    const float t = static_cast<float>(source - low);
    const float* a = wide_.ptr<float>(low);
    const float* b = wide_.ptr<float>(std::min(low + 1, last));
    for (int x = 0; x < wide_.cols; ++x) out[x] = (1.f - t) * a[x] + t * b[x];
  }

private:
  cv::Mat wide_;
  int height_;
};

using cv::v_float32x4;
using cv::v_uint8x16;

/** Sixteen 8-bit values as four float vectors. */
inline void widen(const v_uint8x16& bytes, v_float32x4 (&out)[4]) {
  cv::v_uint16x8 low, high;
  cv::v_expand(bytes, low, high);
  cv::v_uint32x4 words[4];
  cv::v_expand(low, words[0], words[1]);
  cv::v_expand(high, words[2], words[3]);
  for (int i = 0; i < 4; ++i) out[i] = cv::v_cvt_f32(cv::v_reinterpret_as_s32(words[i]));
}

/** Four float vectors back to sixteen 8-bit values, rounded half to even and saturated, as cv::saturate_cast. */
inline v_uint8x16 narrow(const v_float32x4 (&values)[4]) {
  return cv::v_pack_u(cv::v_pack(cv::v_round(values[0]), cv::v_round(values[1])),
                      cv::v_pack(cv::v_round(values[2]), cv::v_round(values[3])));
}

/** Row [y] of [level] enlarged and rounded to 8 bits, as Imgproc.resize then convertTo CV_8U would, in float. */
void paperRow(const Field& level, int y, int width, float* out) {
  level.row(y, out);
  int x = 0;
  for (; x <= width - 16; x += 16) {
    v_float32x4 v[4];
    for (int i = 0; i < 4; ++i) v[i] = cv::v_load(out + x + 4 * i);
    widen(narrow(v), v);
    for (int i = 0; i < 4; ++i) cv::v_store(out + x + 4 * i, v[i]);
  }
  for (; x < width; ++x) out[x] = cv::saturate_cast<uchar>(out[x]);
}

/** [v] × [scale] / [paper], zero where [paper] is zero, as Core.divide with a scale computes it. */
inline v_float32x4 divided(const v_float32x4& v, const v_float32x4& scale, const v_float32x4& paper) {
  const v_float32x4 zero = cv::v_setzero_f32();
  return cv::v_select(cv::v_eq(paper, zero), zero, cv::v_div(cv::v_mul(v, scale), paper));
}

inline uchar divided(float v, float scale, float paper) { return paper != 0 ? cv::saturate_cast<uchar>(v * scale / paper) : 0; }

std::vector<cv::Mat*> mats(alias_ref<JArrayLong> addresses) {
  const auto size = static_cast<int>(addresses->size());
  const auto values = addresses->getRegion(0, size);
  std::vector<cv::Mat*> result(size);
  for (int i = 0; i < size; ++i) result[i] = reinterpret_cast<cv::Mat*>(values[i]);
  return result;
}

/** Runs [body] for every row of a [height]-row image, strips of rows in parallel. */
template <typename Body> void forRows(int height, Body&& body) {
  cv::parallel_for_(cv::Range(0, (height + kStripRows - 1) / kStripRows), [&](const cv::Range& range) {
    for (int strip = range.start; strip < range.end; ++strip) {
      const int end = std::min(height, (strip + 1) * kStripRows);
      for (int y = strip * kStripRows; y < end; ++y) body(y);
    }
  });
}
} // namespace

void FieldKernels::registerNatives() {
  javaClassStatic()->registerNatives({
      makeNativeMethod("inkNative", FieldKernels::inkNative),
      makeNativeMethod("gainNative", FieldKernels::gainNative),
      makeNativeMethod("divideNative", FieldKernels::divideNative),
      makeNativeMethod("grayDivideNative", FieldKernels::grayDivideNative),
  });
}

/**
 * Each 8-bit channel x in place becomes x + a (x - blur) - k ink, with a from [amount] and k from [deepen] enlarged,
 * in float in that order and rounded once, as the OpenCV passes it replaces did. [ink] is 0 when there is none.
 */
void FieldKernels::inkNative(alias_ref<jclass>, alias_ref<JArrayLong> channelAddresses, alias_ref<JArrayLong> blurredAddresses,
                             jlong inkAddress, jlong deepenAddress, jlong amountAddress) {
  const auto channels = mats(channelAddresses);
  const auto blurred = mats(blurredAddresses);
  const cv::Mat* ink = reinterpret_cast<cv::Mat*>(inkAddress);
  const int width = channels.front()->cols;
  const int height = channels.front()->rows;
  const Field amount(*reinterpret_cast<cv::Mat*>(amountAddress), width, height);
  const Field deepen(*reinterpret_cast<cv::Mat*>(deepenAddress), width, height);
  forRows(height, [&](int y) {
    thread_local std::vector<float> a, k;
    a.resize(width);
    k.resize(width);
    amount.row(y, a.data());
    const uchar* inkRow = nullptr;
    if (ink != nullptr) {
      deepen.row(y, k.data());
      inkRow = ink->ptr<uchar>(y);
      for (int x = 0; x < width; ++x) k[x] = static_cast<float>(inkRow[x]) * k[x];
    }
    for (size_t c = 0; c < channels.size(); ++c) {
      uchar* pixel = channels[c]->ptr<uchar>(y);
      const uchar* soft = blurred[c]->ptr<uchar>(y);
      int x = 0;
      for (; x <= width - 16; x += 16) {
        v_float32x4 v[4], b[4];
        widen(cv::v_load(pixel + x), v);
        widen(cv::v_load(soft + x), b);
        for (int i = 0; i < 4; ++i) {
          v[i] = cv::v_add(v[i], cv::v_mul(cv::v_sub(v[i], b[i]), cv::v_load(a.data() + x + 4 * i)));
          if (inkRow != nullptr) v[i] = cv::v_sub(v[i], cv::v_load(k.data() + x + 4 * i));
        }
        cv::v_store(pixel + x, narrow(v));
      }
      if (inkRow != nullptr) {
        for (; x < width; ++x) {
          const float v = pixel[x];
          pixel[x] = cv::saturate_cast<uchar>((v + (v - soft[x]) * a[x]) - k[x]);
        }
      } else {
        for (; x < width; ++x) {
          const float v = pixel[x];
          pixel[x] = cv::saturate_cast<uchar>(v + (v - soft[x]) * a[x]);
        }
      }
    }
  });
}

/** Multiplies the colour channels of the 8-bit RGBA [rgba] in place by [gain] enlarged; alpha is unchanged. */
void FieldKernels::gainNative(alias_ref<jclass>, jlong rgbaAddress, jlong gainAddress) {
  cv::Mat& rgba = *reinterpret_cast<cv::Mat*>(rgbaAddress);
  const int width = rgba.cols;
  const Field gain(*reinterpret_cast<cv::Mat*>(gainAddress), width, rgba.rows);
  forRows(rgba.rows, [&](int y) {
    thread_local std::vector<float> g;
    g.resize(width);
    gain.row(y, g.data());
    uchar* row = rgba.ptr<uchar>(y);
    int x = 0;
    for (; x <= width - 16; x += 16) {
      v_uint8x16 channel[4];
      cv::v_load_deinterleave(row + 4 * x, channel[0], channel[1], channel[2], channel[3]);
      for (int c = 0; c < 3; ++c) {
        v_float32x4 v[4];
        widen(channel[c], v);
        for (int i = 0; i < 4; ++i) v[i] = cv::v_mul(v[i], cv::v_load(g.data() + x + 4 * i));
        channel[c] = narrow(v);
      }
      cv::v_store_interleave(row + 4 * x, channel[0], channel[1], channel[2], channel[3]);
    }
    for (uchar* pixel = row + 4 * x; x < width; ++x, pixel += 4) {
      pixel[0] = cv::saturate_cast<uchar>(pixel[0] * g[x]);
      pixel[1] = cv::saturate_cast<uchar>(pixel[1] * g[x]);
      pixel[2] = cv::saturate_cast<uchar>(pixel[2] * g[x]);
    }
  });
}

/**
 * Each channel c of the 8-bit [source], 1 or 3 interleaved, divided by the paper (see paperRow) with scale [scales][c],
 * into the single-channel 8-bit [into][c], already sized; with one channel, [into] may be [source].
 */
void FieldKernels::divideNative(alias_ref<jclass>, jlong sourceAddress, alias_ref<JArrayLong> intoAddresses, jlong levelAddress,
                                alias_ref<JArrayFloat> scaleValues) {
  const cv::Mat& source = *reinterpret_cast<cv::Mat*>(sourceAddress);
  const auto into = mats(intoAddresses);
  const int channels = source.channels();
  const auto scales = scaleValues->getRegion(0, channels);
  const int width = source.cols;
  const Field level(*reinterpret_cast<cv::Mat*>(levelAddress), width, source.rows);
  forRows(source.rows, [&](int y) {
    thread_local std::vector<float> paper;
    paper.resize(width);
    paperRow(level, y, width, paper.data());
    const uchar* in = source.ptr<uchar>(y);
    uchar* out[3] = {};
    for (int c = 0; c < channels; ++c) out[c] = into[c]->ptr<uchar>(y);
    int x = 0;
    for (; x <= width - 16; x += 16) {
      v_uint8x16 bytes[3];
      if (channels == 3) {
        cv::v_load_deinterleave(in + 3 * x, bytes[0], bytes[1], bytes[2]);
      } else {
        bytes[0] = cv::v_load(in + x);
      }
      for (int c = 0; c < channels; ++c) {
        const v_float32x4 scale = cv::v_setall_f32(scales[c]);
        v_float32x4 v[4];
        widen(bytes[c], v);
        for (int i = 0; i < 4; ++i) v[i] = divided(v[i], scale, cv::v_load(paper.data() + x + 4 * i));
        cv::v_store(out[c] + x, narrow(v));
      }
    }
    for (; x < width; ++x) {
      for (int c = 0; c < channels; ++c) out[c][x] = divided(in[channels * x + c], scales[c], paper[x]);
    }
  });
}

/**
 * The gray of the 8-bit 3-channel [source], weights[0..2] times its channels summed and rounded to 8 bits as
 * Core.transform does, divided by the paper (see paperRow) with [scale], into the 8-bit [into], already sized.
 */
void FieldKernels::grayDivideNative(alias_ref<jclass>, jlong sourceAddress, jlong intoAddress, jlong levelAddress,
                                    alias_ref<JArrayFloat> weightValues, alias_ref<JArrayFloat> gainValues, jfloat scale) {
  const cv::Mat& source = *reinterpret_cast<cv::Mat*>(sourceAddress);
  cv::Mat& into = *reinterpret_cast<cv::Mat*>(intoAddress);
  const auto w = weightValues->getRegion(0, 3);
  const auto gain = gainValues->getRegion(0, 3);
  const int width = source.cols;
  const Field level(*reinterpret_cast<cv::Mat*>(levelAddress), width, source.rows);
  forRows(source.rows, [&](int y) {
    thread_local std::vector<float> paper;
    paper.resize(width);
    paperRow(level, y, width, paper.data());
    const uchar* in = source.ptr<uchar>(y);
    uchar* out = into.ptr<uchar>(y);
    const v_float32x4 w0 = cv::v_setall_f32(w[0]), w1 = cv::v_setall_f32(w[1]), w2 = cv::v_setall_f32(w[2]);
    const v_float32x4 gain0 = cv::v_setall_f32(gain[0]), gain1 = cv::v_setall_f32(gain[1]), gain2 = cv::v_setall_f32(gain[2]);
    const v_float32x4 white = cv::v_setall_f32(255.f);
    const v_float32x4 s = cv::v_setall_f32(scale);
    int x = 0;
    for (; x <= width - 16; x += 16) {
      v_uint8x16 bytes[3];
      cv::v_load_deinterleave(in + 3 * x, bytes[0], bytes[1], bytes[2]);
      v_float32x4 b[4], g[4], r[4];
      widen(bytes[0], b);
      widen(bytes[1], g);
      widen(bytes[2], r);
      // Each balanced channel clips at white before it is weighted, as balancing in its own pass would.
      for (int i = 0; i < 4; ++i) {
        b[i] = cv::v_add(cv::v_add(cv::v_mul(cv::v_min(cv::v_mul(b[i], gain0), white), w0),
                                   cv::v_mul(cv::v_min(cv::v_mul(g[i], gain1), white), w1)),
                         cv::v_mul(cv::v_min(cv::v_mul(r[i], gain2), white), w2));
      }
      widen(narrow(b), b);
      for (int i = 0; i < 4; ++i) b[i] = divided(b[i], s, cv::v_load(paper.data() + x + 4 * i));
      cv::v_store(out + x, narrow(b));
    }
    for (; x < width; ++x) {
      const float gray = cv::saturate_cast<uchar>(std::min(in[3 * x] * gain[0], 255.f) * w[0] +
                                                  std::min(in[3 * x + 1] * gain[1], 255.f) * w[1] +
                                                  std::min(in[3 * x + 2] * gain[2], 255.f) * w[2]);
      out[x] = divided(gray, scale, paper[x]);
    }
  });
}

} // namespace margelo::nitro::documentscanner
