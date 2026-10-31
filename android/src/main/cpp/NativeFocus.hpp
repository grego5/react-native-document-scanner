#pragma once
#include <fbjni/fbjni.h>
#include <document_scanner/Focus.hpp>
namespace margelo::nitro::documentscanner {
class NativeFocus final : public facebook::jni::HybridClass<NativeFocus> {
public:
  static constexpr auto kJavaDescriptor = "Lcom/margelo/nitro/documentscanner/processing/NativeFocus;";
  static void registerNatives();

private:
  friend HybridBase;
  NativeFocus() = default;
  static facebook::jni::local_ref<jhybriddata> initHybrid(facebook::jni::alias_ref<jhybridobject>);
  jint observeNative(facebook::jni::alias_ref<facebook::jni::JArrayDouble>, jboolean, jboolean, jlong, jboolean,
                     jboolean);
  void documentResetNative() { policy_.document().reset(); }
  void documentSettledNative(jint result, jlong now) { policy_.document().settled(completion(result), now); }
  jlong startNative(jint owner, jlong now);
  jboolean completeNative(jlong token, jint result, jlong now) {
    return policy_.complete(token, completion(result), now);
  }
  jboolean releaseDocumentNative(jboolean shot) { return policy_.releaseDocument(shot); }
  jboolean expireTapNative(jlong token, jlong now, jboolean shot) { return policy_.expireTap(token, now, shot); }
  jboolean afterCaptureNative(jlong now, jboolean wanted) { return policy_.afterCapture(now, wanted); }
  jboolean resetNative(jboolean keep, jboolean shot) { return policy_.reset(keep, shot); }
  void unbindNative() { policy_.unbind(); }
  facebook::jni::local_ref<facebook::jni::JArrayLong> snapshotNative();
  facebook::jni::local_ref<facebook::jni::JArrayLong> deadlinesNative(jlong);
  jint wakeNative(jlong now, jboolean shot) { return static_cast<jint>(policy_.wakeDocument(now, shot)); }
  static document_scanner::focus::Completion completion(jint);
  document_scanner::focus::Policy policy_;
};
} // namespace margelo::nitro::documentscanner
