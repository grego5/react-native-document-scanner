package com.margelo.nitro.documentscanner

import android.content.Context
import android.net.Uri
import com.margelo.nitro.documentscanner.processing.CornerInference
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import com.margelo.nitro.documentscanner.processing.FastCores
import com.margelo.nitro.documentscanner.processing.PhotoAnalysis
import com.margelo.nitro.documentscanner.processing.CornerModelStore

/** One state owns this model lease; all image use and its close barrier share the serial image owner. */
internal class SessionPageProcessing(
  context: Context,
  private val resources: SessionResources,
) : ScanSessionState.Actions {
  private val context = context.applicationContext
  private var model: CornerInference.Lease? = null

  override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?): () -> DocumentAnalysis {
    return { resources.lease(original).use { source -> preparePinnedAnalysis(source, liveCorners, selection)() } }
  }

  override fun preparePinnedAnalysis(source: SessionResources.Lease, liveCorners: DocumentCorners?, selection: CornerModelStore.Selection?): () -> DocumentAnalysis = {
    val selected = selection ?: throw ScannerException(ScannerErrorCode.MODEL_LOAD_FAILED)
    val lease = model ?: CornerInference.acquire(context).also { model = it }
    PhotoAnalysis.analyze(Uri.fromFile(source.file).toString(), liveCorners, lease, selected)
  }

  override fun renderSnapshot(source: SessionResources.Lease, key: ScanPage.RenderKey): ScanPage.Artifact {
    val extension = if (DocumentRenderer.outputFormat(key.options) == DocumentImageFormat.PNG) "png" else "jpg"
    val destination = resources.renderDestination(extension)
    val rendered = FastCores.run { DocumentRenderer.render(source, key.corners, key.options, destination) }
    return ScanPage.Artifact(destination.image.resource, destination.thumbnail.resource,
      rendered.width, rendered.height, rendered.validContentPolygon.toList())
  }

  override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact =
    resources.lease(original).use { source -> renderSnapshot(source, key) }

  override fun close() {
    model?.close()
    model = null
  }
}
