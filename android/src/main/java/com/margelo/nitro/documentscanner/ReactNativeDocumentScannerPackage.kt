package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.CachedImageFiles
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.margelo.nitro.documentscanner.views.HybridDocumentCropEditorViewManager
import com.margelo.nitro.documentscanner.views.HybridDocumentScannerCameraViewManager
import com.margelo.nitro.documentscanner.views.HybridImageViewerViewManager
import com.margelo.nitro.documentscanner.views.HybridImagePagerViewManager
import com.margelo.nitro.documentscanner.views.HybridPageThumbnailViewManager

class ReactNativeDocumentScannerPackage : BaseReactPackage() {
  companion object {
    init {
      ReactNativeDocumentScannerOnLoad.initializeNative()
    }
  }

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? = null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
    ReactModuleInfoProvider { emptyMap() }

  override fun createViewManagers(
    reactContext: ReactApplicationContext,
  ): List<ViewManager<*, *>> {
    // Intentionally asynchronous; the process-wide registry protects every live JS context.
    ScanSessionRegistry.shared.cleanupAbandoned(CachedImageFiles.directory(reactContext))
    return listOf(
      HybridDocumentScannerCameraViewManager(),
      HybridDocumentCropEditorViewManager(),
      HybridImageViewerViewManager(),
      HybridImagePagerViewManager(),
      HybridPageThumbnailViewManager(),
    )
  }
}
