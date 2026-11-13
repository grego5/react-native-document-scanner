#import "ScannerDocumentRender.h"
#import "ScannerSourcePixels.h"
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <document_scanner/DocumentFilters.hpp>
#include <document_scanner/Geometry.hpp>
#include <document_scanner/GrayscalePng.hpp>
#include <document_scanner/PdfWriter.hpp>
#include <document_scanner/RenderGeometry.hpp>
#include <fcntl.h>
#include <memory>
#include <opencv2/imgproc.hpp>
#include <stdexcept>
#include <unistd.h>
#include <vector>

NSErrorDomain const ScannerRenderErrorDomain = @"DocumentScanner.Render";

@implementation ScannerRenderedPage
- (instancetype)initWithWidth:(NSInteger)width
                       height:(NSInteger)height
                       curved:(BOOL)curved
                      polygon:(NSArray<NSNumber *> *)polygon {
  if ((self = [super init])) {
    _width = width;
    _height = height;
    _curved = curved;
    _validContentPolygon = [polygon copy];
  }
  return self;
}
@end

namespace {
using namespace document_scanner;

void failure(NSError **error, NSInteger code, const char *message) {
  if (error)
    *error = [NSError errorWithDomain:ScannerRenderErrorDomain
                                 code:code
                             userInfo:@{
                               NSLocalizedDescriptionKey : @(message)
                             }];
}

template <typename Operation>
auto guarded(NSError **error, Operation operation) -> decltype(operation()) {
  try {
    return operation();
  } catch (const std::invalid_argument &cause) {
    failure(error, 1, cause.what());
  } catch (const std::exception &cause) {
    failure(error, 2, cause.what());
  }
  return {};
}

Corners cornersFrom(NSArray<NSNumber *> *values) {
  if (values.count != 8)
    throw std::invalid_argument("Expected eight corners coordinates.");
  const auto v = [&](NSUInteger i) { return values[i].doubleValue; };
  return {
      document_scanner::Point{v(0), v(1)}, document_scanner::Point{v(2), v(3)},
      document_scanner::Point{v(4), v(5)}, document_scanner::Point{v(6), v(7)}};
}

using File = std::unique_ptr<FILE, decltype(&fclose)>;
File openFile(NSURL *url, const char *mode) {
  File file(fopen(url.fileSystemRepresentation, mode), fclose);
  if (!file)
    throw std::runtime_error("Cannot open file.");
  return file;
}

ByteSink fileSink(FILE *file) {
  return [file](std::span<const std::byte> bytes) {
    if (fwrite(bytes.data(), 1, bytes.size(), file) != bytes.size())
      throw std::runtime_error("Cannot write file.");
  };
}

// Flush and sync before the caller renames, so a published file is never
// partial.
void finish(File &file) {
  if (fflush(file.get()) != 0 || fsync(fileno(file.get())) != 0)
    throw std::runtime_error("Cannot sync file.");
  if (fclose(file.release()) != 0)
    throw std::runtime_error("Cannot close file.");
}

void syncPath(NSURL *url) {
  const int descriptor = open(url.fileSystemRepresentation, O_RDONLY);
  if (descriptor < 0)
    throw std::runtime_error("Cannot open written file.");
  const bool synced = fsync(descriptor) == 0;
  close(descriptor);
  if (!synced)
    throw std::runtime_error("Cannot sync file.");
}

CGImageRef makeImage(const std::byte *bytes, size_t width, size_t height,
                     size_t channels, size_t stride) {
  NSData *data = [NSData dataWithBytesNoCopy:const_cast<std::byte *>(bytes)
                                      length:stride * height
                                freeWhenDone:NO];
  CGDataProviderRef provider =
      CGDataProviderCreateWithCFData((__bridge CFDataRef)data);
  CGColorSpaceRef space =
      channels == 1
          ? CGColorSpaceCreateWithName(kCGColorSpaceGenericGrayGamma2_2)
          : CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  const CGBitmapInfo info = channels == 4
                                ? CGBitmapInfo(kCGImageAlphaNoneSkipLast)
                                : CGBitmapInfo(kCGImageAlphaNone);
  CGImageRef image =
      CGImageCreate(width, height, 8, 8 * channels, stride, space, info,
                    provider, nullptr, false, kCGRenderingIntentDefault);
  CGColorSpaceRelease(space);
  CGDataProviderRelease(provider);
  if (!image)
    throw std::runtime_error("Cannot create image.");
  return image;
}

void encodeJpeg(CGImageRef image, CFTypeRef destinationTarget, bool toData,
                double quality) {
  CGImageDestinationRef destination =
      toData
          ? CGImageDestinationCreateWithData(
                (CFMutableDataRef)destinationTarget,
                (CFStringRef)UTTypeJPEG.identifier, 1, nullptr)
          : CGImageDestinationCreateWithURL((CFURLRef)destinationTarget,
                                            (CFStringRef)UTTypeJPEG.identifier,
                                            1, nullptr);
  if (!destination)
    throw std::runtime_error("Cannot create JPEG encoder.");
  NSDictionary *properties = @{
    (id)kCGImageDestinationLossyCompressionQuality : @(quality),
    (id)kCGImagePropertyOrientation : @1,
  };
  CGImageDestinationAddImage(destination, image,
                             (__bridge CFDictionaryRef)properties);
  const bool written = CGImageDestinationFinalize(destination);
  CFRelease(destination);
  if (!written)
    throw std::runtime_error("Cannot encode JPEG.");
}

void writeJpeg(NSURL *url, const std::byte *bytes, size_t width, size_t height,
               size_t channels, size_t stride, double quality) {
  CGImageRef image = makeImage(bytes, width, height, channels, stride);
  try {
    encodeJpeg(image, (__bridge CFURLRef)url, false, quality);
  } catch (...) {
    CGImageRelease(image);
    throw;
  }
  CGImageRelease(image);
  syncPath(url);
}

struct Buffer final {
  std::vector<std::byte> bytes;
  PixelFormat format = PixelFormat::rgb8;
  uint32_t width = 0, height = 0;
  size_t channels() const { return bytesPerPixel(format); }
  size_t stride() const { return size_t(width) * channels(); }
  PixelAllocator allocator() {
    return [this](PixelFormat value, document_scanner::Size size) {
      format = value;
      width = size.width;
      height = size.height;
      bytes.assign(stride() * height, std::byte{0});
      return PixelTarget{bytes, stride()};
    };
  }
  PixelLease lease() const {
    auto owner = std::shared_ptr<const void>(this, [](const void *) {});
    return PixelLease(owner, bytes,
                      {format,
                       {width, height},
                       stride(),
                       channels(),
                       {0, 0, width, height},
                       Orientation::upright});
  }
};
} // namespace

