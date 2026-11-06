#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN
/// Bridge failures: 1 rejects the input, 2 is a render/codec/write failure.
FOUNDATION_EXPORT NSErrorDomain const ScannerRenderErrorDomain;

/// Immutable metadata for staged render artifacts; no pixel pointer survives.
@interface ScannerRenderedPage : NSObject
@property(nonatomic, readonly) NSInteger width;
@property(nonatomic, readonly) NSInteger height;
@property(nonatomic, readonly) BOOL curved;
/// Normalized output x,y pairs covered by the source photo.
@property(nonatomic, readonly) NSArray<NSNumber *> *validContentPolygon;
@end

/// Synchronous shared warp/filter/encode on the caller's native worker. The
/// owner must retain the borrowed upright RGBA8 bytes and their store pin.
@interface ScannerDocumentRenderer : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (BOOL)validateCorners:(NSArray<NSNumber *> *)corners error:(NSError **)error;
/// Writes the image (three-channel JPEG or four-bit gray PNG) and a JPEG
/// thumbnail to the given private paths. The caller owns publication and
/// cleanup.
+ (nullable ScannerRenderedPage *)renderOwner:(NSObject *)owner
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
                                        error:(NSError **)error;
/// The upright image under a document filter, without shadow removal, scaled to at
/// most `longSide` pixels on its long side. Synchronous on the caller's worker.
+ (nullable CGImageRef)filterPreview:(CGImageRef)image
                            longSide:(NSInteger)longSide
                              filter:(NSInteger)filter
                               error:(NSError **)error CF_RETURNS_RETAINED;
@end

/// Shared crop-editor geometry over normalized upright TL/TR/BR/BL x,y pairs.
@interface ScannerCropGeometry : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (NSArray<NSNumber *> *)clamp:(NSArray<NSNumber *> *)corners;
/// Inside the photo, clockwise and not collapsed: what an edited crop must be.
+ (BOOL)validCrop:(NSArray<NSNumber *> *)corners;
/// The corners with corner `index` moved to (x, y), or edge `index` (from that corner to
/// the next) moved by (x, y) and shortened at the photo's bounds; nil when the result
/// is crossed, concave or collapsed.
+ (nullable NSArray<NSNumber *> *)move:(NSArray<NSNumber *> *)corners
                                 index:(NSInteger)index
                                     x:(double)x
                                     y:(double)y
                                  edge:(BOOL)edge;
@end

/// Streams the shared PDF serializer into `output`. Eligible JPEG/four-bit gray
/// PNG pages embed unchanged; other inputs (TIFF included) are re-encoded
/// through ImageIO.
@interface ScannerPdfWriter : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (BOOL)writePages:(NSArray<NSURL *> *)pages
                 to:(NSURL *)output
          pageWidth:(nullable NSNumber *)pageWidth
         pageHeight:(nullable NSNumber *)pageHeight
    pageAspectRatio:(nullable NSNumber *)pageAspectRatio
          targetDpi:(nullable NSNumber *)targetDpi
        jpegQuality:(nullable NSNumber *)jpegQuality
                fit:(NSInteger)fit
     autoRotateExif:(BOOL)autoRotateExif
              error:(NSError **)error;
@end
NS_ASSUME_NONNULL_END
