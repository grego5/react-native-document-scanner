#pragma once
#include <fbjni/fbjni.h>
#include <document_scanner/LiveGuide.hpp>
namespace margelo::nitro::documentscanner {
class NativeLiveGuide final : public facebook::jni::HybridClass<NativeLiveGuide> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeLiveGuide;";
  static void registerNatives();

private:
  friend HybridBase;
  NativeLiveGuide() = default;
  static facebook::jni::local_ref<jhybriddata> initHybrid(facebook::jni::alias_ref<jhybridobject>);
  void configureNative(jdouble area, jlong hold) { guide_.configure({area, hold}); }
  void resetNative() { guide_.reset(); }
  facebook::jni::local_ref<facebook::jni::JArrayDouble>
      updateNative(facebook::jni::alias_ref<facebook::jni::JArrayDouble>, jint, jint, jlong, jlong, jboolean,
                   facebook::jni::alias_ref<facebook::jni::JArrayLong>);
  static jboolean scoreRejectedNative(facebook::jni::alias_ref<jclass>, jdouble score, jboolean hasScore,
                                      jdouble minimum) {
    return document_scanner::guide::scoreRejected(hasScore ? std::optional<double>{score} : std::nullopt, minimum);
  }
  static jboolean keepScorePauseNative(facebook::jni::alias_ref<jclass>, jboolean current, jdouble speed,
                                       jboolean measured, jint width) {
    return document_scanner::guide::keepScorePause(current, measured ? std::optional<double>{speed} : std::nullopt,
                                                   width);
  }
  document_scanner::guide::Stabilizer guide_;
};
} // namespace margelo::nitro::documentscanner
