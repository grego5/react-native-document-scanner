#import "ScannerCornerRuntime.h"
#import "ScannerSourcePixels.h"
#import <TensorFlowLiteC/c_api.h>
#include <chrono>
#include <cstring>
#include <document_scanner/Geometry.hpp>
#include <document_scanner/Tensor.hpp>
#include <memory>
#include <stdexcept>

namespace {
using namespace document_scanner;
using Clock = std::chrono::steady_clock;
using Model = std::unique_ptr<TfLiteModel, decltype(&TfLiteModelDelete)>;
using Interpreter =
    std::unique_ptr<TfLiteInterpreter, decltype(&TfLiteInterpreterDelete)>;
struct Runtime final {
  // Destruction is reversed: interpreter/model must die before the model bytes.
  NSData *bytes;
  Model model{nullptr, TfLiteModelDelete};
  Interpreter interpreter{nullptr, TfLiteInterpreterDelete};
  std::unique_ptr<TensorPreprocessor> preprocessor;
  std::vector<float> input;
  uint32_t width = 0, height = 0;
  explicit Runtime(NSData *data) : bytes([data copy]) {
    model.reset(TfLiteModelCreate(bytes.bytes, bytes.length));
    if (!model)
      throw std::invalid_argument("Cannot load corner model.");
    std::unique_ptr<TfLiteInterpreterOptions,
                    decltype(&TfLiteInterpreterOptionsDelete)>
        options(TfLiteInterpreterOptionsCreate(),
                TfLiteInterpreterOptionsDelete);
    if (!options)
      throw std::runtime_error("Cannot create interpreter options.");
    TfLiteInterpreterOptionsSetNumThreads(options.get(), 1);
    interpreter.reset(TfLiteInterpreterCreate(model.get(), options.get()));
    if (!interpreter)
      throw std::invalid_argument("Cannot create corner interpreter.");
    if (TfLiteInterpreterGetInputTensorCount(interpreter.get()) != 1 ||
        TfLiteInterpreterGetOutputTensorCount(interpreter.get()) != 1)
      throw std::invalid_argument("Unexpected DocCornerNet tensor count.");
    auto *in = TfLiteInterpreterGetInputTensor(interpreter.get(), 0);
    const auto *out = TfLiteInterpreterGetOutputTensor(interpreter.get(), 0);
    if (!in || !out)
      throw std::invalid_argument("Missing corner tensors.");
    auto shape = [](const TfLiteTensor *tensor) {
      std::vector<int> result;
      for (int i = 0; i < TfLiteTensorNumDims(tensor); ++i)
        result.push_back(TfLiteTensorDim(tensor, i));
      return result;
    };
    const auto size = validateCornerTensors(
        1, 1, TfLiteTensorType(in) == kTfLiteFloat32,
        TfLiteTensorType(out) == kTfLiteFloat32, shape(in), shape(out));
    width = size.width;
    height = size.height;
    if (TfLiteInterpreterAllocateTensors(interpreter.get()) != kTfLiteOk)
      throw std::runtime_error("Cannot allocate corner tensors.");
    if (TfLiteTensorByteSize(TfLiteInterpreterGetInputTensor(
            interpreter.get(), 0)) != cornerInputBytes(size) ||
        TfLiteTensorByteSize(TfLiteInterpreterGetOutputTensor(
            interpreter.get(), 0)) != 9 * sizeof(float))
      throw std::invalid_argument("Unexpected corner tensor storage.");
    preprocessor = std::make_unique<TensorPreprocessor>(size);
    input.resize(cornerInputBytes(size) / sizeof(float), 0.f);
    invoke(); // Blank-input warmup must succeed before the selection can be
              // installed.
  }
  std::array<float, 9> invoke() {
    auto *tensor = TfLiteInterpreterGetInputTensor(interpreter.get(), 0);
    if (TfLiteTensorCopyFromBuffer(tensor, input.data(),
                                   input.size() * sizeof(float)) != kTfLiteOk ||
        TfLiteInterpreterInvoke(interpreter.get()) != kTfLiteOk)
      throw std::runtime_error("Corner inference failed.");
    std::array<float, 9> result;
    if (TfLiteTensorCopyToBuffer(
            TfLiteInterpreterGetOutputTensor(interpreter.get(), 0),
            result.data(), sizeof(result)) != kTfLiteOk)
      throw std::runtime_error("Cannot read corner output.");
    decodeCornerOutput(result); // Also rejects non-finite output during warmup.
    return result;
  }
};
void failure(NSError **error, const std::exception &cause) {
  if (error)
    *error = [NSError errorWithDomain:@"DocumentScanner.CornerInference"
                                 code:1
                             userInfo:@{
                               NSLocalizedDescriptionKey : @(cause.what())
                             }];
}
double elapsed(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start)
      .count();
}
} // namespace

