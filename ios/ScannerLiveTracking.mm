#import "ScannerLiveTracking.h"
#include <document_scanner/LiveGuide.hpp>
#include <document_scanner/Motion.hpp>
#include <memory>
#include <optional>
#include <stdexcept>

namespace {
using namespace document_scanner;

std::optional<Quad> quad(NSArray<NSNumber *> *_Nullable values) {
  if (values.count != 8)
    return std::nullopt;
  auto at = [&](NSUInteger i) { return values[i].doubleValue; };
  using P = document_scanner::Point;
  return Quad{P{at(0), at(1)}, P{at(2), at(3)}, P{at(4), at(5)}, P{at(6), at(7)}};
}

NSArray<NSNumber *> *numbers(const Quad &q) {
  return @[ @(q[0].x), @(q[0].y), @(q[1].x), @(q[1].y), @(q[2].x), @(q[2].y), @(q[3].x), @(q[3].y) ];
}

void failure(NSError **error, const std::exception &cause) {
  if (error)
    *error = [NSError errorWithDomain:@"DocumentScanner.LiveTracking"
                                 code:1
                             userInfo:@{NSLocalizedDescriptionKey : @(cause.what())}];
}
} // namespace

@implementation ScannerGuideOutput
- (instancetype)initWithOutput:(const guide::Output &)output {
  if ((self = [super init])) {
    _status = NSInteger(output.status);
    _decision = NSInteger(output.decision);
    _accepted = output.accepted ? numbers(*output.accepted) : nil;
    _acceptanceId = output.acceptanceId;
    _deadlineNs = output.deadlineNs;
    _chainLength = output.chainLength;
    _expired = output.expired;
  }
  return self;
}
@end

@implementation ScannerLiveGuide {
  guide::Stabilizer _stabilizer;
}
- (instancetype)initWithMinArea:(double)minArea holdNs:(int64_t)holdNs {
  if ((self = [super init]))
    _stabilizer.configure({minArea, holdNs});
  return self;
}
- (void)reset {
  _stabilizer.reset();
}
- (ScannerGuideOutput *)updateQuad:(NSArray<NSNumber *> *)values
                             width:(NSInteger)width
                            height:(NSInteger)height
                          sensorNs:(int64_t)sensorNs
                         arrivalNs:(int64_t)arrivalNs {
  const auto output = _stabilizer.update({quad(values), int(width), int(height), sensorNs, arrivalNs});
  return [[ScannerGuideOutput alloc] initWithOutput:output];
}
- (ScannerGuideOutput *)retainQuad:(NSArray<NSNumber *> *)values
                             width:(NSInteger)width
                            height:(NSInteger)height
                          sensorNs:(int64_t)sensorNs
                         arrivalNs:(int64_t)arrivalNs {
  const auto output = _stabilizer.retain({quad(values), int(width), int(height), sensorNs, arrivalNs});
  return output ? [[ScannerGuideOutput alloc] initWithOutput:*output] : nil;
}
+ (BOOL)scoreRejected:(NSNumber *)score minimum:(double)minimum {
  return guide::scoreRejected(score ? std::optional<double>(score.doubleValue) : std::nullopt, minimum);
}
+ (BOOL)keepScorePause:(BOOL)current speed:(NSNumber *)speed sourceWidth:(NSInteger)sourceWidth {
  return guide::keepScorePause(current, speed ? std::optional<double>(speed.doubleValue) : std::nullopt, int(sourceWidth));
}
@end

@implementation ScannerFlowEstimate
- (instancetype)initWithEstimate:(const motion::Estimate &)estimate {
  if ((self = [super init])) {
    _status = NSInteger(estimate.status);
    _speed = estimate.speed ? @(*estimate.speed) : nil;
    _sourceWidth = estimate.sourceWidth;
    _validTracks = estimate.validTracks;
    _lostRegions = estimate.lostRegions;
    _hasTransform = estimate.transform.has_value();
    _fromNs = estimate.fromNs;
    _toNs = estimate.toNs;
  }
  return self;
}
@end

@implementation ScannerFlowTracker {
  std::unique_ptr<motion::FlowEstimator> _estimator;
  motion::FlowHistory _history;
}
- (instancetype)init {
  if ((self = [super init]))
    _estimator = std::make_unique<motion::FlowEstimator>();
  return self;
}
- (ScannerFlowEstimate *)updatePixelBuffer:(CVPixelBufferRef)buffer
                               orientation:(uint8_t)orientation
                                      crop:(CGRect)crop
                                  sensorNs:(int64_t)sensorNs
                                    region:(NSArray<NSNumber *> *)region
                              fitTransform:(BOOL)fitTransform
                                     error:(NSError **)error {
  try {
    const auto format = CVPixelBufferGetPixelFormatType(buffer);
    const size_t width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer);
    if (CVPixelBufferIsPlanar(buffer) || (format != kCVPixelFormatType_32BGRA && format != kCVPixelFormatType_32RGBA) ||
        width > UINT32_MAX || height > UINT32_MAX || CGRectIsEmpty(crop) || CGRectIsInfinite(crop) ||
        !CGRectEqualToRect(crop, CGRectIntegral(crop)) || crop.origin.x < 0 || crop.origin.y < 0 ||
        CGRectGetMaxX(crop) > width || CGRectGetMaxY(crop) > height)
      throw std::invalid_argument("Expected an integral in-frame packed RGBA/BGRA crop.");
    if (CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess)
      throw std::runtime_error("Cannot lock live flow pixels.");
    CVPixelBufferRetain(buffer);
    // The lease ends inside this call, so the buffer unlocks before the caller reuses it.
    std::shared_ptr<const void> owner(buffer, [](const void *value) {
      auto buffer = const_cast<CVPixelBufferRef>(static_cast<const __CVBuffer *>(value));
      CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly);
      CVPixelBufferRelease(buffer);
    });
    const PixelLease lease(
        std::move(owner),
        {static_cast<const std::byte *>(CVPixelBufferGetBaseAddress(buffer)), CVPixelBufferGetDataSize(buffer)},
        {format == kCVPixelFormatType_32BGRA ? PixelFormat::bgra8 : PixelFormat::rgba8,
         {uint32_t(width), uint32_t(height)},
         CVPixelBufferGetBytesPerRow(buffer),
         4,
         {uint32_t(crop.origin.x), uint32_t(crop.origin.y), uint32_t(crop.size.width), uint32_t(crop.size.height)},
         static_cast<Orientation>(orientation)});
    const auto estimate = _estimator->update(lease, sensorNs, quad(region), fitTransform);
    _history.add(estimate);
    return [[ScannerFlowEstimate alloc] initWithEstimate:estimate];
  } catch (const std::exception &cause) {
    _history.clear();
    failure(error, cause);
    return nil;
  }
}
- (NSArray<NSNumber *> *)warpQuad:(NSArray<NSNumber *> *)values fromNs:(int64_t)fromNs toNs:(int64_t)toNs {
  const auto q = quad(values);
  if (!q)
    return nil;
  const auto warped = _history.warp(*q, fromNs, toNs);
  return warped ? numbers(*warped) : nil;
}
- (void)reset {
  _estimator->reset();
  _history.clear();
}
@end
