#include "document_scanner/Focus.hpp"
#include <stdexcept>
#include <iostream>
#include <limits>
using namespace document_scanner;
using namespace document_scanner::focus;
void check(bool v) {
  if (!v)
    throw std::runtime_error("Focus contract failed");
}
Quad rect(double l, double t, double r, double b) { return {Point{l, t}, Point{r, t}, Point{r, b}, Point{l, b}}; }
const auto page = rect(.2, .2, .8, .8);
Action observe(DocumentPolicy &p, std::optional<Quad> q, Time ms, bool trusted = false, bool failed = false,
               bool blocked = false, bool parked = false) {
  return p.observe(q, trusted, failed, ms * 1000000, blocked, parked);
}
DocumentPolicy locked() {
  DocumentPolicy p;
  observe(p, page, 0);
  p.settled(Completion::success, 500000000);
  observe(p, page, 600, true);
  return p;
}
int main() {
  DocumentPolicy p;
  for (int i = 0; i < 3; ++i)
    check(observe(p, page, i * 33, false, false, false, true) == Action::none);
  check(observe(p, page, 100, true, false, false, true) == Action::start);
  check(observe(p, page, 133, true) == Action::none);
  p.reset();
  check(observe(p, std::nullopt, 0) == Action::none);
  check(observe(p, rect(.4, .4, .5, .5), 10) == Action::none);
  check(observe(p, page, 20) == Action::start);
  for (int i = 0; i < 20; ++i)
    check(observe(p, page, 100 + i * 66) == Action::none);
  p.reset();
  observe(p, rect(.1, .1, .9, .9), 0);
  p.settled(Completion::success, 500000000);
  check(observe(p, page, 600, true) == Action::none);
  for (int i = 0; i < 20; ++i)
    check(observe(p, page, 700 + i * 66, true) == Action::none);
  p = locked();
  check(observe(p, rect(.3, .15, .9, .75), 2000, true) == Action::none);
  const Quad diamond{Point{.5, .0757359313}, Point{.9242640687, .5}, Point{.5, .9242640687}, Point{.0757359313, .5}};
  for (int i = 0; i < 4; ++i)
    check(observe(p, diamond, 2100 + i * 66, true) == Action::none);
  for (const auto &q : {rect(.16, .16, .84, .84), rect(.24, .24, .76, .76)}) {
    p = locked();
    check(observe(p, q, 700, true) == Action::none);
    check(observe(p, q, 766, true) == Action::start);
    check(observe(p, q, 832, true) == Action::none);
  }
  p = locked();
  for (int i = 0; i < 10; ++i)
    check(observe(p, rect(.1, .1, .9, .9), 1600 + i * 66) == Action::none);
  check(observe(p, page, 2400, true) == Action::none);
  p = locked();
  check(observe(p, std::nullopt, 1200) == Action::none);
  check(observe(p, page, 1300, true) == Action::none);
  check(observe(p, std::nullopt, 2200) == Action::none);
  check(observe(p, std::nullopt, 2300) == Action::release);
  check(observe(p, page, 2400) == Action::start);
  p.reset();
  observe(p, page, 0);
  p.settled(Completion::failure, 500000000);
  check(p.deadline(500000000) == 1500000000);
  check(observe(p, page, 1499) == Action::none);
  check(observe(p, page, 1500) == Action::start);
  p.settled(Completion::success, 2000000000);
  check(observe(p, page, 2600, false, true) == Action::none);
  check(p.deadline(2600000000) == 3100000000);
  check(observe(p, page, 3100, false, true) == Action::start);
  p = locked();
  check(observe(p, std::nullopt, 5000, false, false, true) == Action::none);
  check(observe(p, rect(.1, .1, .9, .9), 5100, true, false, true) == Action::none);
  p.reset();
  p.settled(Completion::success, 5200000000);
  check(observe(p, page, 5300, false, false, true) == Action::none);
  check(observe(p, page, 5400) == Action::start);
  // A release wake uses an observed missing target; it never invents a missing frame or scale confirmation.
  p = locked();
  check(!p.deadline(700000000));
  observe(p, std::nullopt, 1000);
  check(p.deadline(1000000000) == 1600000000);
  check(p.wake(1600000000, true) == Action::none);
  check(p.wake(1600000000, false) == Action::release);
  p.reset();
  observe(p, page, 0);
  p.settled(Completion::unknown, second);
  check(observe(p, page, 1999) == Action::none);
  check(observe(p, page, 2000) == Action::start);
  // One policy owns lifecycle generations, tap priority, completion tokens, and absolute deadlines.
  Policy f;
  check(f.snapshot().generation == 0);
  auto tap = f.start(Owner::tap, 0);
  check(f.deadlines(0).tap == 5 * second);
  check(!f.deadlines(0).scan);
  check(f.observe(page, true, false, second, false, true) == Action::none);
  check(f.complete(tap, Completion::success, second));
  check(f.snapshot().generation > tap && f.snapshot().locked == true);
  check(!f.complete(tap, Completion::failure, 2 * second));
  check(!f.expireTap(tap, 5 * second, true));
  check(f.snapshot().owner == Owner::tap);
  check(f.afterCapture(5 * second, true));
  check(!f.snapshot().owner);
  check(f.observe(page, true, false, 5 * second, false, true) == Action::start);
  auto doc = f.start(Owner::document, 5 * second);
  check(f.deadlines(5 * second).scan == 7 * second);
  check(!f.complete(tap, Completion::success, 6 * second));
  check(f.complete(doc, Completion::unknown, 6 * second));
  check(!f.snapshot().locked && !f.snapshot().pending);
  check(f.releaseDocument(false));
  check(f.observe(page, true, false, 6999999999, false, true) == Action::none);
  check(f.observe(page, true, false, 7 * second, false, true) == Action::start);
  doc = f.start(Owner::document, 7 * second);
  check(f.complete(doc, Completion::failure, 9 * second));
  check(!f.releaseDocument(true));
  check(f.snapshot().owner == Owner::document);
  check(f.afterCapture(9 * second, true));
  doc = f.start(Owner::document, 10 * second);
  check(f.complete(doc, Completion::success, 11 * second));
  check(!f.afterCapture(11 * second, true));
  const auto held = f.snapshot().generation;
  check(!f.reset(false, true));
  check(f.snapshot().generation == held);
  check(f.reset(false, false));
  auto stale = f.start(Owner::tap, 12 * second);
  const auto gen = f.snapshot().generation;
  f.unbind();
  check(f.snapshot().generation > gen && !f.snapshot().owner);
  check(!f.complete(stale, Completion::success, 13 * second));
  auto newer = f.start(Owner::tap, 13 * second);
  check(!f.expireTap(stale, 100 * second, false));
  check(f.complete(newer, Completion::unknown, 14 * second));
  check(!f.snapshot().locked && !f.snapshot().pending);
  check(f.expireTap(newer, 18 * second, false));
  // Nanoseconds remain integers beyond double precision; hardware units have no portable default.
  const Time large = 9007199254740993LL;
  auto token = f.start(Owner::tap, large);
  check(f.deadlines(large).tap == large + 5 * second);
  check(!f.expireTap(token, large + 5 * second - 1, false));
  LearnedPosition learned;
  check(!learned.value());
  learned.learn(3.5);
  check(learned.value() == 3.5);
  f.unbind();
  check(learned.value() == 3.5);
  learned.learn(std::numeric_limits<double>::quiet_NaN());
  check(!learned.value());
  learned.learn(.6);
  check(learned.value() == .6);
  learned.learn(std::nullopt);
  check(!learned.value());
  std::cout << "Document/tap focus contracts passed\n";
}
