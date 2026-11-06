#import <CoreGraphics/CoreGraphics.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN
/// Immutable normalized upright TL/TR/BR/BL prediction; nil corners means invalid
/// geometry.
@interface ScannerCornerResult : NSObject
@property(nonatomic, readonly, nullable) NSArray<NSNumber *> *corners;
@property(nonatomic, readonly) double score;
@property(nonatomic, readonly) double preprocessingMs;
@property(nonatomic, readonly) double inferenceMs;
@end

/// Confined to CornerInference's serial owner. No pixel pointers survive a
/// call.
@interface ScannerCornerRuntime : NSObject
@property(nonatomic, readonly) NSUInteger inputWidth;
@property(nonatomic, readonly) NSUInteger inputHeight;
- (nullable instancetype)initWithModel:(NSData *)model error:(NSError **)error;
- (instancetype)init NS_UNAVAILABLE;
- (nullable ScannerCornerResult *)
    analyzeOwner:(NSObject *)owner
           bytes:(const void *)bytes
       byteCount:(NSUInteger)byteCount
           width:(uint32_t)width
          height:(uint32_t)height
       rowStride:(NSUInteger)rowStride
            seed:(nullable NSArray<NSNumber *> *)seed
           error:(NSError **)error;
/// Locks on the inference worker and unlocks before returning, including
/// failure.
- (nullable ScannerCornerResult *)analyzePixelBuffer:(CVPixelBufferRef)buffer
                                         orientation:(uint8_t)orientation
                                                crop:(CGRect)crop
                                               error:(NSError **)error;
/// Already prepared float32 NHWC input; copied synchronously into the owned
/// workspace.
- (nullable ScannerCornerResult *)predictTensor:(NSData *)tensor
                                          error:(NSError **)error;
@end
NS_ASSUME_NONNULL_END
