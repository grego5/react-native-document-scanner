#include "NativeLiveGuide.hpp"
#include <stdexcept>
namespace margelo::nitro::documentscanner {
using namespace facebook::jni;
using namespace document_scanner;
namespace {
Quad quad(alias_ref<JArrayDouble> a) {
  if (!a || a->size() != 8)
    throw std::invalid_argument("Invalid guide quad.");
  const auto v = a->getRegion(0, 8);
  return {Point{v[0], v[1]}, Point{v[2], v[3]}, Point{v[4], v[5]}, Point{v[6], v[7]}};
}
} // namespace
local_ref<NativeLiveGuide::jhybriddata> NativeLiveGuide::initHybrid(alias_ref<jhybridobject>) {
  return makeCxxInstance();
}
local_ref<JArrayDouble> NativeLiveGuide::updateNative(alias_ref<JArrayDouble> q, jint w, jint h, jlong sensor,
                                                      jlong arrival, jboolean retain, alias_ref<JArrayLong> times) {
  if (!times || times->size() != 2)
    throw std::invalid_argument("Invalid guide metadata.");
  guide::Input input{q ? std::optional{quad(q)} : std::nullopt, w, h, sensor, arrival};
  auto result = retain ? guide_.retain(input) : std::optional{guide_.update(input)};
  if (!result)
    return nullptr;
  const auto &o = *result;
  const jlong metadata[]{o.acceptanceId, o.deadlineNs};
  times->setRegion(0, 2, metadata);
  const size_t size = o.accepted ? 12 : 4;
  double v[12]{double(o.status), double(o.decision), double(o.chainLength), o.expired ? 1.0 : 0.0};
  if (o.accepted)
    for (size_t i = 0; i < 4; ++i) {
      v[4 + i * 2] = (*o.accepted)[i].x;
      v[5 + i * 2] = (*o.accepted)[i].y;
    }
  auto a = JArrayDouble::newArray(size);
  a->setRegion(0, size, v);
  return a;
}
jlong NativeLiveGuide::intervalNative(jboolean trusted, jboolean tracked, jboolean degraded, alias_ref<JString> doubt) {
  return adaptive_.interval(trusted, tracked, degraded, doubt ? std::optional{doubt->toStdString()} : std::nullopt);
}
jboolean NativeLiveGuide::changedNative(alias_ref<jclass>, alias_ref<JArrayDouble> a, alias_ref<JArrayDouble> b, jint w,
                                        jint h) {
  return guide::Adaptive::changed(quad(a), quad(b), w, h);
}
void NativeLiveGuide::referenceNative(alias_ref<JArrayDouble> q) {
  adaptive_.reference(q ? std::optional{quad(q)} : std::nullopt);
}
local_ref<JString> NativeLiveGuide::doubtNative(alias_ref<JArrayDouble> q, jint w, jint h) {
  auto result = adaptive_.doubt(q ? std::optional{quad(q)} : std::nullopt, w, h);
  return result ? make_jstring(*result) : nullptr;
}
void NativeLiveGuide::registerNatives() {
  registerHybrid({makeNativeMethod("referenceNative", NativeLiveGuide::referenceNative),
                  makeNativeMethod("hasReferenceNative", NativeLiveGuide::hasReferenceNative),
                  makeNativeMethod("doubtNative", NativeLiveGuide::doubtNative),
                  makeNativeMethod("scoreRejectedNative", scoreRejectedNative),
                  makeNativeMethod("keepScorePauseNative", keepScorePauseNative),
                  makeNativeMethod("initHybrid", initHybrid),
                  makeNativeMethod("configureNative", NativeLiveGuide::configureNative),
                  makeNativeMethod("resetNative", NativeLiveGuide::resetNative),
                  makeNativeMethod("updateNative", NativeLiveGuide::updateNative),
                  makeNativeMethod("adaptiveResetNative", NativeLiveGuide::adaptiveResetNative),
                  makeNativeMethod("confirmedNative", NativeLiveGuide::confirmedNative),
                  makeNativeMethod("intervalNative", NativeLiveGuide::intervalNative),
                  makeNativeMethod("statusNative", NativeLiveGuide::statusNative),
                  makeNativeMethod("changedNative", changedNative)});
}
} // namespace margelo::nitro::documentscanner