@implementation ScannerDocumentRenderer
+ (BOOL)validateCorners:(NSArray<NSNumber *> *)corners error:(NSError **)error {
  return guarded(error, [&] {
    geometry::validateRenderCorners(cornersFrom(corners));
    return true;
  });
}

+ (ScannerRenderedPage *)renderOwner:(NSObject *)owner
                               bytes:(const void *)bytes
                           byteCount:(NSUInteger)byteCount
                               width:(uint32_t)width
                              height:(uint32_t)height
                           rowStride:(NSUInteger)rowStride
                                corners:(NSArray<NSNumber *> *)corners
                       paperLongEdge:(double)paperLongEdge
                     simpleDewarp:(BOOL)simpleDewarp
                              filter:(NSInteger)filter
                       removeShadows:(BOOL)removeShadows
                             grayPng:(BOOL)grayPng
                         jpegQuality:(double)jpegQuality
                               image:(NSURL *)image
                           thumbnail:(NSURL *)thumbnail
                   thumbnailLongSide:(NSInteger)thumbnailLongSide
                               error:(NSError **)error {
  return guarded(error, [&]() -> ScannerRenderedPage * {
    const Corners normalized = cornersFrom(corners);
    geometry::validateRenderCorners(normalized);
    if (filter < 0 || filter > 3 || thumbnailLongSide <= 0 ||
        !(jpegQuality >= 0 && jpegQuality <= 1))
      throw std::invalid_argument("Invalid render configuration.");
    const PixelLease source = ScannerBorrowSourcePixels(
        owner, bytes, byteCount, width, height, rowStride);
    const std::optional<double> paper =
        paperLongEdge > 0 ? std::optional<double>(paperLongEdge) : std::nullopt;
    Buffer page;
    // Shared warp and filtering stay together; only the final RGB/gray page
    // returns here.
    const auto geometric =
        renderGeometry(source, normalized, 1.0, paper, simpleDewarp);
    filterDocument(geometric.pixels.lease(),
                   static_cast<DocumentFilter>(filter), removeShadows,
                   PixelFormat::rgb8, page.allocator());
    const bool gray = page.format == PixelFormat::gray8;
    if (grayPng) {
      if (!gray)
        throw std::invalid_argument(
            "The png format requires a grayscale filter.");
      File file = openFile(image, "wb");
      writeGrayscalePng4(page.lease(), fileSink(file.get()));
      finish(file);
    } else if (gray) {
      // A JPEG stays three-channel, so PDF writers can also embed it unchanged.
      std::vector<std::byte> rgb(page.bytes.size() * 3);
      for (size_t i = 0; i < page.bytes.size(); ++i)
        rgb[i * 3] = rgb[i * 3 + 1] = rgb[i * 3 + 2] = page.bytes[i];
      writeJpeg(image, rgb.data(), page.width, page.height, 3,
                size_t(page.width) * 3, jpegQuality);
    } else {
      writeJpeg(image, page.bytes.data(), page.width, page.height, 3,
                page.stride(), jpegQuality);
    }

    const cv::Mat full(int(page.height), int(page.width),
                       gray ? CV_8UC1 : CV_8UC3, page.bytes.data(),
                       page.stride());
    const double scale = std::min(1.0, double(thumbnailLongSide) /
                                           std::max(page.width, page.height));
    cv::Mat reduced;
    cv::resize(full, reduced, cv::Size(), scale, scale, cv::INTER_AREA);
    writeJpeg(thumbnail, reinterpret_cast<const std::byte *>(reduced.data),
              size_t(reduced.cols), size_t(reduced.rows),
              size_t(reduced.channels()), reduced.step, 0.85);

    NSMutableArray<NSNumber *> *polygon = [NSMutableArray array];
    for (document_scanner::Point p : geometric.validContentPolygon) {
      [polygon addObject:@(p.x)];
      [polygon addObject:@(p.y)];
    }
    return [[ScannerRenderedPage alloc] initWithWidth:page.width
                                               height:page.height
                                               curved:geometric.curved
                                              polygon:polygon];
  });
}

