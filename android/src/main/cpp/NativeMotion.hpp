#pragma once
#include <fbjni/ByteBuffer.h>
#include <fbjni/fbjni.h>
#include <document_scanner/Motion.hpp>

namespace margelo::nitro::documentscanner {
class NativeFlowEstimator final : public facebook::jni::HybridClass<NativeFlowEstimator> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeFlowEstimator;";
  static void registerNatives();

private:
  friend HybridBase;
  NativeFlowEstimator(jint edge, jint features, jlong gap) : estimator_(edge, features, gap) {}
  static facebook::jni::local_ref<jhybriddata> initHybrid(facebook::jni::alias_ref<jhybridobject>, jint, jint, jlong);
  facebook::jni::local_ref<facebook::jni::JArrayDouble>
      updateNative(facebook::jni::alias_ref<facebook::jni::JByteBuffer>, jint, jint, jint, jint, jint, jint, jint, jint,
                   jint, jint, jint, jlong, facebook::jni::alias_ref<facebook::jni::JArrayDouble>, jboolean,
                   facebook::jni::alias_ref<facebook::jni::JArrayLong>);
  void resetNative() { estimator_.reset(); }
  document_scanner::motion::FlowEstimator estimator_;
};
void registerMotionMath();
class NativeFlowHistory final : public facebook::jni::HybridClass<NativeFlowHistory> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeFlowHistory;";
  static void registerNatives();

private:
  friend HybridBase;
  NativeFlowHistory() = default;
  static facebook::jni::local_ref<jhybriddata> initHybrid(facebook::jni::alias_ref<jhybridobject>);
  void clearNative() { history_.clear(); }
  void addNative(jlong, jlong, facebook::jni::alias_ref<facebook::jni::JArrayDouble>);
  facebook::jni::local_ref<facebook::jni::JArrayDouble>
      warpNative(facebook::jni::alias_ref<facebook::jni::JArrayDouble>, jlong, jlong);
  document_scanner::motion::FlowHistory history_;
};
} // namespace margelo::nitro::documentscanner
