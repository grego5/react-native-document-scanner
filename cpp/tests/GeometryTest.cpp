#include <document_scanner/Geometry.hpp>
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>

using namespace document_scanner;
namespace g = document_scanner::geometry;
namespace {
void check(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
void near(double actual, double expected, double epsilon = 1e-9) {
  check(std::abs(actual - expected) <= epsilon, "Coordinate mismatch");
}
void near(Point actual, Point expected) {
  near(actual.x, expected.x);
  near(actual.y, expected.y);
}
void near(const Quad &actual, const Quad &expected) {
  for (size_t i = 0; i < 4; ++i)
    near(actual[i], expected[i]);
}
void rejects(const std::function<void()> &f) {
  try {
    f();
  } catch (const std::invalid_argument &) {
    return;
  }
  throw std::runtime_error("Invalid geometry accepted");
}
Quad rect(double l, double t, double r, double b) { return {Point{l, t}, Point{r, t}, Point{r, b}, Point{l, b}}; }
void orientations() {
  // Independent marker order: encoded TL=red, TR=green, BR=yellow, BL=blue.
  const std::array<std::array<int, 4>, 8> orders{
      {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}, {0, 3, 2, 1}, {3, 0, 1, 2}, {2, 1, 0, 3}, {1, 2, 3, 0}}};
  for (int o = 1; o <= 8; ++o) {
    const auto orientation = static_cast<Orientation>(o);
    const auto upright = g::uprightSize({80, 60}, orientation);
    check(upright.width == (o >= 5 ? 60u : 80u) && upright.height == (o >= 5 ? 80u : 60u), "Upright dimensions");
    const auto edge = rect(0, 0, 80, 60), destination = rect(0, 0, upright.width, upright.height);
    for (size_t i = 0; i < 4; ++i) {
      near(g::encodedToUpright(edge[orders[o - 1][i]], {80, 60}, {80, 60}, orientation), destination[i]);
    }
    for (Point p : {Point{0, 0}, Point{80, 60}, Point{20, 15}, Point{-10, 72}}) {
      const auto mapped = g::encodedToUpright(p, {80, 60}, {19, 13}, orientation);
      near(g::uprightToEncoded(mapped, {80, 60}, {19, 13}, orientation), p);
    }
    const auto m = g::orientationMatrix({80, 60}, orientation, true);
    const auto centers = rect(0, 0, 79, 59), uprightCenters = rect(0, 0, upright.width - 1, upright.height - 1);
    for (size_t i = 0; i < 4; ++i) {
      const auto p = centers[orders[o - 1][i]];
      near(Point{m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5]}, uprightCenters[i]);
    }
  }
  rejects([] { g::uprightSize({0, 1}, Orientation::upright); });
  rejects([] { g::orientationMatrix({1, 1}, static_cast<Orientation>(9)); });
}
void coordinatesAndRegions() {
  const auto q = rect(-.2, .1, 1.3, .9);
  near(g::toNormalized(g::toImage(q, {123, 456}), {123, 456}), q);
  rejects([] { g::toImage(Point{0, 0}, {0, 1}); });
  rejects([] { g::toNormalized(Point{NAN, 0}, {1, 1}); });
  for (auto source : {Size{1000, 2000}, Size{2000, 1000}, Size{1001, 800}, Size{800, 1001}, Size{7, 7}}) {
    const auto transform = g::centeredSquare(source);
    const auto region = transform.region();
    near(region.width, region.height);
    near(region.left * 2 + region.width, source.width);
    near(region.top * 2 + region.height, source.height);
    near(transform.fromSource(transform.toSource(q)), q);
  }
  const auto portrait = g::centeredSquare({1000, 2000});
  near(portrait.toSource(rect(0, 0, 1, 1)), rect(0, .25, 1, .75));
  const auto landscape = g::centeredSquare({2000, 1000});
  near(landscape.toSource(rect(0, 0, 1, 1)), rect(.25, 0, .75, 1));
  rejects([] { g::RegionTransform({10, 10}, {0, 0, 11, 1}); });
}
void inferenceRegions() {
  // Migrated PhotoInferenceCropTest assertions, with the same margin, rounding and limits.
  const auto crop = *g::photoInferenceCrop(rect(.3, .3, .7, .7), {1000, 2000});
  check(crop.left == 260 && crop.top == 520 && crop.width == 480 && crop.height == 960, "Centered seed crop");
  const g::RegionTransform transform({1000, 2000},
                                     {double(crop.left), double(crop.top), double(crop.width), double(crop.height)});
  const auto mapped = transform.toSource(rect(0, 0, 1, 1));
  near(mapped[0].x, .26);
  near(mapped[2].y, .74);
  const auto edge = *g::photoInferenceCrop(rect(.01, .2, .31, .8), {1000, 2000});
  check(edge.left <= 10 && edge.left + edge.width >= 340 && edge.top <= 280 && edge.top + edge.height >= 1720,
        "Shift at photo edge");
  near(double(edge.width) / edge.height, .5, .002);
  const auto full = *g::photoInferenceCrop(rect(0, 0, 1, 1), {1200, 800});
  check(full.left == 0 && full.top == 0 && full.width == 1200 && full.height == 800, "Full photo crop");
  check(!g::photoInferenceCrop(rect(-.1, .2, .8, .9), {1000, 2000}), "Outside seed rejected");
  check(!g::photoInferenceCrop(rect(.8, .2, .1, .9), {1000, 2000}), "Crossed seed rejected");
  check(!g::photoInferenceCrop(rect(NAN, .2, .8, .9), {1000, 2000}), "Nonfinite seed rejected");
  check(!g::photoInferenceCrop(rect(.1, .1, .1001, .1001), {1000, 2000}), "Prediction turn limit");
  // Prediction regions deliberately have no edit-area minimum.
  check(g::photoInferenceCrop(rect(.1, .1, .105, .105), {1000, 2000}).has_value(), "Small valid prediction");
}
void cropEditing() {
  // Migrated CropQuadEditingTest assertions.
  const auto q = rect(.1, .1, .9, .9);
  near(*g::moveEdge(q, 0, {.3, -.5}), {Point{.2, 0}, Point{1, 0}, Point{.9, .9}, Point{.1, .9}});
  check(!g::moveEdge(q, 0, {0, .9}), "Opposite edge crossing rejected");
  near(*g::moveCorner(q, 2, {1.4, .5}), {Point{.1, .1}, Point{.9, .1}, Point{1, .5}, Point{.1, .9}});
  for (Point p : {Point{.95, .95}, Point{.6, .6}, Point{.5, .5}})
    check(!g::moveCorner(q, 0, p), "Crossed, concave or collapsed corner rejected");
  check(g::moveCorner(q, 0, {.3, .3}).has_value(), "Valid corner accepted");
  near(g::clampToFrame({Point{-.2, .1}, Point{1.3, 0}, Point{1, 1.5}, Point{0, 1}}),
       {Point{0, .1}, Point{1, 0}, Point{1, 1}, Point{0, 1}});
  check(!g::validCropQuad(rect(0, 0, .001, .001)), "Minimum edit area");
  check(!g::moveCorner(q, 0, {NAN, .2}) && !g::moveEdge(q, 0, {0, NAN}), "Nonfinite motion rejected");
  rejects([&] { g::moveCorner(q, 4, {0, 0}); });
}
void rendering() {
  g::validateRenderQuad(rect(-.12, -.08, .9, .9));
  rejects([] { g::validateRenderQuad(rect(-.81, .1, .9, .9)); });
  rejects([] { g::validateRenderQuad(rect(.8, .1, .1, .9)); });
  rejects([] { g::validateRenderQuad(rect(0, 0, .001, .001)); });
  rejects([] { g::validateRenderQuad(rect(-.8, -.8, 1.8, 1.8)); });
  rejects([] { g::validateRenderQuad(rect(NAN, 0, 1, 1)); });
  const auto page = rect(65, 48, 570, 430);
  const auto paper = g::targetCanvas(page, 1, 10 / 2.54 * 100);
  check(paper.size.width == 394 && paper.size.height == 298, "Paper size keeps proportions");
  check(g::targetCanvas(page, 1, 29.7 / 2.54 * 200).size.width == 505, "No enlargement past source pixels");
  const auto sampled = g::targetCanvas(rect(0, 0, 512.5, 106.25), 4);
  check(sampled.size.width == 2050 && sampled.size.height == 425, "Original source scale");
  check(g::targetCanvas(rect(0, 0, 8000, 6000), 1).size.width == 4096, "Output dimension limit");
  rejects([] { g::targetCanvas(rect(0, 0, 100, 1), 1); });
  rejects([] { g::targetCanvas(rect(0, 0, 100, 10), 1); });
  const auto outside = rect(-20, -10, 120, 90);
  const auto clipped = g::clipImageToQuad(outside, {100, 80});
  check(clipped.size() == 4, "Outside render clipping");
  // Homography from [-20,-10,120,90] to [0,0,140,100] is a translation.
  const auto output = g::normalizedOutputPolygon(clipped, {1, 0, 20, 0, 1, 10, 0, 0, 1}, {140, 100});
  near(output[0], {20.0 / 140, .1});
  near(output[2], {120.0 / 140, .9});
  const Quad angled{Point{-12, -8}, Point{87, 12}, Point{82, 90}, Point{8, 84}};
  const auto polygon = g::clipImageToQuad(angled, {100, 80});
  check(polygon.size() >= 5, "Clipped corner polygon");
  const auto normalized = g::normalizedOutputPolygon(polygon, {1, 0, 0, 0, 1, 0, 0, 0, 1}, {100, 80});
  for (Point p : normalized)
    check(p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1, "Normalized output polygon");
  check(g::clipImageToQuad(rect(200, 200, 300, 300), {100, 80}).empty(), "No overlap");
}
} // namespace
int main() {
  try {
    orientations();
    coordinatesAndRegions();
    inferenceRegions();
    cropEditing();
    rendering();
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
  std::cout << "Orientation, region, inference crop, editing and render geometry tests passed\n";
}
