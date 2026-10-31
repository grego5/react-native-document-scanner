#include "FieldKernels.hpp"

#include "internal/Kernels.hpp"
#include <vector>

namespace margelo::nitro::documentscanner {

using namespace facebook::jni;

namespace {
std::vector<cv::Mat*> mats(alias_ref<JArrayLong> addresses) {
  const auto size = static_cast<int>(addresses->size());
  const auto values = addresses->getRegion(0, size);
  std::vector<cv::Mat*> result(size);
  for (int i = 0; i < size; ++i) result[i] = reinterpret_cast<cv::Mat*>(values[i]);
  return result;
}

}

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
  document_scanner::internal::ink(channels, blurred, ink, *reinterpret_cast<cv::Mat*>(deepenAddress), *reinterpret_cast<cv::Mat*>(amountAddress));
}

/** Multiplies the colour channels of the 8-bit RGBA [rgba] in place by [gain] enlarged; alpha is unchanged. */
void FieldKernels::gainNative(alias_ref<jclass>, jlong rgbaAddress, jlong gainAddress) {
  cv::Mat& rgba = *reinterpret_cast<cv::Mat*>(rgbaAddress);
  document_scanner::internal::gain(rgba, *reinterpret_cast<cv::Mat*>(gainAddress));
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
  document_scanner::internal::divide(source, into, *reinterpret_cast<cv::Mat*>(levelAddress), &scales[0]);
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
  document_scanner::internal::grayDivide(source, into, *reinterpret_cast<cv::Mat*>(levelAddress), &w[0], &gain[0], scale);
}

} // namespace margelo::nitro::documentscanner
