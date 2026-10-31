-keep class com.margelo.nitro.documentscanner.HybridDocumentScannerCameraView { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessor { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeGeometry { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeRenderGeometry { *; }
# Constructed by the geometry JNI adapter to preserve input/invalid failures.
-keep class com.margelo.nitro.documentscanner.InvalidInputException { public <init>(java.lang.String); }
-keep class com.margelo.nitro.documentscanner.processing.NativeDocumentFilters { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeGrayscalePng { *; }
-keep interface com.margelo.nitro.documentscanner.processing.GrayPngRows { *; }