+ (CGImageRef)filterPreview:(CGImageRef)image
                   longSide:(NSInteger)longSide
                     filter:(NSInteger)filter
                      error:(NSError **)error {
  return guarded(error, [&]() -> CGImageRef {
    if (!image || longSide <= 0 || longSide > 4096 || filter < 0 || filter > 3)
      throw std::invalid_argument("Invalid filter preview.");
    const size_t sourceWidth = CGImageGetWidth(image);
    const size_t sourceHeight = CGImageGetHeight(image);
    if (sourceWidth == 0 || sourceHeight == 0)
      throw std::invalid_argument("Invalid filter preview.");
    const double scale = std::min(
        1.0, double(longSide) / double(std::max(sourceWidth, sourceHeight)));
    const auto side = [&](size_t value) {
      return uint32_t(std::max(1.0, std::round(double(value) * scale)));
    };
    const uint32_t width = side(sourceWidth), height = side(sourceHeight);
    // Opaque RGBA8 the shared filters borrow for this call only.
    std::vector<std::byte> rgba(size_t(width) * height * 4);
    CGColorSpaceRef srgb = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    const auto context = [&](void *data, size_t stride) {
      CGContextRef value = CGBitmapContextCreate(
          data, width, height, 8, stride, srgb,
          kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
      if (!value)
        throw std::runtime_error("Cannot create filter context.");
      CGContextSetInterpolationQuality(value, kCGInterpolationHigh);
      return value;
    };
    CGContextRef input = nullptr, output = nullptr;
    CGImageRef filtered = nullptr;
    try {
      input = context(rgba.data(), size_t(width) * 4);
      CGContextSetRGBFillColor(input, 1, 1, 1, 1);
      CGContextFillRect(input, CGRectMake(0, 0, width, height));
      CGContextDrawImage(input, CGRectMake(0, 0, width, height), image);
      const OwnedPixels pixels(
          std::move(rgba), {PixelFormat::rgba8, {width, height}, size_t(width) * 4, 4,
                            {0, 0, width, height}, Orientation::upright});
      Buffer page;
      filterDocument(pixels.lease(), static_cast<DocumentFilter>(filter), false,
                     PixelFormat::rgb8, page.allocator());
      filtered = makeImage(page.bytes.data(), page.width, page.height,
                           page.channels(), page.stride());
      // An independent 32-bit copy, so the result outlives `page` and composites directly.
      output = context(nullptr, 0);
      CGContextDrawImage(output, CGRectMake(0, 0, width, height), filtered);
      CGImageRef result = CGBitmapContextCreateImage(output);
      if (!result)
        throw std::runtime_error("Cannot create filter preview.");
      CGImageRelease(filtered);
      CGContextRelease(output);
      CGContextRelease(input);
      CGColorSpaceRelease(srgb);
      return result;
    } catch (...) {
      CGImageRelease(filtered);
      CGContextRelease(output);
      CGContextRelease(input);
      CGColorSpaceRelease(srgb);
      throw;
    }
  });
}
@end

namespace {
NSArray<NSNumber *> *numbers(const Corners &corners) {
  NSMutableArray<NSNumber *> *values = [NSMutableArray arrayWithCapacity:8];
  for (document_scanner::Point p : corners) {
    [values addObject:@(p.x)];
    [values addObject:@(p.y)];
  }
  return values;
}
} // namespace

@implementation ScannerCropGeometry
+ (NSArray<NSNumber *> *)clamp:(NSArray<NSNumber *> *)corners {
  if (corners.count != 8)
    return corners;
  return numbers(geometry::clampToFrame(cornersFrom(corners)));
}

+ (BOOL)validCrop:(NSArray<NSNumber *> *)corners {
  if (corners.count != 8)
    return NO;
  for (NSNumber *value in corners)
    if (!std::isfinite(value.doubleValue) || value.doubleValue < 0 || value.doubleValue > 1)
      return NO;
  return geometry::validCropCorners(cornersFrom(corners));
}

+ (NSArray<NSNumber *> *)move:(NSArray<NSNumber *> *)corners
                        index:(NSInteger)index
                            x:(double)x
                            y:(double)y
                         edge:(BOOL)edge {
  if (corners.count != 8 || index < 0 || index > 3)
    return nil;
  const Corners current = cornersFrom(corners);
  const auto next = edge ? geometry::moveEdge(current, size_t(index), {x, y})
                         : geometry::moveCorner(current, size_t(index), {x, y});
  return next ? numbers(*next) : nil;
}
@end

namespace {
namespace p = document_scanner::pdf;

// One open page at a time, as the serializer requires; each open() closes the
// previous.
class Images final : public p::ImageAdapter {
public:
  explicit Images(NSArray<NSURL *> *pages) : pages_(pages) {}
  p::ByteSource open(size_t index) override {
    file_.reset();
    url_ = pages_[index];
    file_ = openFile(url_, "rb");
    if (fseeko(file_.get(), 0, SEEK_END) != 0)
      throw std::runtime_error("Cannot read page image.");
    const off_t length = ftello(file_.get());
    if (length < 0)
      throw std::runtime_error("Cannot read page image.");
    FILE *file = file_.get();
    return {uint64_t(length),
            [file](uint64_t offset, std::span<std::byte> into) {
              if (fseeko(file, off_t(offset), SEEK_SET) != 0 ||
                  fread(into.data(), 1, into.size(), file) != into.size())
                throw std::runtime_error("Cannot read page image.");
            }};
  }
  document_scanner::Size dimensions() override {
    NSDictionary *values = properties();
    const NSInteger width =
        [values[(id)kCGImagePropertyPixelWidth] integerValue];
    const NSInteger height =
        [values[(id)kCGImagePropertyPixelHeight] integerValue];
    if (width <= 0 || height <= 0)
      throw std::invalid_argument("Page image cannot be decoded.");
    return {uint32_t(width), uint32_t(height)};
  }
  Orientation orientation() override {
    const NSInteger value =
        [properties()[(id)kCGImagePropertyOrientation] integerValue];
    return Orientation(value >= 1 && value <= 8 ? value : 1);
  }
  // Stored (un-rotated) pixels drawn over white at the target size; the core
  // places them.
  p::EncodedImage encode(document_scanner::Size size, bool gray4,
                         int quality) override {
    CGImageSourceRef source = imageSource();
    // Owned before anything below can throw (the canvas allocation included).
    const std::unique_ptr<CGImage, decltype(&CGImageRelease)> decoded(
        CGImageSourceCreateImageAtIndex(source, 0, nullptr), &CGImageRelease);
    CFRelease(source);
    if (!decoded)
      throw std::invalid_argument("Page image cannot be decoded.");
    const size_t stride = size_t(size.width) * 4;
    std::vector<std::byte> rgba(stride * size.height);
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(
        rgba.data(), size.width, size.height, 8, stride, space,
        kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(space);
    if (!context)
      throw std::runtime_error("Cannot create page canvas.");
    const CGRect bounds = CGRectMake(0, 0, size.width, size.height);
    CGContextSetRGBFillColor(context, 1, 1, 1, 1);
    CGContextFillRect(context, bounds);
    CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
    CGContextDrawImage(context, bounds, decoded.get());
    CGContextRelease(context);

    auto bytes = std::make_shared<std::vector<std::byte>>();
    if (gray4) {
      // The red channel, as Android takes it; gray pages carry equal channels.
      Buffer gray;
      gray.allocator()(PixelFormat::gray8, size);
      for (size_t i = 0; i < gray.bytes.size(); ++i)
        gray.bytes[i] = rgba[i * 4];
      writeCompressedGray4(gray.lease(), [&](std::span<const std::byte> part) {
        bytes->insert(bytes->end(), part.begin(), part.end());
      });
    } else {
      NSMutableData *data = [NSMutableData data];
      CGImageRef image =
          makeImage(rgba.data(), size.width, size.height, 4, stride);
      try {
        encodeJpeg(image, (__bridge CFMutableDataRef)data, true,
                   quality / 100.0);
      } catch (...) {
        CGImageRelease(image);
        throw;
      }
      CGImageRelease(image);
      const auto *start = static_cast<const std::byte *>(data.bytes);
      bytes->assign(start, start + data.length);
    }
    return {size, gray4 ? p::ImageKind::gray4 : p::ImageKind::jpegRgb,
            bytes->size(), [bytes](const ByteSink &sink) { sink(*bytes); }};
  }

private:
  CGImageSourceRef imageSource() {
    CGImageSourceRef source = CGImageSourceCreateWithURL(
        (__bridge CFURLRef)url_, (__bridge CFDictionaryRef)
                                     @{(id)kCGImageSourceShouldCache : @NO});
    if (!source || CGImageSourceGetCount(source) < 1) {
      if (source)
        CFRelease(source);
      throw std::invalid_argument("Page image cannot be decoded.");
    }
    return source;
  }
  NSDictionary *properties() {
    CGImageSourceRef source = imageSource();
    NSDictionary *values = CFBridgingRelease(
        CGImageSourceCopyPropertiesAtIndex(source, 0, nullptr));
    CFRelease(source);
    if (!values)
      throw std::invalid_argument("Page image cannot be decoded.");
    return values;
  }
  NSArray<NSURL *> *pages_;
  NSURL *url_ = nil;
  File file_{nullptr, fclose};
};

std::optional<double> optionalValue(NSNumber *value) {
  return value ? std::optional<double>(value.doubleValue) : std::nullopt;
}
} // namespace

@implementation ScannerPdfWriter
+ (BOOL)writePages:(NSArray<NSURL *> *)pages
                 to:(NSURL *)output
          pageWidth:(NSNumber *)pageWidth
         pageHeight:(NSNumber *)pageHeight
    pageAspectRatio:(NSNumber *)pageAspectRatio
          targetDpi:(NSNumber *)targetDpi
        jpegQuality:(NSNumber *)jpegQuality
                fit:(NSInteger)fit
     autoRotateExif:(BOOL)autoRotateExif
              error:(NSError **)error {
  return guarded(error, [&] {
    if (pages.count == 0)
      throw std::invalid_argument("No pages provided.");
    if (fit < 0 || fit > 3)
      throw std::invalid_argument("Invalid image fit.");
    p::Options options;
    options.pageWidth = optionalValue(pageWidth);
    options.pageHeight = optionalValue(pageHeight);
    options.pageAspectRatio = optionalValue(pageAspectRatio);
    options.targetDpi = optionalValue(targetDpi);
    options.jpegQuality = optionalValue(jpegQuality);
    options.fit = static_cast<p::Fit>(fit);
    options.autoRotateExif = autoRotateExif;
    Images images(pages);
    File file = openFile(output, "wb");
    p::write(pages.count, options, images, fileSink(file.get()));
    finish(file);
    return true;
  });
}
@end
