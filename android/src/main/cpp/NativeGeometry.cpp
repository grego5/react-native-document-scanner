#include "NativeGeometry.hpp"
#include <document_scanner/Geometry.hpp>
#include <stdexcept>

namespace margelo::nitro::documentscanner {
using namespace facebook::jni;
namespace {
namespace g = document_scanner::geometry;
using document_scanner::Orientation;
using document_scanner::Point;
using document_scanner::Quad;
using document_scanner::Size;
using Doubles = alias_ref<JArrayDouble>;

Size size(jint width, jint height) {
  if (width <= 0 || height <= 0)
    throw std::invalid_argument("Image dimensions must be positive.");
  return {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
}
Orientation orientation(jint value) {
  if (value < 1 || value > 8)
    throw std::invalid_argument("EXIF orientation must be between 1 and 8.");
  return static_cast<Orientation>(value);
}
Quad quad(Doubles values) {
  if (!values || values->size() != 8)
    throw std::invalid_argument("A quad requires eight coordinates.");
  const auto a = values->getRegion(0, 8);
  return {Point{a[0], a[1]}, Point{a[2], a[3]}, Point{a[4], a[5]}, Point{a[6], a[7]}};
}
local_ref<JArrayDouble> doubles(std::span<const double> values) {
  auto result = JArrayDouble::newArray(values.size());
  result->setRegion(0, values.size(), values.data());
  return result;
}
local_ref<JArrayDouble> points(std::span<const Point> values) {
  std::vector<double> result;
  result.reserve(values.size() * 2);
  for (Point p : values) {
    result.push_back(p.x);
    result.push_back(p.y);
  }
  return doubles(result);
}
// fbjni maps std::invalid_argument to CppException by default; preserve Kotlin's
// argument-failure contract explicitly. Render input uses its more specific type.
template <typename F> auto checked(F f) {
  try {
    return f();
  } catch (const std::invalid_argument &e) {
    throwNewJavaException("java/lang/IllegalArgumentException", e.what());
  }
}
local_ref<JArrayInt> uprightSizeNative(alias_ref<jclass>, jint w, jint h, jint o) {
  return checked([&]() -> local_ref<JArrayInt> {
    const auto s = g::uprightSize(size(w, h), orientation(o));
    const jint values[]{static_cast<jint>(s.width), static_cast<jint>(s.height)};
    auto result = JArrayInt::newArray(2);
    result->setRegion(0, 2, values);
    return result;
  });
}
local_ref<JArrayDouble> orientationNative(alias_ref<jclass>, jint w, jint h, jint o) {
  return checked(
      [&]() -> local_ref<JArrayDouble> { return doubles(g::orientationMatrix(size(w, h), orientation(o), true)); });
}
local_ref<JArrayDouble> sourcePointNative(alias_ref<jclass>, jdouble x, jdouble y, jint ew, jint eh, jint sw, jint sh,
                                          jint o, jboolean inverse) {
  return checked([&]() -> local_ref<JArrayDouble> {
    const auto encoded = size(ew, eh), sampled = size(sw, sh);
    const auto p = inverse ? g::uprightToEncoded({x, y}, encoded, sampled, orientation(o))
                           : g::encodedToUpright({x, y}, encoded, sampled, orientation(o));
    return points(std::span(&p, 1));
  });
}
local_ref<JArrayDouble> coordinatesNative(alias_ref<jclass>, Doubles values, jint w, jint h, jboolean normalized) {
  return checked([&]() -> local_ref<JArrayDouble> {
    if (!values || (values->size() != 2 && values->size() != 8))
      throw std::invalid_argument("Expected a point or quad.");
    const auto s = size(w, h);
    const auto a = values->getRegion(0, values->size());
    std::vector<Point> result;
    for (size_t i = 0; i < values->size(); i += 2)
      result.push_back(normalized ? g::toNormalized(Point{a[i], a[i + 1]}, s) : g::toImage(Point{a[i], a[i + 1]}, s));
    return points(result);
  });
}
local_ref<JArrayInt> inferenceCropNative(alias_ref<jclass>, Doubles seed, jint w, jint h) {
  return checked([&]() -> local_ref<JArrayInt> {
    const auto crop = g::photoInferenceCrop(quad(seed), size(w, h));
    if (!crop)
      return nullptr;
    const jint a[]{static_cast<jint>(crop->left), static_cast<jint>(crop->top), static_cast<jint>(crop->width),
                   static_cast<jint>(crop->height)};
    auto result = JArrayInt::newArray(4);
    result->setRegion(0, 4, a);
    return result;
  });
}
local_ref<JArrayDouble> regionNative(alias_ref<jclass>, Doubles values, jint l, jint t, jint w, jint h, jint pw,
                                     jint ph) {
  return checked([&]() -> local_ref<JArrayDouble> {
    return points(
        g::RegionTransform(size(pw, ph), {double(l), double(t), double(w), double(h)}).toSource(quad(values)));
  });
}
local_ref<JArrayDouble> clampNative(alias_ref<jclass>, Doubles values) {
  return checked([&]() -> local_ref<JArrayDouble> { return points(g::clampToFrame(quad(values))); });
}
jboolean validCropNative(alias_ref<jclass>, Doubles values) {
  return checked([&]() -> jboolean { return values && values->size() == 8 && g::validCropQuad(quad(values)); });
}
local_ref<JArrayDouble> moveNative(alias_ref<jclass>, Doubles values, jint index, jdouble x, jdouble y, jboolean edge) {
  return checked([&]() -> local_ref<JArrayDouble> {
    const auto q = quad(values);
    const auto next = edge ? g::moveEdge(q, index, {x, y}) : g::moveCorner(q, index, {x, y});
    return next ? points(*next) : nullptr;
  });
}
void validateRenderNative(alias_ref<jclass>, Doubles values) {
  try {
    g::validateRenderQuad(quad(values));
  } catch (const std::invalid_argument &e) {
    throwNewJavaException("com/margelo/nitro/documentscanner/InvalidInputException", e.what());
  }
}
local_ref<JArrayDouble> targetNative(alias_ref<jclass>, Doubles values, jdouble scale, jdouble paper) {
  try {
    const auto target = g::targetCanvas(quad(values), scale, paper > 0 ? std::optional<double>(paper) : std::nullopt);
    const double result[]{double(target.size.width), double(target.size.height)};
    return doubles(result);
  } catch (const std::invalid_argument &e) {
    throwNewJavaException("com/margelo/nitro/documentscanner/InvalidInputException", e.what());
  }
}
local_ref<JArrayDouble> outputPolygonNative(alias_ref<jclass>, Doubles values, jint w, jint h, Doubles homography,
                                            jint tw, jint th) {
  return checked([&]() -> local_ref<JArrayDouble> {
    if (!homography || homography->size() != 9)
      throw std::invalid_argument("Homography requires nine values.");
    const auto a = homography->getRegion(0, 9);
    const g::Matrix m{a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]};
    const auto clipped = g::clipImageToQuad(quad(values), size(w, h));
    return points(g::normalizedOutputPolygon(clipped, m, size(tw, th)));
  });
}
} // namespace
void NativeGeometry::registerNatives() {
  javaClassStatic()->registerNatives({
      makeNativeMethod("uprightSizeNative", uprightSizeNative),
      makeNativeMethod("orientationNative", orientationNative),
      makeNativeMethod("sourcePointNative", sourcePointNative),
      makeNativeMethod("coordinatesNative", coordinatesNative),
      makeNativeMethod("inferenceCropNative", inferenceCropNative),
      makeNativeMethod("regionNative", regionNative),
      makeNativeMethod("clampNative", clampNative),
      makeNativeMethod("validCropNative", validCropNative),
      makeNativeMethod("moveNative", moveNative),
      makeNativeMethod("validateRenderNative", validateRenderNative),
      makeNativeMethod("targetNative", targetNative),
      makeNativeMethod("outputPolygonNative", outputPolygonNative),
  });
}
} // namespace margelo::nitro::documentscanner
