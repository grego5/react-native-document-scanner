#include "NativeFocus.hpp"
#include <stdexcept>
namespace margelo::nitro::documentscanner {
using namespace facebook::jni;
namespace f = document_scanner::focus;
local_ref<NativeFocus::jhybriddata> NativeFocus::initHybrid(alias_ref<jhybridobject>) { return makeCxxInstance(); }
f::Completion NativeFocus::completion(jint result) {
  switch (result) {
  case -1:
    return f::Completion::unknown;
  case 0:
    return f::Completion::failure;
  case 1:
    return f::Completion::success;
  default:
    throw std::invalid_argument("Invalid focus completion.");
  }
}
jint NativeFocus::observeNative(alias_ref<JArrayDouble> q, jboolean trusted, jboolean failed, jlong now, jboolean shot,
                                jboolean parked) {
  std::optional<document_scanner::Quad> quad;
  if (q) {
    if (q->size() != 8)
      throw std::invalid_argument("Invalid focus quad.");
    const auto v = q->getRegion(0, 8);
    quad.emplace(document_scanner::Quad{document_scanner::Point{v[0], v[1]}, document_scanner::Point{v[2], v[3]},
                                        document_scanner::Point{v[4], v[5]}, document_scanner::Point{v[6], v[7]}});
  }
  return static_cast<jint>(policy_.observe(quad, trusted, failed, now, shot, parked));
}
jlong NativeFocus::startNative(jint owner, jlong now) {
  if (owner != 0 && owner != 1)
    throw std::invalid_argument("Invalid focus owner.");
  return policy_.start(owner == 0 ? f::Owner::tap : f::Owner::document, now);
}
local_ref<JArrayLong> NativeFocus::snapshotNative() {
  const auto &s = policy_.snapshot();
  const jlong v[]{s.generation,
                  s.owner ? static_cast<jlong>(*s.owner) : -1,
                  s.locked ? (*s.locked ? 1 : 0) : -1,
                  s.heldUntil,
                  s.scanToken,
                  s.pending ? 1 : 0};
  auto a = JArrayLong::newArray(6);
  a->setRegion(0, 6, v);
  return a;
}
local_ref<JArrayLong> NativeFocus::deadlinesNative(jlong now) {
  const auto d = policy_.deadlines(now);
  const jlong v[]{d.tap.value_or(0), d.scan.value_or(0), d.document.value_or(0)};
  auto a = JArrayLong::newArray(3);
  a->setRegion(0, 3, v);
  return a;
}
void NativeFocus::registerNatives() {
  registerHybrid({makeNativeMethod("initHybrid", initHybrid),
                  makeNativeMethod("observeNative", NativeFocus::observeNative),
                  makeNativeMethod("documentResetNative", NativeFocus::documentResetNative),
                  makeNativeMethod("documentSettledNative", NativeFocus::documentSettledNative),
                  makeNativeMethod("startNative", NativeFocus::startNative),
                  makeNativeMethod("completeNative", NativeFocus::completeNative),
                  makeNativeMethod("releaseDocumentNative", NativeFocus::releaseDocumentNative),
                  makeNativeMethod("expireTapNative", NativeFocus::expireTapNative),
                  makeNativeMethod("afterCaptureNative", NativeFocus::afterCaptureNative),
                  makeNativeMethod("resetNative", NativeFocus::resetNative),
                  makeNativeMethod("unbindNative", NativeFocus::unbindNative),
                  makeNativeMethod("snapshotNative", NativeFocus::snapshotNative),
                  makeNativeMethod("deadlinesNative", NativeFocus::deadlinesNative),
                  makeNativeMethod("wakeNative", NativeFocus::wakeNative)});
}
} // namespace margelo::nitro::documentscanner
