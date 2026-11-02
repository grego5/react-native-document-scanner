#import <XCTest/XCTest.h>
#include <NitroModules/HybridObjectRegistry.hpp>
#include <opencv2/core.hpp>
#include "document_scanner/Pixels.hpp"

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

- (void)testCoreAndPinnedOpenCVRuntimeLink {
  XCTAssertEqual(document_scanner::bytesPerPixel(document_scanner::PixelFormat::rgba8), size_t(4));
  XCTAssertEqualObjects([NSString stringWithUTF8String:cv::getVersionString().c_str()], @"4.12.0");
}
@end
