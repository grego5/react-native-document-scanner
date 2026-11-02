#import <XCTest/XCTest.h>
#include <NitroModules/HybridObjectRegistry.hpp>
#include <opencv2/core.hpp>
#include "document_scanner/Pixels.hpp"
#import "ScannerSourcePixels.h"
#include <optional>

@interface DependencyLinkTests : XCTestCase
@end

@implementation DependencyLinkTests
- (void)testGeneratedRegistryConstructorsAreInstalled {
  using margelo::nitro::HybridObjectRegistry;
  for (const char* name : {"CameraPermission", "DocumentScannerProcessor", "ScanSession",
                          "DocumentScannerCameraView", "DocumentCropEditorView",
                          "ImageViewerView", "ImagePagerView", "PageThumbnailView"}) {
    XCTAssertTrue(HybridObjectRegistry::hasHybridObject(name), @"Missing registration: %s", name);
    XCTAssertTrue(HybridObjectRegistry::createHybridObject(name) != nullptr);
  }
}

- (void)testSourceCoreLeaseRetainsNativeOwner {
  __weak NSObject *weakOwner;
  std::optional<document_scanner::PixelLease> lease;
  @autoreleasepool {
    NSMutableData *owner = [[NSMutableData alloc] initWithLength:8];
    auto *bytes = static_cast<uint8_t *>(owner.mutableBytes);
    bytes[0] = 17; bytes[3] = 255; bytes[4] = 29; bytes[7] = 255;
    weakOwner = owner;
    lease.emplace(ScannerBorrowSourcePixels(owner, owner.bytes, owner.length, 2, 1, 8));
    owner = nil;
    XCTAssertNotNil(weakOwner);
    XCTAssertEqual(lease->view().at(1, 0).red, uint8_t(29));
    XCTAssertEqual(lease->view().layout().orientation, document_scanner::Orientation::upright);
  }
  XCTAssertNotNil(weakOwner);
  lease.reset();
  XCTAssertNil(weakOwner);
}

- (void)testCoreAndPinnedOpenCVRuntimeLink {
  XCTAssertEqual(document_scanner::bytesPerPixel(document_scanner::PixelFormat::rgba8), size_t(4));
  XCTAssertEqualObjects([NSString stringWithUTF8String:cv::getVersionString().c_str()], @"4.12.0");
}
@end
