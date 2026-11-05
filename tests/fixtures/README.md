# Android parity baseline fixtures

These public synthetic fixtures freeze the Android baseline before migration. Production code and public contracts are unchanged. No photos were captured. Private model photos and reports remain under ignored .local/.

parity/*.tsv is UTF-8, tab-separated, with a header and # metadata comments. Commas separate vector elements. Every file declares dimensions (or no pixels), format, coordinate space and tolerance. Rows have stable names; event rows sharing an id are ordered in file order and share one policy instance. Missing/malformed resources fail tests. Android test assets and JVM resources reference this directory directly; release assets do not.

## Portable cases and executable consumers

| Fixture | Expected result | Android baseline consumer |
| --- | --- | --- |
| orientation | Eight EXIF transforms of an 80x60 quadrant JPEG; upright dimensions and TL/TR/BR/BL colors. RGB L1 distance <140; transform roundtrip 1e-8. | SourceImageLoaderTest.allExifOrientationsProduceExpectedUprightDimensionsAndMarkerPositions (device) |
| strided-rgb | RGB pattern, explicit stride/offset in pixels, magenta padding; five independent Pillow resize hashes copied unchanged from DocCornerGeometryTest. | ParityFixtureTest.paddedRgbRowsKeepTheExistingIndependentResizeGoldens (JVM) |
| quads | Nine float32 model outputs; accept/reject/error and exact accepted corners. Low scores remain valid. | ParityFixtureTest.normalizedModelOutputsPreserveCornerOrderAndValidity (JVM) |
| warp | Five synthetic 640x480 RGBA sources (flat, bent, competing rule, outside corner, scaled output). Exact pre-filter SHA-256 and dimensions; curved flag and normalized straight-warp polygon at 1e-12. Recorded on Android OpenCV 4.12 before Task 07 migration. | GeometricRenderTest.prefilterPixelsMatchPreMigrationGoldens (device); RenderGeometryTest supplies portable behavior/ownership checks |
| filters/ | Sixteen corrected-reference 256x224 pre-encoding BGR8/gray8 outputs: shaded color/gray/ink page and empty page, all filters, independent shadow on/off. Gzip files have zero mtime; manifest records decompressed SHA-256/length and original SHA-256. Six shaded-page filtered outputs were refreshed after the user-approved sharpness fix; none/empty outputs are unchanged. The reference is the original Kotlin implementation from 234886b with independently owned blur/Laplacian/magnitude matrices, retained under androidTest only. Android asset packaging expands gzip and strips `.gz`; tolerance is one intensity level. | FilterParityTest.preEncodingPixelsMatchCorrectedReference (device); DocumentFiltersTest.cpp adds portable semantics/ownership |
| adaptive | Ordered adaptive pacing sequences per case on one fresh policy: acquisition, loss then clean tracking, degradation, expiry/reset, the 5%-of-width corner threshold in both orientations, perspective and a cleared reference. Exact gap, status, last wake reason and doubt. | AdaptiveDetectionPolicyTest.sharedPlatformSequences (JVM); iOS LiveGuidanceTests.testAdaptivePolicyMatchesTheSharedPlatformSequences |
| capture-events | Ordered document/focus/light/motion events, explicit monotonic elapsed milliseconds, exact decision/deadline. | ParityFixtureTest.captureEventsPreserveOrderingAndDeadlines (JVM) |
| gray-png | Gray input (13*x+7*y)%256; 33x3, 1x1, 16x2. Expected 4-bit sample is integer (input+8)/17, expanded by 17 only for 8-bit decoding; exact values, source error <=8. | portable GrayscalePngTest.cpp reads this TSV and independently inverts PNG filters/packing; GrayscalePngRuntimeTest checks Android decoding |
| gray-png/ | Independent Python/zlib-generated parser inputs: 3x1 transparency/truncation sources and a 1025x400 Java Random(7) source in multiple IDAT chunks. All are four-bit grayscale with filter 0. These now exercise the shared PDF probe after the remaining Kotlin reader assertions moved atomically in Task 10. | portable PdfWriterTest.cpp (multi-IDAT length/inflate/samples, transparency, rejected bit depths and truncation) |
| filters | White RGBA 400x300 page with faint strokes; blackWhite paper=255 and every stroke stays in 1..254. Existing bounds retained. | DocumentFiltersTest.blackWhitePreservesContinuousFaintOnePixelStrokes (device/OpenCV) |
| pdf-placement | 400x300 JPEG, 600x800 point page; contain/cover/fill and EXIF rotation; six-element PDF matrix, clipping and unchanged embedded JPEG. | portable PdfWriterTest.cpp and PdfWriterTest.portablePlacementMatricesPreserveFitClippingAndExif (device) |

Hash definition: initialize signed 32-bit h=1; for each row-major opaque ARGB32 output pixel set h=31*h+pixel modulo 2^32. The strided test exercises the existing row-reader boundary, not a future C++ byte-stride interface. That adapter must validate byte strides/channel layouts in Task 02. The original filters.tsv ranges are behavioral bounds; filters/ adds full-image numerical goldens from Task 08; FieldKernelsTest supplies native-versus-OpenCV numerical comparisons. PDF tolerance is 0.0001 point because Android boxes use float32.

## Migration inventory

Names refer to classes and methods under android/src/test (JVM) or android/src/androidTest (device). Portable cases are the shared subset; retain the richer existing assertions when moving each algorithm. A mapping identifies a baseline, not proof of iOS parity.

| Later task | Named baseline cases / remaining integration evidence |
| --- | --- |
| 02 portable boundary | strided-rgb, quads; NativeTensorPreprocessorTest.rejectedInputsUnlockPixelsAndCloseIsIdempotent; future raw byte-stride/lease tests required |
| 03 model resources | DocCornerNetRuntimeTest.replacementUsesOwnedBytesAndFailuresKeepPreviousModel, missingAndIncompatibleAssetsFailWithoutPoisoningNextSession; private model SHA256 |
| 04 feasibility | NativeTensorPreprocessorTest; DocCornerNetRuntimeTest.privatePhotosMeetNativeParityBudgetAndRecordWarmTimings; iOS toolchain/storage costs need new evidence |
| 05 geometry | orientation, quads; PhotoInferenceCropTest, CropQuadEditingTest, CaptureResolutionPolicyTest |
| 06 preprocessing | strided-rgb, quads; DocCornerGeometryTest.bilinearResamplingMatchesPillowRgbGoldenPixels; NativeTensorPreprocessorTest.dynamicSquareAndRectangularTensorsMatchKotlinAcrossSourceChanges; private runtime photos |
| 07 warping | warp; GeometricRenderTest; portable RenderGeometryTest; DocumentRendererTest.quadCornersMapExactlyToOutputCornersWithoutMargin, bentPageIsFlattenedBackOntoItsGrid, cornerOutsideThePhotoKeepsTheStraightWarp |
| 08 filters | filters, filters/; FilterParityTest; portable DocumentFiltersTest; DocumentFiltersTest.grayscaleEvensUnevenLightAndKeepsInk, enhanceKeepsBroadColourAndMidTones, aGrayBoxAmongFadedTextKeepsItsTone; ShadowRemovalTest, FieldKernelsTest |
| 09 gray PNG | gray-png; portable GrayscalePngTest.cpp; GrayscalePngRuntimeTest; reader assertions moved from JVM GrayscalePngTest into portable PdfWriterTest.cpp in Task 10 |
| 10 PDF | pdf-placement, gray-png/; portable PdfWriterTest.cpp; PdfWriterTest.fourBitPngEmbedsUnchangedAndDisplaysAllSixteenGrayLevels, fourBitPngRespectsTargetDpiWithoutBecomingJpeg |
| 11 motion | FlowMotionEstimatorTest.stillFramesMeasureNearZero, doublingTheIntervalHalvesTheSpeed, blankFramesAreUnknown; FlowQuadMotionTest, GateMotionTest |
| 12 guide | LiveQuadStabilizerTest.acceptsFirstValidResultAndIgnoresDuplicateTimestamp, invalidAndTinyOutlinesNeverAcquire; adaptive; AdaptiveDetectionPolicyTest.sharedPlatformSequences (pacing is platform-owned); LiveQuadTraceReplayTest.trackedHeartbeatsBridgeTheModelIntervalWithoutCountingAsInference |
| 13 focus | DocumentFocusPolicyTest.twoTrustedScaleChangesRefocusWithoutHoldingAStaleDistanceForTheCooldown, tapsAndCapturesBlockAutomaticActionsAndResetDropsOldCompletion |
| 14 torch | TorchPolicyTest.sustainedDarkFramesLightTheTorchOnceAndItStaysLit, aBrightOrUnreportedFrameRestartsTheWait |
| 15 readiness | capture-events; all CaptureGateTest / CaptureStabilityTest assertions, including optional focus and interrupted sensor chains |
| 16 migration gate | All mapped Android baselines; performance and physical camera evidence remain separate |
| 17 package | src/scanner-contracts.test.cjs; iOS registration/build evidence required |
| 18 permissions | LiveCameraRuntimeTest and JS error contracts; iOS permission transitions need physical validation |
| 19 source store | orientation; SourceImageLoaderTest.sampledDecodeHonorsRequestedMaximumDimensionAndPreservesAspect, CachedImageFilesTest; iOS lease/cache memory tests required |
| 20 inference | quads, strided-rgb; runtime concurrent lease and private photo assertions |
| 21 processor | filters, gray-png, pdf-placement; HybridDocumentScannerProcessorTest, DocumentRendererTest |
| 22 viewer | ImageViewerViewTest, ImageZoomTest; platform UI evidence required |
| 23 pager | ImagePagerViewTest; platform UI evidence required |
| 24 editor | CropEditorViewTest, CropQuadEditingTest; normalized quad contract |
| 25 camera | LiveCameraRuntimeTest; iOS camera/lifecycle evidence required |
| 26 guidance | Same guide and trace baselines as 12; Swift adaptive pacing must pass adaptive |
| 27 controls | Same focus/torch baselines as 13/14; CameraExposurePolicyTest |
| 28 capture | CaptureResolutionPolicyTest, source orientation and renderer immutable-original assertions; iOS dimension cap/memory evidence required |
| 29 capture gate | capture-events; same complete gate/stability assertions as 15 |
| 30 feedback | CaptureFeedbackTimelineTest, QuadTransitionTest; retained-preview device evidence required |
| 31 lifecycle | LiveCameraRuntimeTest and runtime lease/close assertions; iOS interruption/memory evidence required |
| 32 example | npm run check JS workflow contracts; iOS complete workflow evidence required |
| 33 release | All above; cross-platform numerical/performance/memory and human-triggered capture evidence cannot be replaced by a build |

## Traces and private photos

Public trace input remains android/src/test/resources/live-quad-trace/synthetic.tsv; replay tests also construct current tracking/focus/expiry sequences. The file is a legacy-version compatibility fixture, not a current numerical golden. LiveQuadTraceReplayTest.recordedTracesReplay explicitly skips without -PliveQuadTrace; mismatched configurations report strict-comparison skips.

Prepare private runtime assets using tools/models/README.md, then explicitly pass -PdocCornerFixtures=<absolute assets directory> to the instrumentation build only. The runtime test checks model and per-photo SHA256. Preserve tensor-output error <=2e-5 and photo corner mean <=.005 / worst <=.015. The private-photo method also requires the instrumentation runner's -Measure switch; without it, the method is an assumption skip even when assets exist. Missing assets are likewise assumption skips, never parity passes.

Task 08 used a temporary synthetic-only recorder before replacing the algorithms; its final consumer compares the frozen gzip fixtures. No production export boundary was added: existing tests expose row-reader inputs, normalized outputs, policy decisions, generated filter inputs, PNG samples and PDF content matrices. Runtime tests already write a private report. No production boundary or capture automation is needed.

Validation results and environment limitations are recorded in Tasks/01-establish-parity-fixtures.md.