@implementation ScannerCornerResult
- (instancetype)initWithValues:(std::span<const float>)values
                     transform:
                         (const document_scanner::geometry::RegionTransform *)
                             transform
                 preprocessing:(double)preprocessing
                     inference:(double)inference {
  if ((self = [super init])) {
    _score = values[8];
    _preprocessingMs = preprocessing;
    _inferenceMs = inference;
    if (auto decoded = document_scanner::decodeCornerOutput(values)) {
      const auto quad =
          transform ? transform->toSource(decoded->quad) : decoded->quad;
      if (document_scanner::validPredictionQuad(quad)) {
        NSMutableArray<NSNumber *> *points =
            [NSMutableArray arrayWithCapacity:8];
        for (auto point : quad) {
          [points addObject:@(point.x)];
          [points addObject:@(point.y)];
        }
        _quad = [points copy];
      }
    }
  }
  return self;
}
@end

@implementation ScannerCornerRuntime {
  std::unique_ptr<Runtime> _runtime;
}
- (instancetype)initWithModel:(NSData *)model error:(NSError **)error {
  if ((self = [super init])) {
    try {
      _runtime = std::make_unique<Runtime>(model);
    } catch (const std::exception &cause) {
      failure(error, cause);
      return nil;
    }
  }
  return self;
}
- (NSUInteger)inputWidth {
  return _runtime->width;
}
- (NSUInteger)inputHeight {
  return _runtime->height;
}
- (ScannerCornerResult *)analyzeOwner:(NSObject *)owner
                                bytes:(const void *)bytes
                            byteCount:(NSUInteger)byteCount
                                width:(uint32_t)width
                               height:(uint32_t)height
                            rowStride:(NSUInteger)rowStride
                                 seed:(NSArray<NSNumber *> *)seed
                                error:(NSError **)error {
  try {
    const auto started = Clock::now();
    auto source = ScannerBorrowSourcePixels(owner, bytes, byteCount, width,
                                            height, rowStride);
    if (seed) {
      if (seed.count != 8)
        throw std::invalid_argument("Expected eight seed coordinates.");
      const document_scanner::Quad quad{
          {{seed[0].doubleValue, seed[1].doubleValue},
           {seed[2].doubleValue, seed[3].doubleValue},
           {seed[4].doubleValue, seed[5].doubleValue},
           {seed[6].doubleValue, seed[7].doubleValue}}};
      if (auto crop = document_scanner::geometry::photoInferenceCrop(
              quad, {width, height})) {
        // Crop fields are immutable values; reconstruct the view rather than
        // retaining a new buffer.
        auto layout = source.view().layout();
        document_scanner::PixelLease cropped(
            std::make_shared<document_scanner::PixelLease>(source),
            source.view().bytes(),
            {layout.format, layout.size, layout.rowStride, layout.pixelStride,
             *crop, layout.orientation});
        _runtime->preprocessor->prepare(cropped, _runtime->input);
        const double preparation = elapsed(started);
        const auto inference = Clock::now();
        const auto values = _runtime->invoke();
        const document_scanner::geometry::RegionTransform mapping(
            {width, height}, {double(crop->left), double(crop->top),
                              double(crop->width), double(crop->height)});
        return [[ScannerCornerResult alloc] initWithValues:values
                                                 transform:&mapping
                                             preprocessing:preparation
                                                 inference:elapsed(inference)];
      }
    }
    _runtime->preprocessor->prepare(source, _runtime->input);
    const double preparation = elapsed(started);
    const auto inference = Clock::now();
    const auto values = _runtime->invoke();
    return [[ScannerCornerResult alloc] initWithValues:values
                                             transform:nullptr
                                         preprocessing:preparation
                                             inference:elapsed(inference)];
  } catch (const std::exception &cause) {
    failure(error, cause);
    return nil;
  }
}
- (ScannerCornerResult *)analyzePixelBuffer:(CVPixelBufferRef)buffer
                                orientation:(uint8_t)orientation
                                       crop:(CGRect)crop
                                      error:(NSError **)error {
  try {
    const auto format = CVPixelBufferGetPixelFormatType(buffer);
    const size_t width = CVPixelBufferGetWidth(buffer),
                 height = CVPixelBufferGetHeight(buffer);
    if (CVPixelBufferIsPlanar(buffer) ||
        (format != kCVPixelFormatType_32BGRA &&
         format != kCVPixelFormatType_32RGBA) ||
        width > UINT32_MAX || height > UINT32_MAX || CGRectIsEmpty(crop) ||
        CGRectIsInfinite(crop) ||
        !CGRectEqualToRect(crop, CGRectIntegral(crop)) || crop.origin.x < 0 ||
        crop.origin.y < 0 || CGRectGetMaxX(crop) > width ||
        CGRectGetMaxY(crop) > height)
      throw std::invalid_argument(
          "Expected an integral in-frame packed RGBA/BGRA crop.");
    if (CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) !=
        kCVReturnSuccess)
      throw std::runtime_error("Cannot lock live source pixels.");
    CVPixelBufferRetain(buffer);
    // This lease never escapes the synchronous worker operation. Its last
    // release unlocks on the same worker before the caller may reuse the
    // capture buffer.
    std::shared_ptr<const void> owner(buffer, [](const void *value) {
      auto buffer =
          const_cast<CVPixelBufferRef>(static_cast<const __CVBuffer *>(value));
      CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly);
      CVPixelBufferRelease(buffer);
    });
    const auto start = Clock::now();
    const document_scanner::PixelLease source(
        std::move(owner),
        {static_cast<const std::byte *>(CVPixelBufferGetBaseAddress(buffer)),
         CVPixelBufferGetDataSize(buffer)},
        {format == kCVPixelFormatType_32BGRA
             ? document_scanner::PixelFormat::bgra8
             : document_scanner::PixelFormat::rgba8,
         {uint32_t(width), uint32_t(height)},
         CVPixelBufferGetBytesPerRow(buffer),
         4,
         {uint32_t(crop.origin.x), uint32_t(crop.origin.y),
          uint32_t(crop.size.width), uint32_t(crop.size.height)},
         static_cast<document_scanner::Orientation>(orientation)});
    _runtime->preprocessor->prepare(source, _runtime->input);
    const double preparation = elapsed(start);
    const auto inference = Clock::now();
    const auto values = _runtime->invoke();
    return [[ScannerCornerResult alloc] initWithValues:values
                                             transform:nullptr
                                         preprocessing:preparation
                                             inference:elapsed(inference)];
  } catch (const std::exception &cause) {
    failure(error, cause);
    return nil;
  }
}
- (ScannerCornerResult *)predictTensor:(NSData *)tensor
                                 error:(NSError **)error {
  try {
    if (tensor.length != _runtime->input.size() * sizeof(float))
      throw std::invalid_argument("Unexpected tensor byte count.");
    std::memcpy(_runtime->input.data(), tensor.bytes, tensor.length);
    const auto start = Clock::now();
    const auto values = _runtime->invoke();
    return [[ScannerCornerResult alloc] initWithValues:values
                                             transform:nullptr
                                         preprocessing:0
                                             inference:elapsed(start)];
  } catch (const std::exception &cause) {
    failure(error, cause);
    return nil;
  }
}
@end
