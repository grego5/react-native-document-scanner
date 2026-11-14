import Foundation

/// Production engine actions. One session owns this model lease; all image use and
/// its release share the session's serial image worker, then its close barrier.
final class SessionPageProcessing: ScanSessionActions {
  private let resources: SessionResources
  private let store: SourceStore
  private let inference: CornerInference
  private var model: CornerInference.Lease?

  init(resources: SessionResources, inference: CornerInference = .shared) {
    self.resources = resources
    store = resources.registry.store
    self.inference = inference
  }

  func prepareAnalysis(_ source: SessionResources.Lease, liveCorners: CornerCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue {
    { [self] in
      let started = DispatchTime.now().uptimeNanoseconds
      let pixels = try store.acquire(source.url)
      // Without a model the analysis finds no page.
      guard let selection else {
        return AnalysisValue(width: pixels.width, height: pixels.height, duration: 0, candidate: nil, score: 0)
      }
      let lease = model ?? inference.acquire()
      model = lease
      let result = try lease.analyze(pixels, seed: liveCorners, selection: selection)
      let candidate = result.prediction.corners.flatMap { $0.count == 8 ? $0 : nil }
      return AnalysisValue(width: pixels.width, height: pixels.height,
                           duration: Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000,
                           candidate: candidate, score: result.prediction.score)
    }
  }

  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact {
    let options = key.settings.options
    let destination = try resources.renderDestination(
      extension: DocumentRenderService.fileExtension(DocumentRenderService.outputFormat(options)))
    defer { destination.close() }
    do {
      let pixels = try store.acquire(source.url)
      let rendered = try DocumentRenderService.render(pixels, corners: SessionCorners.corners(key.corners), options: options,
        image: destination.image.url, thumbnail: destination.thumbnail.url)
      return ScanPage.Artifact(image: try destination.image.commit(), thumbnail: try destination.thumbnail.commit(),
                               width: rendered.width, height: rendered.height,
                               validContentPolygon: rendered.validContentPolygon)
    } catch {
      destination.discard()
      throw error
    }
  }

  func close() {
    model?.close()
    model = nil
    inference.drain()
  }
}
