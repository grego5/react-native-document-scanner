-keep class com.margelo.nitro.documentscanner.HybridDocumentScannerCameraView { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessor { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeGeometry { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeRenderGeometry { *; }
# Constructed by the geometry JNI adapter to preserve input/invalid failures.
-keep class com.margelo.nitro.documentscanner.InvalidInputException { public <init>(java.lang.String); }
-keep class com.margelo.nitro.documentscanner.processing.NativeDocumentFilters { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeGrayscalePng { *; }
-keep interface com.margelo.nitro.documentscanner.processing.GrayPngRows { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativePdfWriter { *; }
-keep interface com.margelo.nitro.documentscanner.processing.PdfImageAdapter { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeMotion { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeFlowEstimator { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeFlowHistory { *; }

-keep class com.margelo.nitro.documentscanner.processing.NativeLiveGuide { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeFocus { *; }

# Looked up by class name during JNI_OnLoad, including when only diagnostics use a bridge.
-keep class com.margelo.nitro.documentscanner.processing.FieldKernels { *; }
-keep class com.margelo.nitro.documentscanner.processing.FastCores { *; }
