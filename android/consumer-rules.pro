-keep class com.margelo.nitro.documentscanner.HybridDocumentScannerCameraView { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeTensorPreprocessor { *; }
-keep class com.margelo.nitro.documentscanner.processing.NativeGeometry { *; }
# Constructed by the geometry JNI adapter to preserve input/invalid failures.
-keep class com.margelo.nitro.documentscanner.InvalidInputException { public <init>(java.lang.String); }
