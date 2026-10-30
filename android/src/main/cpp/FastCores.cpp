#include "FastCores.hpp"

#include <opencv2/core/utility.hpp>
#include <sched.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <fstream>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace margelo::nitro::documentscanner {

using namespace facebook::jni;

namespace {
/**
 * Cores rated below this share of the fastest by the kernel's capacity (its scheduler's performance rating) are left
 * out: little cores rate about a third of the biggest, while the mid cores of all-big designs rate well over half.
 */
constexpr double kMinCapacityShare = .5;
/** The same by top clock speed, where no capacity is exposed; a clock understates the gap, little cores reaching ~60%. */
constexpr double kMinSpeedShare = .7;

/** Cores within this share of the fastest's rating count as its peers among the prime cores. */
constexpr double kPrimeShare = .95;

struct CoreSets {
  cpu_set_t fast;
  cpu_set_t prime;
  cpu_set_t all;
  /** False when no core is markedly slower than the rest, or the cores cannot be rated. */
  bool distinct = false;
  /** False when every core rates as the fastest, or the cores cannot be rated. */
  bool primeDistinct = false;
};

/** Each core's value in the per-core sysfs file [name], or an empty list when any core lacks it. */
std::vector<long> perCore(int count, const char* name) {
  std::vector<long> values(count, 0);
  for (int cpu = 0; cpu < count; ++cpu) {
    std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/" + name);
    if (!(file >> values[cpu]) || values[cpu] <= 0) return {};
  }
  return values;
}

/** The cores not markedly slower than the fastest, by capacity or else by top clock speed, and every core. */
const CoreSets& coreSets() {
  static const CoreSets sets = [] {
    CoreSets result;
    CPU_ZERO(&result.fast);
    CPU_ZERO(&result.prime);
    CPU_ZERO(&result.all);
    const int count = std::min(static_cast<int>(sysconf(_SC_NPROCESSORS_CONF)), CPU_SETSIZE);
    for (int cpu = 0; cpu < count; ++cpu) CPU_SET(cpu, &result.all);
    double share = kMinCapacityShare;
    std::vector<long> ratings = perCore(count, "cpu_capacity");
    if (ratings.empty()) {
      share = kMinSpeedShare;
      ratings = perCore(count, "cpufreq/cpuinfo_max_freq");
    }
    if (ratings.empty()) return result;
    const long fastest = *std::max_element(ratings.begin(), ratings.end());
    int kept = 0;
    int peers = 0;
    for (int cpu = 0; cpu < count; ++cpu) {
      if (ratings[cpu] >= fastest * share) {
        CPU_SET(cpu, &result.fast);
        ++kept;
      }
      if (ratings[cpu] >= fastest * kPrimeShare) {
        CPU_SET(cpu, &result.prime);
        ++peers;
      }
    }
    result.distinct = kept < count;
    result.primeDistinct = peers < count;
    return result;
  }();
  return sets;
}

std::mutex workersLock;
/** OpenCV's worker threads seen so far, by thread id; they persist across parallel calls. */
std::set<pid_t> workers;

/**
 * Adds OpenCV's current workers to [workers]: every pool thread takes one task and waits up to 20 ms for the rest, so
 * each shows up (5 ms missed some waking from idle). A worker that wakes later still is found on a later call.
 */
void collectWorkers() {
  const int threads = cv::getNumThreads();
  if (threads <= 1) return;
  const pid_t caller = gettid();
  std::atomic<int> arrived{0};
  cv::parallel_for_(cv::Range(0, threads), [&](const cv::Range& range) {
    for (int task = range.start; task < range.end; ++task) {
      const pid_t self = gettid();
      if (self != caller) {
        std::lock_guard<std::mutex> guard(workersLock);
        workers.insert(self);
      }
      arrived.fetch_add(1);
      const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(20);
      while (arrived.load() < threads && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
    }
  }, threads);
}

/** Applies [set] to every known worker by its id; workers that have exited are forgotten. */
void applyToWorkers(const cpu_set_t& set) {
  std::lock_guard<std::mutex> guard(workersLock);
  for (auto worker = workers.begin(); worker != workers.end();) {
    if (sched_setaffinity(*worker, sizeof(set), &set) != 0 && errno == ESRCH) {
      worker = workers.erase(worker);
    } else {
      ++worker;
    }
  }
}

std::mutex pinLock;
/** Callers between a pin and its release; OpenCV's workers, which they share, are released after the last. */
int pinned = 0;
} // namespace

void FastCores::registerNatives() {
  javaClassStatic()->registerNatives({
    makeNativeMethod("pinNative", FastCores::pinNative),
    makeNativeMethod("primeNative", FastCores::primeNative),
  });
}

/**
 * Holds the calling thread alone to the prime cores, those rated as the fastest. False when no core is slower than the
 * fastest, or the system refuses, as while the process's cpuset leaves them out.
 */
jboolean FastCores::primeNative(alias_ref<jclass>) {
  const CoreSets& sets = coreSets();
  if (!sets.primeDistinct) return false;
  return sched_setaffinity(0, sizeof(sets.prime), &sets.prime) == 0;
}

/**
 * Holds the calling thread to the fast cores when [fast], or releases it to every core; OpenCV's workers follow the
 * first pin and the last release. False when there is nothing to pin. The kernel narrows each set to the cores the
 * process's cpuset allows, and set by thread id, no worker is missed however the pool schedules.
 */
jboolean FastCores::pinNative(alias_ref<jclass>, jboolean fast) {
  const CoreSets& sets = coreSets();
  if (!sets.distinct) return false;
  const cpu_set_t& set = fast ? sets.fast : sets.all;
  sched_setaffinity(0, sizeof(set), &set);
  std::lock_guard<std::mutex> guard(pinLock);
  if (fast) {
    if (pinned++ == 0) {
      collectWorkers();
      applyToWorkers(set);
    }
  } else if (pinned > 0 && --pinned == 0) {
    applyToWorkers(set);
  }
  return true;
}

} // namespace margelo::nitro::documentscanner
