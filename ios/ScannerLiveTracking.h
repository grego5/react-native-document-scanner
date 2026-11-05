#import <CoreGraphics/CoreGraphics.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Quads cross this boundary as eight numbers: normalized upright TL, TR, BR, BL x/y pairs.

/// One shared-guide decision. Status: 0 searching, 1 tracking, 2 held. Decision
/// follows the portable `guide::Decision` order.
@interface ScannerGuideOutput : NSObject
@property(nonatomic, readonly) NSInteger status;
@property(nonatomic, readonly) NSInteger decision;
@property(nonatomic, readonly, nullable) NSArray<NSNumber *> *accepted;
@property(nonatomic, readonly) int64_t acceptanceId;
@property(nonatomic, readonly) int64_t deadlineNs;
@property(nonatomic, readonly) NSInteger chainLength;
@property(nonatomic, readonly) BOOL expired;
@end

/// The shared guide stabilizer, confined to one analysis owner. Width and height
/// are the upright analyzed frame in pixels; arrival times are monotonic.
@interface ScannerLiveGuide : NSObject
- (instancetype)initWithMinArea:(double)minArea holdNs:(int64_t)holdNs;
- (instancetype)init NS_UNAVAILABLE;
- (void)reset;
- (ScannerGuideOutput *)updateQuad:(nullable NSArray<NSNumber *> *)quad
                             width:(NSInteger)width
                            height:(NSInteger)height
                          sensorNs:(int64_t)sensorNs
                         arrivalNs:(int64_t)arrivalNs NS_SWIFT_NAME(update(_:width:height:sensorNs:arrivalNs:));
/// A flow-carried outline keeps the accepted guide; nil when it cannot.
- (nullable ScannerGuideOutput *)retainQuad:(nullable NSArray<NSNumber *> *)quad
                                      width:(NSInteger)width
                                     height:(NSInteger)height
                                   sensorNs:(int64_t)sensorNs
                                  arrivalNs:(int64_t)arrivalNs NS_SWIFT_NAME(retainTracked(_:width:height:sensorNs:arrivalNs:));
/// `score` is nil when the runtime reports none.
+ (BOOL)scoreRejected:(nullable NSNumber *)score minimum:(double)minimum NS_SWIFT_NAME(scoreRejected(_:minimum:));
/// `speed` is the measured flow speed in pixels per second, nil when not measured.
+ (BOOL)keepScorePause:(BOOL)current
                 speed:(nullable NSNumber *)speed
           sourceWidth:(NSInteger)sourceWidth NS_SWIFT_NAME(keepScorePause(_:speed:sourceWidth:));
@end

/// One flow measurement. Status follows the portable `motion::Status` order:
/// 0 measured, 1 noReference, 2 tooFewFeatures, 3 trackingFailed, 4 poorCoverage.
@interface ScannerFlowEstimate : NSObject
@property(nonatomic, readonly) NSInteger status;
@property(nonatomic, readonly, nullable) NSNumber *speed;
@property(nonatomic, readonly) NSInteger sourceWidth;
@property(nonatomic, readonly) NSInteger validTracks;
@property(nonatomic, readonly) NSInteger lostRegions;
@property(nonatomic, readonly) BOOL hasTransform;
@property(nonatomic, readonly) int64_t fromNs;
@property(nonatomic, readonly) int64_t toNs;
@end

/// The shared flow estimator and its bounded transform history, confined to one
/// analysis owner. Only its own downscaled gray copy survives a call.
@interface ScannerFlowTracker : NSObject
/// Locks the packed BGRA/RGBA buffer read-only and unlocks before returning.
/// `orientation` is the EXIF turn that makes the buffer upright (1, 3, 6 or 8);
/// `crop` is integral, in buffer pixels. The estimate joins the history.
- (nullable ScannerFlowEstimate *)updatePixelBuffer:(CVPixelBufferRef)buffer
                                        orientation:(uint8_t)orientation
                                               crop:(CGRect)crop
                                           sensorNs:(int64_t)sensorNs
                                             region:(nullable NSArray<NSNumber *> *)region
                                       fitTransform:(BOOL)fitTransform
                                              error:(NSError **)error
    NS_SWIFT_NAME(update(_:orientation:crop:sensorNs:region:fitTransform:));
/// Carries a quad across recorded intervals; nil when any interval is missing.
- (nullable NSArray<NSNumber *> *)warpQuad:(NSArray<NSNumber *> *)quad
                                    fromNs:(int64_t)fromNs
                                      toNs:(int64_t)toNs NS_SWIFT_NAME(warp(_:fromNs:toNs:));
- (void)reset;
@end

NS_ASSUME_NONNULL_END
